package tools.jackson.databind.deser.enums;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.deser.UnknownEnumValueRecorder;
import tools.jackson.databind.exc.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.*;

import static tools.jackson.databind.testutil.DatabindTestUtil.*;

/**
 * Tests for {@link UnknownEnumValueRecorder}: a local (per-ObjectReader)
 * {@link DeserializationProblemHandler} adapter that records unknown Enum
 * value text and field path, and then lets the existing fallback decision
 * (default value / null / reject) proceed.
 */
public class UnknownEnumValueRecordingTest
{
    enum Color { RED, GREEN, BLUE }

    enum Size {
        SMALL,
        @JsonEnumDefaultValue
        MEDIUM,
        LARGE
    }

    static class Nested {
        public Color color;
        public Size size;
    }

    static class Outer {
        public Nested nested;
    }

    // known "color", unknown "size"
    static final String DOC_UNKNOWN_SIZE =
            "{\"nested\":{\"color\":\"RED\",\"size\":\"HUGE\"}}";

    // unknown "color", known "size"
    static final String DOC_UNKNOWN_COLOR =
            "{\"nested\":{\"color\":\"PURPLE\",\"size\":\"SMALL\"}}";

    // both unknown
    static final String DOC_BOTH_UNKNOWN =
            "{\"nested\":{\"color\":\"PURPLE\",\"size\":\"HUGE\"}}";

    // all known
    static final String DOC_ALL_KNOWN =
            "{\"nested\":{\"color\":\"GREEN\",\"size\":\"LARGE\"}}";

    private final ObjectMapper MAPPER = newJsonMapper();

    /*
    /**********************************************************************
    /* Fallback strategies: recording works with each, none overrides another
    /**********************************************************************
     */

    // Reject strategy (default settings): read fails, but unknown value
    // is still recorded before the exception
    @Test
    void rejectStrategyRecordsThenFails() {
        UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
        ObjectReader r = MAPPER.readerFor(Outer.class).withHandler(recorder);

        InvalidFormatException e = assertThrows(InvalidFormatException.class,
                () -> r.readValue(DOC_UNKNOWN_SIZE));
        verifyException(e, "not one of the values accepted for Enum class");

        List<UnknownEnumValueRecorder.UnknownEnumValue> records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("HUGE", records.get(0).getValueText());
        assertEquals(Size.class, records.get(0).getEnumType());
        assertEquals("/nested/size", records.get(0).getPath().toString());

        // Isolation: next read call must not retain records of the previous call
        assertThrows(InvalidFormatException.class, () -> r.readValue(DOC_UNKNOWN_COLOR));
        records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("PURPLE", records.get(0).getValueText());
        assertEquals(Color.class, records.get(0).getEnumType());
        assertEquals("/nested/color", records.get(0).getPath().toString());
    }

    // Null strategy: unknown -> null, recorded
    @Test
    void nullStrategyRecords() throws Exception {
        UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .withHandler(recorder);

        Outer outer = r.readValue(DOC_UNKNOWN_SIZE);
        assertEquals(Color.RED, outer.nested.color);
        assertNull(outer.nested.size);
        List<UnknownEnumValueRecorder.UnknownEnumValue> records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("HUGE", records.get(0).getValueText());
        assertEquals("/nested/size", records.get(0).getPath().toString());

        // Isolation: next read call only exposes its own records
        outer = r.readValue(DOC_UNKNOWN_COLOR);
        assertNull(outer.nested.color);
        assertEquals(Size.SMALL, outer.nested.size);
        records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("PURPLE", records.get(0).getValueText());
        assertEquals("/nested/color", records.get(0).getPath().toString());
    }

