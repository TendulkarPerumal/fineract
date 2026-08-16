package dev.tendulkar.fineractagent.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which model, which database, and whether Flyway is actually active.
 * <p>
 * Every configuration failure in this stage looked identical from the outside:
 * a 500 with an opaque body, or worse, no symptom at all. An empty
 * {@code AGENT_DB_JDBC_URL} silently became localhost. A retired model id
 * silently became a 404. And bare {@code flyway-core} on Spring Boot 4 silently
 * disabled migrations entirely — the app started fine, the {@code spring.flyway.*}
 * properties were ignored, and no schema history table was ever created.
 * <p>
 * That last one is the dangerous kind: it has no error, so it is only noticed
 * when a deployment reaches an environment where nobody ran the SQL by hand.
 * Asserting it at startup costs three lines.
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
    private final ObjectProvider<Flyway> flyway;

    StartupInfoLogger(
            @Value("${spring.ai.google.genai.chat.model:UNSET}") String model,
            @Value("${spring.ai.google.genai.api-key:}") String apiKey,
            @Value("${spring.datasource.url:UNSET}") String jdbcUrl,
            ObjectProvider<Flyway> flyway) {
        this.model = model;
        this.apiKey = apiKey;
        this.jdbcUrl = jdbcUrl;
        this.flyway = flyway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logResolvedConfiguration() {
        log.info("gemini model      : {}", model);
        log.info("gemini api key    : {}", apiKey.isBlank() ? "ABSENT" : "present");
        log.info("datasource url    : {}", jdbcUrl);

        Flyway configured = flyway.getIfAvailable();
        if (configured == null) {
            log.error("flyway            : NOT CONFIGURED - migrations did not run. "
                    + "On Spring Boot 4 this happens when flyway-core is present without "
                    + "spring-boot-starter-flyway; the schema is whatever someone applied by hand.");
        } else {
            log.info("flyway            : {} migration(s) applied",
                    configured.info().applied().length);
        }

        if (jdbcUrl.startsWith("jdbc:postgresql://localhost")
                || "jdbc:postgresql://".equals(jdbcUrl)) {
            log.warn("datasource points at localhost. If you meant Neon, "
                    + "AGENT_DB_JDBC_URL was empty or unexported when this process started.");
        }
    }
}
