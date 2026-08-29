package io.laminardb;

import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;

/** The binding's view of a source's schema: a list of {@link FieldInfo}s. */
public final class Schema {

    private final List<FieldInfo> fields;

    Schema(org.apache.arrow.vector.types.pojo.Schema arrowSchema) {
        this.fields = arrowSchema.getFields().stream()
                .map(f -> new FieldInfo(f.getName(), f.isNullable(), typeName(f.getType())))
                .toList();
    }

    /** Returns an unmodifiable view of the fields. */

    /** Returns the schema's fields, in declaration order. */
    /** Returns the schema's fields, in declaration order. */
    public List<FieldInfo> fields() {
        return List.copyOf(fields);
    }

    /** Returns the field with the given name, or null. */
    public FieldInfo field(String name) {
        for (FieldInfo field : fields) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /** Mirrors the Rust-side canonical names (src/arrow_jni.rs {@code type_name}). */
    private static String typeName(ArrowType type) {
        if (type instanceof ArrowType.Int integer) {
            return (integer.getIsSigned() ? "" : "U") + "Int" + integer.getBitWidth();
        }
        if (type instanceof ArrowType.FloatingPoint fp) {
            return switch (fp.getPrecision()) {
                case HALF -> "Float16";
                case SINGLE -> "Float32";
                case DOUBLE -> "Float64";
            };
        }
        if (type instanceof ArrowType.Utf8) {
            return "Utf8";
        }
        if (type instanceof ArrowType.Bool) {
            return "Boolean";
        }
        if (type instanceof ArrowType.Timestamp ts) {
            String unit =
                    switch (ts.getUnit()) {
                        case SECOND -> "s";
                        case MILLISECOND -> "ms";
                        case MICROSECOND -> "us";
                        case NANOSECOND -> "ns";
                    };
            return ts.getTimezone() == null
                    ? "Timestamp(" + unit + ")"
                    : "Timestamp(" + unit + ", " + ts.getTimezone() + ")";
        }
        if (type instanceof ArrowType.Binary) {
            return "Binary";
        }
        return "Other";
    }
}
