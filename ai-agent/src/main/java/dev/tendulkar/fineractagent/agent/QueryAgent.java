package dev.tendulkar.fineractagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * The agent loop.
 * <p>
 * One call to {@link #answer} can produce several round trips to Gemini:
 * <ol>
 *   <li>The question is sent along with the JSON schema of every tool in
 *       {@link PortfolioTools}.</li>
 *   <li>The model replies either with text, or with a request to call a tool
 *       and the arguments to call it with.</li>
 *   <li>Spring AI executes that tool — real SQL against Postgres — and appends
 *       the result to the conversation.</li>
 *   <li>The model is called again with that result in context. It may answer,
 *       or call another tool. Steps 2-4 repeat until it answers.</li>
 * </ol>
 * Spring AI drives this loop; the interesting engineering is in what the tools
 * refuse to do, not in the loop itself.
 * <p>
 * The division of labour is the point: the model decides WHICH question to ask
 * and how to phrase the answer, and the database decides WHAT IS TRUE. Every
 * number in an answer arrives from a tool result. None is produced by the
 * model, because a model asked for a figure it does not have will produce a
 * plausible one.
 */
@Service
public class QueryAgent {

    private static final String SYSTEM_PROMPT = """
            You are a reporting assistant for a microfinance institution running \
            Apache Fineract. You answer questions about clients, loans, arrears \
            and savings using the tools provided.

            Rules:
            - Every figure you report must come from a tool result. Never estimate, \
              extrapolate or recall a number. If the tools cannot answer, say so.
            - If a tool returns an empty list, that means no rows matched the \
              filters you used. Say that, and say which filters you used. Do not \
              restate it as a general fact about the portfolio.
            - If a tool returns an error, read it: it usually says what argument \
              was wrong. Correct the call and retry once.
            - Arrears figures are relative to a fixed business date, not today. \
              Call get_business_date and state that date whenever you report \
              anything about lateness.
            - Quote account numbers and client names exactly as returned.

            Domain facts that are easy to get wrong:
            - No loan status means overdue. A loan months behind is still ACTIVE. \
              Lateness comes only from get_arrears_summary.
            - Days in arrears is measured from the oldest unpaid instalment, not \
              from the last payment date.
            - Office totals can mean one branch alone or a branch with everything \
              beneath it. Decide which the question means and say which you used.

            Cost and latency:
            - For "how many" or "total" questions about arrears, call \
              get_arrears_totals. It counts in the database. Never call \
              get_arrears_summary and count its rows: that pulls every matching \
              loan into the conversation to answer a question about one number.
            - Use the detail tools only when specific accounts must be named.

            Be concise and factual. Lead with the answer, then at most a few lines \
            of supporting detail. Do not list individual accounts unless asked for \
            them. No preamble.
            """;

    private final ChatClient chatClient;

    QueryAgent(ChatClient.Builder builder, PortfolioTools tools) {
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(tools)
                .build();
    }

    public AgentAnswer answer(String question) {
        ChatResponse response = chatClient.prompt()
                .user(question)
                .call()
                .chatResponse();

        String text = (response == null || response.getResult() == null)
                ? "" : response.getResult().getOutput().getText();

        // Usage is read from the response rather than estimated: cost per
        // question is a number this project reports, so it has to be measured.
        // A missing usage block reads as zero rather than breaking the answer.
        long prompt = 0;
        long completion = 0;
        if (response != null && response.getMetadata() != null
                && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            prompt = usage.getPromptTokens() == null ? 0L : usage.getPromptTokens();
            completion = usage.getCompletionTokens() == null ? 0L : usage.getCompletionTokens();
        }
        return new AgentAnswer(text, prompt, completion);
    }
}
