package dev.tendulkar.fineractagent.agent;

import dev.tendulkar.fineractagent.agent.PortfolioTools.ToolResult;
import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.CallMetrics;
import dev.tendulkar.fineractagent.portfolio.PortfolioQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool boundary is where the project's central claim lives: a bad argument
 * must produce an error the model can read, never an empty list.
 * <p>
 * An empty list is indistinguishable from truth. Asked for loans with status
 * OVERDUE — a status Fineract does not have — a tool that passed the value
 * through would match zero rows, and the model would report "there are none",
 * which is a confident lie about a portfolio that is 106 loans in arrears.
 */
class PortfolioToolsTest {

    private static final AgentProperties PROPERTIES = new AgentProperties(
            LocalDate.of(2026, 6, 30), 20, Duration.ofSeconds(90), 400_000,
            4, Duration.ofSeconds(60), 6);

    private PortfolioQueries queries;
    private PortfolioTools tools;

    @BeforeEach
    void setUp() {
        queries = mock(PortfolioQueries.class);
        tools = new PortfolioTools(queries, PROPERTIES, new CallMetrics());
    }

    @Test
    @DisplayName("an unknown loan status is rejected, not passed through to match nothing")
    void rejectsUnknownStatus() {
        ToolResult result = tools.queryLoans("OVERDUE", null, null);

        assertFalse(result.ok());
        assertEquals(0, result.rowCount());
        // The message has to be actionable: the model should be able to correct
        // its own call from it.
        assertTrue(result.error().contains("OVERDUE"));
        assertTrue(result.error().contains("ACTIVE"), "should list the valid statuses");
        assertTrue(result.error().contains("get_arrears_summary"),
                "should redirect to the tool that answers lateness");
    }

    @Test
    @DisplayName("a valid status is normalised rather than demanding exact case")
    void normalisesStatusCase() {
        when(queries.queryLoans(eq("ACTIVE"), isNull(), any())).thenReturn(List.of());

        ToolResult result = tools.queryLoans("active", null, null);

        assertTrue(result.ok());
        verify(queries).queryLoans(eq("ACTIVE"), isNull(), any());
    }

    @Test
    @DisplayName("a status written with spaces is accepted")
    void normalisesSpacesToUnderscores() {
        when(queries.queryLoans(eq("CLOSED_OBLIGATIONS_MET"), isNull(), any())).thenReturn(List.of());

        ToolResult result = tools.queryLoans("closed obligations met", null, null);

        assertTrue(result.ok());
        verify(queries).queryLoans(eq("CLOSED_OBLIGATIONS_MET"), isNull(), any());
    }

    @Test
    @DisplayName("a blank office becomes no filter rather than a filter matching nothing")
    void blankOfficeBecomesNoFilter() {
        when(queries.queryLoans(isNull(), isNull(), any())).thenReturn(List.of());

        tools.queryLoans(null, "   ", null);

        verify(queries).queryLoans(isNull(), isNull(), any());
    }

    @Test
    @DisplayName("rowCount is reported so the model does not have to count rows")
    void reportsRowCount() {
        when(queries.arrearsTotals(anyInt(), isNull())).thenReturn(List.of(
                new PortfolioQueries.ArrearsBucketRow("61-90 days", "Eastern Branch", 21, null, null),
                new PortfolioQueries.ArrearsBucketRow("90+ days", "Western Branch", 42, null, null)));

        ToolResult result = tools.getArrearsTotals(60, null);

        assertTrue(result.ok());
        assertEquals(2, result.rowCount());
        assertNull(result.error());
    }

    @Test
    @DisplayName("a negative arrears threshold is rejected")
    void rejectsNegativeDays() {
        ToolResult result = tools.getArrearsSummary(-5, null, null);

        assertFalse(result.ok());
        assertTrue(result.error().contains("negative"));
    }

    @Test
    @DisplayName("a missing arrears threshold defaults to any arrears at all")
    void defaultsMinDays() {
        when(queries.arrearsSummary(eq(1), isNull(), any())).thenReturn(List.of());

        tools.getArrearsSummary(null, null, null);

        verify(queries).arrearsSummary(eq(1), isNull(), any());
    }

    @Test
    @DisplayName("a missing account number is rejected with the expected format")
    void rejectsBlankAccountNumber() {
        ToolResult result = tools.getLoanSchedule("  ");

        assertFalse(result.ok());
        assertTrue(result.error().contains("accountNo"));
    }

    @Test
    @DisplayName("a database failure never reaches the model as SQL")
    void doesNotLeakSqlToTheModel() {
        // A thrown exception used to be handed back as its raw message, which
        // the Gemini client then tried to parse as JSON. Beyond breaking the
        // request, it put schema details into the prompt.
        when(queries.officeTotals(false)).thenThrow(
                new DataAccessResourceFailureException(
                        "bad SQL grammar [SELECT l.account_no FROM m_loan l JOIN m_client c ...]"));

        ToolResult result = tools.getOfficeTotals(false);

        assertFalse(result.ok());
        assertFalse(result.error().contains("SELECT"), "SQL must not reach the model");
        assertFalse(result.error().contains("m_loan"), "table names must not reach the model");
        assertTrue(result.error().contains("could not be retrieved"));
    }

    @Test
    @DisplayName("tools return a result rather than throwing")
    void neverThrows() {
        when(queries.clientPortfolio(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        // If this threw, Spring AI would hand the message back as a tool result
        // and the model client would fail parsing it as JSON.
        ToolResult result = tools.getClientPortfolio(null, null, null, null);

        assertFalse(result.ok());
        assertEquals(0, result.rowCount());
    }

    @Test
    @DisplayName("the business date is the pinned one, not today")
    void reportsPinnedBusinessDate() {
        // Arrears is measured against this date, and the seed is built around
        // it. If it ever became the wall clock, every arrears answer would
        // drift daily and the evals would decay with it.
        assertEquals("2026-06-30", tools.getBusinessDate());
    }
}