    // Default-value strategy: unknown -> @JsonEnumDefaultValue, recorded;
    // strategy does not leak to enums without a default
    @Test
    void defaultValueStrategyRecords() throws Exception {
        UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .withHandler(recorder);

        Outer outer = r.readValue(DOC_UNKNOWN_SIZE);
        assertEquals(Color.RED, outer.nested.color);
        assertEquals(Size.MEDIUM, outer.nested.size); // @JsonEnumDefaultValue
        List<UnknownEnumValueRecorder.UnknownEnumValue> records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("HUGE", records.get(0).getValueText());
        assertEquals(Size.class, records.get(0).getEnumType());
        assertEquals("/nested/size", records.get(0).getPath().toString());

        // Color has no default value: default strategy does not apply, still fails...
        assertThrows(InvalidFormatException.class, () -> r.readValue(DOC_UNKNOWN_COLOR));
        // ...but the unknown value was still recorded (and old records are gone)
        records = recorder.getRecords();
        assertEquals(1, records.size());
        assertEquals("PURPLE", records.get(0).getValueText());
        assertEquals("/nested/color", records.get(0).getPath().toString());
    }

    // Both lenient strategies enabled: default-value wins for enums with a
    // default, null applies otherwise; neither overrides the other and both
    // unknown values are recorded
    @Test
    void strategiesDoNotOverrideEachOther() throws Exception {
        UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .withHandler(recorder);

        Outer outer = r.readValue(DOC_BOTH_UNKNOWN);
        assertNull(outer.nested.color);               // null strategy (no default for Color)
        assertEquals(Size.MEDIUM, outer.nested.size); // default strategy wins for Size

        List<UnknownEnumValueRecorder.UnknownEnumValue> records = recorder.getRecords();
        assertEquals(2, records.size());
        assertEquals("PURPLE", records.get(0).getValueText());
        assertEquals("/nested/color", records.get(0).getPath().toString());
        assertEquals("HUGE", records.get(1).getValueText());
        assertEquals("/nested/size", records.get(1).getPath().toString());
    }

    /*
    /**********************************************************************
    /* Isolation, known values, opt-in nature
    /**********************************************************************
     */

    // Known values only: nothing recorded
    @Test
    void knownValuesRecordNothing() throws Exception {
        UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .withHandler(recorder);

        Outer outer = r.readValue(DOC_ALL_KNOWN);
        assertEquals(Color.GREEN, outer.nested.color);
        assertEquals(Size.LARGE, outer.nested.size);
        assertTrue(recorder.getRecords().isEmpty());
    }

    // Without the recorder (new option not enabled), the original interface
    // behaves exactly as before
    @Test
    void withoutRecorderOriginalBehavior() throws Exception {
        // reject by default
        ObjectReader reject = MAPPER.readerFor(Outer.class);
        assertThrows(InvalidFormatException.class, () -> reject.readValue(DOC_UNKNOWN_SIZE));

        // null strategy still works
        ObjectReader asNull = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        Outer outer = asNull.readValue(DOC_UNKNOWN_SIZE);
        assertEquals(Color.RED, outer.nested.color);
        assertNull(outer.nested.size);

        // default-value strategy still works
        ObjectReader asDefault = MAPPER.readerFor(Outer.class)
                .with(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
        outer = asDefault.readValue(DOC_UNKNOWN_SIZE);
        assertEquals(Color.RED, outer.nested.color);
        assertEquals(Size.MEDIUM, outer.nested.size);
    }

    // A custom handler may also replace the unknown value, bypassing fallback
    @Test
    void handlerMayProvideReplacement() throws Exception {
        ObjectReader r = MAPPER.readerFor(Outer.class)
                .withHandler(new DeserializationProblemHandler() {
                    @Override
                    public Object handleUnknownEnumValue(DeserializationContext ctxt,
                            Class<?> enumClass, String enumValueText,
                            ValueDeserializer<?> deserializer) {
                        if (enumClass == Color.class) {
                            return Color.BLUE;
                        }
                        if (enumClass == Size.class) {
                            return Size.LARGE;
                        }
                        return NOT_HANDLED;
                    }
                });

        Outer outer = r.readValue(DOC_BOTH_UNKNOWN);
        assertEquals(Color.BLUE, outer.nested.color);
        assertEquals(Size.LARGE, outer.nested.size);
    }
}
