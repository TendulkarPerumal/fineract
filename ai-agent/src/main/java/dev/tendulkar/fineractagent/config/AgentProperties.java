package dev.tendulkar.fineractagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalDate;

/**
 * @param businessDate     Fineract's logical "today". Pinned rather than read
 *                         from the clock: arrears is measured against it, so a
 *                         moving date makes every eval answer decay daily
 *                         (docs/DOMAIN.md section 6.3). The seed is built around
 *                         this date, so the two must change together.
 * @param maxRows          Hard cap on rows any tool may return. Every row is
 *                         prompt tokens on the next model call.
 * @param requestTimeout   Wall-clock budget for one question, tool calls
 *                         included. Without it a slow model holds a request
 *                         thread open indefinitely.
 * @param dailyTokenCap    Total tokens the service will spend per day. This is
 *                         a free-tier project on a public URL; the cap is what
 *                         stops one visitor exhausting the quota for everyone.
 * @param failureThreshold Consecutive failures before the circuit opens.
 * @param circuitOpenFor   How long the circuit stays open before a trial call.
 * @param requestsPerMinute Per-client request allowance.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        LocalDate businessDate,
        int maxRows,
        Duration requestTimeout,
        long dailyTokenCap,
        int failureThreshold,
        Duration circuitOpenFor,
        int requestsPerMinute) {

    public int cap(Integer requested) {
        if (requested == null || requested <= 0) {
            return maxRows;
        }
        return Math.min(requested, maxRows);
    }
}
