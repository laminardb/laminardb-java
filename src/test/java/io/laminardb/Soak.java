package io.laminardb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/** Nightly-only tests (plan 03 §6): long loops excluded from per-PR runs. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Tag("io.laminardb.Soak")
public @interface Soak {}
