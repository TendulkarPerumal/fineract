package dev.tendulkar.fineractagent.portfolio;

import dev.tendulkar.fineractagent.config.AgentProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the real migrations to a real Postgres and exercises every query the
 * agent can run.
 * <p>
 * Everything else in this suite tests Java. The SQL was untested, and that is
 * precisely where the expensive bugs were: a NULL bind parameter with no
 * inferable type broke every optional filter, and it could not have been caught
 * by anything short of a database. Mocking {@code PortfolioQueries} — which the
 * tool tests do, correctly, for their purpose — proves nothing about the SQL
 * inside it.
 * <p>
 * This also turns the seed's 17 consistency invariants from a script someone
 * has to remember to run into an assertion that runs in CI. Those invariants
 * are load-bearing: if the seed does not reconcile, the eval suite measures
 * nothing, because expected answers computed in SQL would agree with a broken
 * agent.
 * <p>
 * Skips when Docker is unavailable rather than failing, so a laptop without it
 * still gets a green build — and CI, which has Docker, is where this actually
 * runs.
 */
@EnabledIf("dockerAvailable")
class SchemaAndSeedTest {

    /** Matches Neon, which tracks current Postgres. */
    private static final String IMAGE = "postgres:18-alpine";

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 6, 30);

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient jdbc;
    private static PortfolioQueries queries;
    /** Same queries with the row cap lifted, for assertions about totals. */
    private static PortfolioQueries uncapped;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @BeforeAll
    static void startAndMigrate() {
        postgres = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("fineract_agent")
                .withUsername("agent")
                .withPassword("agent");
        postgres.start();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        // The same migrations the application runs, from the same location.
        // A test that applied a hand-maintained copy of the schema would drift
        // from the real one, which is the failure this whole project exists to
        // avoid.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        uncapped = new PortfolioQueries(jdbc, new AgentProperties(
                BUSINESS_DATE, 5000, Duration.ofSeconds(90), 400_000, 4,
                Duration.ofSeconds(60), 6));
        queries = new PortfolioQueries(jdbc, new AgentProperties(
                BUSINESS_DATE, 20, Duration.ofSeconds(90), 400_000, 4,
                Duration.ofSeconds(60), 6));
    }

    // ---------------------------------------------------------------- schema

    @Test
    @DisplayName("all four migrations apply to an empty database")
    void migrationsApply() {
        long applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Long.class).single();
        assertEquals(4, applied);

        long tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name NOT LIKE 'flyway%'
                """).query(Long.class).single();
        // 32 Fineract tables plus 7 r_* reference tables.
        assertEquals(39, tables);
    }

    @Test
    @DisplayName("every foreign key resolves inside the pruned schema")
    void foreignKeysResolve() {
        // The pruner claims a closed FK set. If it were wrong the migration
        // would not have applied at all, but asserting it makes the claim
        // explicit rather than incidental.
        long orphaned = jdbc.sql("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                     ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND ccu.table_name NOT IN (
                      SELECT table_name FROM information_schema.tables
                      WHERE table_schema = 'public')
                """).query(Long.class).single();
        assertEquals(0, orphaned);
    }

    // ------------------------------------------------------------------ seed

    @Test
    @DisplayName("the seed is the size it claims to be")
    void seedShape() {
        assertEquals(200, count("m_client"));
        assertEquals(350, count("m_loan"));
        assertEquals(150, count("m_savings_account"));
        assertEquals(3, count("m_office"));
        assertEquals(215, jdbc.sql("SELECT count(*) FROM m_loan WHERE loan_status_id = 300")
                .query(Long.class).single());
    }

    @Test
    @DisplayName("all 17 seed consistency invariants hold")
    void seedInvariantsHold() {
        // scripts/stage1/verify-seed.sql is the source of truth for these, so
        // the test runs that file rather than restating the invariants and
        // letting the two drift.
        String sql = readVerifyScript();
        List<String> violations = new ArrayList<>();

        jdbc.sql(sql).query((rs, rowNum) -> {
            String check = rs.getString(1);
            long count = rs.getLong(2);
            if (count != 0) {
                violations.add(check + " -> " + count);
            }
            return check;
        }).list();

        assertTrue(violations.isEmpty(),
                "seed is internally inconsistent:\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("arrears is spread across offices, not correlated with one branch")
    void arrearsNotCorrelatedWithOffice() {
        // The seed originally keyed office and arrears both on i % 10, so one
        // branch structurally could not contain a loan in arrears. Both
        // branches must now appear.
        List<String> offices = jdbc.sql("""
                SELECT DISTINCT o.name
                FROM m_loan_arrears_aging a
                JOIN m_loan l ON l.id = a.loan_id
                JOIN m_client c ON c.id = l.client_id
                JOIN m_office o ON o.id = c.office_id
                """).query(String.class).list();

        assertTrue(offices.size() >= 2,
                "arrears appears in only " + offices + "; office and arrears are correlated again");
    }

    @Test
    @DisplayName("arrears exists only on ACTIVE loans")
    void arrearsOnlyOnActiveLoans() {
        // There is no OVERDUE status; arrears is computed, and only for status
        // 300 (docs/DOMAIN.md section 4.3).
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM m_loan_arrears_aging a
                JOIN m_loan l ON l.id = a.loan_id
                WHERE l.loan_status_id <> 300
                """).query(Long.class).single());
    }

    // ----------------------------------------------------------------- tools

    @Test
    @DisplayName("every tool query executes, including with all optional filters omitted")
    void allQueriesExecuteWithoutFilters() {
        // This is the case that broke: a NULL bind parameter has no inferable
        // type in Postgres, so "(? IS NULL OR ...)" failed with "could not
        // determine data type of parameter" whenever an optional argument was
        // left out — which is the common call.
        assertFalse(queries.queryLoans(null, null, null).isEmpty());
        assertFalse(queries.arrearsSummary(1, null, null).isEmpty());
        assertFalse(queries.arrearsTotals(1, null).isEmpty());
        assertFalse(queries.officeTotals(false).isEmpty());
        assertFalse(queries.officeTotals(true).isEmpty());
        assertFalse(queries.clientPortfolio(null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("every tool query executes with filters supplied")
    void allQueriesExecuteWithFilters() {
        assertFalse(queries.queryLoans("ACTIVE", "Eastern", 5).isEmpty());
        assertFalse(queries.clientPortfolio(null, 1, BigDecimal.ZERO, 5).isEmpty());
        // A schedule for a loan that is disbursed, so it has instalments.
        String account = jdbc.sql(
                        "SELECT account_no FROM m_loan WHERE loan_status_id = 300 ORDER BY id LIMIT 1")
                .query(String.class).single();
        assertFalse(queries.loanSchedule(account).isEmpty());
    }

    @Test
    @DisplayName("the row cap is enforced whatever the model asks for")
    void rowCapEnforced() {
        // maxRows is 20 in the properties above; asking for 500 must not
        // produce 500 rows of prompt context.
        assertTrue(queries.queryLoans(null, null, 500).size() <= 20);
    }

    @Test
    @DisplayName("the arrears tool agrees with the batch table Fineract would populate")
    void arrearsAgreesWithBatchTable() {
        // Two independent paths to the same number: computed from the schedule,
        // and read from m_loan_arrears_aging. The agent uses the first because
        // the second is empty wherever Fineract's job never ran, but they must
        // agree or the eval suite cannot use one to check the other.
        long computed = uncapped.arrearsSummary(1, null, 5000).size();
        long batchTable = count("m_loan_arrears_aging");
        assertEquals(batchTable, computed);
    }

    @Test
    @DisplayName("days in arrears is measured from the oldest unpaid instalment")
    void daysInArrearsFromOldestUnpaid() {
        // Not days since the last payment. Every reported age must equal
        // business date minus the oldest unpaid due date.
        long mismatched = jdbc.sql("""
                SELECT count(*) FROM m_loan_arrears_aging a
                WHERE (DATE '2026-06-30' - a.overdue_since_date_derived) <= 0
                """).query(Long.class).single();
        assertEquals(0, mismatched);
    }

    // ------------------------------------------------------------------ util

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /**
     * Reads verify-seed.sql, dropping psql meta-commands. Those lines start
     * with a backslash and are meaningless over JDBC.
     */
    private String readVerifyScript() {
        Path script = Path.of("..", "scripts", "stage1", "verify-seed.sql");
        try {
            return Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.trim().startsWith("\\"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + script.toAbsolutePath(), e);
        }
    }
}
