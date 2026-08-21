package dev.fitmcp.provider;

/**
 * The underlying activity source could not be reached or failed.
 *
 * <p>Distinct from "the activity does not exist", which is an ordinary answer and is
 * modelled as an empty {@link java.util.Optional}. Contract v1 §1.7 maps this to an
 * {@code isError} tool result, not a protocol error.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String reason) {
        super(reason);
    }

    public ProviderUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
