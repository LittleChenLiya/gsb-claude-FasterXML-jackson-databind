package tools.jackson.databind.deser;

import java.util.Collection;
import java.util.List;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;

/**
 * Stateless {@link DeserializationProblemHandler} adapter that records each
 * unknown textual {@link Enum} value (its JSON Pointer field path, enum type
 * and the unrecognized text) into a <b>per-call</b> bucket stored in
 * {@link DeserializationContext} attributes, and then lets enum deserialization
 * continue with its existing fallback decision.
 *
 * <p>This handler never changes how an unknown enum value is resolved: after
 * recording, the same configured strategy still applies &mdash; enum default
 * value, {@code null}, or failure
 * ({@code EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE} /
 * {@code EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL}). In particular this
 * means the three strategies never override each other; this handler only
 * observes.
 *
 * <p>The handler itself is immutable and safe to share between threads and
 * readers; all state lives in the per-call context attributes allocated by
 * {@code ObjectReader.readValueRecordingUnknownEnums(...)}, which also
 * guarantees that records of one {@code ObjectReader} invocation never leak
 * into the next. If no recording bucket is present (for example, the handler
 * was registered directly and a plain {@code readValue(...)} is used),
 * recording is a no-op &mdash; original interfaces keep working unchanged.
 *
 * @since 3.3
 */
public class RecordingUnknownEnumHandler extends DeserializationProblemHandler
{
    /**
     * Attribute key under which the per-call recording bucket
     * ({@code List<UnknownEnumValue>}) is stored in
     * {@link DeserializationContext} attributes.
     */
    public static final Object ATTRIBUTE_KEY = RecordingUnknownEnumHandler.class;

    /**
     * Singleton instance: the handler is stateless.
     */
    private static final RecordingUnknownEnumHandler INSTANCE = new RecordingUnknownEnumHandler();

    public RecordingUnknownEnumHandler() { }

    /**
     * @return Shared stateless singleton instance
     */
    public static RecordingUnknownEnumHandler instance() { return INSTANCE; }

    /**
     * Retrieves the per-call recording bucket from context attributes.
     *
     * @return Recording bucket, or {@code null} if the current call did not
     *   opt in to recording
     */
    @SuppressWarnings("unchecked")
    public static List<UnknownEnumValue> getBucket(DeserializationContext ctxt) {
        Object attr = ctxt.getAttribute(ATTRIBUTE_KEY);
        return (attr instanceof List<?>) ? (List<UnknownEnumValue>) attr : null;
    }

    @Override
    public void reportUnknownEnumValue(DeserializationContext ctxt,
            JsonParser p, Class<?> enumClass,
            String valueToConvert, Collection<String> acceptableValues)
    {
        List<UnknownEnumValue> bucket = getBucket(ctxt);
        if (bucket != null) {
            bucket.add(new UnknownEnumValue(
                    p.streamReadContext().pathAsPointer(), enumClass, valueToConvert));
        }
    }
}
