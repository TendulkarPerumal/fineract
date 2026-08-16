-- Seed consistency checks.
--
-- Fineract's _derived rollups are maintained by its Java write path, which we
-- are not running, so nothing recomputes or repairs them (docs/DOMAIN.md
-- section 5). If the seed is internally inconsistent, the Stage 5 eval suite
-- measures nothing: expected answers computed in plain SQL would agree with a
-- broken agent because both read the same broken numbers.
--
-- Every check below must return 0. Run:
--   psql "$AGENT_DB_URL" -f scripts/stage1/verify-seed.sql
--
-- The business date is pinned at 2026-06-30 and appears literally, as it does
-- in the seed. Nothing here uses CURRENT_DATE.

\pset footer off

SELECT 'principal_repaid = sum of non-reversed cash principal portions' AS check, count(*) AS violations
FROM m_loan l
LEFT JOIN (
    SELECT t.loan_id, SUM(COALESCE(t.principal_portion_derived,0)) AS s
    FROM m_loan_transaction t
    JOIN r_loan_transaction_type rt ON rt.id = t.transaction_type_enum
    WHERE t.is_reversed IS FALSE AND rt.is_cash_movement AND t.transaction_type_enum <> 1
    GROUP BY t.loan_id
) x ON x.loan_id = l.id
WHERE l.principal_repaid_derived <> COALESCE(x.s, 0)

UNION ALL
SELECT 'principal_outstanding = disbursed - repaid - writtenoff', count(*)
FROM m_loan
WHERE principal_outstanding_derived <>
      principal_disbursed_derived - principal_repaid_derived - principal_writtenoff_derived

UNION ALL
SELECT 'total_outstanding = principal + interest outstanding', count(*)
FROM m_loan
WHERE total_outstanding_derived <> principal_outstanding_derived + interest_outstanding_derived

UNION ALL
SELECT 'schedule completed_derived matches zero outstanding', count(*)
FROM m_loan_repayment_schedule
WHERE completed_derived <> (
    COALESCE(principal_amount,0) - COALESCE(principal_completed_derived,0)
        - COALESCE(principal_writtenoff_derived,0)
  + COALESCE(interest_amount,0)  - COALESCE(interest_completed_derived,0)
        - COALESCE(interest_writtenoff_derived,0) - COALESCE(interest_waived_derived,0)
    = 0)

UNION ALL
SELECT 'schedule completed amounts = mapped transaction portions', count(*)
FROM m_loan_repayment_schedule s
JOIN m_loan_transaction_repayment_schedule_mapping m ON m.loan_repayment_schedule_id = s.id
WHERE COALESCE(s.principal_completed_derived,0) <> COALESCE(m.principal_portion_derived,0)
   OR COALESCE(s.interest_completed_derived,0)  <> COALESCE(m.interest_portion_derived,0)

UNION ALL
SELECT 'schedule principal sums to loan principal', count(*)
FROM (
    SELECT s.loan_id, SUM(s.principal_amount) AS sched, MAX(l.principal_amount) AS loan
    FROM m_loan_repayment_schedule s JOIN m_loan l ON l.id = s.loan_id
    GROUP BY s.loan_id
) q WHERE sched <> loan

UNION ALL
SELECT 'no mapping points at a reversed transaction', count(*)
FROM m_loan_transaction_repayment_schedule_mapping m
JOIN m_loan_transaction t ON t.id = m.loan_transaction_id
WHERE t.is_reversed IS TRUE

