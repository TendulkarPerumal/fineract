package dev.tendulkar.fineractagent.agent;

import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.StatsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything that decides whether a question reaches the model at all.
 * <p>
 * Three separate protections, because they fail for different reasons and a
 * user deserves to be told which:
 * <ul>
 *   <li><b>Deadline</b> — a question is abandoned after a fixed budget. Without
 *       it a slow model holds a request thread for as long as it likes, which
 *       on Cloud Run means paying for an instance to wait.</li>
 *   <li><b>Circuit breaker</b> — after repeated failures the service stops
 *       calling the model and fails immediately. When the provider is down,
 *       queueing more calls converts one outage into a thread pool exhaustion.</li>
 *   <li><b>Daily token budget</b> — a hard cap on spend. This runs on a free
 *       tier at a public URL: without a cap, one visitor with a loop exhausts
 *       the quota for every other visitor, and the demo is dead until
 *       tomorrow.</li>
 * </ul>
 * Hand-written rather than pulled from a resilience library: the whole policy
 * is about forty lines, and it is worth being able to explain every one of them
 * rather than a dependency's defaults.
 */
@Service
public class ResilientQueryAgent {

    private static final Logger log = LoggerFactory.getLogger(ResilientQueryAgent.class);

    private enum Circuit { CLOSED, OPEN, HALF_OPEN }

    private final QueryAgent delegate;
    private final AgentProperties properties;
    private final StatsRegistry stats;

    /**
     * One thread per in-flight question, so a timed-out call can be abandoned
     * by the request thread. The abandoned task still runs to completion in the
     * background - an HTTP call already in flight cannot be truly cancelled -
     * but the user is not made to wait for it.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-call");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<Circuit> circuit = new AtomicReference<>(Circuit.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(Instant.EPOCH);

    private final AtomicLong tokensSpentToday = new AtomicLong();
    private final AtomicReference<LocalDate> budgetDay =
            new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));

    ResilientQueryAgent(QueryAgent delegate, AgentProperties properties, StatsRegistry stats) {
        this.delegate = delegate;
        this.properties = properties;
        this.stats = stats;
    }

    public AgentAnswer answer(String question) {
        rejectIfCircuitOpen();
        rejectIfBudgetSpent();

        Future<AgentAnswer> future = executor.submit(() -> delegate.answer(question));
        try {
            AgentAnswer answer = future.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            onSuccess(answer);
            return answer;
        } catch (TimeoutException e) {
            future.cancel(true);
            onFailure("timeout");
            throw new AgentUnavailableException(
                    "The question took longer than " + properties.requestTimeout().toSeconds()
                            + " seconds and was abandoned. Try a narrower question.");
        } catch (ExecutionException e) {
            onFailure(String.valueOf(e.getCause()));
            throw new AgentUnavailableException("The agent failed to answer.", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while answering.", e);
        }
    }

    private void rejectIfCircuitOpen() {
        if (circuit.get() == Circuit.OPEN) {
            boolean elapsed = Instant.now()
                    .isAfter(openedAt.get().plus(properties.circuitOpenFor()));
            if (!elapsed) {
                stats.recordRefusal();
                throw new AgentUnavailableException(
                        "The model is currently unavailable after repeated failures. "
                                + "Retry in a minute.");
            }
            // Let exactly one call through to test whether the provider recovered.
            circuit.compareAndSet(Circuit.OPEN, Circuit.HALF_OPEN);
        }
    }

    private void rejectIfBudgetSpent() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(budgetDay.get())) {
            budgetDay.set(today);
            tokensSpentToday.set(0);
        }
        if (tokensSpentToday.get() >= properties.dailyTokenCap()) {
            stats.recordRefusal();
            throw new AgentUnavailableException(
                    "The daily token budget for this demo is spent. It resets at midnight UTC.");
        }
    }

    private void onSuccess(AgentAnswer answer) {
        tokensSpentToday.addAndGet(answer.totalTokens());
        consecutiveFailures.set(0);
        circuit.set(Circuit.CLOSED);
    }

    private void onFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (circuit.get() == Circuit.HALF_OPEN || failures >= properties.failureThreshold()) {
            circuit.set(Circuit.OPEN);
            openedAt.set(Instant.now());
            log.error("circuit opened after {} consecutive failures, last: {}", failures, reason);
        } else {
            log.warn("agent call failed ({} consecutive): {}", failures, reason);
        }
    }

    public String circuitState() {
        return circuit.get().name();
    }

    public long tokensSpentToday() {
        return tokensSpentToday.get();
    }
}
