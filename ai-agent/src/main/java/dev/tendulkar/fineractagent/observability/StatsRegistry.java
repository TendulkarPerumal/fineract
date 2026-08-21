package dev.tendulkar.fineractagent.observability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide aggregate of what the agent has cost and how it has behaved.
 * <p>
 * Deliberately in-memory and reset on restart. This is a single-instance
 * demonstration service; a metrics backend would be more infrastructure than
 * the thing it measures. What matters for the portfolio claim is that the
 * numbers in the README - pass rate, p95 latency, cost per question - are
 * measured rather than estimated.
 * <p>
 * Latency samples are kept in a bounded window so memory cannot grow without
 * limit on a public URL.
 */
@Component
public class StatsRegistry {

    private static final int WINDOW = 500;

    /** Gemini 3.5 Flash list price per million tokens, USD, as of 2026-08. */
    private static final double INPUT_USD_PER_MTOK = 1.50;
    private static final double OUTPUT_USD_PER_MTOK = 9.00;

    private final AtomicLong asked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final Deque<Long> latencies = new ArrayDeque<>();
    private final Instant startedAt = Instant.now();

    public void recordSuccess(CallMetrics.Snapshot snapshot) {
        asked.incrementAndGet();
        toolCalls.addAndGet(snapshot.toolCallCount());
        promptTokens.addAndGet(snapshot.promptTokens());
        completionTokens.addAndGet(snapshot.completionTokens());
        synchronized (latencies) {
            latencies.addLast(snapshot.totalMillis());
            while (latencies.size() > WINDOW) {
                latencies.removeFirst();
            }
        }
    }

    public void recordFailure() {
        asked.incrementAndGet();
        failed.incrementAndGet();
    }

    /** Rejected before reaching the model: rate limit, token cap or open circuit. */
    public void recordRefusal() {
        refused.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        List<Long> sorted;
        synchronized (latencies) {
            sorted = new ArrayList<>(latencies);
        }
        sorted.sort(Long::compareTo);

        long prompt = promptTokens.get();
        long completion = completionTokens.get();
        long answered = Math.max(1, asked.get() - failed.get());
        double costUsd = (prompt / 1_000_000.0) * INPUT_USD_PER_MTOK
                + (completion / 1_000_000.0) * OUTPUT_USD_PER_MTOK;

        return Map.ofEntries(
                Map.entry("uptimeSeconds", java.time.Duration.between(startedAt, Instant.now()).toSeconds()),
                Map.entry("questionsAsked", asked.get()),
                Map.entry("questionsFailed", failed.get()),
                Map.entry("questionsRefused", refused.get()),
                Map.entry("toolCallsTotal", toolCalls.get()),
                Map.entry("toolCallsPerQuestion", round(toolCalls.get() / (double) answered)),
                Map.entry("latencyMsP50", percentile(sorted, 50)),
                Map.entry("latencyMsP95", percentile(sorted, 95)),
                Map.entry("latencyMsMax", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1)),
                Map.entry("promptTokens", prompt),
                Map.entry("completionTokens", completion),
                Map.entry("tokensPerQuestion", (prompt + completion) / answered),
                Map.entry("estimatedCostUsd", round(costUsd)),
                Map.entry("estimatedCostUsdPerQuestion", round(costUsd / answered)));
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
