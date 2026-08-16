-- Seed, part 2 of 2: clients, loans, schedules, transactions and savings.
--
-- PINNED BUSINESS DATE: 2026-06-30. Nothing here uses CURRENT_DATE or now().
--
-- THE RULE THIS FILE OBEYS (docs/DOMAIN.md section 5.2):
-- Fineract's ~40 _derived rollup columns are maintained by its Java write path,
-- with no triggers. We are not running Fineract, so nothing will ever recompute
-- or repair them. Therefore every rollup here is COMPUTED FROM the transactions
-- and mappings this file writes, never authored alongside them. Two
-- independently written numbers would disagree, and an eval suite computing
-- expected answers in plain SQL against an inconsistent seed measures nothing.
--
-- Determinism: all variation comes from arithmetic on the row number. No
-- random(), no setseed(), so the same data appears on every run regardless of
-- session or Postgres version.
--
-- Scope: individual loans only (loan_type_enum = 1). m_loan.client_id is
-- nullable because group loans hang off group_id instead, so a client join
-- silently drops them (docs/DOMAIN.md section 9.4). Restricting scope is fine;
-- inheriting the restriction silently is not, so it is stated here and the
-- group tables are left empty.

-- ===========================================================================
-- 1. Clients
-- ===========================================================================
INSERT INTO m_client (
    id, account_no, status_enum, office_id, staff_id, display_name, firstname,
    lastname, mobile_no, gender_cv_id, client_type_cv_id, date_of_birth,
    submittedon_date, activation_date, legal_form_enum, is_staff,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc
)
SELECT
    i,
    lpad(i::text, 9, '0'),
    CASE WHEN i > 195 THEN 600 WHEN i > 190 THEN 100 ELSE 300 END,
    off_id,
    CASE WHEN off_id = 2 THEN 1 + (i % 2) ELSE 3 + (i % 2) END,
    fn || ' ' || ln,
    fn,
    ln,
    '9' || lpad(((i * 7919) % 1000000000)::text, 9, '0'),
    1 + (i % 2),                                    -- Female / Male
    3,                                              -- Individual
    (DATE '1995-01-01' + ((i * 37) % 8000) * INTERVAL '1 day')::date,
    (DATE '2019-01-01' + ((i * 11) % 1800) * INTERVAL '1 day')::date,
    CASE WHEN i > 190 THEN NULL
         ELSE (DATE '2019-01-15' + ((i * 11) % 1800) * INTERVAL '1 day')::date END,
    1,                                              -- PERSON
    false,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM (
    SELECT i,
           CASE WHEN i % 10 < 6 THEN 2 ELSE 3 END AS off_id,
           (ARRAY['Aarti','Bhavna','Chetan','Deepa','Farhan','Gita','Harish',
                  'Ishaan','Jaya','Kiran','Lalita','Manoj','Nisha','Omkar',
                  'Pooja','Rajesh','Sarita','Tarun','Usha','Vijay'])[1 + (i % 20)] AS fn,
           (ARRAY['Agarwal','Bhat','Chauhan','Desai','Fernandes','Gupta','Hegde',
                  'Iyer','Joshi','Kulkarni'])[1 + ((i / 20) % 10)] AS ln
    FROM generate_series(1, 200) AS i
) s;

