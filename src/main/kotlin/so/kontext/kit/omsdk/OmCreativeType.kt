package so.kontext.kit.omsdk

/**
 * Creative type for Open Measurement (OM) SDK sessions. Decoded from the
 * `/preload` response and consumed by [OmManager.createSession] to choose
 * between an OMID `htmlDisplay` and a `video` configuration.
 *
 * Mirrors iOS's `OMCreativeType` enum. Use [fromString] at the JSON
 * decoding boundary so the rest of the kit handles the type-safe enum
 * instead of stringly-typed `creativeType`.
 */
public enum class OmCreativeType(public val wireValue: String) {
    /** Static or rich-media display ad (default for unknown types). */
    DISPLAY("display"),

    /** Video ad creative — OMID picks `video` configuration with media-events JS owner. */
    VIDEO("video"),
    ;

    public companion object {
        /**
         * Parses the wire-format value. Unrecognised strings (and `null`)
         * fall through to `null` so callers can short-circuit OM tracking
         * for unknown creative shapes — same behaviour as the previous
         * stringly-typed code path.
         */
        public fun fromString(value: String?): OmCreativeType? = when (value) {
            "display" -> DISPLAY
            "video" -> VIDEO
            else -> null
        }
    }
}
