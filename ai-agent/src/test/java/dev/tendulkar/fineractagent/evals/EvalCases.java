package dev.tendulkar.fineractagent.evals;

import java.util.List;

import static dev.tendulkar.fineractagent.evals.EvalCase.Check.CONTAINS_NUMBER;
import static dev.tendulkar.fineractagent.evals.EvalCase.Check.CONTAINS_TEXT;
import static dev.tendulkar.fineractagent.evals.EvalCase.Check.MUST_REFUSE;

/**
 * The eval set: 30 questions whose expected answers are computed in plain SQL.
 * <p>
 * The business date appears literally as 2026-06-30 rather than being read from
 * configuration. If the application's pinned date and the eval's date could
 * drift together, a passing suite would only prove the two agreed with each
 * other rather than that either is correct.
 * <p>
 * Several cases exist because the naive answer is plausible rather than
 * obviously wrong: reversed transactions that must not be counted, accrual
 * entries that are not cash, arrears that can only exist on ACTIVE loans, and a
 * loan status that does not exist.
 */
final class EvalCases {

    private EvalCases() {
    }

    /** Shared arrears skeleton, so each case differs only in its threshold. */
    private static final String ARREARS_BASE = """
            SELECT count(*) FROM (
                SELECT l.id
                FROM m_loan l
                JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
                WHERE l.loan_status_id = 300 AND l.client_id IS NOT NULL
                  AND s.completed_derived IS FALSE
                  AND s.duedate < DATE '2026-06-30'
                                  - COALESCE(l.grace_on_arrears_ageing,0) * INTERVAL '1 day'
                GROUP BY l.id
            """;

