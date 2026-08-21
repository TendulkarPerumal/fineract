package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.agent.AgentAnswer;
import dev.tendulkar.fineractagent.agent.AgentUnavailableException;
import dev.tendulkar.fineractagent.agent.ResilientQueryAgent;
import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.CallMetrics;
import dev.tendulkar.fineractagent.observability.StatsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * The demo page. Server-rendered so the whole project stays one deployable jar.
 * <p>
 * Failures render on the page rather than as a 500. This is the surface a
 * stranger judges the project by: an agent that says what went wrong reads as
 * engineering, a browser error page reads as broken.
 */
@Controller
public class UiController {

    private static final Logger log = LoggerFactory.getLogger(UiController.class);

    /** Seeded so a first-time visitor is not facing an empty box. */
    private static final List<String> EXAMPLES = List.of(
            "How many loans are more than 60 days in arrears?",
            "What is the total outstanding principal by office?",
            "Which clients have more than one active loan?",
            "Which clients have an active loan and savings over 20000?",
            "Show me the repayment schedule for loan L00000009",
            "Show me loans with status OVERDUE");

    private final ResilientQueryAgent agent;
    private final AgentProperties properties;
    private final CallMetrics metrics;
    private final StatsRegistry stats;

    UiController(ResilientQueryAgent agent, AgentProperties properties,
                 CallMetrics metrics, StatsRegistry stats) {
        this.agent = agent;
        this.properties = properties;
        this.metrics = metrics;
        this.stats = stats;
    }

    @GetMapping("/")
    public String index(Model model) {
        addCommonAttributes(model, null);
        return "index";
    }

    @PostMapping("/ask")
    public String ask(@RequestParam(name = "question", required = false) String question, Model model) {
        addCommonAttributes(model, question);

        if (question == null || question.isBlank()) {
            model.addAttribute("error", "Please enter a question.");
            return "index";
        }
        if (question.length() > 1000) {
            model.addAttribute("error", "Question is too long (limit 1000 characters).");
            return "index";
        }

        metrics.begin();
        long startedAt = System.nanoTime();
        long promptTokens = 0;
        long completionTokens = 0;
        boolean succeeded = false;
        try {
            AgentAnswer answer = agent.answer(question);
            promptTokens = answer.promptTokens();
            completionTokens = answer.completionTokens();
            succeeded = true;
            model.addAttribute("answer", answer.text());
        } catch (AgentUnavailableException e) {
            model.addAttribute("error", e.getMessage());
        } catch (RuntimeException e) {
            log.error("ask failed for question: {}", question, e);
            model.addAttribute("error", "The agent could not answer that. The error has been logged.");
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        CallMetrics.Snapshot snapshot = metrics.end(elapsedMs, promptTokens, completionTokens);
        if (succeeded) {
            stats.recordSuccess(snapshot);
        } else {
            stats.recordFailure();
        }

        model.addAttribute("latencyMs", elapsedMs);
        model.addAttribute("dbMs", snapshot.dbMillis());
        model.addAttribute("modelMs", snapshot.modelMillis());
        model.addAttribute("toolCalls", snapshot.toolCallCount());
        model.addAttribute("totalTokens", snapshot.totalTokens());
        return "index";
    }

    private void addCommonAttributes(Model model, String question) {
        model.addAttribute("examples", EXAMPLES);
        model.addAttribute("businessDate", properties.businessDate());
        model.addAttribute("question", question);
    }
}
