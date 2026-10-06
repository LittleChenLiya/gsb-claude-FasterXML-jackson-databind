package tools.jackson.databind.deser.creators;

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
 * Tests for the pre-check that rejects two distinct creator parameters
 * bound to the same input name via {@code @JsonAlias}: the conflict must
 * be reported (with both parameter positions) before the creator
 * (constructor/factory method) is ever invoked.
 */
public class CreatorAliasConflictTest
{
    // Legal fixture: distinct aliases per creator parameter
    static class ValidAliasBean {
        final String first;
        final String second;

        @JsonCreator
        ValidAliasBean(@JsonProperty("first") @JsonAlias("1st") String first,
                @JsonProperty("second") @JsonAlias("2nd") String second) {
            this.first = first;
            this.second = second;
        }
    }

    // Conflicting fixture: two different creator parameters share one alias
    static class ConflictingAliasBean {
        static int constructionCount;

        final String first;
        final String second;

        @JsonCreator
        ConflictingAliasBean(@JsonProperty("first") @JsonAlias("shared") String first,
                @JsonProperty("second") @JsonAlias("shared") String second) {
            ++constructionCount;
            this.first = first;
            this.second = second;
        }
    }

    private final ObjectMapper MAPPER = newJsonMapper();

    @Test
    void legalCreatorAliasesViaMapper() throws Exception {
        ValidAliasBean result = MAPPER.readValue("""
                {"1st":"a", "second":"b"}
                """, ValidAliasBean.class);
        assertNotNull(result);
        assertEquals("a", result.first);
        assertEquals("b", result.second);
    }

    // Normal ObjectReader path must remain compatible
    @Test
    void legalCreatorAliasesViaObjectReader() throws Exception {
        ObjectReader reader = MAPPER.readerFor(ValidAliasBean.class);
        ValidAliasBean result = reader.readValue("""
                {"first":"a", "2nd":"b"}
                """);
        assertNotNull(result);
        assertEquals("a", result.first);
        assertEquals("b", result.second);
    }

    @Test
    void conflictingCreatorAliasesRejected() throws Exception {
        ConflictingAliasBean.constructionCount = 0;
        try {
            MAPPER.readValue("""
                    {"first":"a", "second":"b"}
                    """, ConflictingAliasBean.class);
            fail("Should not pass with two creator parameters sharing alias \"shared\"");
        } catch (InvalidDefinitionException e) {
            verifyException(e, "Conflicting creator parameter aliases");
            // both sides of the conflict must be reported
            verifyException(e, "\"shared\"");
            verifyException(e, "\"first\"");
            verifyException(e, "\"second\"");
            verifyException(e, "#0");
            verifyException(e, "#1");
        }
        // conflict must be detected before the constructor ever runs
        assertEquals(0, ConflictingAliasBean.constructionCount);
    }

    @Test
    void conflictingCreatorAliasesRejectedViaObjectReader() throws Exception {
        ConflictingAliasBean.constructionCount = 0;
        ObjectReader reader = MAPPER.readerFor(ConflictingAliasBean.class);
        try {
            reader.readValue("""
                    {"shared":"a"}
                    """);
            fail("Should not pass with two creator parameters sharing alias \"shared\"");
        } catch (InvalidDefinitionException e) {
            verifyException(e, "Conflicting creator parameter aliases");
            verifyException(e, "\"shared\"");
        }
        assertEquals(0, ConflictingAliasBean.constructionCount);
    }
}
