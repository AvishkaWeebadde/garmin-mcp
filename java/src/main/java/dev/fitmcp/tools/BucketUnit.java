package dev.fitmcp.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Bucket granularity for {@code getTrends}. Contract v2 §2.4.
 *
 * <p>Constants are lowercase, for exactly the reason {@code Sport} is (ADR-0010): Spring
 * AI's schema generator reads the constant names while the MCP SDK's mapper honours
 * {@link JsonValue}, and conventional {@code WEEK} constants would make the published schema
 * and the payload disagree, failing the SDK's own validation.
 */
public enum BucketUnit {

    week("week"),
    month("month");

    private final String wire;

    BucketUnit(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Unknown values return null; the tool treats a null bucket as the {@code week} default.
     * In practice the generated schema's enum keeps non-members out, so this only matters if
     * the schema keyword is dropped (F-007).
     */
    @JsonCreator
    public static BucketUnit fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (BucketUnit unit : values()) {
            if (unit.wire.equalsIgnoreCase(value)) {
                return unit;
            }
        }
        return null;
    }
}
