package dev.tendulkar.fineractagent.agent;

import dev.tendulkar.fineractagent.config.AgentProperties;
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

/**
 * The five tools the model may call.
 * <p>
 * Descriptions are written for the model, not for a developer: they say what
 * the tool answers and, where the domain is counter-intuitive, what it does NOT
 * answer. The single most valuable line in this file is the one telling the
 * model that no loan status means "overdue" — without it, a model that has seen
 * other lending schemas will ask for a status that does not exist and read the
 * resulting empty list as "nobody is in arrears".
 */
@Component
public class PortfolioTools {

    private static final Logger log = LoggerFactory.getLogger(PortfolioTools.class);

    /** r_loan_status codes. Kept here so a bad argument fails loudly rather than returning zero rows. */
    private static final Set<String> LOAN_STATUS_CODES = Set.of(
            "INVALID", "SUBMITTED_AND_PENDING_APPROVAL", "APPROVED", "ACTIVE",
            "TRANSFER_IN_PROGRESS", "TRANSFER_ON_HOLD", "WITHDRAWN_BY_CLIENT", "REJECTED",
            "CLOSED_OBLIGATIONS_MET", "CLOSED_WRITTEN_OFF",
            "CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT", "OVERPAID");

    private final PortfolioQueries queries;
    private final AgentProperties properties;

    PortfolioTools(PortfolioQueries queries, AgentProperties properties) {
        this.queries = queries;
        this.properties = properties;
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
    public List<PortfolioQueries.LoanRow> queryLoans(
            @ToolParam(description = "Loan status code, or omit for all statuses", required = false) String status,
            @ToolParam(description = "Office name or part of it, e.g. 'Eastern'", required = false) String office,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        String normalised = normaliseStatus(status);
        log.info("tool query_loans status={} office={} limit={}", normalised, office, limit);
        return queries.queryLoans(normalised, blankToNull(office), limit);
    }

    @Tool(name = "get_loan_schedule", description = """
            The full repayment schedule for one loan, by account number: every
            instalment with its due date, amounts due, amounts paid, and whether it
            is settled. Use this to explain why a specific loan is behind.
            """)
    public List<PortfolioQueries.InstalmentRow> getLoanSchedule(
            @ToolParam(description = "Loan account number, e.g. L00000042") String accountNo) {
        log.info("tool get_loan_schedule accountNo={}", accountNo);
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("accountNo is required");
        }
        return queries.loanSchedule(accountNo.trim());
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
    public List<PortfolioQueries.ArrearsRow> getArrearsSummary(
            @ToolParam(description = "Minimum days in arrears, e.g. 60. Use 1 for any arrears at all") Integer minDaysInArrears,
            @ToolParam(description = "Office name or part of it", required = false) String office,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        int minDays = minDaysInArrears == null ? 1 : minDaysInArrears;
        if (minDays < 0) {
            throw new IllegalArgumentException("minDaysInArrears must not be negative");
        }
        log.info("tool get_arrears_summary minDays={} office={} limit={}", minDays, office, limit);
        return queries.arrearsSummary(minDays, blankToNull(office), limit);
    }

    @Tool(name = "get_office_totals", description = """
            Active loan count and outstanding balances per office.
            includeSubOffices=false reports each office on its own. true rolls each
            office up with every office beneath it in the hierarchy, so a regional
            office includes its branches. These give different numbers and both are
            valid; choose deliberately and say which was used.
            """)
    public List<PortfolioQueries.OfficeTotalRow> getOfficeTotals(
            @ToolParam(description = "Roll sub-offices into their parent", required = false) Boolean includeSubOffices) {
        boolean rollUp = Boolean.TRUE.equals(includeSubOffices);
        log.info("tool get_office_totals includeSubOffices={}", rollUp);
        return queries.officeTotals(rollUp);
    }

    @Tool(name = "get_client_portfolio", description = """
            Clients with their active loan count, outstanding loan balance, savings
            account count and total savings balance. Filter by name, by minimum
            number of active loans, or by minimum savings balance.
            Use minActiveLoans=2 for clients holding more than one active loan.
            """)
    public List<PortfolioQueries.ClientPortfolioRow> getClientPortfolio(
            @ToolParam(description = "Client name or part of it", required = false) String name,
            @ToolParam(description = "Only clients with at least this many active loans", required = false) Integer minActiveLoans,
            @ToolParam(description = "Only clients with at least this total savings balance", required = false) BigDecimal minSavingsBalance,
            @ToolParam(description = "Maximum rows to return", required = false) Integer limit) {

        log.info("tool get_client_portfolio name={} minLoans={} minBalance={}",
                name, minActiveLoans, minSavingsBalance);
        return queries.clientPortfolio(blankToNull(name), minActiveLoans, minSavingsBalance, limit);
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
     * Rejects an unrecognised status rather than passing it through.
     * <p>
     * A bogus status would match no rows and return an empty list, which the
     * model reports as "there are none" — a confidently wrong answer. Failing
     * here turns that into an error the model can see and correct, which is the
     * whole point of validating arguments at the tool boundary.
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
