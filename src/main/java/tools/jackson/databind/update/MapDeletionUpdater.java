package tools.jackson.databind.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter that plugs the explicit {@link DeletionMarker} contract into the
 * existing {@link ObjectReader} update data flow for {@link Map} values.
 *<p>
 * Processing order is chosen so that the target object is never modified on
 * failure:
 * <ol>
 *  <li>Walk the {@code overrides} tree, collect every declared deletion path
 *      ({@link DeletionMarker} leaf) and produce a cleaned tree in which the
 *      markers are omitted; plain {@code null} values are kept so that they
 *      keep going through the original {@code null}-handling
 *      configuration.</li>
 *  <li>Validate <b>all</b> deletion paths against the effective result of the
 *      upcoming merge; any invalid path aborts the operation before the target
 *      is touched.</li>
 *  <li>Run the normal update merge through {@link ObjectReader#readValue}.</li>
 *  <li>Apply the validated deletions to the merged target. Only the exact
 *      declared paths are removed: same-named keys in other paths are left
 *      untouched.</li>
 * </ol>
 */
public final class MapDeletionUpdater
{
    private final ObjectReader _reader;

    public MapDeletionUpdater(ObjectReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader");
        }
        _reader = reader;
    }

    /**
     * Update given root Map with values from given overrides Map, honoring
     * {@link DeletionMarker} values as requests to remove the corresponding
     * existing keys; any other {@code null} keeps going through the original
     * update configuration.
     *
     * @param valueToUpdate root Map to update (returned as-is when successful)
     * @param overrides Map of override values; may contain
     *     {@link DeletionMarker} leaves
     *
     * @return the updated {@code valueToUpdate}
     *
     * @throws IllegalArgumentException if an argument is invalid or a declared
     *     deletion path cannot be applied; in that case {@code valueToUpdate}
     *     is left completely unchanged
     */
    public <T> T update(T valueToUpdate, Map<?,?> overrides)
    {
        if (valueToUpdate == null) {
            return null;
        }
        if (!(valueToUpdate instanceof Map)) {
            throw new IllegalArgumentException(
                    "Value to update must be a java.util.Map, got: "
                    +valueToUpdate.getClass().getName());
        }
        if (overrides == null) {
            return valueToUpdate;
        }
        // 1. Collect deletion paths and build cleaned override tree
        List<List<String>> paths = new ArrayList<>();
        ObjectNode cleaned = JsonNodeFactory.instance.objectNode();
        collectMap(overrides, cleaned, new ArrayList<String>(), paths);

        // 2. Validate every declared path against the pre-merge target;
        //    if any one is illegal, nothing has been touched yet
        @SuppressWarnings("unchecked")
        Map<?,?> root = (Map<?,?>) valueToUpdate;
        for (List<String> path : paths) {
            validatePath(root, path);
        }

        // 3. Normal merge through the existing update data flow
        @SuppressWarnings("unchecked")
        T result = (T) _reader.withValueToUpdate(valueToUpdate).readValue(cleaned);

        // 4. Apply validated deletions; same-named keys elsewhere are unaffected
        if (!paths.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<?,?> merged = (Map<?,?>) result;
            for (List<String> path : paths) {
                applyPath(merged, path);
            }
        }
        return result;
    }

    /*
    /**********************************************************************
    /* Collection: split delete markers from regular overrides
    /**********************************************************************
     */

    private void collectMap(Map<?,?> src, ObjectNode dst,
            List<String> path, List<List<String>> paths)
    {
        for (Map.Entry<?,?> entry : src.entrySet()) {
            String key = asKey(entry.getKey(), path);
            Object v = entry.getValue();
            if (v instanceof DeletionMarker) {
                List<String> p = new ArrayList<>(path.size() + 1);
                p.addAll(path);
                p.add(key);
                paths.add(p);
                continue;
            }
            path.add(key);
            dst.set(key, toNode(v, path, paths));
            path.remove(path.size() - 1);
        }
    }

    private JsonNode toNode(Object v,
            List<String> path, List<List<String>> paths)
    {
        if (v == null) {
            // Plain null: kept, goes through original null handling
            return JsonNodeFactory.instance.nullNode();
        }
        if (v instanceof Map) {
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            collectMap((Map<?,?>) v, obj, path, paths);
            return obj;
        }
        if (v instanceof List) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            int i = 0;
            for (Object item : (List<?>) v) {
                if (item instanceof DeletionMarker) {
                    throw new IllegalArgumentException(
                            "Deletion marker not supported inside arrays at path "
                            +formatPath(path, "["+i+"]"));
                }
                arr.add(toNode(item, path, paths));
                ++i;
            }
            return arr;
        }
        if (v instanceof JsonNode) {
            return (JsonNode) v;
        }
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (v instanceof String) {
            return f.textNode((String) v);
        }
        if (v instanceof Boolean) {
            return f.booleanNode((Boolean) v);
        }
        if (v instanceof Integer) {
            return f.numberNode((Integer) v);
        }
        if (v instanceof Long) {
            return f.numberNode((Long) v);
        }
        if (v instanceof Short) {
            return f.numberNode(((Number) v).shortValue());
        }
        if (v instanceof Byte) {
            return f.numberNode(((Number) v).byteValue());
        }
        if (v instanceof Double) {
            return f.numberNode((Double) v);
        }
        if (v instanceof Float) {
            return f.numberNode((Float) v);
        }
        if (v instanceof java.math.BigDecimal) {
            return f.numberNode((java.math.BigDecimal) v);
        }
        if (v instanceof java.math.BigInteger) {
            return f.numberNode((java.math.BigInteger) v);
        }
        if (v instanceof byte[]) {
            return f.binaryNode((byte[]) v);
        }
        throw new IllegalArgumentException(
                "Unsupported override value type at path "+formatPath(path, null)
                +": "+v.getClass().getName());
    }

    private String asKey(Object key, List<String> path) {
        if (!(key instanceof String)) {
            throw new IllegalArgumentException(
                    "Only String map keys supported at path "
                    +formatPath(path, key == null ? null : String.valueOf(key))
                    +" (got "+(key == null ? "null" : key.getClass().getName())+")");
        }
        return (String) key;
    }

    /*
    /**********************************************************************
    /* Validation: verify every delete path on the effective merged view
    /**********************************************************************
     */

    /**
     * Validate a declared deletion path against the pre-merge target.
     * Every intermediate segment must resolve to a nested {@link Map};
     * a leaf key missing from the target is an idempotent no-op.
     */
    private void validatePath(Map<?,?> target, List<String> path)
    {
        Map<?,?> cur = target;
        for (int i = 0, end = path.size(); i < end; ++i) {
            String seg = path.get(i);
            boolean leaf = (i == end - 1);
            Object child = cur.get(seg);
            if (leaf) {
                // Existing key of any type (including null) can be removed;
                // an absent key is simply a no-op
                return;
            }
            if (!(child instanceof Map)) {
                throw invalidPath(path, i);
            }
            cur = (Map<?,?>) child;
        }
    }

    private IllegalArgumentException invalidPath(List<String> path, int failAt) {
        return new IllegalArgumentException(
                "Invalid deletion path "+formatPath(path, null)
                +": segment "+quote(path.get(failAt))
                +" does not resolve to an object Map");
    }

    /*
    /**********************************************************************
    /* Application of validated deletions
    /**********************************************************************
     */

    @SuppressWarnings("unchecked")
    private void applyPath(Map<?,?> root, List<String> path)
    {
        Map<String,Object> m = (Map<String,Object>) root;
        for (int i = 0, end = path.size() - 1; i < end; ++i) {
            Object child = m.get(path.get(i));
            if (!(child instanceof Map)) {
                // Defensive: validatePath() already ruled this out
                return;
            }
            m = (Map<String,Object>) child;
        }
        m.remove(path.get(path.size() - 1));
    }

    /*
    /**********************************************************************
    /* Formatting
    /**********************************************************************
     */

    private String formatPath(List<String> path, String extra) {
        StringBuilder sb = new StringBuilder("$");
        for (String seg : path) {
            sb.append('.').append(seg);
        }
        if (extra != null) {
            sb.append(extra);
        }
        return sb.toString();
    }

    private String quote(String s) {
        return "'" + s + "'";
    }
}

