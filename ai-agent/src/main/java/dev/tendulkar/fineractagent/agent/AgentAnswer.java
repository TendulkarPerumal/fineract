package dev.tendulkar.fineractagent.agent;

/**
 * One answer plus what it cost. Token counts come from the model response, not
 * an estimate: cost per question is a number this project claims in its README,
 * so it has to be measured.
 */
public record AgentAnswer(String text, long promptTokens, long completionTokens) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
