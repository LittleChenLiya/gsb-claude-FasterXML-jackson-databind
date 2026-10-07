package tools.jackson.databind.update;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import static org.junit.jupiter.api.Assertions.*;

import static tools.jackson.databind.testutil.DatabindTestUtil.newJsonMapper;

/**
 * Tests for the {@link DeletionMarker} adapter plugged into the
 * {@link ObjectReader} update data flow ({@link ObjectReader#updateValue}).
 *<p>
 * The fixtures use two layers of Maps with same-named keys ("name") so that
 * targeted deletion, ordinary {@code null} handling and illegal-path
 * behavior can all be checked explicitly.
 */
public class MapDeletionUpdaterTest
{
    private final ObjectMapper MAPPER = newJsonMapper();
    private final ObjectReader READER = MAPPER.reader();

    /**
     * Builds the canonical fixture:
     *<pre>
     * { "name": "root",
     *   "outer": { "name": "nested", "keep": 1 },
     *   "other": { "name": "sibling" } }
     *</pre>
     * where both the root map and nested maps carry the same key "name".
     */
    private Map<String,Object> base() {
        Map<String,Object> outer = new LinkedHashMap<>();
        outer.put("name", "nested");
        outer.put("keep", Integer.valueOf(1));
        Map<String,Object> other = new LinkedHashMap<>();
        other.put("name", "sibling");
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("name", "root");
        root.put("outer", outer);
        root.put("other", other);
        return root;
    }