-- ===========================================================================
-- 2. Loan parameters
--
-- Derived once into a working table so that the schedule, the transactions and
-- the rollups all read the same numbers. This is the mechanism that makes the
-- seed consistent by construction rather than by careful re-typing.
--
-- unpaid_months drives arrears. Disbursement is anchored to the 15th, so
-- installment due dates fall on the 15th and the oldest unpaid one lands a
-- predictable number of days before the 2026-06-30 business date:
--   0 -> current      1 -> 15d     2 -> 46d     3 -> 76d
--   4 -> 107d         6 -> 166d
-- which places one loan population in each delinquency range from V3.
-- ===========================================================================
CREATE TABLE _seed_loan_params AS
WITH base AS (
    SELECT
        i AS loan_id,
        CASE WHEN i <= 190 THEN i ELSE i - 190 END              AS client_id,
        1 + (i % 3)                                              AS product_id,
        CASE
            WHEN i <= 215 THEN 300   -- ACTIVE
            WHEN i <= 285 THEN 600   -- CLOSED_OBLIGATIONS_MET
            WHEN i <= 300 THEN 601   -- CLOSED_WRITTEN_OFF
            WHEN i <= 310 THEN 700   -- OVERPAID
            WHEN i <= 330 THEN 100   -- SUBMITTED_AND_PENDING_APPROVAL
            WHEN i <= 342 THEN 200   -- APPROVED
            WHEN i <= 347 THEN 500   -- REJECTED
            ELSE               400   -- WITHDRAWN_BY_CLIENT
        END                                                      AS loan_status_id
    FROM generate_series(1, 350) AS i
),
withprod AS (
    SELECT b.*,
           p.number_of_repayments::int                           AS n,
           p.grace_on_arrears_ageing::int                         AS grace,
           p.fund_id,
           -- Flat interest: total = principal * annual rate * years, split
           -- equally across installments. The products are FLAT
           -- (interest_method_enum = 1), so an equal split is the correct
           -- schedule for them rather than an approximation.
           (p.principal_amount + (b.loan_id % 5) * (p.principal_amount / 10))::numeric(19,6) AS principal,
           p.annual_nominal_interest_rate
    FROM base b
    JOIN m_product_loan p ON p.id = b.product_id
),
calc AS (
    SELECT w.*,
           (w.principal * w.annual_nominal_interest_rate / 100.0
                * (w.n::numeric / 12.0))::numeric(19,6)          AS interest_total,
           CASE
               WHEN w.loan_status_id = 300 THEN
                   CASE (w.loan_id % 10)
                       WHEN 5 THEN 1 WHEN 6 THEN 2 WHEN 7 THEN 3
                       WHEN 8 THEN 4 WHEN 9 THEN 6 ELSE 0
                   END
               ELSE 0
           END                                                    AS unpaid_months
    FROM withprod w
)
SELECT
    c.loan_id, c.client_id, c.product_id, c.loan_status_id, c.n, c.grace,
    c.fund_id, c.principal, c.interest_total,
    c.unpaid_months,
    -- months elapsed since disbursement
    CASE
        WHEN c.loan_status_id = 300 THEN GREATEST(c.n / 2, c.unpaid_months + 1)
        WHEN c.loan_status_id IN (600, 700) THEN c.n + (c.loan_id % 6)
        WHEN c.loan_status_id = 601 THEN c.n
        ELSE 0
    END                                                           AS months_elapsed,
    -- per-installment amounts; the remainder lands on the final installment so
    -- the schedule sums exactly to the loan amount
    round(c.principal / c.n, 2)                                   AS inst_principal,
    round(c.interest_total / c.n, 2)                              AS inst_interest
FROM calc c;

ALTER TABLE _seed_loan_params ADD COLUMN disbursed_on date;
ALTER TABLE _seed_loan_params ADD COLUMN paid_installments int;
ALTER TABLE _seed_loan_params ADD COLUMN has_schedule boolean;

UPDATE _seed_loan_params SET
    disbursed_on = CASE
        WHEN loan_status_id IN (300, 600, 601, 700)
        THEN (DATE '2026-06-15' - (months_elapsed * INTERVAL '1 month'))::date
        ELSE NULL END,
    has_schedule = loan_status_id IN (300, 600, 601, 700);

UPDATE _seed_loan_params SET
    paid_installments = CASE
        WHEN loan_status_id = 300 THEN GREATEST(months_elapsed - unpaid_months, 0)
        WHEN loan_status_id IN (600, 700) THEN n
        WHEN loan_status_id = 601 THEN n / 3
        ELSE 0
    END;

