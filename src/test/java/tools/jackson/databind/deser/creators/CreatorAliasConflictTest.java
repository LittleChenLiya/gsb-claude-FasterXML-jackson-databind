package tools.jackson.databind.deser.creators;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.exc.InvalidDefinitionException;

import static org.junit.jupiter.api.Assertions.*;

import static tools.jackson.databind.testutil.DatabindTestUtil.newJsonMapper;
import static tools.jackson.databind.testutil.DatabindTestUtil.verifyException;

/**
 * Tests for the pre-check that detects two distinct creator (constructor)
 * parameters bound to the same input name via {@code @JsonAlias}: the
 * conflict must be reported before the constructor is ever invoked.
 */
public class CreatorAliasConflictTest
{
    // Example object 1: legal creator, distinct aliases -- must keep working
    static class ValidAliasBean {
        static final AtomicInteger CONSTRUCT_COUNT = new AtomicInteger(0);

        final String name;
        final int value;

        @JsonCreator
        public ValidAliasBean(@JsonProperty("name") @JsonAlias("nm") String name,
                @JsonProperty("value") @JsonAlias("val") int value) {
            CONSTRUCT_COUNT.incrementAndGet();
            this.name = name;
            this.value = value;
        }
    }

    // Example object 2: two constructor parameters share one alias -- must be
    // rejected before the constructor runs
    static class ConflictingAliasBean {
        static final AtomicInteger CONSTRUCT_COUNT = new AtomicInteger(0);

        final String first;
        final String second;

        @JsonCreator
        public ConflictingAliasBean(@JsonProperty("first") @JsonAlias("shared") String first,
                @JsonProperty("second") @JsonAlias("shared") String second) {
            CONSTRUCT_COUNT.incrementAndGet();
            this.first = first;
            this.second = second;
        }
    }

    private final ObjectMapper MAPPER = newJsonMapper();

    @Test
    public void validCreatorAliasesReadOk() throws Exception
    {
        ValidAliasBean.CONSTRUCT_COUNT.set(0);
        // primary names
        ValidAliasBean bean = MAPPER.readValue(
                "{\"name\":\"bob\",\"value\":42}", ValidAliasBean.class);
        assertEquals("bob", bean.name);
        assertEquals(42, bean.value);
        // aliases
        bean = MAPPER.readValue("{\"nm\":\"alice\",\"val\":7}", ValidAliasBean.class);
        assertEquals("alice", bean.name);
        assertEquals(7, bean.value);
        assertEquals(2, ValidAliasBean.CONSTRUCT_COUNT.get());
    }

    @Test
    public void validCreatorAliasesViaObjectReader() throws Exception
    {
        // Normal ObjectReader path must remain compatible
        ObjectReader reader = MAPPER.readerFor(ValidAliasBean.class);
        ValidAliasBean bean = reader.readValue("{\"nm\":\"x\",\"val\":1}");
        assertEquals("x", bean.name);
        assertEquals(1, bean.value);
    }

    @Test
    public void conflictingCreatorAliasesRejectedBeforeConstruction() throws Exception
    {
        ConflictingAliasBean.CONSTRUCT_COUNT.set(0);
        try {
            MAPPER.readValue("{\"shared\":\"x\"}", ConflictingAliasBean.class);
            fail("Should not pass with conflicting creator aliases");
        } catch (InvalidDefinitionException e) {
            verifyException(e, "Conflicting creator parameter aliases");
            // both sides of the conflict must be reported
            verifyException(e, "\"shared\"");
            verifyException(e, "parameter #0");
            verifyException(e, "parameter #1");
            verifyException(e, "\"first\"");
            verifyException(e, "\"second\"");
        }
        // constructor must never have executed
        assertEquals(0, ConflictingAliasBean.CONSTRUCT_COUNT.get());
    }

    @Test
    public void conflictingCreatorAliasesRejectedViaObjectReader() throws Exception
    {
        ConflictingAliasBean.CONSTRUCT_COUNT.set(0);
        ObjectReader reader = MAPPER.readerFor(ConflictingAliasBean.class);
        try {
            reader.readValue("{\"first\":\"a\",\"second\":\"b\"}");
            fail("Should not pass with conflicting creator aliases");
        } catch (InvalidDefinitionException e) {
            verifyException(e, "Conflicting creator parameter aliases");
        }
        assertEquals(0, ConflictingAliasBean.CONSTRUCT_COUNT.get());
    }
}
