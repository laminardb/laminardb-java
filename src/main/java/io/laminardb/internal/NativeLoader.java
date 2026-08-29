package io.laminardb.internal;

/** Loads the Rust cdylib backing the native surface. */
public final class NativeLoader {

    private NativeLoader() {}

    /**
     * Resolution order: the absolute file named by the
     * {@code laminardb.native.path} system property, then
     * {@code java.library.path} via {@code System.loadLibrary}. The
     * property-first order keeps the bundled-extraction path (Phase 1, plan
     * 04 §2) testable ahead of time.
     */
    public static void load() {
        String explicit = System.getProperty("laminardb.native.path");
        if (explicit != null) {
            System.load(explicit);
            return;
        }
        System.loadLibrary("laminar_java");
    }
}