-- ===========================================================================
-- 3. Loans
-- ===========================================================================
INSERT INTO m_loan (
    id, account_no, external_id, client_id, product_id, fund_id, loan_officer_id,
    loanpurpose_cv_id, loan_status_id, loan_type_enum, currency_code,
    currency_digits, principal_amount_proposed, principal_amount,
    approved_principal, net_disbursal_amount, annual_nominal_interest_rate,
    interest_method_enum, interest_calculated_in_period_enum, repay_every,
    repayment_period_frequency_enum, number_of_repayments,
    amortization_method_enum, grace_on_arrears_ageing,
    submittedon_date, approvedon_date, approvedon_userid,
    expected_disbursedon_date, disbursedon_date, disbursedon_userid,
    expected_maturedon_date, maturedon_date, closedon_date,
    is_npa, created_by, created_on_utc, last_modified_by, last_modified_on_utc
)
SELECT
    p.loan_id,
    'L' || lpad(p.loan_id::text, 8, '0'),
    'EXT-L-' || lpad(p.loan_id::text, 8, '0'),
    p.client_id, p.product_id, p.fund_id,
    CASE WHEN c.office_id = 2 THEN 1 + (p.loan_id % 2) ELSE 3 + (p.loan_id % 2) END,
    5 + (p.loan_id % 4),                                   -- LoanPurpose code values 5..8
    p.loan_status_id, 1, 'INR', 2,
    p.principal, p.principal, p.principal, p.principal,
    pl.annual_nominal_interest_rate, 1, 1, 1, 2, p.n, 1, p.grace,
    COALESCE((p.disbursed_on - INTERVAL '20 days')::date, DATE '2026-05-01'),
    CASE WHEN p.loan_status_id IN (200, 300, 600, 601, 700)
         THEN COALESCE((p.disbursed_on - INTERVAL '10 days')::date, DATE '2026-05-10') END,
    CASE WHEN p.loan_status_id IN (200, 300, 600, 601, 700) THEN 1 END,
    COALESCE(p.disbursed_on, DATE '2026-05-20'),
    p.disbursed_on,
    CASE WHEN p.disbursed_on IS NOT NULL THEN 1 END,
    CASE WHEN p.disbursed_on IS NOT NULL
         THEN (p.disbursed_on + (p.n * INTERVAL '1 month'))::date END,
    CASE WHEN p.loan_status_id IN (600, 700)
         THEN (p.disbursed_on + (p.n * INTERVAL '1 month'))::date END,
    CASE WHEN p.loan_status_id IN (600, 700)
         THEN (p.disbursed_on + (p.n * INTERVAL '1 month'))::date
         WHEN p.loan_status_id = 601
         THEN (p.disbursed_on + ((p.n / 3 + 1) * INTERVAL '1 month'))::date END,
    false,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM _seed_loan_params p
JOIN m_client c        ON c.id = p.client_id
JOIN m_product_loan pl ON pl.id = p.product_id;

-- ===========================================================================
-- 4. Repayment schedules
--
-- Note the asymmetry carried from Fineract: there is no
-- principal_waived_derived column. Principal can be written off but not waived
-- (docs/DOMAIN.md section 2.3). completed_derived starts false everywhere and
-- is set in step 7 from the payment mappings.
-- ===========================================================================
INSERT INTO m_loan_repayment_schedule (
    id, loan_id, fromdate, duedate, installment,
    principal_amount, interest_amount, fee_charges_amount, penalty_charges_amount,
    completed_derived, recalculated_interest_component, is_additional,
    is_down_payment, is_re_aged,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc
)
SELECT
    (p.loan_id * 100) + j,
    p.loan_id,
    CASE WHEN j = 1 THEN p.disbursed_on
         ELSE (p.disbursed_on + ((j - 1) * INTERVAL '1 month'))::date END,
    (p.disbursed_on + (j * INTERVAL '1 month'))::date,
    j,
    CASE WHEN j < p.n THEN p.inst_principal
         ELSE p.principal - p.inst_principal * (p.n - 1) END,
    CASE WHEN j < p.n THEN p.inst_interest
         ELSE p.interest_total - p.inst_interest * (p.n - 1) END,
    0, 0,
    false, false, false, false, false,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM _seed_loan_params p
