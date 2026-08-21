package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.agent.ResilientQueryAgent;
import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.StatsRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * What the agent has cost and how it has behaved since startup.
 * <p>
 * Exists so the numbers quoted in the README - p95 latency, tokens and cost per
 * question - are measured rather than asserted, and so the circuit breaker and
 * token budget are observable rather than invisible until they fire.
 */
@RestController
@RequestMapping("/api")
public class StatsController {

    private final StatsRegistry stats;
    private final ResilientQueryAgent agent;
    private final AgentProperties properties;

    StatsController(StatsRegistry stats, ResilientQueryAgent agent, AgentProperties properties) {
        this.stats = stats;
        this.agent = agent;
        this.properties = properties;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> body = new HashMap<>(stats.snapshot());
        body.put("circuitState", agent.circuitState());
        body.put("tokensSpentToday", agent.tokensSpentToday());
        body.put("dailyTokenCap", properties.dailyTokenCap());
        body.put("businessDate", properties.businessDate().toString());
        return body;
    }
}
