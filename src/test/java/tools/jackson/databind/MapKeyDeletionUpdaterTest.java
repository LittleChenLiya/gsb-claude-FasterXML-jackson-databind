package tools.jackson.databind;

import java.util.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.testutil.DatabindTestUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MapKeyDeletionUpdater}: explicit deletion markers hooked
 * into the {@link ObjectReader} update data flow, plus a short regression
 * example of the original (un-adapted) update interface.
 */
public class MapKeyDeletionUpdaterTest extends DatabindTestUtil
{
    private final ObjectMapper MAPPER = newJsonMapper();

    private final static String MARKER = MapKeyDeletionUpdater.DEFAULT_DELETION_MARKER;

    // Two-level Map fixture: same-name key "x" exists under both "a" and "b"
    private Map<String, Object> twoLevelTarget() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("y", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("x", 10);
        b.put("z", 20);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("a", a);
        root.put("b", b);
        return root;
    }

    private MapKeyDeletionUpdater updater() {
        return MapKeyDeletionUpdater.forReader(MAPPER.readerFor(Map.class));
    }

    @Test
    public void deleteOnlyTargetedKeyAmongSameNameKeys() throws Exception
    {
        Map<String, Object> target = twoLevelTarget();
        String before = MAPPER.writeValueAsString(target);

        Map<String, Object> result = updater().update(target,
                a2q("{'a':{'x':'" + MARKER + "'}}"));
        String after = MAPPER.writeValueAsString(result);
        System.out.println("[delete-targeted] before=" + before);
        System.out.println("[delete-targeted] after =" + after);

        Map<?, ?> a = (Map<?, ?>) result.get("a");
        Map<?, ?> b = (Map<?, ?>) result.get("b");
        // targeted key removed
        assertFalse(a.containsKey("x"));
        // sibling key untouched
        assertEquals(2, a.get("y"));
        // same-name key at other path NOT removed
        assertEquals(10, b.get("x"));
        assertEquals(20, b.get("z"));
    }

    @Test
    public void plainNullStillFollowsOriginalConfig() throws Exception
    {
        Map<String, Object> target = twoLevelTarget();

        // deletion marker and plain null mixed in one update document
        Map<String, Object> result = updater().update(target,
                a2q("{'a':{'x':'" + MARKER + "','y':null}}"));
        String after = MAPPER.writeValueAsString(result);
        System.out.println("[delete+null] after =" + after);

        Map<?, ?> a = (Map<?, ?>) result.get("a");
        assertFalse(a.containsKey("x"));
        // default config: null is bound as a null Map entry (not skipped, not deleted)
        assertTrue(a.containsKey("y"));
        assertNull(a.get("y"));
        // other branch fully intact
        assertEquals(Map.of("x", 10, "z", 20), result.get("b"));
    }

    @Test
    public void invalidDeletionPathLeavesWholeTargetUnchanged() throws Exception
    {
        // unknown nested key
        _assertFailsAtomically(a2q("{'a':{'nope':'" + MARKER + "'}}"));
        // unknown root key
        _assertFailsAtomically(a2q("{'ghost':{'x':'" + MARKER + "'}}"));
        // path traverses a non-Map value ("y" is an Integer)
        _assertFailsAtomically(a2q("{'a':{'y':{'deep':'" + MARKER + "'}}}"));
        // marker inside an array cannot map to a Map key
        _assertFailsAtomically(a2q("{'arr':['" + MARKER + "']}"));
    }

    @Test
    public void oneBadPathAmongManyAbortsEverything() throws Exception
    {
        // first deletion path is valid, second is not: nothing may change
        Map<String, Object> target = twoLevelTarget();
        String before = MAPPER.writeValueAsString(target);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> updater().update(target,
                        a2q("{'a':{'x':'" + MARKER + "'},'b':{'nope':'" + MARKER + "'}}")));
        System.out.println("[mixed-invalid] error=" + e.getMessage());
        System.out.println("[mixed-invalid] target=" + MAPPER.writeValueAsString(target));

        assertEquals(before, MAPPER.writeValueAsString(target));
        Map<?, ?> a = (Map<?, ?>) target.get("a");
        assertTrue(a.containsKey("x"));
    }

    private void _assertFailsAtomically(String json) throws Exception
    {
        Map<String, Object> target = twoLevelTarget();
        String before = MAPPER.writeValueAsString(target);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> updater().update(target, json));
        System.out.println("[invalid-path] json=" + json);
        System.out.println("[invalid-path] error=" + e.getMessage());
        System.out.println("[invalid-path] target=" + MAPPER.writeValueAsString(target));

        // whole object must be unmodified
        assertEquals(before, MAPPER.writeValueAsString(target));
        assertEquals(twoLevelTarget(), target);
    }

    /*
    /**********************************************************************
    /* Regression: original update interface (no deletion adapter)
    /**********************************************************************
     */

    @Test
    public void regressionOriginalReaderForUpdating() throws Exception
    {
        Map<String, Object> target = twoLevelTarget();

        // Original interface: no deletion semantics; the marker String is
        // bound as a plain value and null follows default config (null entry)
        Map<String, Object> result = MAPPER.readerForUpdating(target)
                .readValue(a2q("{'a':{'x':'" + MARKER + "','y':null}}"));
        String after = MAPPER.writeValueAsString(result);
        System.out.println("[regression-original] after=" + after);

        Map<?, ?> a = (Map<?, ?>) result.get("a");
        assertEquals(MARKER, a.get("x"));
        assertTrue(a.containsKey("y"));
        assertNull(a.get("y"));
        // nested maps still merge; other branch untouched
        assertEquals(Map.of("x", 10, "z", 20), result.get("b"));
    }
}
