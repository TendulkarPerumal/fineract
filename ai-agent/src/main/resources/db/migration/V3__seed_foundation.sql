-- Seed, part 1 of 2: organisation, reference data and products.
--
-- Part 2 (V4) seeds clients, loans, schedules, transactions and savings, and
-- computes the _derived rollups from the transactions it writes. This file
-- exists separately because everything here is small, hand-authored and
-- referenced by everything there; splitting them keeps a failure diagnosable.
--
-- PINNED BUSINESS DATE: 2026-06-30.
-- Fineract uses a configurable logical business date interpolated from the JVM,
-- not CURRENT_DATE (docs/DOMAIN.md section 6.3). Every date below is chosen
-- relative to that pinned date so that "loans over 60 days in arrears" has one
-- correct answer forever and eval failures are agent regressions rather than
-- calendar drift. Nothing here may use CURRENT_DATE or now().
--
-- All ids are explicit rather than generated, so the data is reproducible and
-- V4 can reference it without lookups. Identity sequences are resynchronised at
-- the end.

-- ---------------------------------------------------------------------------
-- Offices. m_office.hierarchy is a materialised path, not a parent pointer
-- chain: the root is '.', and a child of office N is the parent hierarchy with
-- N appended (Office.java:188-199). This is what makes "this office and
-- everything under it" a LIKE prefix match rather than a recursive CTE.
-- ---------------------------------------------------------------------------
INSERT INTO m_office (id, parent_id, hierarchy, external_id, name, opening_date) VALUES
  (1, NULL, '.',   'HO',   'Head Office',     DATE '2015-01-01'),
  (2, 1,    '.2.', 'BR-E', 'Eastern Branch',  DATE '2016-03-01'),
  (3, 1,    '.3.', 'BR-W', 'Western Branch',  DATE '2017-07-01');

-- ---------------------------------------------------------------------------
-- System user. Required because audit columns across the schema are NOT NULL
-- and FK to m_appuser (docs/DOMAIN.md section 9.5), including created_by on
-- every delinquency table.
--
-- This is seed data for a read-only analytical database that never
-- authenticates anyone; the password column is filled with an explicit
-- non-credential placeholder rather than a hash, so it cannot be mistaken for
-- one or used to log in.
-- ---------------------------------------------------------------------------
INSERT INTO m_appuser (
    id, office_id, username, firstname, lastname, password, email,
    firsttime_login_remaining, nonexpired, nonlocked, nonexpired_credentials,
    enabled, is_deleted, last_time_password_updated
) VALUES
  (1, 1, 'seed', 'Seed', 'Process', 'NO-LOGIN-SEED-DATA-ONLY', 'seed@example.invalid',
   false, true, true, true, false, false, DATE '2015-01-01');

-- ---------------------------------------------------------------------------
-- Loan officers. Note the column is loan_officer_id on m_loan but
-- field_officer_id on m_savings_account - same concept, two names, kept
-- verbatim (decision 3).
-- ---------------------------------------------------------------------------
INSERT INTO m_staff (id, office_id, is_loan_officer, display_name, is_active, joining_date) VALUES
  (1, 2, true, 'Anita Rao',      true, DATE '2016-04-01'),
  (2, 2, true, 'Vikram Shah',    true, DATE '2018-06-01'),
  (3, 3, true, 'Priya Nair',     true, DATE '2017-09-01'),
  (4, 3, true, 'Rahul Menon',    true, DATE '2019-02-01'),
  (5, 1, false, 'Sunita Iyer',   true, DATE '2015-01-01');

-- ---------------------------------------------------------------------------
-- Currency. Note m_currency is a catalogue: currency_code on m_loan,
-- m_savings_account and the products is a denormalised varchar(3) joined by
-- code, and is NOT foreign-keyed to this table.
-- ---------------------------------------------------------------------------
INSERT INTO m_currency (id, code, decimal_places, display_symbol, name, internationalized_name_code) VALUES
  (1, 'INR', 2, U&'\20B9', 'Indian Rupee', 'currency.INR');

