package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.agent.QueryAgent;
import dev.tendulkar.fineractagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * The demo page. Server-rendered rather than a separate frontend so the whole
 * thing stays one deployable jar.
 * <p>
 * Errors are rendered on the page instead of surfacing as a 500, because this
 * is the surface a stranger will judge the project by: an agent that says what
 * went wrong reads as engineering, a browser error page reads as broken.
 */
@Controller
public class UiController {

    private static final Logger log = LoggerFactory.getLogger(UiController.class);

    /** Seeded so a first-time visitor does not face an empty box. */
    private static final List<String> EXAMPLES = List.of(
            "How many loans are more than 60 days in arrears?",
            "What is the total outstanding principal by office?",
            "Which clients have more than one active loan?",
            "Which clients have an active loan and savings over 20000?",
            "Show me the repayment schedule for loan L00000009",
            "Show me loans with status OVERDUE");

    private final QueryAgent agent;
    private final AgentProperties properties;

    UiController(QueryAgent agent, AgentProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("examples", EXAMPLES);
        model.addAttribute("businessDate", properties.businessDate());
        return "index";
    }

    @PostMapping("/ask")
    public String ask(@RequestParam(name = "question", required = false) String question, Model model) {
        model.addAttribute("examples", EXAMPLES);
        model.addAttribute("businessDate", properties.businessDate());
        model.addAttribute("question", question);

        if (question == null || question.isBlank()) {
            model.addAttribute("error", "Please enter a question.");
            return "index";
        }
        if (question.length() > 1000) {
            model.addAttribute("error", "Question is too long (limit 1000 characters).");
            return "index";
        }

        long startedAt = System.nanoTime();
        try {
            String answer = agent.answer(question);
            model.addAttribute("answer", answer);
        } catch (RuntimeException e) {
            log.error("ask failed for question: {}", question, e);
            model.addAttribute("error",
                    "The agent could not answer that. The error has been logged.");
        }
        model.addAttribute("latencyMs", (System.nanoTime() - startedAt) / 1_000_000);
        return "index";
    }
}