CROSS JOIN LATERAL generate_series(1, p.n) AS j
WHERE p.has_schedule;

-- ===========================================================================
-- 5. Transactions
--
-- Type ids come from r_loan_transaction_type:
--   1 DISBURSEMENT, 2 REPAYMENT, 6 WRITEOFF, 10 ACCRUAL
--
-- Ids are allocated in disjoint bands per kind so the file stays re-readable:
--   1..350          disbursements       (= loan_id)
--   1_000_000 +     repayments          (schedule id based)
--   2_000_000 +     write-offs
--   3_000_000 +     overpayments
--   4_000_000 +     accruals
--   5_000_000 +     REVERSED repayments
-- ===========================================================================

-- 5a. Disbursement. principal_portion_derived is deliberately NULL: a
-- disbursement pays nothing off, it creates the balance.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, is_reversed, transaction_type_enum, transaction_date,
    submitted_on_date, amount, outstanding_loan_balance_derived,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT p.loan_id, p.loan_id, c.office_id, false, 1, p.disbursed_on,
       p.disbursed_on, p.principal, p.principal,
       1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM _seed_loan_params p
JOIN m_client c ON c.id = p.client_id
WHERE p.has_schedule;

-- 5b. Repayments: one per paid installment, on the due date, for the exact
-- installment total.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, payment_detail_id, is_reversed, transaction_type_enum,
    transaction_date, submitted_on_date, amount,
    principal_portion_derived, interest_portion_derived,
    fee_charges_portion_derived, penalty_charges_portion_derived,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    1000000 + s.id, s.loan_id, c.office_id, NULL, false, 2,
    s.duedate, s.duedate,
    s.principal_amount + s.interest_amount,
    s.principal_amount, s.interest_amount, 0, 0,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM m_loan_repayment_schedule s
JOIN _seed_loan_params p ON p.loan_id = s.loan_id
JOIN m_client c          ON c.id = p.client_id
WHERE s.installment <= p.paid_installments;

-- 5c. Write-offs for status 601, covering everything still outstanding.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, is_reversed, transaction_type_enum, transaction_date,
    submitted_on_date, amount, principal_portion_derived, interest_portion_derived,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    2000000 + p.loan_id, p.loan_id, c.office_id, false, 6,
    (p.disbursed_on + ((p.paid_installments + 1) * INTERVAL '1 month'))::date,
    (p.disbursed_on + ((p.paid_installments + 1) * INTERVAL '1 month'))::date,
    unpaid.principal_rem + unpaid.interest_rem,
    unpaid.principal_rem, unpaid.interest_rem,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM _seed_loan_params p
JOIN m_client c ON c.id = p.client_id
JOIN LATERAL (
    SELECT COALESCE(SUM(s.principal_amount), 0) AS principal_rem,
           COALESCE(SUM(s.interest_amount), 0)  AS interest_rem
    FROM m_loan_repayment_schedule s
    WHERE s.loan_id = p.loan_id AND s.installment > p.paid_installments
) unpaid ON true
WHERE p.loan_status_id = 601;

-- 5d. Overpayment for status 700: a final repayment beyond the balance.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, is_reversed, transaction_type_enum, transaction_date,
    submitted_on_date, amount, overpayment_portion_derived,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    3000000 + p.loan_id, p.loan_id, c.office_id, false, 2,
    (p.disbursed_on + ((p.n + 1) * INTERVAL '1 month'))::date,
    (p.disbursed_on + ((p.n + 1) * INTERVAL '1 month'))::date,
    round(p.inst_principal / 4, 2), round(p.inst_principal / 4, 2),
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM _seed_loan_params p
JOIN m_client c ON c.id = p.client_id
WHERE p.loan_status_id = 700;