-- ---------------------------------------------------------------------------
-- Code values. Every *_cv_id column FKs here (docs/DOMAIN.md section 9.5).
-- ---------------------------------------------------------------------------
INSERT INTO m_code (id, code_name, is_system_defined) VALUES
  (1, 'Gender',          true),
  (2, 'ClientType',      true),
  (3, 'LoanPurpose',     true),
  (4, 'WriteOffReasons', true);

INSERT INTO m_code_value (id, code_id, code_value, code_description, order_position, is_active, is_mandatory) VALUES
  (1,  1, 'Female',              'Female',                          1, true, false),
  (2,  1, 'Male',                'Male',                            2, true, false),
  (3,  2, 'Individual',          'Individual client',               1, true, false),
  (4,  2, 'Entity',              'Entity client',                   2, true, false),
  (5,  3, 'Working Capital',     'Working capital for a business',  1, true, false),
  (6,  3, 'Agriculture',         'Agricultural inputs',             2, true, false),
  (7,  3, 'Housing Improvement', 'Home repair or extension',        3, true, false),
  (8,  3, 'Education',           'School or college fees',          4, true, false),
  (9,  4, 'Default',             'Borrower defaulted',              1, true, false),
  (10, 4, 'Deceased',            'Borrower deceased',               2, true, false);

INSERT INTO m_fund (id, name, external_id) VALUES
  (1, 'General Lending Fund',    'FUND-GEN'),
  (2, 'Agricultural Credit Line','FUND-AGRI');

INSERT INTO m_payment_type (id, value, description, is_cash_payment, order_position, is_system_defined) VALUES
  (1, 'Cash',          'Cash payment at branch',   true,  1, true),
  (2, 'Bank Transfer', 'NEFT/RTGS bank transfer',  false, 2, true),
  (3, 'UPI',           'UPI instant transfer',     false, 3, true);

-- charge_applies_to_enum 1 = LOAN; charge_time_enum 1 = DISBURSEMENT,
-- 9 = OVERDUE_INSTALLMENT; charge_calculation_enum 1 = FLAT.
-- is_penalty is the fee/penalty discriminator - the same table holds both.
INSERT INTO m_charge (
    id, name, currency_code, charge_applies_to_enum, charge_time_enum,
    charge_calculation_enum, amount, is_penalty, is_active, is_deleted
) VALUES
  (1, 'Loan Processing Fee',  'INR', 1, 1, 1,  500.000000, false, true, false),
  (2, 'Late Payment Penalty', 'INR', 1, 9, 1,  250.000000, true,  true, false);