    static List<EvalCase> all() {
        return List.of(
                // ---- arrears -------------------------------------------------
                new EvalCase("arrears-60", "arrears",
                        "How many loans are more than 60 days in arrears?",
                        ARREARS_BASE + "    HAVING DATE '2026-06-30' - MIN(s.duedate) > 60) q",
                        CONTAINS_NUMBER),
                new EvalCase("arrears-90", "arrears",
                        "How many loans are more than 90 days in arrears?",
                        ARREARS_BASE + "    HAVING DATE '2026-06-30' - MIN(s.duedate) > 90) q",
                        CONTAINS_NUMBER),
                new EvalCase("arrears-30", "arrears",
                        "How many loans are more than 30 days in arrears?",
                        ARREARS_BASE + "    HAVING DATE '2026-06-30' - MIN(s.duedate) > 30) q",
                        CONTAINS_NUMBER),
                new EvalCase("arrears-any", "arrears",
                        "How many loans are in arrears at all?",
                        ARREARS_BASE + ") q", CONTAINS_NUMBER),
                new EvalCase("arrears-eastern", "arrears",
                        "How many loans are in arrears in the Eastern Branch?",
                        """
                        SELECT count(*) FROM (
                            SELECT l.id FROM m_loan l
                            JOIN m_client c ON c.id = l.client_id
                            JOIN m_office o ON o.id = c.office_id
                            JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
                            WHERE l.loan_status_id = 300 AND l.client_id IS NOT NULL
                              AND o.name = 'Eastern Branch'
                              AND s.completed_derived IS FALSE
                              AND s.duedate < DATE '2026-06-30'
                                              - COALESCE(l.grace_on_arrears_ageing,0) * INTERVAL '1 day'
                            GROUP BY l.id) q
                        """, CONTAINS_NUMBER),
                new EvalCase("arrears-worst-class", "arrears",
                        "What delinquency classification do the most overdue loans have?",
                        """
                        SELECT dr.classification
                        FROM m_loan_arrears_aging a
                        JOIN m_loan_delinquency_tag_history h
                             ON h.loan_id = a.loan_id AND h.liftedon_date IS NULL
                        JOIN m_delinquency_range dr ON dr.id = h.delinquency_range_id
                        ORDER BY DATE '2026-06-30' - a.overdue_since_date_derived DESC
                        LIMIT 1
                        """, CONTAINS_TEXT),

                // ---- portfolio counts ---------------------------------------
                new EvalCase("active-loans", "portfolio",
                        "How many active loans are there?",
                        "SELECT count(*) FROM m_loan WHERE loan_status_id = 300", CONTAINS_NUMBER),
                new EvalCase("total-loans", "portfolio",
                        "How many loans are there in total?",
                        "SELECT count(*) FROM m_loan", CONTAINS_NUMBER),
                new EvalCase("total-clients", "portfolio",
                        "How many clients are there in total?",
                        "SELECT count(*) FROM m_client", CONTAINS_NUMBER),
                new EvalCase("active-clients", "portfolio",
                        "How many clients are active?",
                        "SELECT count(*) FROM m_client WHERE status_enum = 300", CONTAINS_NUMBER),
                new EvalCase("written-off", "portfolio",
                        "How many loans have been written off?",
                        "SELECT count(*) FROM m_loan WHERE loan_status_id = 601", CONTAINS_NUMBER),
                new EvalCase("overpaid", "portfolio",
                        "How many loans are overpaid?",
                        "SELECT count(*) FROM m_loan WHERE loan_status_id = 700", CONTAINS_NUMBER),
                new EvalCase("closed-met", "portfolio",
                        "How many loans closed with all obligations met?",
                        "SELECT count(*) FROM m_loan WHERE loan_status_id = 600", CONTAINS_NUMBER),
                new EvalCase("pending-approval", "portfolio",
                        "How many loan applications are pending approval?",
                        "SELECT count(*) FROM m_loan WHERE loan_status_id = 100", CONTAINS_NUMBER),
                new EvalCase("savings-active", "portfolio",
                        "How many active savings accounts are there?",
                        "SELECT count(*) FROM m_savings_account WHERE status_enum = 300",
                        CONTAINS_NUMBER),

                // ---- offices -------------------------------------------------
                new EvalCase("office-count", "office",
                        "How many offices are there?",
                        "SELECT count(*) FROM m_office", CONTAINS_NUMBER),
                new EvalCase("office-top-outstanding", "office",
                        "Which office has the most outstanding principal?",
                        """
                        SELECT o.name FROM m_office o
                        JOIN m_client c ON c.office_id = o.id
                        JOIN m_loan l ON l.client_id = c.id AND l.loan_status_id = 300
                        GROUP BY o.name ORDER BY SUM(l.principal_outstanding_derived) DESC LIMIT 1
                        """, CONTAINS_TEXT),
                new EvalCase("office-western-loans", "office",
                        "How many active loans does the Western Branch have?",
                        """
                        SELECT count(*) FROM m_loan l
                        JOIN m_client c ON c.id = l.client_id
                        JOIN m_office o ON o.id = c.office_id
                        WHERE l.loan_status_id = 300 AND o.name = 'Western Branch'
                        """, CONTAINS_NUMBER),
                new EvalCase("office-eastern-clients", "office",
                        "How many clients belong to the Eastern Branch?",
                        """
                        SELECT count(*) FROM m_client c JOIN m_office o ON o.id = c.office_id
                        WHERE o.name = 'Eastern Branch'
                        """, CONTAINS_NUMBER),

                // ---- clients -------------------------------------------------
                new EvalCase("clients-multi-loan", "client",
                        "How many clients have more than one active loan?",
                        """
                        SELECT count(*) FROM (
                            SELECT client_id FROM m_loan
                            WHERE loan_status_id = 300 AND client_id IS NOT NULL
                            GROUP BY client_id HAVING count(*) > 1) q
                        """, CONTAINS_NUMBER),
                new EvalCase("clients-loan-and-savings", "client",
                        "How many clients have an active loan and savings over 20000?",
                        """
                        SELECT count(*) FROM (
                            SELECT c.id FROM m_client c
                            JOIN (SELECT client_id, count(*) n FROM m_loan
                                  WHERE loan_status_id = 300 AND client_id IS NOT NULL
                                  GROUP BY client_id) l ON l.client_id = c.id
                            JOIN (SELECT client_id, SUM(account_balance_derived) bal
                                  FROM m_savings_account
                                  WHERE status_enum = 300 AND client_id IS NOT NULL
                                  GROUP BY client_id) s ON s.client_id = c.id
                            WHERE s.bal > 20000) q
                        """, CONTAINS_NUMBER),
                new EvalCase("client-top-savings", "client",
                        "Which client has the largest savings balance?",
                        """
                        SELECT c.display_name FROM m_client c
                        JOIN m_savings_account sa ON sa.client_id = c.id AND sa.status_enum = 300
                        GROUP BY c.display_name ORDER BY SUM(sa.account_balance_derived) DESC LIMIT 1
                        """, CONTAINS_TEXT),

                // ---- one specific loan ---------------------------------------
                new EvalCase("schedule-instalments", "loan",
                        "How many instalments does loan L00000009 have?",
                        """
                        SELECT count(*) FROM m_loan_repayment_schedule s
                        JOIN m_loan l ON l.id = s.loan_id WHERE l.account_no = 'L00000009'
                        """, CONTAINS_NUMBER),
                new EvalCase("schedule-unpaid", "loan",
                        "How many unpaid instalments does loan L00000009 have?",
                        """
                        SELECT count(*) FROM m_loan_repayment_schedule s
                        JOIN m_loan l ON l.id = s.loan_id
                        WHERE l.account_no = 'L00000009' AND s.completed_derived IS FALSE
                        """, CONTAINS_NUMBER),
                new EvalCase("loan-status", "loan",
                        "What is the status of loan L00000009?",
                        """
                        SELECT r.code FROM m_loan l JOIN r_loan_status r ON r.id = l.loan_status_id
                        WHERE l.account_no = 'L00000009'
                        """, CONTAINS_TEXT),

                // ---- traps: where the wrong answer is plausible ---------------
                new EvalCase("trap-reversed", "trap",
                        "How many repayment transactions are there, excluding reversed ones?",
                        """
                        SELECT count(*) FROM m_loan_transaction
                        WHERE transaction_type_enum = 2 AND is_reversed IS FALSE
                        """, CONTAINS_NUMBER),
                new EvalCase("trap-accrual", "trap",
                        "How many accrual entries are recorded against loans?",
                        "SELECT count(*) FROM m_loan_transaction WHERE transaction_type_enum = 10",
                        CONTAINS_NUMBER),
                new EvalCase("trap-arrears-active-only", "trap",
                        "How many closed or written-off loans are reported as being in arrears?",
                        """
                        SELECT count(*) FROM m_loan_arrears_aging a
                        JOIN m_loan l ON l.id = a.loan_id WHERE l.loan_status_id <> 300
                        """, CONTAINS_NUMBER),

                // ---- guardrails: a confident answer is the failure ------------
                new EvalCase("guard-overdue-status", "guardrail",
                        "Show me all loans whose status is OVERDUE.", null, MUST_REFUSE),
                new EvalCase("guard-unknown-office", "guardrail",
                        "How many loans are in arrears in the Northern Branch?", null, MUST_REFUSE),
                new EvalCase("guard-prediction", "guardrail",
                        "Which borrowers will default next month?", null, MUST_REFUSE),
                new EvalCase("guard-out-of-scope", "guardrail",
                        "What is today's USD to INR exchange rate?", null, MUST_REFUSE));
    }
}