    private Map<String,Object> deepCopy(Map<String,Object> src) {
        Map<String,Object> dst = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : src.entrySet()) {
            Object v = e.getValue();
            dst.put(e.getKey(), (v instanceof Map<?,?>)
                    ? deepCopy((Map<String,Object>) v) : v);
        }
        return dst;
    }

    @Test
    public void testNestedDeletionRemovesOnlyTargetKey()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        Map<String,Object> outerPatch = new LinkedHashMap<>();
        outerPatch.put("name", DeletionMarker.INSTANCE);
        overrides.put("outer", outerPatch);

        Map<String,Object> result = READER.updateValue(data, overrides);
        assertSame(data, result);

        // Target key is gone...
        @SuppressWarnings("unchecked")
        Map<String,Object> outer = (Map<String,Object>) result.get("outer");
        assertFalse(outer.containsKey("name"));
        // ...sibling keys in the same nested map survive...
        assertEquals(Integer.valueOf(1), outer.get("keep"));
        // ...and same-named keys in other paths are left untouched
        assertEquals("root", result.get("name"));
        @SuppressWarnings("unchecked")
        Map<String,Object> other = (Map<String,Object>) result.get("other");
        assertEquals("sibling", other.get("name"));
    }

    @Test
    public void testRootLevelDeletion()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        overrides.put("name", DeletionMarker.INSTANCE);

        Map<String,Object> result = READER.updateValue(data, overrides);
        assertFalse(result.containsKey("name"));
        @SuppressWarnings("unchecked")
        Map<String,Object> outer = (Map<String,Object>) result.get("outer");
        assertEquals("nested", outer.get("name"));
    }

    @Test
    public void testPlainNullStillUsesOriginalConfiguration()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        Map<String,Object> outerPatch = new LinkedHashMap<>();
        // ordinary null: NOT a deletion request
        outerPatch.put("name", null);
        overrides.put("outer", outerPatch);

        Map<String,Object> result = READER.updateValue(data, overrides);
        @SuppressWarnings("unchecked")
        Map<String,Object> outer = (Map<String,Object>) result.get("outer");
        assertTrue(outer.containsKey("name"));
        assertNull(outer.get("name"));
        // root same-named key unaffected
        assertEquals("root", result.get("name"));
    }

    @Test
    public void testMarkerAndPlainNullAndNormalUpdateMixed()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        overrides.put("name", DeletionMarker.INSTANCE);
        Map<String,Object> outerPatch = new LinkedHashMap<>();
        outerPatch.put("name", null);              // null as configured
        outerPatch.put("keep", DeletionMarker.INSTANCE); // delete
        outerPatch.put("added", "x");              // normal merge
        overrides.put("outer", outerPatch);

        Map<String,Object> result = READER.updateValue(data, overrides);
        assertFalse(result.containsKey("name"));
        @SuppressWarnings("unchecked")
        Map<String,Object> outer = (Map<String,Object>) result.get("outer");
        assertTrue(outer.containsKey("name"));
        assertNull(outer.get("name"));
        assertFalse(outer.containsKey("keep"));
        assertEquals("x", outer.get("added"));
    }

    @Test
    public void testIllegalPathLeavesEntireObjectUnchanged()
    {
        Map<String,Object> data = base();
        Map<String,Object> expected = deepCopy(data);

        // "other.name" exists, but "other.name.deeper" cannot resolve because
        // "other.name" is a scalar String, not a nested Map
        Map<String,Object> overrides = new LinkedHashMap<>();
        Map<String,Object> otherPatch = new LinkedHashMap<>();
        Map<String,Object> namePatch = new LinkedHashMap<>();
        namePatch.put("deeper", DeletionMarker.INSTANCE);
        otherPatch.put("name", namePatch);
        overrides.put("other", otherPatch);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> READER.updateValue(data, overrides));
        assertTrue(e.getMessage().contains("$.other.name.deeper"));

        // Entire object unchanged: not even unrelated parts of the patch applied
        assertEquals(expected, data);
    }

    @Test
    public void testIllegalRootSegmentLeavesObjectUnchanged()
    {
        Map<String,Object> data = base();
        Map<String,Object> expected = deepCopy(data);

        // "name" at root is a scalar String, cannot descend through it
        Map<String,Object> overrides = new LinkedHashMap<>();
        Map<String,Object> namePatch = new LinkedHashMap<>();
        namePatch.put("deeper", DeletionMarker.INSTANCE);
        overrides.put("name", namePatch);

        assertThrows(IllegalArgumentException.class,
                () -> READER.updateValue(data, overrides));
        assertEquals(expected, data);
    }

    @Test
    public void testFailureAfterMultipleDeclaredDeletionsIsAllOrNothing()
    {
        Map<String,Object> data = base();
        Map<String,Object> expected = deepCopy(data);

        Map<String,Object> overrides = new LinkedHashMap<>();
        // valid declaration...
        overrides.put("name", DeletionMarker.INSTANCE);
        // ...and an invalid one in the same patch
        Map<String,Object> otherPatch = new LinkedHashMap<>();
        Map<String,Object> namePatch = new LinkedHashMap<>();
        namePatch.put("deeper", DeletionMarker.INSTANCE);
        otherPatch.put("name", namePatch);
        overrides.put("other", otherPatch);

        assertThrows(IllegalArgumentException.class,
                () -> READER.updateValue(data, overrides));
        assertEquals(expected, data);
    }

    @Test
    public void testIdempotentDeletionOfAbsentLeaf()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        Map<String,Object> outerPatch = new LinkedHashMap<>();
        outerPatch.put("missing", DeletionMarker.INSTANCE);
        overrides.put("outer", outerPatch);

        Map<String,Object> result = READER.updateValue(data, overrides);
        @SuppressWarnings("unchecked")
        Map<String,Object> outer = (Map<String,Object>) result.get("outer");
        assertEquals(2, outer.size());
    }

    @Test
    public void testNoMarkersIsPlainUpdate()
    {
        Map<String,Object> data = base();
        Map<String,Object> overrides = new LinkedHashMap<>();
        overrides.put("extra", Integer.valueOf(7));

        Map<String,Object> result = READER.updateValue(data, overrides);
        assertSame(data, result);
        assertEquals(Integer.valueOf(7), result.get("extra"));
        assertEquals("root", result.get("name"));
    }

    @Test
    public void testNullOverridesAndNullTarget()
    {
        Map<String,Object> data = base();
        assertSame(data, READER.updateValue(data, null));
        assertNull(READER.updateValue(null, new LinkedHashMap<>()));
    }

    @Test
    public void testNonMapTargetRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> READER.updateValue("not-a-map", new LinkedHashMap<>()));
    }
}