-- 5e. Accrual entries (type 10) on active loans. These are accounting entries,
-- not money (docs/DOMAIN.md section 9.2). They sit in the same table as real
-- repayments, which is exactly why "how much did this client repay" is not
-- SUM(amount). They are excluded from every rollup below.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, is_reversed, transaction_type_enum, transaction_date,
    submitted_on_date, amount, interest_portion_derived,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    4000000 + p.loan_id, p.loan_id, c.office_id, false, 10,
    DATE '2026-06-30', DATE '2026-06-30',
    p.inst_interest, p.inst_interest,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM _seed_loan_params p
JOIN m_client c ON c.id = p.client_id
WHERE p.loan_status_id = 300;

-- 5f. Reversed repayments. is_reversed is a flag, not a delete: the row stays
-- as an audit trail and every aggregate must exclude it (docs/DOMAIN.md
-- section 9.1). Seeding these means an agent that forgets the filter returns a
-- wrong number rather than accidentally being right.
INSERT INTO m_loan_transaction (
    id, loan_id, office_id, is_reversed, transaction_type_enum, transaction_date,
    submitted_on_date, amount, principal_portion_derived, interest_portion_derived,
    manually_adjusted_or_reversed,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    5000000 + p.loan_id, p.loan_id, c.office_id, true, 2,
    (p.disbursed_on + INTERVAL '1 month')::date,
    (p.disbursed_on + INTERVAL '1 month')::date,
    p.inst_principal + p.inst_interest, p.inst_principal, p.inst_interest,
    true,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1
FROM _seed_loan_params p
JOIN m_client c ON c.id = p.client_id
WHERE p.has_schedule AND p.loan_id % 23 = 0;

-- ===========================================================================
-- 6. Transaction-to-installment mappings
--
-- A genuine many-to-many in Fineract; here each seeded repayment clears exactly
-- one installment, which keeps the arithmetic checkable. Reversed transactions
-- are not mapped.
-- ===========================================================================
INSERT INTO m_loan_transaction_repayment_schedule_mapping (
    id, loan_transaction_id, loan_repayment_schedule_id, amount,
    principal_portion_derived, interest_portion_derived,
    fee_charges_portion_derived, penalty_charges_portion_derived
)
SELECT
    s.id, 1000000 + s.id, s.id,
    s.principal_amount + s.interest_amount,
    s.principal_amount, s.interest_amount, 0, 0
FROM m_loan_repayment_schedule s
JOIN _seed_loan_params p ON p.loan_id = s.loan_id
WHERE s.installment <= p.paid_installments;

-- ===========================================================================
-- 7. Schedule rollups, computed from the mappings
-- ===========================================================================
UPDATE m_loan_repayment_schedule s SET
    principal_completed_derived   = m.principal_portion_derived,
    interest_completed_derived    = m.interest_portion_derived,
    fee_charges_completed_derived = m.fee_charges_portion_derived,
    penalty_charges_completed_derived = m.penalty_charges_portion_derived,
    completed_derived             = true,
    obligations_met_on_date       = s.duedate
FROM m_loan_transaction_repayment_schedule_mapping m
WHERE m.loan_repayment_schedule_id = s.id;

-- Written-off installments: the balance is cleared by the write-off, not paid.
UPDATE m_loan_repayment_schedule s SET
    principal_writtenoff_derived = s.principal_amount,
    interest_writtenoff_derived  = s.interest_amount,
    completed_derived            = true
FROM _seed_loan_params p
WHERE p.loan_id = s.loan_id
  AND p.loan_status_id = 601
  AND s.installment > p.paid_installments;

-- ===========================================================================
-- 8. Loan rollups, computed from the transactions
--
-- Only non-reversed, cash-movement transactions contribute. The accrual rows
-- from 5e and the reversed rows from 5f are excluded here, which is the whole
-- point of seeding them.
-- ===========================================================================
WITH paid AS (
    SELECT t.loan_id,
           SUM(COALESCE(t.principal_portion_derived, 0)) AS principal_repaid,
           SUM(COALESCE(t.interest_portion_derived, 0))  AS interest_repaid,
           SUM(COALESCE(t.overpayment_portion_derived, 0)) AS overpaid
    FROM m_loan_transaction t
    JOIN r_loan_transaction_type rt ON rt.id = t.transaction_type_enum
    WHERE t.is_reversed IS FALSE
      AND rt.is_cash_movement
      AND t.transaction_type_enum <> 1        -- disbursement creates, not repays
    GROUP BY t.loan_id
),
woff AS (
    SELECT t.loan_id,
           SUM(COALESCE(t.principal_portion_derived, 0)) AS principal_written,
           SUM(COALESCE(t.interest_portion_derived, 0))  AS interest_written
    FROM m_loan_transaction t
    WHERE t.is_reversed IS FALSE AND t.transaction_type_enum = 6
    GROUP BY t.loan_id
)
UPDATE m_loan l SET
    principal_disbursed_derived   = p.principal,
    principal_repaid_derived      = COALESCE(paid.principal_repaid, 0),
    principal_writtenoff_derived  = COALESCE(woff.principal_written, 0),
    principal_outstanding_derived = p.principal
                                    - COALESCE(paid.principal_repaid, 0)
                                    - COALESCE(woff.principal_written, 0),
    interest_charged_derived      = p.interest_total,
    interest_repaid_derived       = COALESCE(paid.interest_repaid, 0),
    interest_writtenoff_derived   = COALESCE(woff.interest_written, 0),
    interest_outstanding_derived  = p.interest_total
                                    - COALESCE(paid.interest_repaid, 0)
                                    - COALESCE(woff.interest_written, 0),
    total_expected_repayment_derived = p.principal + p.interest_total,
    total_repayment_derived       = COALESCE(paid.principal_repaid, 0)
                                    + COALESCE(paid.interest_repaid, 0),
    total_writtenoff_derived      = COALESCE(woff.principal_written, 0)
                                    + COALESCE(woff.interest_written, 0),
    total_outstanding_derived     = (p.principal + p.interest_total)
                                    - COALESCE(paid.principal_repaid, 0)
                                    - COALESCE(paid.interest_repaid, 0)
                                    - COALESCE(woff.principal_written, 0)
                                    - COALESCE(woff.interest_written, 0),
    total_overpaid_derived        = COALESCE(paid.overpaid, 0)
FROM _seed_loan_params p
LEFT JOIN paid ON paid.loan_id = p.loan_id
LEFT JOIN woff ON woff.loan_id = p.loan_id
WHERE l.id = p.loan_id AND p.has_schedule;

-- Loans never disbursed have no money at all.
UPDATE m_loan l SET
    principal_disbursed_derived = 0, principal_repaid_derived = 0,
    principal_outstanding_derived = 0, total_outstanding_derived = 0,
    total_repayment_derived = 0
FROM _seed_loan_params p
WHERE l.id = p.loan_id AND NOT p.has_schedule;

-- ===========================================================================
-- 9. m_loan_arrears_aging
--
-- Fineract populates this from a batch job that truncates and repopulates, so
-- it is empty in any database the job has never run against - which is ours
-- (docs/DOMAIN.md section 9.3). We populate it from the schedule using
-- Fineract's own definition so the table is realistic AND so the eval suite can
-- cross-check it against arrears computed directly from the schedule.
--
-- This is LoanArrearsAgeingUpdateHandler.java:112-148 rendered as SQL, with the
-- pinned business date substituted for its interpolated one:
--   status 300 only, unpaid installments only, and the grace period subtracted
--   before the due date is compared.
-- ===========================================================================
INSERT INTO m_loan_arrears_aging (
    loan_id, principal_overdue_derived, interest_overdue_derived,
    fee_charges_overdue_derived, penalty_charges_overdue_derived,
    total_overdue_derived, overdue_since_date_derived
)
SELECT
    l.id,
    SUM(COALESCE(s.principal_amount, 0)
        - COALESCE(s.principal_completed_derived, 0)
        - COALESCE(s.principal_writtenoff_derived, 0)),
    SUM(COALESCE(s.interest_amount, 0)
        - COALESCE(s.interest_completed_derived, 0)
        - COALESCE(s.interest_writtenoff_derived, 0)
        - COALESCE(s.interest_waived_derived, 0)),
    0, 0,
    SUM(COALESCE(s.principal_amount, 0)
        - COALESCE(s.principal_completed_derived, 0)
        - COALESCE(s.principal_writtenoff_derived, 0)
        + COALESCE(s.interest_amount, 0)
        - COALESCE(s.interest_completed_derived, 0)
        - COALESCE(s.interest_writtenoff_derived, 0)
        - COALESCE(s.interest_waived_derived, 0)),
    MIN(s.duedate)
FROM m_loan l
JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
WHERE l.loan_status_id = 300
  AND s.completed_derived IS FALSE
  AND s.duedate < (DATE '2026-06-30' - COALESCE(l.grace_on_arrears_ageing, 0) * INTERVAL '1 day')
GROUP BY l.id;

-- ===========================================================================
-- 10. Delinquency tags
--
-- The classification is the label put on the arrears age, and the range is
-- matched by min/max age with the open-ended top range carrying max_age_days
-- IS NULL (docs/DOMAIN.md section 6.2). liftedon_date IS NULL marks the
-- current tag; reading this table without that filter double-counts loans.
-- ===========================================================================
INSERT INTO m_loan_delinquency_tag_history (
    id, loan_id, delinquency_range_id, addedon_date, liftedon_date,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
)
SELECT
    a.loan_id, a.loan_id, r.id, a.overdue_since_date_derived, NULL,
    1, TIMESTAMPTZ '2026-06-30 00:00:00+00', 1, TIMESTAMPTZ '2026-06-30 00:00:00+00', 1
FROM m_loan_arrears_aging a
JOIN LATERAL (
    SELECT r.id
    FROM m_delinquency_range r
    JOIN m_delinquency_bucket_mappings bm ON bm.delinquency_range_id = r.id
    WHERE bm.delinquency_bucket_id = 1
      AND r.min_age_days <= (DATE '2026-06-30' - a.overdue_since_date_derived)
      AND (r.max_age_days IS NULL
           OR r.max_age_days >= (DATE '2026-06-30' - a.overdue_since_date_derived))
    ORDER BY r.min_age_days
    LIMIT 1
) r ON true;

-- ===========================================================================
-- 11. Savings
--
-- Direction is carried entirely by transaction_type_enum - there is no signed
-- amount and no debit/credit column - so the balance is computed by joining to
-- r_savings_transaction_type.entry_type (docs/DOMAIN.md section 2.5).
-- ===========================================================================
INSERT INTO m_savings_account (
    id, account_no, client_id, product_id, field_officer_id, status_enum,
    sub_status_enum, account_type_enum, deposit_type_enum, submittedon_date,
    submittedon_userid, approvedon_date, activatedon_date, currency_code,
    currency_digits, nominal_annual_interest_rate,
    interest_compounding_period_enum, interest_posting_period_enum,
    interest_calculation_type_enum, interest_calculation_days_in_year_type_enum,
    allow_overdraft, account_balance_derived, enforce_min_required_balance,
    withhold_tax, is_lien_allowed, version,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc
)
SELECT
    i, 'S' || lpad(i::text, 8, '0'), i, 1,
    CASE WHEN c.office_id = 2 THEN 1 + (i % 2) ELSE 3 + (i % 2) END,
    CASE WHEN i > 145 THEN 600 ELSE 300 END,
    0, 1, 100,
    (DATE '2023-01-10' + ((i * 13) % 900) * INTERVAL '1 day')::date, 1,
    (DATE '2023-01-15' + ((i * 13) % 900) * INTERVAL '1 day')::date,
    (DATE '2023-01-20' + ((i * 13) % 900) * INTERVAL '1 day')::date,
    'INR', 2, 4.000000, 4, 4, 1, 365,
    false, 0, false, false, false, 1,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM generate_series(1, 150) AS i
JOIN m_client c ON c.id = i;

-- Opening deposit, then monthly deposits, with a withdrawal every third month.
INSERT INTO m_savings_account_transaction (
    id, savings_account_id, office_id, transaction_type_enum, is_reversed,
    is_reversal, is_lien_transaction, transaction_date, submitted_on_date,
    amount, is_manual,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc
)
SELECT
    (sa.id * 100) + j,
    sa.id, c.office_id,
    CASE WHEN j = 1 THEN 1 WHEN j % 3 = 0 THEN 2 ELSE 1 END,   -- 1 DEPOSIT, 2 WITHDRAWAL
    false, false, false,
    (sa.activatedon_date + ((j - 1) * INTERVAL '1 month'))::date,
    (sa.activatedon_date + ((j - 1) * INTERVAL '1 month'))::date,
    CASE WHEN j = 1 THEN 5000 + (sa.id % 7) * 1000
         WHEN j % 3 = 0 THEN 1000 + (sa.id % 3) * 500
         ELSE 2000 + (sa.id % 5) * 500 END,
    true,
    1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM m_savings_account sa
JOIN m_client c ON c.id = sa.client_id
CROSS JOIN LATERAL generate_series(1, 6 + (sa.id % 7)) AS j;

-- Running balance, then the account rollup. Both computed from the
-- transactions above, with direction taken from the reference table.
WITH signed AS (
    SELECT t.id, t.savings_account_id,
           CASE WHEN rt.entry_type = 'CREDIT' THEN t.amount ELSE -t.amount END AS delta
    FROM m_savings_account_transaction t
    JOIN r_savings_transaction_type rt ON rt.id = t.transaction_type_enum
    WHERE t.is_reversed IS FALSE
),
running AS (
    SELECT id, savings_account_id,
           SUM(delta) OVER (PARTITION BY savings_account_id ORDER BY id
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS bal
    FROM signed
)
UPDATE m_savings_account_transaction t
SET running_balance_derived = running.bal,
    cumulative_balance_derived = running.bal
FROM running WHERE running.id = t.id;

UPDATE m_savings_account sa SET
    account_balance_derived  = agg.bal,
    total_deposits_derived   = agg.deposits,
    total_withdrawals_derived = agg.withdrawals
FROM (
    SELECT t.savings_account_id,
           SUM(CASE WHEN rt.entry_type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS bal,
           SUM(CASE WHEN rt.entry_type = 'CREDIT' THEN t.amount ELSE 0 END)         AS deposits,
           SUM(CASE WHEN rt.entry_type = 'DEBIT'  THEN t.amount ELSE 0 END)         AS withdrawals
    FROM m_savings_account_transaction t
    JOIN r_savings_transaction_type rt ON rt.id = t.transaction_type_enum
    WHERE t.is_reversed IS FALSE
    GROUP BY t.savings_account_id
) agg
WHERE sa.id = agg.savings_account_id;

-- ===========================================================================
-- 12. Tidy up
-- ===========================================================================
DROP TABLE _seed_loan_params;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'm_client', 'm_loan', 'm_loan_repayment_schedule', 'm_loan_transaction',
        'm_loan_transaction_repayment_schedule_mapping',
        'm_loan_delinquency_tag_history', 'm_savings_account',
        'm_savings_account_transaction'
    ] LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, %L), COALESCE((SELECT MAX(id) FROM %I), 1), true)',
            t, 'id', t);
    END LOOP;
END $$;
