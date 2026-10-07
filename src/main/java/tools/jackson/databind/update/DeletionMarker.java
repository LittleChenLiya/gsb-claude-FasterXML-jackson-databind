package tools.jackson.databind.update;

/**
 * Explicit marker value for requesting deletion of a pre-existing entry
 * when updating a {@link java.util.Map}-shaped value.
 *<p>
 * The marker is <b>not</b> the same as a plain {@code null}: a {@code null}
 * override keeps following the normal {@code null}-handling configuration
 * (typically setting the entry to {@code null}), whereas this marker requests
 * removal of the entry altogether.
 *<p>
 * Marker instances are only interpreted by deletion-aware update adapters
 * such as {@link tools.jackson.databind.ObjectReader#updateValue(Object, Map)};
 * in regular data binding they act like opaque {@link Object} values.
 */
public final class DeletionMarker
    implements java.io.Serializable
{
    private static final long serialVersionUID = 1L;

    /**
     * Singleton marker instance used to declare a deletion request.
     */
    public static final DeletionMarker INSTANCE = new DeletionMarker();

    private DeletionMarker() { }

    /**
     * Convenience accessor equivalent to {@link #INSTANCE}.
     */
    public static DeletionMarker instance() { return INSTANCE; }

    @Override
    public String toString() { return "<DELETE>"; }

    // Singleton: identity-based equality/hash code
    @Override
    public boolean equals(Object o) { return o == this; }

    @Override
    public int hashCode() { return System.identityHashCode(this); }
}
