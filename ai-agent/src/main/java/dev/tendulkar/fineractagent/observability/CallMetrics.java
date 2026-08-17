package dev.tendulkar.fineractagent.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-request timing for one question.
 * <p>
 * "It takes three minutes" is not actionable. The only useful question is how
 * the time splits between the database and the model, and the answer decides
 * what to optimise: a slow query is an index problem, slow model time with a
 * large tool result is a payload problem, and slow model time with a small one
 * is a reasoning-budget problem.
 * <p>
 * ThreadLocal because Spring AI's blocking tool loop runs the tool calls on the
 * request thread. If a streaming or async path is added later this must move to
 * an explicit context passed through the call.
 */
@Component
public class CallMetrics {

    public record ToolCall(String tool, long millis, int rows) {
    }

    public record Snapshot(long totalMillis, long dbMillis, long modelMillis, List<ToolCall> toolCalls) {
        public int toolCallCount() {
            return toolCalls.size();
        }
    }

    private static final ThreadLocal<List<ToolCall>> CALLS = ThreadLocal.withInitial(ArrayList::new);

    public void begin() {
        CALLS.get().clear();
    }

    public void recordToolCall(String tool, long millis, int rows) {
        CALLS.get().add(new ToolCall(tool, millis, rows));
    }

    /**
     * Model time is derived rather than measured: everything that is not a tool
     * call is network plus inference. That is imprecise but it is the split that
     * matters, and it needs no hook inside the framework.
     */
    public Snapshot end(long totalMillis) {
        List<ToolCall> calls = List.copyOf(CALLS.get());
        CALLS.get().clear();
        long dbMillis = calls.stream().mapToLong(ToolCall::millis).sum();
        return new Snapshot(totalMillis, dbMillis, Math.max(0, totalMillis - dbMillis), calls);
    }
}
