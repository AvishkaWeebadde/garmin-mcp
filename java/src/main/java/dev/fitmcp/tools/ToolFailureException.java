package dev.fitmcp.tools;

/**
 * A tool ran correctly and has nothing good to report.
 *
 * <p>Contract v1 §1.7 splits errors by <em>who got it wrong</em>. An unknown tool name or
 * a schema violation is the caller misusing the protocol, and surfaces as a JSON-RPC
 * protocol error raised by the framework. This exception covers the other side: the call
 * was well-formed, the tool executed, and the answer is a failure. Those become a
 * successful result carrying {@code isError: true}.
 *
 * <p>The message is contractual and fixed verbatim. Construct it only through the factory
 * methods here, so the three strings live in one place and a Phase 3 wording difference
 * is a real finding rather than a typo.
 */
public class ToolFailureException extends RuntimeException {

    private ToolFailureException(String message) {
        super(message);
    }

    public static ToolFailureException activityNotFound(String activityId) {
        return new ToolFailureException("activity not found: " + activityId);
    }

    public static ToolFailureException invalidDateRange(String startDate, String endDate) {
        return new ToolFailureException(
                "invalid date range: endDate " + endDate + " is before startDate " + startDate);
    }

    public static ToolFailureException providerUnavailable(String reason) {
        return new ToolFailureException("provider unavailable: " + reason);
    }
}
