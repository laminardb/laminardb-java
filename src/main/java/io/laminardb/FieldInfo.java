package io.laminardb;

/** One field of a {@link Schema}: name, nullability, and canonical Arrow type name. */
public final class FieldInfo {

    private final String name;
    private final boolean nullable;
    private final String typeName;

    FieldInfo(String name, boolean nullable, String typeName) {
        this.name = name;
        this.nullable = nullable;
        this.typeName = typeName;
    }

    /** Returns the field name. */
    public String name() {
        return name;
    }

    /** Returns whether the field accepts nulls. */
    public boolean nullable() {
        return nullable;
    }

    /**
     * Returns the canonical Arrow type name (e.g. {@code "Int64"}, {@code
     * "Utf8"}, {@code "Timestamp(ns)"}); never an engine-internal rendering.
     */
    public String typeName() {
        return typeName;
    }

    @Override
    public String toString() {
        return name + ": " + typeName + (nullable ? " (nullable)" : "");
    }
}