UNION ALL
-- The batch table must agree with arrears computed straight from the schedule.
-- These are the two independent paths the agent can take; if they disagree the
-- eval suite cannot use one to check the other (docs/DOMAIN.md decision 7).
SELECT 'm_loan_arrears_aging agrees with schedule-computed arrears', count(*)
FROM (
    SELECT l.id,
           MIN(s.duedate) AS computed_since,
           SUM(COALESCE(s.principal_amount,0) - COALESCE(s.principal_completed_derived,0)
               - COALESCE(s.principal_writtenoff_derived,0)) AS computed_principal
    FROM m_loan l
    JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
    WHERE l.loan_status_id = 300
      AND s.completed_derived IS FALSE
      AND s.duedate < (DATE '2026-06-30' - COALESCE(l.grace_on_arrears_ageing,0) * INTERVAL '1 day')
    GROUP BY l.id
) c
FULL OUTER JOIN m_loan_arrears_aging a ON a.loan_id = c.id
WHERE a.loan_id IS NULL OR c.id IS NULL
   OR a.overdue_since_date_derived <> c.computed_since
   OR a.principal_overdue_derived  <> c.computed_principal

UNION ALL
SELECT 'every arrears loan has exactly one current delinquency tag', count(*)
FROM m_loan_arrears_aging a
LEFT JOIN m_loan_delinquency_tag_history h
       ON h.loan_id = a.loan_id AND h.liftedon_date IS NULL
GROUP BY a.loan_id
HAVING count(h.id) <> 1

UNION ALL
SELECT 'delinquency tag range matches the arrears age', count(*)
FROM m_loan_arrears_aging a
JOIN m_loan_delinquency_tag_history h ON h.loan_id = a.loan_id AND h.liftedon_date IS NULL
JOIN m_delinquency_range r ON r.id = h.delinquency_range_id
WHERE (DATE '2026-06-30' - a.overdue_since_date_derived) < r.min_age_days
   OR (r.max_age_days IS NOT NULL
       AND (DATE '2026-06-30' - a.overdue_since_date_derived) > r.max_age_days)

UNION ALL
SELECT 'savings balance = credits - debits over non-reversed transactions', count(*)
FROM m_savings_account sa
LEFT JOIN (
    SELECT t.savings_account_id,
           SUM(CASE WHEN rt.entry_type='CREDIT' THEN t.amount ELSE -t.amount END) AS bal
    FROM m_savings_account_transaction t
    JOIN r_savings_transaction_type rt ON rt.id = t.transaction_type_enum
    WHERE t.is_reversed IS FALSE
    GROUP BY t.savings_account_id
) x ON x.savings_account_id = sa.id
WHERE sa.account_balance_derived <> COALESCE(x.bal, 0)

UNION ALL
SELECT 'savings running balance ends at account balance', count(*)
FROM (
    SELECT DISTINCT ON (savings_account_id) savings_account_id, running_balance_derived
    FROM m_savings_account_transaction
    WHERE is_reversed IS FALSE
    ORDER BY savings_account_id, id DESC
) last
JOIN m_savings_account sa ON sa.id = last.savings_account_id
WHERE sa.account_balance_derived <> last.running_balance_derived

UNION ALL
-- No loan may be in arrears unless it is ACTIVE. There is no OVERDUE status;
-- arrears is computed, and it only exists for status 300
-- (docs/DOMAIN.md section 4.3).
SELECT 'arrears only on ACTIVE loans', count(*)
FROM m_loan_arrears_aging a JOIN m_loan l ON l.id = a.loan_id
WHERE l.loan_status_id <> 300

UNION ALL
SELECT 'every _enum value has a matching r_* row (loan status)', count(*)
FROM m_loan l LEFT JOIN r_loan_status r ON r.id = l.loan_status_id
WHERE r.id IS NULL

UNION ALL
SELECT 'every _enum value has a matching r_* row (loan txn type)', count(*)
FROM m_loan_transaction t LEFT JOIN r_loan_transaction_type r ON r.id = t.transaction_type_enum
WHERE r.id IS NULL

UNION ALL
SELECT 'every _enum value has a matching r_* row (savings txn type)', count(*)
FROM m_savings_account_transaction t
LEFT JOIN r_savings_transaction_type r ON r.id = t.transaction_type_enum
WHERE r.id IS NULL

UNION ALL
SELECT 'every _enum value has a matching r_* row (client status)', count(*)
FROM m_client c LEFT JOIN r_client_status r ON r.id = c.status_enum
WHERE r.id IS NULL;
