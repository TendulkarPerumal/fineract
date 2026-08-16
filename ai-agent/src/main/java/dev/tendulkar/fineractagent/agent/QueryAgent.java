package dev.tendulkar.fineractagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Stage 2: a single call to Gemini with no access to the database.
 * <p>
 * The model is told what the domain is and, more importantly, told that it
 * cannot see any data yet. That is not politeness — an LLM asked a portfolio
 * question with no tools will invent plausible loan counts, and a fabricated
 * number is worse than a refusal because it looks like an answer. Stage 3
 * replaces this with tool calling, at which point every figure comes from SQL.
 */
@Service
public class QueryAgent {

    private static final String SYSTEM_PROMPT = """
            You are a reporting assistant for a microfinance institution running \
            Apache Fineract. Users ask questions about clients, loans, arrears \
            and savings.

            You currently have NO access to the database. You cannot see any \
            client, loan, or balance.

            Therefore:
            - Never state a number, name, account or total as if it were real data.
            - If the question needs data, say plainly that data access is not \
              wired up yet, then explain which tables and columns would answer it.
            - You may explain domain concepts. Two worth getting right:
              a loan's status never says it is overdue — arrears is computed from \
              the repayment schedule — and a reversed transaction stays in the \
              table and must be excluded from any total.

            Be concise. No preamble.
            """;

    private final ChatClient chatClient;

    QueryAgent(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    public String answer(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
