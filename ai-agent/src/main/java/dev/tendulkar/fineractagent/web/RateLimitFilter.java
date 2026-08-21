package dev.tendulkar.fineractagent.web;

import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.StatsRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window request allowance per client, applied only to the endpoints
 * that cost money.
 * <p>
 * The page and the health endpoints are free to serve; the model is not. This
 * project runs on a free tier at a public URL, so the realistic threat is not
 * an attacker but a crawler or a curious visitor holding down enter.
 * <p>
 * In-memory and per-instance, which is honest for a single Cloud Run container.
 * Behind more than one instance this becomes per-instance rather than global,
 * and the daily token budget in ResilientQueryAgent is the backstop that
 * actually bounds spend.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(Instant startedAt, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AgentProperties properties;
    private final StatsRegistry stats;

    RateLimitFilter(AgentProperties properties, StatsRegistry stats) {
        this.properties = properties;
        this.stats = stats;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean costsMoney = "/ask".equals(path) || "/api/ask".equals(path);
        return !costsMoney;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!allow(clientKey(request))) {
            stats.recordRefusal();
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too many questions. This demo allows "
                            + properties.requestsPerMinute() + " per minute.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String key) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || now.isAfter(current.startedAt().plusSeconds(60))) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.count() + 1);
        });
        // Bound the map: without this a public URL grows one entry per client
        // address forever, which is a slow memory leak dressed as a rate limiter.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now.isAfter(e.getValue().startedAt().plusSeconds(120)));
        }
        return updated.count() <= properties.requestsPerMinute();
    }

    /**
     * Cloud Run terminates TLS and forwards the caller in X-Forwarded-For, so
     * the socket address is the load balancer for every request. Trusting that
     * header is only safe because nothing reaches this container except through
     * Cloud Run; behind a different proxy it would need revisiting.
     */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
