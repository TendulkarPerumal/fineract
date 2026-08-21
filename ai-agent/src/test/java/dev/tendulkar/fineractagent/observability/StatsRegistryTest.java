package dev.tendulkar.fineractagent.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These numbers end up in the README, so the arithmetic behind them should not
 * be taken on trust.
 */
class StatsRegistryTest {

    private StatsRegistry stats;

    @BeforeEach
    void setUp() {
        stats = new StatsRegistry();
    }

    private static CallMetrics.Snapshot snapshot(long totalMs, long prompt, long completion) {
        return new CallMetrics.Snapshot(totalMs, 50, totalMs - 50,
                List.of(new CallMetrics.ToolCall("get_arrears_totals", 50, 3)),
                prompt, completion);
    }

    @Test
    @DisplayName("percentiles are computed over the recorded latencies")
    void percentiles() {
        for (int ms = 100; ms <= 1000; ms += 100) {
            stats.recordSuccess(snapshot(ms, 1000, 200));
        }

        Map<String, Object> result = stats.snapshot();

        assertEquals(10L, result.get("questionsAsked"));
        assertEquals(500L, result.get("latencyMsP50"));
        assertEquals(1000L, result.get("latencyMsP95"));
        assertEquals(1000L, result.get("latencyMsMax"));
    }

    @Test
    @DisplayName("cost is derived from actual token counts, not estimated")
    void costFromTokens() {
        // 1,000,000 prompt + 1,000,000 completion tokens = one unit of each
        // list price, so the arithmetic is checkable by inspection.
        stats.recordSuccess(snapshot(200, 1_000_000, 1_000_000));

        Map<String, Object> result = stats.snapshot();

        assertEquals(1_000_000L, result.get("promptTokens"));
        assertEquals(1_000_000L, result.get("completionTokens"));
        // 1.50 in + 9.00 out
        assertEquals(10.5, (Double) result.get("estimatedCostUsd"), 0.0001);
    }

    @Test
    @DisplayName("refusals are counted apart from failures")
    void refusalsAreNotFailures() {
        // A rate-limited or budget-capped request never reached the model, so
        // counting it as a failure would make the agent look broken when it was
        // in fact protecting itself.
        stats.recordSuccess(snapshot(100, 10, 10));
        stats.recordRefusal();
        stats.recordFailure();

        Map<String, Object> result = stats.snapshot();

        assertEquals(2L, result.get("questionsAsked"), "a refusal is not an attempt");
        assertEquals(1L, result.get("questionsFailed"));
        assertEquals(1L, result.get("questionsRefused"));
    }

    @Test
    @DisplayName("an empty registry reports zeros rather than dividing by zero")
    void emptyRegistry() {
        Map<String, Object> result = stats.snapshot();

        assertEquals(0L, result.get("questionsAsked"));
        assertEquals(0L, result.get("latencyMsP50"));
        assertEquals(0.0, (Double) result.get("estimatedCostUsd"), 0.0001);
    }

    @Test
    @DisplayName("the latency window is bounded so a public URL cannot grow it forever")
    void windowIsBounded() {
        for (int i = 0; i < 900; i++) {
            stats.recordSuccess(snapshot(100 + i, 10, 10));
        }

        Map<String, Object> result = stats.snapshot();

        assertEquals(900L, result.get("questionsAsked"));
        // Only the most recent 500 samples are retained, so the oldest
        // latencies have aged out of the percentile calculation.
        assertTrue((Long) result.get("latencyMsP50") > 500,
                "percentiles should reflect the recent window, not all history");
    }
}
