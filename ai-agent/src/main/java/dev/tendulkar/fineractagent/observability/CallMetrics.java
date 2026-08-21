package dev.tendulkar.fineractagent.observability;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-request timing and token accounting for one question.
 * <p>
 * "It takes three minutes" is not actionable. The only useful question is how
 * the time splits between the database and the model, because the answer
 * decides what to fix: a slow query is an index problem, slow model time with a
 * large tool result is a payload problem, and slow model time with a small one
 * is a reasoning-budget problem.
 * <p>
 * ThreadLocal because Spring AI's blocking tool loop runs tool calls on the
 * request thread. A streaming or async path would need this passed explicitly
 * instead.
 */
@Component
public class CallMetrics {

    public record ToolCall(String tool, long millis, int rows) {
    }

    public record Snapshot(long totalMillis, long dbMillis, long modelMillis,
                           List<ToolCall> toolCalls, long promptTokens,
                           long completionTokens) {

        public int toolCallCount() {
            return toolCalls.size();
        }

        public long totalTokens() {
            return promptTokens + completionTokens;
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
     * Model time is derived as total minus tool time rather than measured
     * inside the framework: imprecise, since it also carries network and
     * framework overhead, but it is the split that matters and it needs no hook
     * into Spring AI internals.
     */
    public Snapshot end(long totalMillis, long promptTokens, long completionTokens) {
        List<ToolCall> calls = List.copyOf(CALLS.get());
        CALLS.get().clear();
        long dbMillis = calls.stream().mapToLong(ToolCall::millis).sum();
        return new Snapshot(totalMillis, dbMillis, Math.max(0, totalMillis - dbMillis),
                calls, promptTokens, completionTokens);
    }
}
