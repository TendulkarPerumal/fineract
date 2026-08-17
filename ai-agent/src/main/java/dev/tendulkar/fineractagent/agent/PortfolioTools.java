package dev.tendulkar.fineractagent.agent;

import dev.tendulkar.fineractagent.config.AgentProperties;
import dev.tendulkar.fineractagent.observability.CallMetrics;
import dev.tendulkar.fineractagent.portfolio.PortfolioQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The tools the model may call.
 * <p>
 * Descriptions are written for the model, not for a developer: they say what
 * each tool answers and, where the domain is counter-intuitive, what it does
 * NOT answer. The most valuable line here is the one saying no loan status
 * means "overdue" — without it a model that has seen other lending schemas asks
 * for a status that does not exist and reads the empty result as "nobody is in
 * arrears".
 * <p>
 * Every tool returns a {@link ToolResult} rather than a bare list, and never
 * throws. A thrown exception is handed back to the model as its raw message,
 * and the Gemini client then tries to parse that message as JSON — so a SQL
 * error surfaces as a Jackson parse failure, destroying the request and hiding
 * the real cause. A structured result keeps failures inside the protocol, where
 * the model can read them and correct its own call.
 */
@Component
public class PortfolioTools {

    private static final Logger log = LoggerFactory.getLogger(PortfolioTools.class);

    /** r_loan_status codes, so a bad argument fails loudly rather than matching zero rows. */
    private static final Set<String> LOAN_STATUS_CODES = Set.of(
            "INVALID", "SUBMITTED_AND_PENDING_APPROVAL", "APPROVED", "ACTIVE",
            "TRANSFER_IN_PROGRESS", "TRANSFER_ON_HOLD", "WITHDRAWN_BY_CLIENT", "REJECTED",
            "CLOSED_OBLIGATIONS_MET", "CLOSED_WRITTEN_OFF",
            "CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT", "OVERPAID");

    private final PortfolioQueries queries;
    private final AgentProperties properties;
    private final CallMetrics metrics;

