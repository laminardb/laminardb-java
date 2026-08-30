package io.laminardb.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads the Rust cdylib backing the native surface (plan 04 §2). Resolution
 * order, first hit wins:
 *
 * <ol>
 *   <li>System property {@code laminardb.native.path} — absolute library file.</li>
 *   <li>Classpath resource {@code /natives/<os>-<arch>/<lib-name>}, extracted
 *       to a version-scoped cache directory with SHA-256 verification
 *       (re-extraction skipped when the file already matches).</li>
 *   <li>{@code java.library.path} via {@code System.loadLibrary}.</li>
 * </ol>
 */
public final class NativeLoader {

    private static final Logger LOG = Logger.getLogger(NativeLoader.class.getName());

    private static final String LIB_NAME = "laminar_java";
    private static final String VERSION = getVersion();

    private NativeLoader() {}

    /** Loads the native library, trying each resolution step in order. */
    public static void load() {
        String explicit = System.getProperty("laminardb.native.path");
        if (explicit != null) {
            System.load(explicit);
            return;
        }
        String osArch = osArch();
        String libFile = libFileName();
        URL bundled = bundledResource(osArch, libFile);
        if (bundled != null) {
            try {
                System.load(extract(bundled, libFile).toString());
                return;
            } catch (UnsatisfiedLinkError e) {
                // A noexec tmpdir cannot dlopen the extraction; retry from
                // the user cache before falling through to the classpath
                // error path.
                Path cacheBase = Path.of(System.getProperty("user.home"), ".cache", "laminardb-native");
                try {
                    System.load(extractInto(bundled, libFile, cacheBase).toString());
                    return;
                } catch (IOException | UnsatisfiedLinkError retryFailed) {
                    throw unsatisfied(osArch, libFile, e);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("extracting " + libFile + " failed", e);
            }
        }
        try {
            System.loadLibrary(LIB_NAME);
        } catch (UnsatisfiedLinkError e) {
            throw unsatisfied(osArch, libFile, e);
        }
    }

    private static UnsatisfiedLinkError unsatisfied(String osArch, String libFile, Throwable cause) {
        // UnsatisfiedLinkError has no (String, Throwable) constructor.
        UnsatisfiedLinkError error =
                new UnsatisfiedLinkError("laminardb: no native library for " + osArch + " (" + libFile + "). "
                        + "The jar bundles natives under /natives/<os>-<arch>/; "
                        + "resolution tried the laminardb.native.path property, "
                        + "the bundled /natives/ resources, and java.library.path. "
                        + "Escape hatch: -Dlaminardb.native.path=/abs/path/to/"
                        + libFile);
        error.initCause(cause);
        return error;
    }

    /** Extracts the resource into the version-scoped tmpdir cache. */
    private static Path extract(URL resource, String libFile) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "laminardb-native", VERSION);
        return extractInto(resource, libFile, base);
    }

    /**
     * Extracts {@code resource} to {@code base/<libFile>} atomically
     * (write-to-temp + rename); a matching existing file (SHA-256) skips re-extraction.
     * Package-private for the extraction unit test.
     */
    static Path extractInto(URL resource, String libFile, Path base) throws IOException {
        Path target = base.resolve(libFile);
        byte[] digest = sha256(resource.openStream());
        String expected = hex(digest);
        if (Files.isRegularFile(target)) {
            try (InputStream existing = Files.newInputStream(target)) {
                if (hex(sha256(existing)).equals(expected)) {
                    LOG.log(Level.FINE, "native cache hit: {0}", target);
                    return target;
                }
            }
        }
        Files.createDirectories(base);
        Path staging = Files.createTempFile(base, libFile, ".part");
        try (InputStream in = resource.openStream()) {
            Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOG.log(Level.FINE, "extracted native: {0}", target);
        return target;
    }

    private static URL bundledResource(String osArch, String libFile) {
        return NativeLoader.class.getResource("/natives/" + osArch + "/" + libFile);
    }

    private static String libFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + LIB_NAME + ".dylib";
        }
        if (os.contains("win")) {
            return LIB_NAME + ".dll";
        }
        return "lib" + LIB_NAME + ".so";
    }

    private static String osArch() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osKey = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("win") ? "windows" : "linux";
        String archKey =
                switch (arch) {
                    case "x86_64", "amd64" -> "amd64";
                    case "aarch64", "arm64" -> "aarch64";
                    default -> arch;
                };
        return osKey + "-" + archKey;
    }

    private static String getVersion() {
        try (InputStream stream = NativeLoader.class.getResourceAsStream("/laminardb-version.properties")) {
            if (stream != null) {
                var props = new java.util.Properties();
                props.load(stream);
                return props.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
            // Fall through to unknown: the directory is only version-scoping.
        }
        return "unknown";
    }

    private static byte[] sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