-- ---------------------------------------------------------------------------
-- Delinquency ranges and bucket.
--
-- Arrears is the number of days; delinquency is the label put on that number,
-- and the label depends on which bucket the loan's PRODUCT is attached to
-- (docs/DOMAIN.md section 6.2). max_age_days IS NULL is the open-ended top
-- range and is honoured explicitly by
-- DelinquencyWritePlatformServiceHelper.java:66-70.
--
-- These tables carry a NOT NULL audit quartet plus version, so seed rows need
-- real values (docs/DOMAIN.md section 2.4).
-- ---------------------------------------------------------------------------
INSERT INTO m_delinquency_range (
    id, classification, min_age_days, max_age_days,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
) VALUES
  (1, '1-30 days',  1,  30,   1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (2, '31-60 days', 31, 60,   1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (3, '61-90 days', 61, 90,   1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (4, '90+ days',   91, NULL, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1);

INSERT INTO m_delinquency_bucket (
    id, name, bucket_type,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
) VALUES
  (1, 'Standard Delinquency Bucket', 'REGULAR',
   1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1);

INSERT INTO m_delinquency_bucket_mappings (
    id, delinquency_range_id, delinquency_bucket_id,
    created_by, created_on_utc, last_modified_by, last_modified_on_utc, version
) VALUES
  (1, 1, 1, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (2, 2, 1, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (3, 3, 1, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1),
  (4, 4, 1, 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00', 1);

-- ---------------------------------------------------------------------------
-- Loan products.
--
-- interest_method_enum        0 = DECLINING_BALANCE, 1 = FLAT
-- repayment_period_frequency  2 = MONTHS
-- amortization_method_enum    1 = EQUAL_INSTALLMENTS
-- accounting_type             1 = NONE (we are not running Fineract's
--                                 accounting, so no GL entries are produced)
--
-- grace_on_arrears_ageing is set per product here and copied onto each loan in
-- V4. It shifts the arrears threshold, so a loan with 5 days grace and 3 days
-- late does not appear in arrears at all (docs/DOMAIN.md section 6.1).
-- ---------------------------------------------------------------------------
INSERT INTO m_product_loan (
    id, short_name, name, description, fund_id, currency_code, currency_digits,
    principal_amount, min_principal_amount, max_principal_amount,
    nominal_interest_rate_per_period, min_nominal_interest_rate_per_period,
    max_nominal_interest_rate_per_period, interest_period_frequency_enum,
    annual_nominal_interest_rate, interest_method_enum,
    interest_calculated_in_period_enum, repay_every,
    repayment_period_frequency_enum, number_of_repayments,
    amortization_method_enum, accounting_type, grace_on_arrears_ageing,
    delinquency_bucket_id, start_date
) VALUES
  (1, 'WCAP', 'Working Capital Loan', 'Short-term working capital for micro-enterprises',
   1, 'INR', 2, 50000.000000, 10000.000000, 200000.000000,
   1.500000, 1.000000, 2.000000, 2, 18.000000, 0, 1, 1, 2, 12, 1, 1, 5, 1, DATE '2016-01-01'),
  (2, 'AGRI', 'Agricultural Loan', 'Seasonal agricultural credit',
   2, 'INR', 2, 75000.000000, 20000.000000, 300000.000000,
   1.250000, 1.000000, 1.750000, 2, 15.000000, 0, 1, 1, 2, 18, 1, 1, 10, 1, DATE '2016-01-01'),
  (3, 'HOUS', 'Housing Improvement Loan', 'Home repair and extension',
   1, 'INR', 2, 150000.000000, 50000.000000, 500000.000000,
   1.000000, 0.750000, 1.500000, 2, 12.000000, 0, 1, 1, 2, 24, 1, 1, 0, 1, DATE '2017-01-01');

-- ---------------------------------------------------------------------------
-- Savings product.
--
-- interest_compounding_period_enum        4 = MONTHLY
-- interest_posting_period_enum            4 = MONTHLY
-- interest_calculation_type_enum          1 = DAILY_BALANCE
-- interest_calculation_days_in_year_type  365
-- deposit_type_enum                     100 = SAVINGS_DEPOSIT (r_deposit_type)
-- ---------------------------------------------------------------------------
INSERT INTO m_savings_product (
    id, name, short_name, description, deposit_type_enum, currency_code,
    currency_digits, nominal_annual_interest_rate,
    interest_compounding_period_enum, interest_posting_period_enum,
    interest_calculation_type_enum, interest_calculation_days_in_year_type_enum,
    accounting_type, allow_overdraft, enforce_min_required_balance,
    withhold_tax, is_lien_allowed
) VALUES
  (1, 'Regular Savings', 'RSAV', 'Passbook savings account', 100, 'INR',
   2, 4.000000, 4, 4, 1, 365, 1, false, false, false, false);

-- ---------------------------------------------------------------------------
-- Resynchronise identity sequences. Every id above was supplied explicitly, so
-- the underlying sequences still sit at 1 and any later insert that omits an id
-- would collide. The agent's user is read-only, but leaving a database in a
-- state where an ordinary insert fails is a trap for anyone who opens a psql
-- session against it.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'm_office', 'm_appuser', 'm_staff', 'm_currency', 'm_code',
        'm_code_value', 'm_fund', 'm_payment_type', 'm_charge',
        'm_delinquency_range', 'm_delinquency_bucket',
        'm_delinquency_bucket_mappings', 'm_product_loan', 'm_savings_product'
    ] LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, %L), COALESCE((SELECT MAX(id) FROM %I), 1), true)',
            t, 'id', t);
    END LOOP;
END $$;