    PortfolioTools(PortfolioQueries queries, AgentProperties properties, CallMetrics metrics) {
        this.queries = queries;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * @param ok       false means the call failed; rows is then empty.
     * @param error    what went wrong and, where possible, how to fix the call.
     * @param rowCount number of rows returned, so the model does not have to count.
     */
    public record ToolResult(boolean ok, String error, int rowCount, List<?> rows) {

        static ToolResult of(List<?> rows) {
            return new ToolResult(true, null, rows.size(), rows);
        }

        static ToolResult failed(String message) {
            return new ToolResult(false, message, 0, List.of());
        }
    }

    @Tool(name = "query_loans", description = """
            List loan accounts, optionally filtered by status and office.
            status must be one of: SUBMITTED_AND_PENDING_APPROVAL, APPROVED, ACTIVE,
            TRANSFER_IN_PROGRESS, TRANSFER_ON_HOLD, WITHDRAWN_BY_CLIENT, REJECTED,
            CLOSED_OBLIGATIONS_MET, CLOSED_WRITTEN_OFF,
            CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT, OVERPAID.
            There is NO status meaning overdue, in arrears or delinquent. A loan that
            is months late still has status ACTIVE. For anything about lateness use
            get_arrears_summary instead.
            """)
    public ToolResult queryLoans(
            @ToolParam(description = "Loan status code, or omit for all statuses", required = false) String status,
            @ToolParam(description = "Office name or part of it, e.g. 'Eastern'", required = false) String office,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        log.info("tool query_loans status={} office={} limit={}", status, office, limit);
        return guard("query_loans", () -> {
            String normalised = normaliseStatus(status);
            return queries.queryLoans(normalised, blankToNull(office), limit);
        });
    }

    @Tool(name = "get_loan_schedule", description = """
            The full repayment schedule for one loan, by account number: every
            instalment with its due date, amounts due, amounts paid, and whether it
            is settled. Use this to explain why a specific loan is behind.
            """)
    public ToolResult getLoanSchedule(
            @ToolParam(description = "Loan account number, e.g. L00000042") String accountNo) {
        log.info("tool get_loan_schedule accountNo={}", accountNo);
        return guard("get_loan_schedule", () -> {
            if (accountNo == null || accountNo.isBlank()) {
                throw new IllegalArgumentException("accountNo is required, e.g. L00000042");
            }
            return queries.loanSchedule(accountNo.trim());
        });
    }

    @Tool(name = "get_arrears_summary", description = """
            Loans that are behind on payments, with days in arrears, the amount
            overdue and the delinquency classification.
            Days in arrears is measured from the OLDEST UNPAID instalment due date to
            the business date, honouring each loan's grace period. It is not days
            since the last payment: a borrower who pays a little every month but
            never clears instalment 3 has a growing arrears age and a recent payment.
            Only ACTIVE loans can be in arrears.
            """)
    public ToolResult getArrearsSummary(
            @ToolParam(description = "Minimum days in arrears, e.g. 60. Use 1 for any arrears at all") Integer minDaysInArrears,
            @ToolParam(description = "Office name or part of it", required = false) String office,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        log.info("tool get_arrears_summary minDays={} office={} limit={}", minDaysInArrears, office, limit);
        return guard("get_arrears_summary", () -> {
            int minDays = minDaysInArrears == null ? 1 : minDaysInArrears;
            if (minDays < 0) {
                throw new IllegalArgumentException("minDaysInArrears must not be negative");
            }
            return queries.arrearsSummary(minDays, blankToNull(office), limit);
        });
    }

    @Tool(name = "get_arrears_totals", description = """
            HOW MANY / TOTALS for loans in arrears, counted in the database and
            grouped by delinquency classification and office.
            Use this whenever the question asks how many loans are in arrears, or
            for overdue totals. Do NOT use get_arrears_summary and count its rows:
            this returns a handful of grouped rows instead of one row per loan.
            Only use get_arrears_summary when specific loans must be named.
            """)
    public ToolResult getArrearsTotals(
            @ToolParam(description = "Minimum days in arrears, e.g. 60. Use 1 for any arrears at all") Integer minDaysInArrears,
            @ToolParam(description = "Office name or part of it", required = false) String office) {

        log.info("tool get_arrears_totals minDays={} office={}", minDaysInArrears, office);
        return guard("get_arrears_totals", () -> {
            int minDays = minDaysInArrears == null ? 1 : minDaysInArrears;
            if (minDays < 0) {
                throw new IllegalArgumentException("minDaysInArrears must not be negative");
            }
            return queries.arrearsTotals(minDays, blankToNull(office));
        });
    }

    @Tool(name = "get_office_totals", description = """
            Active loan count and outstanding balances per office.
            includeSubOffices=false reports each office on its own. true rolls each
            office up with every office beneath it in the hierarchy, so a regional
            office includes its branches. These give different numbers and both are
            valid; choose deliberately and say which was used.
            """)
    public ToolResult getOfficeTotals(
            @ToolParam(description = "Roll sub-offices into their parent", required = false) Boolean includeSubOffices) {
        boolean rollUp = Boolean.TRUE.equals(includeSubOffices);
        log.info("tool get_office_totals includeSubOffices={}", rollUp);
        return guard("get_office_totals", () -> queries.officeTotals(rollUp));
    }

    @Tool(name = "get_client_portfolio", description = """
            Clients with their active loan count, outstanding loan balance, savings
            account count and total savings balance. Filter by name, by minimum
            number of active loans, or by minimum savings balance.
            Use minActiveLoans=2 for clients holding more than one active loan.
            """)
    public ToolResult getClientPortfolio(
            @ToolParam(description = "Client name or part of it", required = false) String name,
            @ToolParam(description = "Only clients with at least this many active loans", required = false) Integer minActiveLoans,
            @ToolParam(description = "Only clients with at least this total savings balance", required = false) BigDecimal minSavingsBalance,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        log.info("tool get_client_portfolio name={} minLoans={} minBalance={}",
                name, minActiveLoans, minSavingsBalance);
        return guard("get_client_portfolio",
                () -> queries.clientPortfolio(blankToNull(name), minActiveLoans, minSavingsBalance, limit));
    }

    @Tool(name = "get_business_date", description = """
            The business date all figures are measured against. This is a fixed
            reporting date, not today's real date. Quote it when reporting anything
            time-sensitive such as arrears.
            """)
    public String getBusinessDate() {
        return properties.businessDate().toString();
    }

    /**
     * Runs a query and converts any failure into a result the model can read.
     * <p>
     * Argument mistakes are returned verbatim, because they usually name the fix
     * and the model can retry. Anything else is logged with its stack trace and
     * reported to the model generically: a database error message is an internal
     * detail, and feeding SQL text back into a prompt is how a schema leaks into
     * a response.
     */
    private ToolResult guard(String tool, Supplier<List<?>> call) {
        long startedAt = System.nanoTime();
        try {
            List<?> rows = call.get();
            metrics.recordToolCall(tool, millisSince(startedAt), rows.size());
            return ToolResult.of(rows);
        } catch (IllegalArgumentException e) {
            metrics.recordToolCall(tool, millisSince(startedAt), 0);
            log.warn("tool {} rejected arguments: {}", tool, e.getMessage());
            return ToolResult.failed(e.getMessage());
        } catch (RuntimeException e) {
            metrics.recordToolCall(tool, millisSince(startedAt), 0);
            log.error("tool {} failed", tool, e);
            return ToolResult.failed("The query failed to execute. Do not retry it with "
                    + "the same arguments; report that this data could not be retrieved.");
        }
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Rejects an unrecognised status rather than passing it through.
     * <p>
     * A bogus status matches no rows and returns an empty list, which the model
     * reports as "there are none" — confidently wrong. Failing turns that into
     * something the model can see and correct.
     */
    private String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String candidate = status.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!LOAN_STATUS_CODES.contains(candidate)) {
            throw new IllegalArgumentException(
                    "Unknown loan status '" + status + "'. Valid statuses are " + LOAN_STATUS_CODES
                            + ". Note there is no overdue/in-arrears/delinquent status: "
                            + "use get_arrears_summary for loans that are behind.");
        }
        return candidate;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
