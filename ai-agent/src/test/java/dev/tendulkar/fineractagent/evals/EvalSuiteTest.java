package dev.tendulkar.fineractagent.evals;

import dev.tendulkar.fineractagent.agent.AgentAnswer;
import dev.tendulkar.fineractagent.agent.QueryAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The eval suite: 30 questions, each answered by the agent and checked against
 * a value computed independently in SQL.
 * <p>
 * Requires a seeded database and a live model, so it is disabled unless
 * GEMINI_API_KEY is present. A green build on a machine with no key means the
 * unit tests passed, not that the agent works — the CI workflow says so
 * explicitly rather than letting a skipped suite look like a passing one.
 * <p>
 * The suite asserts a pass-rate floor rather than demanding every case pass.
 * An LLM under a fixed temperature is close to deterministic but not
 * guaranteed to be, and a suite that fails the build on one flaky phrasing
 * stops being run. The floor is what is defended; the per-case report is what
 * is read.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class EvalSuiteTest {

    static {
        // @SpringBootTest never calls main(), so the normalisation applied there
        // has to be repeated here. A static block runs at class load, before any
        // JUnit extension callback creates the Spring context.
        dev.tendulkar.fineractagent.config.DatabaseUrlNormaliser.apply();
    }

    /** Below this, the agent is considered regressed rather than merely imperfect. */
    private static final double PASS_RATE_FLOOR = 0.80;

    private enum Outcome {
        PASS,
        /** Answered, but the number or name did not match the SQL. */
        WRONG_VALUE,
        /** Answered with no figure at all where one was required. */
        NO_VALUE,
        /** Should have declined, but answered anyway. */
        ANSWERED_UNANSWERABLE,
        /** Declined a question it should have answered. */
        REFUSED_ANSWERABLE,
        /** Threw or timed out. */
        ERROR
    }

    private record Result(EvalCase evalCase, Outcome outcome, String expected,
                          String actual, long millis) {
    }

    private static final List<Result> RESULTS = new ArrayList<>();

    /**
     * Deliberately QueryAgent, not ResilientQueryAgent.
     *
     * The circuit breaker opens after four consecutive failures and then fails
     * every subsequent call for a minute. Across a 30-question batch that turns
     * a handful of transient provider errors into a wave of ERROR outcomes and
     * a pass rate that measures the breaker rather than the agent.
     *
     * The breaker is right for a live endpoint and wrong for a batch. What is
     * under test here is whether answers are correct, so the suite talks to the
     * agent loop directly. The trade-off is no per-call deadline: a hung call
     * stalls the suite rather than failing one case.
     */
    @Autowired
    private QueryAgent agent;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @Order(1)
    @DisplayName("30 questions answered from the database, checked against independent SQL")
    void runEvals() {
        for (EvalCase evalCase : EvalCases.all()) {
            RESULTS.add(run(evalCase));
        }

        long passed = RESULTS.stream().filter(r -> r.outcome() == Outcome.PASS).count();
        double passRate = passed / (double) RESULTS.size();

        assertTrue(passRate >= PASS_RATE_FLOOR,
                String.format(Locale.ROOT,
                        "pass rate %.0f%% (%d/%d) is below the %.0f%% floor",
                        passRate * 100, passed, RESULTS.size(), PASS_RATE_FLOOR * 100));
    }

    private Result run(EvalCase evalCase) {
        long startedAt = System.nanoTime();
        String answer;
        try {
            AgentAnswer result = agent.answer(evalCase.question());
            answer = result.text() == null ? "" : result.text();
        } catch (RuntimeException e) {
            return new Result(evalCase, Outcome.ERROR, "-", String.valueOf(e.getMessage()),
                    millisSince(startedAt));
        }
        long millis = millisSince(startedAt);

        if (evalCase.check() == EvalCase.Check.MUST_REFUSE) {
            return new Result(evalCase, refused(answer) ? Outcome.PASS : Outcome.ANSWERED_UNANSWERABLE,
                    "a refusal", oneLine(answer), millis);
        }

        String expected = queryExpected(evalCase);
        if (refused(answer)) {
            return new Result(evalCase, Outcome.REFUSED_ANSWERABLE, expected, oneLine(answer), millis);
        }
        if (evalCase.check() == EvalCase.Check.CONTAINS_NUMBER && !hasAnyDigit(answer)) {
            return new Result(evalCase, Outcome.NO_VALUE, expected, oneLine(answer), millis);
        }
        boolean matched = evalCase.check() == EvalCase.Check.CONTAINS_NUMBER
                ? containsNumber(answer, expected)
                : answer.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));

        return new Result(evalCase, matched ? Outcome.PASS : Outcome.WRONG_VALUE,
                expected, oneLine(answer), millis);
    }

    private String queryExpected(EvalCase evalCase) {
        Object value = jdbc.sql(evalCase.expectedSql()).query(Object.class).single();
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * Accepts the number however the model chose to write it: bare, with
     * thousands separators, or with a currency prefix. Judging formatting would
     * measure prose style rather than correctness.
     */
    private boolean containsNumber(String answer, String expected) {
        String digitsOnly = answer.replaceAll("[,_\\s]", "");
        return digitsOnly.contains(expected) || answer.contains(expected);
    }

    private boolean refused(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("cannot") || lower.contains("can't") || lower.contains("no such")
                || lower.contains("does not exist") || lower.contains("doesn't exist")
                || lower.contains("not able") || lower.contains("unable")
                || lower.contains("no status") || lower.contains("not a status")
                || lower.contains("i don't have") || lower.contains("i do not have")
                || lower.contains("outside") || lower.contains("not supported");
    }

    private boolean hasAnyDigit(String text) {
        return text.chars().anyMatch(Character::isDigit);
    }

    private String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * The report is the deliverable. A pass rate on its own says how much is
     * broken; the breakdown by category and failure mode says what to fix, and
     * those are the numbers that go in the README.
     */
    @AfterAll
    static void report() {
        if (RESULTS.isEmpty()) {
            return;
        }
        long passed = RESULTS.stream().filter(r -> r.outcome() == Outcome.PASS).count();

        System.out.println();
        System.out.println("================ EVAL REPORT ================");
        System.out.printf(Locale.ROOT, "pass rate: %d/%d (%.0f%%)%n",
                passed, RESULTS.size(), passed * 100.0 / RESULTS.size());

        Map<String, int[]> byCategory = new LinkedHashMap<>();
        Map<Outcome, Integer> byOutcome = new LinkedHashMap<>();
        for (Result r : RESULTS) {
            int[] tally = byCategory.computeIfAbsent(r.evalCase().category(), k -> new int[2]);
            tally[1]++;
            if (r.outcome() == Outcome.PASS) {
                tally[0]++;
            } else {
                byOutcome.merge(r.outcome(), 1, Integer::sum);
            }
        }

        System.out.println("\nby category:");
        byCategory.forEach((category, tally) ->
                System.out.printf(Locale.ROOT, "  %-12s %d/%d%n", category, tally[0], tally[1]));

        if (!byOutcome.isEmpty()) {
            System.out.println("\nfailures by type:");
            byOutcome.forEach((outcome, count) ->
                    System.out.printf(Locale.ROOT, "  %-24s %d%n", outcome, count));

            System.out.println("\nfailing cases:");
            RESULTS.stream().filter(r -> r.outcome() != Outcome.PASS).forEach(r ->
                    System.out.printf(Locale.ROOT, "  [%s] %s%n      expected: %s%n      actual:   %s%n",
                            r.outcome(), r.evalCase().id(), r.expected(), r.actual()));
        }

        List<Long> times = RESULTS.stream().map(Result::millis).sorted().toList();
        System.out.printf(Locale.ROOT, "%nlatency ms: p50=%d p95=%d max=%d%n",
                times.get(times.size() / 2),
                times.get((int) Math.min(times.size() - 1, Math.ceil(times.size() * 0.95) - 1)),
                times.get(times.size() - 1));
        System.out.println("=============================================");
    }
}
