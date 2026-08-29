package io.laminardb.internal;

import io.laminardb.LaminarIngestionException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Friendly-row conversion (plan 02 §3), mirroring the Python binding's
 * conversion layer: maps to Arrow vectors on insert, vectors to Java values
 * on read.
 */
public final class RowConverter {

    private RowConverter() {}

    /** Converts row maps into a freshly allocated root matching {@code schema}. */
    public static VectorSchemaRoot toRoot(List<Map<String, ?>> rows, Schema schema, BufferAllocator allocator) {
        VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
        try {
            root.allocateNew();
            fill(root, rows);
            root.setRowCount(rows.size());
            return root;
        } catch (RuntimeException e) {
            // The root never escapes on failure; release it here.
            root.close();
            throw e;
        }
    }

    private static void fill(VectorSchemaRoot root, List<Map<String, ?>> rows) {
        List<String> missing = new ArrayList<>();
        for (Field field : root.getSchema().getFields()) {
            if (root.getVector(field.getName()) == null) {
                missing.add(field.getName());
            }
        }
        if (!missing.isEmpty()) {
            throw ingestion("schema has fields absent from the source: " + missing, null);
        }
        for (int row = 0; row < rows.size(); row++) {
            Map<String, ?> map = rows.get(row);
            for (Field field : root.getSchema().getFields()) {
                Object value = map.get(field.getName());
                set(root.getVector(field.getName()), field, row, value);
            }
        }
    }

    private static void set(FieldVector vector, Field field, int row, Object value) {
        if (value == null) {
            if (!field.isNullable()) {
                throw ingestion("null for non-nullable field", field.getName());
            }
            vector.setNull(row);
            return;
        }
        if (vector instanceof IntVector v) {
            if (value instanceof Integer i) {
                v.setSafe(row, i);
                return;
            }
        } else if (vector instanceof BigIntVector v) {
            if (value instanceof Long l) {
                v.setSafe(row, l);
                return;
            }
            if (value instanceof Integer i) {
                v.setSafe(row, i.longValue());
                return;
            }
        } else if (vector instanceof Float4Vector v) {
            if (value instanceof Float f) {
                v.setSafe(row, f);
                return;
            }
        } else if (vector instanceof Float8Vector v) {
            if (value instanceof Double d) {
                v.setSafe(row, d);
                return;
            }
            if (value instanceof Float f) {
                v.setSafe(row, f.doubleValue());
                return;
            }
        } else if (vector instanceof VarCharVector v) {
            if (value instanceof String s) {
                v.setSafe(row, s.getBytes(StandardCharsets.UTF_8));
                return;
            }
        } else if (vector instanceof VarBinaryVector v) {
            if (value instanceof byte[] b) {
                v.setSafe(row, b);
                return;
            }
        } else if (vector instanceof BitVector v) {
            if (value instanceof Boolean b) {
                v.setSafe(row, b ? 1 : 0);
                return;
            }
        } else if (vector instanceof TimeStampMilliVector v) {
            if (setMillis(v, row, value)) {
                return;
            }
        } else if (vector instanceof TimeStampMicroVector v) {
            if (value instanceof Instant t) {
                v.setSafe(row, toMicros(t));
                return;
            }
            if (value instanceof Long l) {
                v.setSafe(row, l);
                return;
            }
        } else if (vector instanceof TimeStampNanoVector v) {
            if (value instanceof Instant t) {
                v.setSafe(row, toNanos(t));
                return;
            }
            if (value instanceof Long l) {
                v.setSafe(row, l);
                return;
            }
        } else if (vector instanceof DateMilliVector v) {
            if (value instanceof LocalDate d) {
                v.setSafe(row, d.toEpochDay());
                return;
            }
        }
        throw ingestion(
                "cannot convert " + value.getClass().getSimpleName() + " for field type " + field.getType(),
                field.getName());
    }

    private static boolean setMillis(TimeStampMilliVector vector, int row, Object value) {
        if (value instanceof Instant t) {
            vector.setSafe(row, t.toEpochMilli());
            return true;
        }
        if (value instanceof Long l) {
            // Long is interpreted as epoch millis per the documented rule.
            vector.setSafe(row, l);
            return true;
        }
        return false;
    }

    /** Materializes a root as row maps, one entry per row. */
    public static List<Map<String, Object>> toMaps(VectorSchemaRoot root) {
        List<Map<String, Object>> rows = new ArrayList<>(root.getRowCount());
        for (List<Object> row : toRows(root)) {
            Map<String, Object> map = new HashMap<>();
            List<Field> fields = root.getSchema().getFields();
            for (int i = 0; i < fields.size(); i++) {
                map.put(fields.get(i).getName(), row.get(i));
            }
            rows.add(map);
        }
        return rows;
    }

    /** Materializes a root as positional row lists. */
    public static List<List<Object>> toRows(VectorSchemaRoot root) {
        int columns = root.getSchema().getFields().size();
        List<List<Object>> rows = new ArrayList<>(root.getRowCount());
        for (int row = 0; row < root.getRowCount(); row++) {
            List<Object> values = new ArrayList<>(columns);
            for (int column = 0; column < columns; column++) {
                values.add(get(root.getVector(column), row));
            }
            rows.add(values);
        }
        return rows;
    }

    private static Object get(FieldVector vector, int row) {
        if (vector.isNull(row)) {
            return null;
        }
        if (vector instanceof IntVector v) {
            return v.get(row);
        }
        if (vector instanceof BigIntVector v) {
            return v.get(row);
        }
        if (vector instanceof Float4Vector v) {
            return v.get(row);
        }
        if (vector instanceof Float8Vector v) {
            return v.get(row);
        }
        if (vector instanceof VarCharVector v) {
            return new String(v.get(row), StandardCharsets.UTF_8);
        }
        if (vector instanceof VarBinaryVector v) {
            return v.get(row);
        }
        if (vector instanceof BitVector v) {
            return v.get(row) != 0;
        }
        if (vector instanceof TimeStampMilliVector v) {
            return Instant.ofEpochMilli(v.get(row));
        }
        if (vector instanceof TimeStampMicroVector v) {
            return Instant.ofEpochSecond(
                    Math.floorDiv(v.get(row), 1_000_000L), Math.floorMod(v.get(row), 1_000_000L) * 1_000L);
        }
        if (vector instanceof TimeStampNanoVector v) {
            return Instant.ofEpochSecond(
                    Math.floorDiv(v.get(row), 1_000_000_000L), Math.floorMod(v.get(row), 1_000_000_000L));
        }
        if (vector instanceof DateMilliVector v) {
            return LocalDate.ofEpochDay(v.get(row));
        }
        if (vector.getField().getType() instanceof ArrowType.Timestamp) {
            // Future/other units fall back to arrow-java's own materialization.
            return vector.getObject(row);
        }
        return vector.getObject(row);
    }

    private static LaminarIngestionException ingestion(String message, String field) {
        String detail = field == null ? message : message + " (field '" + field + "')";
        return new LaminarIngestionException(detail, 302);
    }

    private static long toMicros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000L);
    }

    private static long toNanos(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
    }
}
