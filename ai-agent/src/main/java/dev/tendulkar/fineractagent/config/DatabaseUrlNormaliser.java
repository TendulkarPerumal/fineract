package dev.tendulkar.fineractagent.config;

import java.net.URI;
import java.util.Locale;

/**
 * Accepts a Postgres connection string in either form and hands Spring a JDBC
 * one.
 * <p>
 * Neon, Supabase, Render and Heroku all display a libpq URL:
 * {@code postgresql://user:pass@host/db?sslmode=require}. Spring needs
 * {@code jdbc:postgresql://host/db?sslmode=require} plus username and password
 * as separate properties. Pasting the URL you were shown is the obvious thing
 * to do, and it fails deep inside Hikari with {@code 'url' must start with
 * "jdbc"} — a message that names the symptom rather than the cause, several
 * frames below anything in this codebase.
 * <p>
 * Converting it here removes a mistake that is easy to make and slow to
 * diagnose. Anything already in JDBC form is untouched, so configuring the
 * three variables explicitly still works and still takes precedence.
 * <p>
 * Runs before {@code SpringApplication.run} and writes system properties, which
 * outrank environment variables in Spring's property precedence.
 */
public final class DatabaseUrlNormaliser {

    private static final String USAGE = """
            The database URL must be either the JDBC form:
              jdbc:postgresql://host/db?sslmode=require
              (with AGENT_DB_USER and AGENT_DB_PASSWORD set separately)
            or the libpq form your provider displays:
              postgresql://user:password@host/db?sslmode=require
            """;

    private DatabaseUrlNormaliser() {
    }

    public static void apply() {
        String url = firstNonBlank(
                System.getenv("AGENT_DB_JDBC_URL"),
                System.getenv("AGENT_DB_URL"),
                System.getenv("DATABASE_URL"));

        if (url == null || url.startsWith("jdbc:")) {
            // Already correct, or absent — in which case Spring reports the
            // unresolved placeholder, which is a clear enough message already.
            return;
        }

        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("postgres://") && !lower.startsWith("postgresql://")) {
            throw new IllegalStateException("Unusable database URL, starting with '"
                    + url.substring(0, Math.min(12, url.length())) + "'.\n" + USAGE);
        }

        URI uri = URI.create(url);
        if (uri.getHost() == null) {
            throw new IllegalStateException("Database URL has no host.\n" + USAGE);
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getPath() == null ? "" : uri.getPath());
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbc.append('?').append(uri.getQuery());
        }
        System.setProperty("spring.datasource.url", jdbc.toString());

        // Credentials embedded in the URL apply only when they were not given
        // separately. An explicit AGENT_DB_USER must win, because that is how
        // the read-only role is selected in the deployed configuration.
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            String password = colon < 0 ? "" : userInfo.substring(colon + 1);
            if (isBlank(System.getenv("AGENT_DB_USER"))) {
                System.setProperty("spring.datasource.username", user);
            }
            if (isBlank(System.getenv("AGENT_DB_PASSWORD"))) {
                System.setProperty("spring.datasource.password", password);
            }
        }

        // Host only. The URL carries a password and this line reaches logs.
        System.out.println("[startup] converted libpq connection string to JDBC form for host "
                + uri.getHost());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
