package tools.jackson.databind;

import java.util.*;

import tools.jackson.core.JacksonException;

import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter that hooks explicitly declared field-deletion markers into the
 * regular {@link ObjectReader} "update existing object" data flow
 * (see {@link ObjectReader#withValueToUpdate(Object)}).
 *<p>
 * Usage: build an adapter around a typed {@link ObjectReader} via
 * {@link #forReader(ObjectReader)}, then call {@link #update(Object, String)}
 * or {@link #update(Object, JsonNode)}. Any Object property whose value is a
 * String equal to the deletion marker (default {@value #DEFAULT_DELETION_MARKER})
 * is treated as a request to remove that exact Map key from the target object;
 * all other content, including {@code null} values, is bound by the
 * underlying {@code ObjectReader} exactly as before, so {@code null} handling
 * still follows the original configuration of the reader.
 *<p>
 * Semantics:
 *<ul>
 *  <li>All deletion paths are collected and validated against the target
 *    <em>before</em> anything is bound or removed: if any path is illegal
 *    (traverses a non-Map value, or names a key that does not exist),
 *    the update fails with {@link IllegalArgumentException} and the target
 *    object is left completely unmodified.</li>
 *  <li>Deletions are applied to the exact declared path only: a key with the
 *    same name at any other path is never removed.</li>
 *  <li>Markers appearing inside arrays are rejected, since they cannot be
 *    mapped to a Map key.</li>
 *</ul>
 */
public class MapKeyDeletionUpdater
{
    /**
     * Default String value interpreted as "delete this Map key".
     */
    public final static String DEFAULT_DELETION_MARKER = "__DELETE__";

    /**
     * Underlying reader used for the regular (non-deletion) part of the
     * update data flow.
     */
    protected final ObjectReader _reader;

    protected final String _deletionMarker;

    protected MapKeyDeletionUpdater(ObjectReader reader, String deletionMarker) {
        _reader = Objects.requireNonNull(reader, "reader");
        if (deletionMarker == null || deletionMarker.isEmpty()) {
            throw new IllegalArgumentException("deletionMarker must be a non-empty String");
        }
        _deletionMarker = deletionMarker;
    }

    /**
     * Adapter entry point: wraps the given (typically typed) reader, using
     * {@link #DEFAULT_DELETION_MARKER} as the deletion marker.
     */
    public static MapKeyDeletionUpdater forReader(ObjectReader reader) {
        return new MapKeyDeletionUpdater(reader, DEFAULT_DELETION_MARKER);
    }

    /**
     * Returns an adapter that uses a custom deletion marker value.
     */
    public MapKeyDeletionUpdater withDeletionMarker(String marker) {
        return new MapKeyDeletionUpdater(_reader, marker);
    }

    public String deletionMarker() {
        return _deletionMarker;
    }

    /**
     * Parses {@code json}, then updates {@code target} applying declared
     * deletions as described for this class.
     */
    public <T> T update(T target, String json) throws JacksonException {
        return update(target, _reader.readTree(json));
    }

    /**
     * Updates {@code target} from {@code updateTree}: validates all declared
     * deletion paths first (target untouched on failure), then binds the
     * remaining content through the regular updating {@link ObjectReader}
     * (so plain {@code null} values follow the original configuration of the
     * reader), and finally removes the declared Map keys from the result.
     */
    @SuppressWarnings("unchecked")
    public <T> T update(T target, JsonNode updateTree) throws JacksonException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(updateTree, "updateTree");

        // 1. Collect every declared deletion path (fails on markers inside arrays)
        List<List<String>> deletionPaths = new ArrayList<>();
        _collectDeletionPaths(updateTree, new ArrayList<String>(), deletionPaths);

        // 2. Validate ALL paths against the target before modifying anything
        for (List<String> path : deletionPaths) {
            _validatePath(target, path);
        }

        // 3. Bind the rest through the regular updating reader: markers are
        //    stripped from a copy, everything else (incl. nulls) flows through
        //    the original configuration of the reader
        JsonNode remaining = updateTree.deepCopy();
        _stripMarkers(remaining);
        Object result = _reader.withValueToUpdate(target).readValue(remaining);

        // 4. Apply deletions at the exact declared paths only
        for (List<String> path : deletionPaths) {
            _deleteAtPath(result, path);
        }
        return (T) result;
    }

    private void _collectDeletionPaths(JsonNode node, List<String> path,
            List<List<String>> paths)
    {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode value = entry.getValue();
                if (_isMarker(value)) {
                    paths.add(_append(path, entry.getKey()));
                } else {
                    _collectDeletionPaths(value, _append(path, entry.getKey()), paths);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (_isMarker(element)) {
                    throw new IllegalArgumentException(
                            "Deletion marker '" + _deletionMarker
                            + "' not allowed inside an Array (at " + _pathDesc(path)
                            + "): deletion only applies to Map keys");
                }
                _collectDeletionPaths(element, path, paths);
            }
        }
    }

    private boolean _isMarker(JsonNode node) {
        return node.isTextual() && _deletionMarker.equals(node.asText());
    }

    private void _stripMarkers(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> toRemove = null;
            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                if (_isMarker(entry.getValue())) {
                    if (toRemove == null) {
                        toRemove = new ArrayList<>();
                    }
                    toRemove.add(entry.getKey());
                } else {
                    _stripMarkers(entry.getValue());
                }
            }
            if (toRemove != null) {
                for (String key : toRemove) {
                    obj.remove(key);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                _stripMarkers(element);
            }
        }
    }

    private void _validatePath(Object target, List<String> path) {
        Object curr = target;
        for (int i = 0; i < path.size(); i++) {
            String key = path.get(i);
            if (!(curr instanceof Map)) {
                throw new IllegalArgumentException(
                        "Illegal deletion path " + _pathDesc(path) + ": segment '" + key
                        + "' does not resolve to a Map key (parent is "
                        + (curr == null ? "null" : curr.getClass().getName()) + ")");
            }
            Map<?, ?> map = (Map<?, ?>) curr;
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Illegal deletion path " + _pathDesc(path) + ": no key '" + key
                        + "' in target Map");
            }
            curr = map.get(key);
        }
    }

    @SuppressWarnings("unchecked")
    private void _deleteAtPath(Object target, List<String> path) {
        Object curr = target;
        for (int i = 0; i < path.size() - 1; i++) {
            curr = ((Map<String, Object>) curr).get(path.get(i));
            if (!(curr instanceof Map)) {
                throw new IllegalStateException(
                        "Deletion path " + _pathDesc(path)
                        + " no longer resolves to a Map after update");
            }
        }
        ((Map<String, Object>) curr).remove(path.get(path.size() - 1));
    }

    private static List<String> _append(List<String> path, String key) {
        List<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(key);
        return result;
    }

    private static String _pathDesc(List<String> path) {
        if (path.isEmpty()) {
            return "<root>";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : path) {
            sb.append('/').append(key);
        }
        return sb.toString();
    }
}
