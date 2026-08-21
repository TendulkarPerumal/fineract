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
 * to do, and it fails several frames inside Hikari with {@code 'url' must start
 * with "jdbc"} — a message that names the symptom rather than the cause.
 * <p>
 * The conversion is a pure function ({@link #parse}) so it can be tested
 * without environment variables; {@link #apply()} is the thin shell that reads
 * the environment and publishes system properties, which outrank environment
 * variables in Spring's property precedence.
 */
public final class DatabaseUrlNormaliser {

    private static final String USAGE = """
            The database URL must be either the JDBC form:
              jdbc:postgresql://host/db?sslmode=require
              (with AGENT_DB_USER and AGENT_DB_PASSWORD set separately)
            or the libpq form your provider displays:
              postgresql://user:password@host/db?sslmode=require
            """;

    /**
     * @param jdbcUrl  never contains credentials, because this value is logged
     * @param username null when the source URL carried none
     * @param password null when the source URL carried none
     */
    public record Normalised(String jdbcUrl, String username, String password) {
    }

    private DatabaseUrlNormaliser() {
    }

    /**
     * Converts a libpq URL to JDBC form. Returns {@code null} for input that is
     * already JDBC or is absent — both meaning "nothing to do".
     *
     * @throws IllegalStateException if the URL is set but unusable, so the
     *                               failure names the cause rather than
     *                               surfacing later as a Hikari error
     */
    public static Normalised parse(String url) {
        if (url == null || url.isBlank() || url.startsWith("jdbc:")) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("postgres://") && !lower.startsWith("postgresql://")) {
            throw new IllegalStateException("Unusable database URL, starting with '"
                    + trimmed.substring(0, Math.min(12, trimmed.length())) + "'.\n" + USAGE);
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            // "postgresql://" on its own does not even parse as a URI, so the
            // host check below would never be reached.
            throw new IllegalStateException("Database URL has no host.\n" + USAGE, e);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            // The shape an empty environment variable produces. The Postgres
            // driver resolves a hostless URL to localhost rather than failing,
            // which is how "connection refused" ends up meaning "never exported".
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

        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int colon = userInfo.indexOf(':');
            username = colon < 0 ? userInfo : userInfo.substring(0, colon);
            password = colon < 0 ? null : userInfo.substring(colon + 1);
        }
        return new Normalised(jdbc.toString(), username, password);
    }

    public static void apply() {
        String url = firstNonBlank(
                System.getenv("AGENT_DB_JDBC_URL"),
                System.getenv("AGENT_DB_URL"),
                System.getenv("DATABASE_URL"));

        Normalised normalised = parse(url);
        if (normalised == null) {
            // Already JDBC, or absent — in which case Spring reports the
            // unresolved placeholder, which is clear enough on its own.
            return;
        }

        System.setProperty("spring.datasource.url", normalised.jdbcUrl());

        // Credentials embedded in the URL apply only when they were not given
        // separately. An explicit AGENT_DB_USER must win: that is how the
        // read-only role is selected in the deployed configuration.
        if (normalised.username() != null && isBlank(System.getenv("AGENT_DB_USER"))) {
            System.setProperty("spring.datasource.username", normalised.username());
        }
        if (normalised.password() != null && isBlank(System.getenv("AGENT_DB_PASSWORD"))) {
            System.setProperty("spring.datasource.password", normalised.password());
        }

        // Host only: the source URL carries a password and this line reaches logs.
        System.out.println("[startup] converted libpq connection string to JDBC form for host "
                + URI.create(url.trim()).getHost());
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
