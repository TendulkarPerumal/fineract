package dev.tendulkar.fineractagent.config;

import dev.tendulkar.fineractagent.config.DatabaseUrlNormaliser.Normalised;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the conversion that cost a debugging round trip: a Neon connection
 * string pasted into AGENT_DB_JDBC_URL failed deep inside Hikari with
 * {@code 'url' must start with "jdbc"}.
 */
class DatabaseUrlNormaliserTest {

    private static final String NEON =
            "postgresql://neondb_owner:npg_secret@ep-shy-math-az97y8j5-pooler"
                    + ".c-3.ap-southeast-1.aws.neon.tech"
                    + "/fineract_agent?sslmode=require&channel_binding=require";

    @Test
    @DisplayName("a Neon libpq url becomes a JDBC url, keeping database and query parameters")
    void convertsNeonUrl() {
        Normalised result = DatabaseUrlNormaliser.parse(NEON);

        assertEquals("jdbc:postgresql://ep-shy-math-az97y8j5-pooler.c-3.ap-southeast-1.aws.neon.tech"
                + "/fineract_agent?sslmode=require&channel_binding=require", result.jdbcUrl());
        // Neon refuses a non-TLS connection, so losing sslmode would break it.
        assertTrue(result.jdbcUrl().contains("sslmode=require"));
    }

    @Test
    @DisplayName("credentials are split out and never left in the url")
    void splitsCredentials() {
        Normalised result = DatabaseUrlNormaliser.parse(NEON);

        assertEquals("neondb_owner", result.username());
        assertEquals("npg_secret", result.password());
        // The JDBC url is logged at startup, so a password in it would be a leak.
        assertFalse(result.jdbcUrl().contains("npg_secret"),
                "password must not survive into the url that gets logged");
    }

    @Test
    @DisplayName("a non-default port is preserved")
    void keepsPort() {
        Normalised result = DatabaseUrlNormaliser.parse("postgresql://u:p@host.example:6543/db");
        assertEquals("jdbc:postgresql://host.example:6543/db", result.jdbcUrl());
    }

    @Test
    @DisplayName("the postgres:// scheme is accepted as well as postgresql://")
    void acceptsShortScheme() {
        // Heroku and some tooling emit the short form.
        Normalised result = DatabaseUrlNormaliser.parse("postgres://u:p@host.example/db");
        assertEquals("jdbc:postgresql://host.example/db", result.jdbcUrl());
    }

    @Test
    @DisplayName("a url with no credentials yields none rather than empty strings")
    void handlesUrlWithoutCredentials() {
        Normalised result = DatabaseUrlNormaliser.parse("postgresql://host.example/db");

        assertEquals("jdbc:postgresql://host.example/db", result.jdbcUrl());
        assertNull(result.username());
        assertNull(result.password());
    }

    @Test
    @DisplayName("an already-JDBC url is left alone")
    void leavesJdbcUrlAlone() {
        assertNull(DatabaseUrlNormaliser.parse("jdbc:postgresql://host.example/db?sslmode=require"));
    }

    @Test
    @DisplayName("absent or blank input is not an error")
    void ignoresAbsentInput() {
        assertNull(DatabaseUrlNormaliser.parse(null));
        assertNull(DatabaseUrlNormaliser.parse("   "));
    }

    @Test
    @DisplayName("a url that is neither form fails with a message naming both")
    void rejectsUnusableUrl() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseUrlNormaliser.parse("host.example/fineract_agent"));

        assertTrue(e.getMessage().contains("jdbc:postgresql://"));
        assertTrue(e.getMessage().contains("postgresql://user:password@"));
    }

    @Test
    @DisplayName("a hostless url fails instead of silently becoming localhost")
    void rejectsHostlessUrl() {
        // This is what an empty environment variable expands to. The Postgres
        // driver resolves it to localhost rather than failing, which is how
        // "connection refused" came to mean "the variable was never exported".
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseUrlNormaliser.parse("postgresql://"));

        assertTrue(e.getMessage().contains("no host"));
    }
}
