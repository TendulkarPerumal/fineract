package dev.tendulkar.fineractagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Asserts that the r_* reference tables still agree with Fineract's Java enums.
 * <p>
 * This is the check that stands in for foreign keys. docs/DOMAIN.md decision 5
 * rules out an FK from the m_* _enum columns to these tables, because Fineract's
 * write path does not know they exist and an FK could reject an insert upstream
 * considers valid. Something still has to notice when the two drift, and this is
 * it: build time rather than insert time, and without constraining a schema we
 * do not own.
 * <p>
 * It also guards the specific bug that motivated building our own tables at all.
 * Fineract's shipped r_enum_value labels savings transaction type 19 as
 * WITHHOLD_TAX while the Java enum says 18 is WITHHOLD_TAX and 19 is ESCHEAT
 * (docs/DOMAIN.md section 7.1). If someone ever "fixes" V2 to match r_enum_value,
 * this fails.
 * <p>
 * Needs no database and no API key, so it runs on every CI build. It does need
 * the Fineract sources, which are present because this project lives inside a
 * fork of them; if that ever stops being true the test skips rather than fails
 * misleadingly.
 */
class ReferenceTableConsistencyTest {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V2__reference_tables.sql");

    /** enum constant lines look like:  NAME(123, "message.key"), */
    private static final Pattern JAVA_CONSTANT =
            Pattern.compile("^\\s{4}([A-Z][A-Z_0-9]*)\\((\\d+)[,)]", Pattern.MULTILINE);

    /** seed rows look like:  (123, 'CODE', 'description'...), */
    private static final Pattern SQL_ROW =
            Pattern.compile("^\\s*\\((\\d+),\\s*'([A-Z_0-9]+)'", Pattern.MULTILINE);

    static boolean fineractSourcesPresent() {
        return Files.isDirectory(REPO_ROOT.resolve("fineract-core/src/main/java"));
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_loan_status matches LoanStatus.java")
    void loanStatus() throws IOException {
        assertMatches("r_loan_status",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/loanaccount/domain/LoanStatus.java");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_loan_transaction_type matches LoanTransactionType.java")
    void loanTransactionType() throws IOException {
        assertMatches("r_loan_transaction_type",
                "fineract-loan/src/main/java/org/apache/fineract/portfolio/loanaccount/domain/LoanTransactionType.java");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_client_status matches ClientStatus.java")
    void clientStatus() throws IOException {
        assertMatches("r_client_status",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/client/domain/ClientStatus.java");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_loan_type matches AccountType.java")
    void loanType() throws IOException {
        assertMatches("r_loan_type",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/accountdetails/domain/AccountType.java");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_savings_account_status matches SavingsAccountStatusType.java")
    void savingsAccountStatus() throws IOException {
        assertMatches("r_savings_account_status",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/savings/domain/SavingsAccountStatusType.java");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_savings_transaction_type matches SavingsAccountTransactionType.java, including 18/19")
    void savingsTransactionType() throws IOException {
        Map<Integer, String> seeded = assertMatches("r_savings_transaction_type",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/savings/SavingsAccountTransactionType.java");

        // The mislabel in Fineract's own r_enum_value, pinned explicitly.
        assertEquals("WITHHOLD_TAX", seeded.get(18),
                "18 must be WITHHOLD_TAX; Fineract's r_enum_value gets this wrong");
        assertEquals("ESCHEAT", seeded.get(19),
                "19 must be ESCHEAT; Fineract's r_enum_value labels it WITHHOLD_TAX");
    }

    @Test
    @EnabledIf("fineractSourcesPresent")
    @DisplayName("r_deposit_type matches DepositAccountType.java")
    void depositType() throws IOException {
        assertMatches("r_deposit_type",
                "fineract-core/src/main/java/org/apache/fineract/portfolio/savings/DepositAccountType.java");
    }

    private Map<Integer, String> assertMatches(String table, String javaPath) throws IOException {
        Map<Integer, String> fromJava = parseJavaEnum(REPO_ROOT.resolve(javaPath));
        Map<Integer, String> fromSql = parseSeedRows(table);

        assertFalse(fromJava.isEmpty(), "no constants parsed from " + javaPath);
        assertFalse(fromSql.isEmpty(), "no rows parsed for " + table);
        assertEquals(fromJava, fromSql, table
                + " has drifted from " + Path.of(javaPath).getFileName()
                + ". Regenerate the seed rather than editing one side.");
        return fromSql;
    }

    private Map<Integer, String> parseJavaEnum(Path file) throws IOException {
        String source = Files.readString(file);
        // Skip the Apache licence header before looking for the end of the
        // constant list: line 7 of it contains the text '"License");', and
        // truncating at the first ");" would cut the file before the enum body.
        int enumStart = source.indexOf(" enum ");
        if (enumStart > 0) {
            source = source.substring(enumStart);
        }
        // Stop at the end of the constant list so helper methods with
        // upper-case names cannot be mistaken for constants.
        int semicolon = source.indexOf(");");
        if (semicolon > 0) {
            source = source.substring(0, semicolon + 2);
        }
        Map<Integer, String> values = new LinkedHashMap<>();
        Matcher matcher = JAVA_CONSTANT.matcher(source);
        while (matcher.find()) {
            values.put(Integer.parseInt(matcher.group(2)), matcher.group(1));
        }
        return values;
    }

    private Map<Integer, String> parseSeedRows(String table) throws IOException {
        String sql = Files.readString(MIGRATION);
        int start = sql.indexOf("INSERT INTO " + table + " ");
        assertFalse(start < 0, "no INSERT found for " + table);
        int end = sql.indexOf(';', start);
        String block = sql.substring(start, end < 0 ? sql.length() : end);

        Map<Integer, String> values = new LinkedHashMap<>();
        Matcher matcher = SQL_ROW.matcher(block);
        while (matcher.find()) {
            values.put(Integer.parseInt(matcher.group(1)), matcher.group(2));
        }
        return values;
    }
}
