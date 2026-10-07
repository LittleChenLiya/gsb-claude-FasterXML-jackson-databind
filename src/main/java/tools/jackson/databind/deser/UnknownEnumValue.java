package tools.jackson.databind.deser;

import java.util.Objects;

import tools.jackson.core.JsonPointer;

/**
 * Immutable value object capturing details about a single unknown textual
 * value encountered while deserializing an {@link Enum}.
 *
 * <p>Instances are produced by {@link RecordingUnknownEnumHandler} and exposed
 * via {@link UnknownEnumValueResult#unknownValues()}.
 *
 * <p><b>Contents</b>:
 * <ul>
 * <li>{@link #path()} - RFC 6901 JSON Pointer of the field whose value was
 *     unknown (e.g. {@code "/outer/enumField"} or {@code "/items/0"})</li>
 * <li>{@link #enumClass()} - Enum type being deserialized</li>
 * <li>{@link #unknownValue()} - The textual input value that did not match any
 *     known enum constant</li>
 * </ul>
 *
 * @since 3.3
 */
public final class UnknownEnumValue
{
    private final JsonPointer _path;
    private final Class<?> _enumClass;
    private final String _unknownValue;

    public UnknownEnumValue(JsonPointer path, Class<?> enumClass, String unknownValue) {
        _path = Objects.requireNonNull(path, "path");
        _enumClass = Objects.requireNonNull(enumClass, "enumClass");
        _unknownValue = Objects.requireNonNull(unknownValue, "unknownValue");
    }

    /**
     * @return JSON Pointer (RFC 6901) to the field that held the unknown value
     */
    public JsonPointer path() { return _path; }

    /**
     * @return Enum type for which the value was unrecognized
     */
    public Class<?> enumClass() { return _enumClass; }

    /**
     * @return Unknown textual value from input
     */
    public String unknownValue() { return _unknownValue; }

    @Override
    public String toString() {
        return "UnknownEnumValue{path='%s', enum=%s, value='%s'}".formatted(
                _path, _enumClass.getName(), _unknownValue);
    }
}
