package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.agent.QueryAgent;
import dev.tendulkar.fineractagent.observability.CallMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    private final QueryAgent agent;
    private final JdbcClient jdbc;
    private final String model;
    private final CallMetrics metrics;

    AskController(QueryAgent agent, JdbcClient jdbc, CallMetrics metrics,
                  @Value("${spring.ai.google.genai.chat.model:unknown}") String model) {
        this.agent = agent;
        this.jdbc = jdbc;
        this.model = model;
        this.metrics = metrics;
    }

    /**
     * The single endpoint for Stage 2. Latency is returned from the first
     * request rather than added later: it is the number that decides whether
     * the Stage 3 agent loop is usable, and it belongs in the response shape
     * before anything depends on that shape.
     */
    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        metrics.begin();
        long startedAt = System.nanoTime();
        String answer = agent.answer(request.question());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        CallMetrics.Snapshot snapshot = metrics.end(elapsedMs);
        return new AskResponse(answer, model, elapsedMs, snapshot.dbMillis(),
                snapshot.modelMillis(), snapshot.toolCallCount(), snapshot.toolCalls());
    }

    /**
     * Proves the datasource and Flyway are wired, separately from the model.
     * When Stage 3 starts returning wrong numbers, the first question is always
     * whether the service is even pointed at the seeded database.
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
                              long modelMs, int toolCalls, List<CallMetrics.ToolCall> tools) {
    }
}
