package dev.tendulkar.fineractagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which model and which database this process actually resolved.
 * <p>
 * Every configuration failure so far has looked identical from the outside: a
 * 500 with an opaque body. An empty {@code AGENT_DB_JDBC_URL} silently became
 * localhost; a retired model id silently became a 404. Neither is visible
 * without a stack trace, and both are answered by three lines at startup.
 * <p>
 * The API key is never logged, only whether one is present. "Configured but
 * wrong" and "not configured at all" are different bugs and the log has to
 * distinguish them without printing the secret.
 */
@Component
public class StartupInfoLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    private final String model;
    private final String apiKey;
    private final String jdbcUrl;

    StartupInfoLogger(
            @Value("${spring.ai.google.genai.chat.model:UNSET}") String model,
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            @Value("${spring.datasource.url:UNSET}") String jdbcUrl) {
        this.model = model;
        this.apiKey = apiKey;
        this.jdbcUrl = jdbcUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logResolvedConfiguration() {
        log.info("gemini model      : {}", model);
        log.info("gemini api key    : {}", apiKey.isBlank() ? "ABSENT" : "present");
        log.info("datasource url    : {}", jdbcUrl);
        if (jdbcUrl.startsWith("jdbc:postgresql://localhost")
                || "jdbc:postgresql://".equals(jdbcUrl)) {
            log.warn("datasource points at localhost. If you meant Neon, "
                    + "AGENT_DB_JDBC_URL was empty or unexported when this process started.");
        }
    }
}
