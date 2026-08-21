package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.agent.AgentAnswer;
import dev.tendulkar.fineractagent.agent.AgentUnavailableException;
import dev.tendulkar.fineractagent.agent.ResilientQueryAgent;
import dev.tendulkar.fineractagent.observability.CallMetrics;
import dev.tendulkar.fineractagent.observability.StatsRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AskController {

    private final ResilientQueryAgent agent;
    private final JdbcClient jdbc;
    private final CallMetrics metrics;
    private final StatsRegistry stats;
    private final String model;

    AskController(ResilientQueryAgent agent, JdbcClient jdbc, CallMetrics metrics,
                  StatsRegistry stats,
                  @Value("${spring.ai.google.genai.chat.model:unknown}") String model) {
        this.agent = agent;
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.stats = stats;
        this.model = model;
    }

    /**
     * Latency and token usage are part of the response shape rather than an
     * afterthought: they are what tells a caller whether an answer was cheap or
     * expensive, and adding them later would have been a breaking change.
     */
    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        metrics.begin();
        long startedAt = System.nanoTime();
        AgentAnswer answer = agent.answer(request.question());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        CallMetrics.Snapshot snapshot =
                metrics.end(elapsedMs, answer.promptTokens(), answer.completionTokens());
        stats.recordSuccess(snapshot);

        return new AskResponse(answer.text(), model, elapsedMs, snapshot.dbMillis(),
                snapshot.modelMillis(), snapshot.toolCallCount(), snapshot.promptTokens(),
                snapshot.completionTokens(), snapshot.toolCalls());
    }

    /**
     * Refusals are 503 with a readable reason, not a bare 500. An open circuit,
     * a spent budget and a timeout are all "not now" rather than "broken", and
     * the caller can act on the difference.
     */
    @ExceptionHandler(AgentUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(AgentUnavailableException e) {
        stats.recordFailure();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * Proves the datasource and Flyway are wired, separately from the model.
     * When an answer looks wrong the first question is always whether the
     * service is pointed at the seeded database at all.
     */
    @GetMapping("/health/db")
    public Map<String, Object> dbHealth() {
        return Map.of(
                "clients", count("m_client"),
                "loans", count("m_loan"),
                "activeLoans", jdbc.sql("SELECT count(*) FROM m_loan WHERE loan_status_id = 300")
                        .query(Long.class).single(),
                "loansInArrears", count("m_loan_arrears_aging"),
                "savingsAccounts", count("m_savings_account"));
    }

    private long count(String table) {
        // Table names are hard-coded constants above, never request input.
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    public record AskRequest(
            @NotBlank(message = "question must not be blank")
            @Size(max = 1000, message = "question must be at most 1000 characters")
            String question) {
    }

    public record AskResponse(String answer, String model, long latencyMs, long dbMs,
                              long modelMs, int toolCalls, long promptTokens,
                              long completionTokens, List<CallMetrics.ToolCall> tools) {
    }
}
