package dev.tendulkar.fineractagent.agent;

/**
 * The question was refused before or instead of reaching the model: the circuit
 * is open, the daily token budget is spent, or the call exceeded its deadline.
 * <p>
 * Distinct from a failure inside the agent loop, because the response to a user
 * differs: this is "not now", not "that went wrong".
 */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String message) {
        super(message);
    }

    public AgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
