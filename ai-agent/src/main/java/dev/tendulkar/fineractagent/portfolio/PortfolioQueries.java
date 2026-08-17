package dev.tendulkar.fineractagent.portfolio;

import dev.tendulkar.fineractagent.config.AgentProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Every SQL statement the agent can cause to run.
 * <p>
 * The model never writes SQL. It chooses a tool and supplies arguments, which
 * are bound as parameters. Prompt injection can therefore reach a legitimate
 * query with odd arguments, never arbitrary SQL.
 * <p>
 * The filters that Fineract's model makes mandatory live here rather than in
 * the prompt (docs/DOMAIN.md decision 14):
 * <ul>
 *   <li>{@code is_reversed IS FALSE} — a reversed transaction stays in the
 *       table; including it counts money that never moved (section 9.1).</li>
 *   <li>{@code loan_status_id = 300} for arrears — there is no OVERDUE status,
 *       and only ACTIVE loans can be in arrears (section 4.3).</li>
 *   <li>{@code completed_derived IS FALSE} — without it, cleared installments
 *       drag MIN(duedate) back to origination and age every loan wrongly
 *       (section 6.1).</li>
 *   <li>{@code grace_on_arrears_ageing} — per loan, applied before a loan is
 *       considered late at all (section 6.1).</li>
 *   <li>{@code client_id IS NOT NULL} — stated explicitly rather than implied
 *       by an inner join, so the exclusion of group loans is visible in the
 *       SQL (section 9.4).</li>
 * </ul>
 * An instruction the model can silently forget is not a control.
 */
@Repository
public class PortfolioQueries {

    private final JdbcClient jdbc;
    private final AgentProperties properties;

    PortfolioQueries(JdbcClient jdbc, AgentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record LoanRow(String accountNo, String client, String office, String status,
                          String product, BigDecimal principal, BigDecimal principalOutstanding,
                          BigDecimal totalOutstanding) {
    }

    public record InstalmentRow(int installment, LocalDate dueDate, BigDecimal principalDue,
                                BigDecimal interestDue, BigDecimal principalPaid,
                                BigDecimal interestPaid, boolean settled) {
    }

    public record ArrearsRow(String accountNo, String client, String office, String product,
                             LocalDate overdueSince, int daysInArrears, String classification,
                             BigDecimal principalOverdue, BigDecimal totalOverdue) {
    }

    public record OfficeTotalRow(String office, String hierarchy, long activeLoans,
                                 BigDecimal outstandingPrincipal, BigDecimal outstandingTotal) {
    }

    public record ClientPortfolioRow(long clientId, String client, String office, String status,
                                     long activeLoans, BigDecimal loanOutstanding,
                                     long savingsAccounts, BigDecimal savingsBalance) {
    }

    /** Loans filtered by status and/or office. Status is an r_loan_status code, not free text. */
    public List<LoanRow> queryLoans(String statusCode, String officeName, Integer limit) {
        return jdbc.sql("""
                SELECT l.account_no, c.display_name, o.name, rs.code, p.name,
                       l.principal_amount, l.principal_outstanding_derived,
                       l.total_outstanding_derived
                FROM m_loan l
                JOIN m_client c        ON c.id = l.client_id
                JOIN m_office o        ON o.id = c.office_id
                JOIN r_loan_status rs  ON rs.id = l.loan_status_id
                JOIN m_product_loan p  ON p.id = l.product_id
                WHERE l.client_id IS NOT NULL
                  AND (:status IS NULL OR rs.code = :status)
                  AND (:office IS NULL OR o.name ILIKE '%' || :office || '%')
                ORDER BY l.total_outstanding_derived DESC
                LIMIT :limit
                """)
                .param("status", statusCode)
                .param("office", officeName)
                .param("limit", properties.cap(limit))
                .query((rs, n) -> new LoanRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
                        rs.getBigDecimal(7), rs.getBigDecimal(8)))
                .list();
    }

