package tools.jackson.databind.deser;

import java.util.Collections;
import java.util.List;

/**
 * Result of a single {@code ObjectReader.readValueRecordingUnknownEnums(...)}
 * call: the deserialized value together with the unknown enum values recorded
 * during that call.
 *
 * <p>The list of unknown values is per-call: it contains only occurrences from
 * the read that produced this result and never leaks into subsequent reads.
 *
 * @param <T> Type of the deserialized value
 *
 * @since 3.3
 */
public final class UnknownEnumValueResult<T>
{
    private final T _value;
    private final List<UnknownEnumValue> _unknownValues;

    public UnknownEnumValueResult(T value, List<UnknownEnumValue> unknownValues) {
        _value = value;
        _unknownValues = Collections.unmodifiableList(unknownValues);
    }

    /**
     * @return Deserialized value, with whatever fallback (default value /
     *   {@code null}) was applied to unknown enum inputs
     */
    public T value() { return _value; }

    /**
     * @return Immutable, per-call list of recorded unknown enum values
     *   (empty if none were encountered)
     */
    public List<UnknownEnumValue> unknownValues() { return _unknownValues; }

    /**
     * @return Whether this call encountered at least one unknown enum value
     */
    public boolean hasUnknownValues() { return !_unknownValues.isEmpty(); }
}
