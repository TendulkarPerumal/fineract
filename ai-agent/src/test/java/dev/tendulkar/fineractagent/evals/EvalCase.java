package dev.tendulkar.fineractagent.evals;

/**
 * One eval question and how to decide whether the answer was right.
 *
 * @param id          stable identifier, so a failure can be discussed
 * @param category    what the question exercises; failures are grouped by this
 * @param question    exactly what a user would type
 * @param expectedSql SQL that computes the expected answer INDEPENDENTLY of the
 *                    tools. It must not be copied from PortfolioQueries: an
 *                    eval that reuses the implementation's own query proves
 *                    only that the query is deterministic, not that it is right.
 * @param check       how the answer text is judged
 */
public record EvalCase(String id, String category, String question,
                       String expectedSql, Check check) {

    public enum Check {
        /** The answer must contain the number the SQL returns. */
        CONTAINS_NUMBER,
        /** The answer must contain the string the SQL returns. */
        CONTAINS_TEXT,
        /**
         * The agent must decline rather than answer. Used where a confident
         * answer is itself the failure: a status that does not exist, an office
         * that does not exist, a prediction the data cannot support.
         */
        MUST_REFUSE
    }
}
