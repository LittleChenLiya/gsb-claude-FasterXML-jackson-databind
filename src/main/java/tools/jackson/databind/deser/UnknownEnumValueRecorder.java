package tools.jackson.databind.deser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link DeserializationProblemHandler} adapter that records unknown Enum values
 * -- the unmatched input text together with the field path at which it occurred --
 * and then lets the regular fallback handling proceed unchanged.
 *<p>
 * Because recording happens through
 * {@link DeserializationProblemHandler#handleUnknownEnumValue} (invoked before the
 * fallback decision), unknown values are recorded no matter which fallback strategy
 * is in effect:
 *<ul>
 * <li>default value
 *   ({@link tools.jackson.databind.cfg.EnumFeature#READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE})
 *  </li>
 * <li>{@code null}
 *   ({@link tools.jackson.databind.cfg.EnumFeature#READ_UNKNOWN_ENUM_VALUES_AS_NULL})
 *  </li>
 * <li>reject (fail with an exception)
 *  </li>
 * </ul>
 * The strategies themselves are not affected by recording and keep their existing
 * relative precedence.
 *<p>
 * The handler is intended to be registered locally, on a single
 * {@link tools.jackson.databind.ObjectReader}:
 *<pre>
 *   UnknownEnumValueRecorder recorder = new UnknownEnumValueRecorder();
 *   ObjectReader reader = mapper.readerFor(MyType.class).withHandler(recorder);
 *   MyType value = reader.readValue(json);
 *   List&lt;UnknownEnumValueRecorder.UnknownEnumValue&gt; unknowns = recorder.getRecords();
 *</pre>
 * When it is not registered, deserialization behaves exactly as before.
 *<p>
 * <b>Call isolation</b>: records are stored in a per-call bucket kept in
 * {@link DeserializationContext} attributes (same pattern as
 * {@link CollectingProblemHandler}), so concurrent read calls never share records
 * and each read call starts with an empty bucket. {@link #getRecords()} exposes
 * the bucket of the most recent read call in which unknown Enum values were
 * encountered; records of earlier calls are not retained. Note that if a read
 * call encounters no unknown Enum values, {@link #getRecords()} still exposes the
 * previous non-empty bucket: call {@link #clear()} before such a read if an empty
 * result must be guaranteed.
 *<p>
 * <b>Thread-safety</b>: the handler instance itself holds no per-call mutable
 * state besides the "latest bucket" pointer (an {@link AtomicReference}), so a
 * single instance may be shared by an {@link tools.jackson.databind.ObjectReader}
 * used from multiple threads; each call still gets its own bucket.
 */
public class UnknownEnumValueRecorder extends DeserializationProblemHandler
{
    /**
     * Attribute key for the per-call record bucket.
     * Using class object as key (not a string) for type safety.
     */
    private static final Object ATTR_KEY = UnknownEnumValueRecorder.class;

    /**
     * Pointer to the record bucket of the most recent read call that recorded
     * at least one unknown Enum value.
     */
    private final AtomicReference<List<UnknownEnumValue>> _lastBucket =
            new AtomicReference<>(Collections.emptyList());

    @Override
    public Object handleUnknownEnumValue(DeserializationContext ctxt,
            Class<?> enumClass, String enumValueText, ValueDeserializer<?> deserializer)
        throws JacksonException
    {
        JsonParser p = ctxt.getParser();
        JsonPointer path = (p == null) ? JsonPointer.empty()
                : p.streamReadContext().pathAsPointer();
        _bucketFor(ctxt).add(new UnknownEnumValue(enumClass, enumValueText, path));
        // Let the configured fallback (default value / null / fail) proceed
        return NOT_HANDLED;
    }

    /**
     * Accessor for records of the most recent read call in which unknown Enum
     * values were encountered. Records of earlier read calls are not retained.
     *
     * @return Unmodifiable view of the recorded unknown Enum values
     */
    public List<UnknownEnumValue> getRecords() {
        return Collections.unmodifiableList(_lastBucket.get());
    }

    /**
     * Method to explicitly forget all currently accessible records; useful
     * before a read call that is not expected to encounter unknown Enum values.
     */
    public void clear() {
        _lastBucket.set(Collections.emptyList());
    }

    /**
     * Retrieves (creating and publishing if necessary) the per-call record
     * bucket from context attributes.
     */
    @SuppressWarnings("unchecked")
    protected List<UnknownEnumValue> _bucketFor(DeserializationContext ctxt) {
        Object attr = ctxt.getAttribute(ATTR_KEY);
        if (attr instanceof List<?>) {
            return (List<UnknownEnumValue>) attr;
        }
        List<UnknownEnumValue> bucket = new ArrayList<>();
        ctxt.setAttribute(ATTR_KEY, bucket);
        _lastBucket.set(bucket);
        return bucket;
    }

    /**
     * Simple value class for one recorded unknown Enum value: the Enum type,
     * the unmatched input text and the field path at which it occurred.
     */
    public static class UnknownEnumValue
    {
        private final Class<?> _enumType;
        private final String _valueText;
        private final JsonPointer _path;

        public UnknownEnumValue(Class<?> enumType, String valueText, JsonPointer path) {
            _enumType = enumType;
            _valueText = valueText;
            _path = path;
        }

        /**
         * @return Enum type that was being deserialized
         */
        public Class<?> getEnumType() {
            return _enumType;
        }

        /**
         * @return Input text that did not match any Enum constant
         */
        public String getValueText() {
            return _valueText;
        }

        /**
         * @return Field path (JSON Pointer) at which the unknown value occurred
         */
        public JsonPointer getPath() {
            return _path;
        }

        @Override
        public String toString() {
            return String.format("UnknownEnumValue(%s \"%s\" at %s)",
                    _enumType.getName(), _valueText, _path);
        }
    }
}
