package tools.jackson.databind.deser.enums;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.UnknownEnumValue;
import tools.jackson.databind.deser.UnknownEnumValueResult;
import tools.jackson.databind.exc.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.*;

import static tools.jackson.databind.testutil.DatabindTestUtil.newJsonMapper;
import static tools.jackson.databind.testutil.DatabindTestUtil.verifyException;

/**
 * Acceptance tests for unknown-enum-value source recording via local
 * {@code DeserializationProblemHandler} adapter
 * ({@link tools.jackson.databind.deser.RecordingUnknownEnumHandler}).
 *<p>
 * Covers: nested enum with known and unknown textual values under each of the
 * three fallback strategies (default value / null / reject), verifies records
 * (unknown text + field path), results, and per-{@code ObjectReader}-call
 * isolation (no stale records on the next read). Also verifies that when the
 * new option is not enabled, the original interface keeps working unchanged.
 */
public class UnknownEnumValueRecordingTest
{
    enum Color {
        RED, GREEN, BLUE,
        @JsonEnumDefaultValue
        UNKNOWN;
    }

    static class Inner {
        public Color color;
        public String label;
    }

    static class Outer {
        public String name;
        public Inner inner;
    }

    private final static String MIXED_JSON =
            "{\"name\":\"n1\","
            + "\"inner\":{\"color\":\"GREEN\",\"label\":\"ok\"}}";
    private final static String UNKNOWN_JSON =
            "{\"name\":\"n2\","
            + "\"inner\":{\"color\":\"PURPLE\",\"label\":\"bad\"}}";

    private final ObjectMapper MAPPER = newJsonMapper();

    /*
    /**********************************************************************
    /* Strategy 1: unknown enum -> enum default value
    /**********************************************************************
     */

    @Test
    void defaultStrategyRecordsUnknownTextAndPathAndUsesDefault()
    {
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

        // First read: unknown value -> recorded AND default value applied
        UnknownEnumValueResult<Outer> res1 = r.readValueRecordingUnknownEnums(UNKNOWN_JSON);

        assertEquals(Color.UNKNOWN, res1.value().inner.color,
                "unknown enum must resolve to @JsonEnumDefaultValue");
        assertEquals("n2", res1.value().name);
        assertTrue(res1.hasUnknownValues());
        List<UnknownEnumValue> recs1 = res1.unknownValues();
        assertEquals(1, recs1.size());
        UnknownEnumValue rec = recs1.get(0);
        assertEquals("PURPLE", rec.unknownValue());
        assertEquals(Color.class, rec.enumClass());
        assertEquals("/inner/color", rec.path().toString());

        // Second read (known value): no stale records, correct value
        UnknownEnumValueResult<Outer> res2 = r.readValueRecordingUnknownEnums(MIXED_JSON);
        assertEquals(Color.GREEN, res2.value().inner.color);
        assertTrue(res2.unknownValues().isEmpty(),
                "records must be isolated per ObjectReader call");
        assertFalse(res2.hasUnknownValues());
    }

    /*
    /**********************************************************************
    /* Strategy 2: unknown enum -> null
    /**********************************************************************
     */

    @Test
    void nullStrategyRecordsUnknownTextAndPathAndUsesNull()
    {
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

        UnknownEnumValueResult<Outer> res1 = r.readValueRecordingUnknownEnums(UNKNOWN_JSON);

        assertNull(res1.value().inner.color,
                "unknown enum must resolve to null under AS_NULL");
        assertEquals("bad", res1.value().inner.label);
        assertEquals(1, res1.unknownValues().size());
        UnknownEnumValue rec = res1.unknownValues().get(0);
        assertEquals("PURPLE", rec.unknownValue());
        assertEquals("/inner/color", rec.path().toString());

        // Next read has no old records
        UnknownEnumValueResult<Outer> res2 = r.readValueRecordingUnknownEnums(MIXED_JSON);
        assertEquals(Color.GREEN, res2.value().inner.color);
        assertTrue(res2.unknownValues().isEmpty());
    }

    /*
    /**********************************************************************
    /* Strategy 3 (default): unknown enum -> rejected (exception)
    /**********************************************************************
     */

    @Test
    void rejectStrategyStillThrowsAfterNotifying()
    {
        ObjectReader r = MAPPER.readerFor(Outer.class); // no lenient feature

        InvalidFormatException e = assertThrows(InvalidFormatException.class,
                () -> r.readValueRecordingUnknownEnums(UNKNOWN_JSON));
        verifyException(e, "PURPLE");
        verifyException(e, "not one of the values accepted for Enum class");
    }

    /*
    /**********************************************************************
    /* Strategies must not override each other; known values never recorded
    /**********************************************************************
     */

    @Test
    void knownValuesNeverRecordedUnderAnyStrategy()
    {
        // Default-value strategy enabled: known value just binds, no records
        ObjectReader rDef = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
        UnknownEnumValueResult<Outer> resDef = rDef.readValueRecordingUnknownEnums(MIXED_JSON);
        assertEquals(Color.GREEN, resDef.value().inner.color);
        assertTrue(resDef.unknownValues().isEmpty());

        // Null strategy enabled: known value just binds, no records
        ObjectReader rNull = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        UnknownEnumValueResult<Outer> resNull = rNull.readValueRecordingUnknownEnums(MIXED_JSON);
        assertEquals(Color.GREEN, resNull.value().inner.color);
        assertTrue(resNull.unknownValues().isEmpty());
    }

    @Test
    void bucketIsFreshForEveryCallNoAccumulation()
    {
        ObjectMapper mapper = MAPPER.rebuild()
                .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).build();
        ObjectReader r = mapper.readerFor(Outer.class);
        String json = "{\"name\":\"n\",\"inner\":{\"color\":\"PURPLE\",\"label\":\"x\"}}";

        UnknownEnumValueResult<Outer> res1 = r.readValueRecordingUnknownEnums(json);
        assertNull(res1.value().inner.color);
        assertEquals(1, res1.unknownValues().size());

        UnknownEnumValueResult<Outer> res2 = r.readValueRecordingUnknownEnums(json);
        assertEquals(1, res2.unknownValues().size(),
                "each call must get a fresh bucket (no accumulation)");

        // Alternating unknown / known proves isolation in both directions
        UnknownEnumValueResult<Outer> res3 = r.readValueRecordingUnknownEnums(MIXED_JSON);
        assertEquals(Color.GREEN, res3.value().inner.color);
        assertTrue(res3.unknownValues().isEmpty());
    }

    /*
    /**********************************************************************
    /* New option disabled: original interface keeps working unchanged
    /**********************************************************************
     */

    @Test
    void plainReadValueUnaffectedWhenOptionNotEnabled()
    {
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);

        // Plain readValue (no recording) still returns the bound object directly
        Outer outer = r.readValue(UNKNOWN_JSON);
        assertNull(outer.inner.color);

        // And a default-configured reader still rejects unknown values
        ObjectReader strict = MAPPER.readerFor(Outer.class);
        assertThrows(InvalidFormatException.class, () -> strict.readValue(UNKNOWN_JSON));
    }
}
