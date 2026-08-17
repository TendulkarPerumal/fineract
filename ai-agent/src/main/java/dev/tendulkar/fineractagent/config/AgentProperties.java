package dev.tendulkar.fineractagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * @param businessDate Fineract's logical "today". Pinned rather than derived
 *                     from the clock: arrears is measured against it, so a
 *                     moving date makes every eval answer decay daily
 *                     (docs/DOMAIN.md section 6.3). The seed is built around
 *                     this exact date, so changing it invalidates the seed too.
 * @param maxRows      Hard cap on rows any tool may return. Bounds both the
 *                     prompt size and the cost of a single question; a tool
 *                     that can return 350 loans can blow the context window on
 *                     one careless call.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(LocalDate businessDate, int maxRows) {

    public int cap(Integer requested) {
        if (requested == null || requested <= 0) {
            return maxRows;
        }
        return Math.min(requested, maxRows);
    }
}
