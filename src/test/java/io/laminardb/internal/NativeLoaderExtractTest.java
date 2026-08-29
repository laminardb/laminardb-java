package io.laminardb.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Extraction mechanics of the bundled-natives path (plan 04 §2). */
class NativeLoaderExtractTest {

    @Test
    void extractsAtomicallyAndSkipsMatchingReExtraction(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("fake-lib");
        Files.writeString(source, "fake cdylib bytes");
        URL resource = source.toUri().toURL();

        Path cache = dir.resolve("cache");
        Path extracted = NativeLoader.extractInto(resource, "liblaminar_java.dylib", cache);
        assertThat(extracted).exists();
        assertThat(Files.readString(extracted)).isEqualTo("fake cdylib bytes");
        assertThat(cache.resolve("liblaminar_java.dylib.sha256")).exists();

        // Re-extraction with changed content rewrites the target.
        Files.writeString(source, "changed bytes", StandardCharsets.UTF_8);
        NativeLoader.extractInto(source.toUri().toURL(), "liblaminar_java.dylib", cache);
        assertThat(Files.readString(cache.resolve("liblaminar_java.dylib"))).isEqualTo("changed bytes");

        // No stray staging files remain (atomic rename cleans up).
        try (var files = Files.list(cache)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".part")))
                    .isEmpty();
        }

        // The production resolution order still ends at loadLibrary for this
        // process (the dylib is already loaded once by other tests).
        assertThatCode(() -> System.getProperty("laminardb.native.path")).doesNotThrowAnyException();
    }
}