    /** Full repayment schedule for one loan, by account number. */
    public List<InstalmentRow> loanSchedule(String accountNo) {
        return jdbc.sql("""
                SELECT s.installment, s.duedate,
                       COALESCE(s.principal_amount, 0), COALESCE(s.interest_amount, 0),
                       COALESCE(s.principal_completed_derived, 0),
                       COALESCE(s.interest_completed_derived, 0),
                       s.completed_derived
                FROM m_loan_repayment_schedule s
                JOIN m_loan l ON l.id = s.loan_id
                WHERE l.account_no = :accountNo
                ORDER BY s.installment
                """)
                .param("accountNo", accountNo)
                .query((rs, n) -> new InstalmentRow(rs.getInt(1),
                        rs.getObject(2, LocalDate.class), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7)))
                .list();
    }

    /**
     * Arrears computed from the repayment schedule, which is Fineract's own
     * definition (LoanArrearsAgeingUpdateHandler.java:112-148) rather than a
     * read of m_loan_arrears_aging. The batch table is only populated because
     * our seed fills it; in a database where Fineract's job never ran it is
     * empty, and an empty read looks exactly like "no arrears" (section 9.3).
     * <p>
     * The day threshold is a HAVING on MIN(duedate), not a WHERE on duedate.
     * Filtering in WHERE would drop recent installments from the sums while
     * still reporting the loan — a different and wrong question (section 8.1).
     */
    public List<ArrearsRow> arrearsSummary(int minDaysInArrears, String officeName, Integer limit) {
        return jdbc.sql("""
                SELECT l.account_no, c.display_name, o.name, p.name,
                       MIN(s.duedate) AS overdue_since,
                       (:businessDate::date - MIN(s.duedate)) AS days_in_arrears,
                       COALESCE(MAX(dr.classification), 'unclassified'),
                       SUM(COALESCE(s.principal_amount, 0)
                           - COALESCE(s.principal_completed_derived, 0)
                           - COALESCE(s.principal_writtenoff_derived, 0)),
                       SUM(COALESCE(s.principal_amount, 0)
                           - COALESCE(s.principal_completed_derived, 0)
                           - COALESCE(s.principal_writtenoff_derived, 0)
                           + COALESCE(s.interest_amount, 0)
                           - COALESCE(s.interest_completed_derived, 0)
                           - COALESCE(s.interest_writtenoff_derived, 0)
                           - COALESCE(s.interest_waived_derived, 0))
                FROM m_loan l
                JOIN m_client c       ON c.id = l.client_id
                JOIN m_office o       ON o.id = c.office_id
                JOIN m_product_loan p ON p.id = l.product_id
                JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
                LEFT JOIN m_loan_delinquency_tag_history h
                       ON h.loan_id = l.id AND h.liftedon_date IS NULL
                LEFT JOIN m_delinquency_range dr ON dr.id = h.delinquency_range_id
                WHERE l.loan_status_id = 300
                  AND l.client_id IS NOT NULL
                  AND s.completed_derived IS FALSE
                  AND s.duedate < (:businessDate::date
                                   - COALESCE(l.grace_on_arrears_ageing, 0) * INTERVAL '1 day')
                  AND (:office IS NULL OR o.name ILIKE '%' || :office || '%')
                GROUP BY l.account_no, c.display_name, o.name, p.name
                HAVING (:businessDate::date - MIN(s.duedate)) >= :minDays
                ORDER BY days_in_arrears DESC
                LIMIT :limit
                """)
                .param("businessDate", properties.businessDate())
                .param("office", officeName)
                .param("minDays", minDaysInArrears)
                .param("limit", properties.cap(limit))
                .query((rs, n) -> new ArrearsRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, LocalDate.class), rs.getInt(6),
                        rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9)))
                .list();
    }

    /**
     * Outstanding by office. m_loan has no office_id, so the office is reached
     * through the client (section 2.3).
     * <p>
     * includeSubOffices switches between "this branch" and "this branch and
     * everything under it" using the materialised path in m_office.hierarchy.
     * Both are correct answers to different questions, so the caller states
     * which one it wants rather than the tool guessing (section 8.2).
     */
    public List<OfficeTotalRow> officeTotals(boolean includeSubOffices) {
        String sql = includeSubOffices ? """
                SELECT root.name, root.hierarchy, COUNT(DISTINCT l.id),
                       COALESCE(SUM(l.principal_outstanding_derived), 0),
                       COALESCE(SUM(l.total_outstanding_derived), 0)
                FROM m_office root
                JOIN m_office o ON o.hierarchy LIKE root.hierarchy || '%'
                JOIN m_client c ON c.office_id = o.id
                JOIN m_loan   l ON l.client_id = c.id AND l.loan_status_id = 300
                GROUP BY root.name, root.hierarchy
                ORDER BY root.hierarchy
                """ : """
                SELECT o.name, o.hierarchy, COUNT(DISTINCT l.id),
                       COALESCE(SUM(l.principal_outstanding_derived), 0),
                       COALESCE(SUM(l.total_outstanding_derived), 0)
                FROM m_office o
                JOIN m_client c ON c.office_id = o.id
                JOIN m_loan   l ON l.client_id = c.id AND l.loan_status_id = 300
                GROUP BY o.name, o.hierarchy
                ORDER BY o.hierarchy
                """;
        return jdbc.sql(sql)
                .query((rs, n) -> new OfficeTotalRow(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getBigDecimal(4), rs.getBigDecimal(5)))
                .list();
    }

    /**
     * Clients with their loan and savings position.
     * <p>
     * Savings are aggregated in a subquery before joining. Joining loans and
     * savings directly multiplies rows — a client with 2 loans and 2 savings
     * accounts yields 4 — and SUM over that double-counts the balance. It is a
     * mistake that produces a plausible number rather than an error, which is
     * why it is structural here rather than left to the model (section 8.4).
     */
    public List<ClientPortfolioRow> clientPortfolio(String nameFilter, Integer minActiveLoans,
                                                    BigDecimal minSavingsBalance, Integer limit) {
        return jdbc.sql("""
                SELECT c.id, c.display_name, o.name, rcs.code,
                       COALESCE(loans.n, 0), COALESCE(loans.outstanding, 0),
                       COALESCE(sav.n, 0), COALESCE(sav.balance, 0)
                FROM m_client c
                JOIN m_office o          ON o.id = c.office_id
                JOIN r_client_status rcs ON rcs.id = c.status_enum
                LEFT JOIN (
                    SELECT client_id, COUNT(*) AS n,
                           SUM(principal_outstanding_derived) AS outstanding
                    FROM m_loan
                    WHERE loan_status_id = 300 AND client_id IS NOT NULL
                    GROUP BY client_id
                ) loans ON loans.client_id = c.id
                LEFT JOIN (
                    SELECT client_id, COUNT(*) AS n,
                           SUM(account_balance_derived) AS balance
                    FROM m_savings_account
                    WHERE status_enum = 300 AND client_id IS NOT NULL
                    GROUP BY client_id
                ) sav ON sav.client_id = c.id
                WHERE (:name IS NULL OR c.display_name ILIKE '%' || :name || '%')
                  AND COALESCE(loans.n, 0) >= :minLoans
                  AND COALESCE(sav.balance, 0) >= :minBalance
                ORDER BY COALESCE(loans.outstanding, 0) DESC
                LIMIT :limit
                """)
                .param("name", nameFilter)
                .param("minLoans", minActiveLoans == null ? 0 : minActiveLoans)
                .param("minBalance", minSavingsBalance == null ? BigDecimal.ZERO : minSavingsBalance)
                .param("limit", properties.cap(limit))
                .query((rs, n) -> new ClientPortfolioRow(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5), rs.getBigDecimal(6),
                        rs.getLong(7), rs.getBigDecimal(8)))
                .list();
    }
}
