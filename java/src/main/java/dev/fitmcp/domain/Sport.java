package dev.fitmcp.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed set of sports, per contract v1 §1.5. Wire form is lowercase.
 *
 * <p><strong>The constants are lowercase on purpose, against Java convention.</strong>
 *
 * <p>Spring AI builds tool schemas with victools, and does not enable that library's
 * {@code JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE}. The generator therefore reads
 * the <em>constant names</em> and ignores {@link JsonValue}, while the MCP SDK's own
 * Jackson mapper honours {@link JsonValue} when serialising. With conventional
 * {@code RUN} constants the two disagree: the published schema says {@code "RUN"} and
 * the payload says {@code "run"}, and the SDK's own output validation then rejects
 * every response. Naming the constants for the wire form is what makes the generated
 * schema and the serialised value agree.
 *
 * <p>See docs/adr/0010 and notes/divergence.md finding F-002.
 *
 * <p>Note the annotation package: Jackson 3 moved databind to {@code tools.jackson.*}
 * but left annotations at {@code com.fasterxml.jackson.annotation}.
 */
public enum Sport {

    run("run"),
    ride("ride"),
    swim("swim"),
    walk("walk"),
    /** Anything a provider reports that does not map onto the four above. */
    other("other");

    private final String wire;

    Sport(String wire) {
        this.wire = wire;
    }

    /**
     * Redundant while the constants are already lowercase, and kept deliberately: it
     * pins the wire form so that renaming a constant cannot silently change the payload
     * without also failing against the schema.
     */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Parses the wire form. Unknown values map to {@link #other} rather than throwing,
     * because the contract forbids inventing enum members but says nothing about
     * rejecting a provider that reports something new.
     */
    @JsonCreator
    public static Sport fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Sport sport : values()) {
            if (sport.wire.equalsIgnoreCase(value)) {
                return sport;
            }
        }
        return other;
    }
}
