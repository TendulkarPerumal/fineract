-- Reference tables for Fineract's enum columns.
--
-- These are OURS, not Fineract's. Fineract stores enum meanings in Java and
-- ships a partial copy in r_enum_value, which is baseline data frozen at the
-- Flyway->Liquibase switch: 10 of 12 loan statuses, 19 of 47 loan transaction
-- types, and savings types 18/19 mislabelled (docs/DOMAIN.md section 7.1).
-- Grounding an LLM on that produces answers that are wrong in a way that looks
-- right, so we derive these from the Java enums instead.
--
-- Every value below was read out of the enum classes at commit f4f927ea:
--   LoanStatus.java, LoanTransactionType.java, AccountType.java,
--   ClientStatus.java, SavingsAccountStatusType.java,
--   SavingsAccountTransactionType.java, DepositAccountType.java
--
-- NO foreign keys from the m_* _enum columns to these tables (decision 5).
-- Fineract's write path does not know they exist, and an FK could reject an
-- insert upstream considers valid. A CI check enforces agreement instead.
--
-- Note the id gaps: loan transaction type 11 does not exist, and savings
-- transaction types 9 and 11 do not exist. They were removed upstream. The
-- gaps are real - do not invent values to fill them.

CREATE TABLE r_loan_status (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

INSERT INTO r_loan_status (id, code, description) VALUES
  (0,   'INVALID',                              'Invalid / unrecognised status sentinel'),
  (100, 'SUBMITTED_AND_PENDING_APPROVAL',       'Submitted and awaiting approval'),
  (200, 'APPROVED',                             'Approved, not yet disbursed'),
  (300, 'ACTIVE',                               'Active - disbursed and being repaid'),
  (303, 'TRANSFER_IN_PROGRESS',                 'Client transfer in progress'),
  (304, 'TRANSFER_ON_HOLD',                     'Client transfer on hold'),
  (400, 'WITHDRAWN_BY_CLIENT',                  'Withdrawn by client before approval'),
  (500, 'REJECTED',                             'Rejected by the institution'),
  (600, 'CLOSED_OBLIGATIONS_MET',               'Closed - obligations met'),
  (601, 'CLOSED_WRITTEN_OFF',                   'Closed - written off'),
  (602, 'CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT', 'Closed - outstanding rescheduled'),
  (700, 'OVERPAID',                             'Overpaid - credit balance outstanding');

-- There is deliberately no OVERDUE / IN_ARREARS / DELINQUENT status here,
-- because Fineract has none. A loan 200 days late is status 300, exactly like
-- one that is current. Arrears is computed from the repayment schedule, never
-- stored (docs/DOMAIN.md section 4.3).


CREATE TABLE r_loan_type (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

-- m_loan.loan_type_enum; the Java enum is AccountType.
INSERT INTO r_loan_type (id, code, description) VALUES
  (0, 'INVALID',    'Invalid sentinel'),
  (1, 'INDIVIDUAL', 'Individual loan'),
  (2, 'GROUP',      'Group loan'),
  (3, 'JLG',        'Joint liability group loan, held in a group context'),
  (4, 'GLIM',       'Group loan with individual monitoring'),
  (5, 'GSIM',       'Group savings with individual monitoring');


CREATE TABLE r_client_status (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

-- m_client.status_enum. Note 700 = REJECTED and 800 = WITHDRAWN here, while
-- loan status uses 500 = REJECTED and 400 = WITHDRAWN. The same word maps to
-- different numbers on the two entities, which is why these are separate
-- tables rather than one shared status table.
INSERT INTO r_client_status (id, code, description) VALUES
  (0,   'INVALID',              'Invalid sentinel'),
  (100, 'PENDING',              'Pending activation'),
  (300, 'ACTIVE',               'Active'),
  (303, 'TRANSFER_IN_PROGRESS', 'Transfer in progress'),
  (304, 'TRANSFER_ON_HOLD',     'Transfer on hold'),
  (600, 'CLOSED',               'Closed'),
  (700, 'REJECTED',             'Rejected'),
  (800, 'WITHDRAWN',            'Withdrawn');


CREATE TABLE r_savings_account_status (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

INSERT INTO r_savings_account_status (id, code, description) VALUES
  (0,   'INVALID',                        'Invalid sentinel'),
  (100, 'SUBMITTED_AND_PENDING_APPROVAL', 'Submitted and awaiting approval'),
  (200, 'APPROVED',                       'Approved, not yet activated'),
  (300, 'ACTIVE',                         'Active'),
  (303, 'TRANSFER_IN_PROGRESS',           'Transfer in progress'),
  (304, 'TRANSFER_ON_HOLD',               'Transfer on hold'),
  (400, 'WITHDRAWN_BY_APPLICANT',         'Withdrawn by applicant'),
  (500, 'REJECTED',                       'Rejected'),
  (600, 'CLOSED',                         'Closed'),
  (700, 'PRE_MATURE_CLOSURE',             'Closed before maturity'),
  (800, 'MATURED',                        'Matured');


CREATE TABLE r_deposit_type (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL
);

-- deposit_type_enum on both m_savings_product and m_savings_account.
INSERT INTO r_deposit_type (id, code, description) VALUES
  (0,   'INVALID',           'Invalid sentinel'),
  (100, 'SAVINGS_DEPOSIT',   'Savings account'),
  (200, 'FIXED_DEPOSIT',     'Fixed deposit'),
  (300, 'RECURRING_DEPOSIT', 'Recurring deposit'),
  (400, 'CURRENT_DEPOSIT',   'Current account');


CREATE TABLE r_savings_transaction_type (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL,
    -- Extension beyond the (id, code, description) shape, and a necessary one:
    -- m_savings_account_transaction has no signed amount and no debit/credit
    -- column. Direction is carried entirely by transaction_type_enum, so
    -- SUM(amount) over that table is meaningless without this. Taken from the
    -- TransactionEntryType argument on each Java constant; NULL where the
    -- constant carries none (state markers, not money movements).
    entry_type  varchar(6)   CHECK (entry_type IN ('CREDIT', 'DEBIT'))
);

INSERT INTO r_savings_transaction_type (id, code, description, entry_type) VALUES
  (0,  'INVALID',            'Invalid sentinel',                  NULL),
  (1,  'DEPOSIT',            'Deposit',                           'CREDIT'),
  (2,  'WITHDRAWAL',         'Withdrawal',                        'DEBIT'),
  (3,  'INTEREST_POSTING',   'Interest posted to the account',    'CREDIT'),
  (4,  'WITHDRAWAL_FEE',     'Fee charged on withdrawal',         'DEBIT'),
  (5,  'ANNUAL_FEE',         'Annual account fee',                'DEBIT'),
  (6,  'WAIVE_CHARGES',      'Charge waived',                     NULL),
  (7,  'PAY_CHARGE',         'Charge paid',                       'DEBIT'),
  (8,  'DIVIDEND_PAYOUT',    'Dividend paid out',                 'CREDIT'),
  (10, 'ACCRUAL',            'Accrual entry, not cash',           NULL),
  (12, 'INITIATE_TRANSFER',  'Transfer initiated',                NULL),
  (13, 'APPROVE_TRANSFER',   'Transfer approved',                 NULL),
  (14, 'WITHDRAW_TRANSFER',  'Transfer withdrawn',                NULL),
  (15, 'REJECT_TRANSFER',    'Transfer rejected',                 NULL),
  (16, 'WRITTEN_OFF',        'Written off',                       NULL),
  (17, 'OVERDRAFT_INTEREST', 'Interest charged on overdraft',     'DEBIT'),
  (18, 'WITHHOLD_TAX',       'Tax withheld',                      'DEBIT'),
  (19, 'ESCHEAT',            'Escheated to the state',            'DEBIT'),
  (20, 'AMOUNT_HOLD',        'Amount placed on hold',             'DEBIT'),
  (21, 'AMOUNT_RELEASE',     'Held amount released',              'CREDIT');

-- 18/19 above are the corrected values. Fineract's own r_enum_value seeds
-- enum_id 19 as WITHHOLD_TAX (0002_initial_data.xml:11778-11783) and never
-- seeds ESCHEAT at all, so joining to it returns the wrong label for escheat
-- rows and NULL for actual withholding tax.


CREATE TABLE r_loan_transaction_type (
    id          integer      PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    description varchar(255) NOT NULL,
    -- Extension beyond (id, code, description). "How much did this client
    -- repay" is not SUM(amount): the table mixes real money with accounting
    -- entries and state markers. TRUE means cash actually moved.
    -- FALSE covers accruals and amortizations (10, 32, 34, 36, 39, 42, 43, 45,
    -- 47 - docs/DOMAIN.md section 9.2), waivers and write-offs, which reduce a
    -- balance without cash, and transfer/re-age/re-amortize state markers.
    is_cash_movement boolean NOT NULL
);

INSERT INTO r_loan_transaction_type (id, code, description, is_cash_movement) VALUES
  (0,  'INVALID',                                  'Invalid sentinel',                          false),
  (1,  'DISBURSEMENT',                             'Loan principal paid out to the client',     true),
  (2,  'REPAYMENT',                                'Ordinary repayment',                        true),
  (3,  'CONTRA',                                   'Reversal counter-entry',                    false),
  (4,  'WAIVE_INTEREST',                           'Interest waived',                           false),
  (5,  'REPAYMENT_AT_DISBURSEMENT',                'Repayment collected at disbursement',       true),
  (6,  'WRITEOFF',                                 'Loan written off',                          false),
  (7,  'MARKED_FOR_RESCHEDULING',                  'Marked for rescheduling',                   false),
  (8,  'RECOVERY_REPAYMENT',                       'Recovery on a written-off loan',            true),
  (9,  'WAIVE_CHARGES',                            'Charges waived',                            false),
  (10, 'ACCRUAL',                                  'Accrual of interest, fee or penalty',       false),
  (12, 'INITIATE_TRANSFER',                        'Transfer initiated',                        false),
  (13, 'APPROVE_TRANSFER',                         'Transfer approved',                         false),
  (14, 'WITHDRAW_TRANSFER',                        'Transfer withdrawn',                        false),
  (15, 'REJECT_TRANSFER',                          'Transfer rejected',                         false),
  (16, 'REFUND',                                   'Refund to the client',                      true),
  (17, 'CHARGE_PAYMENT',                           'Payment against a charge',                  true),
  (18, 'REFUND_FOR_ACTIVE_LOAN',                   'Refund on an active loan',                  true),
  (19, 'INCOME_POSTING',                           'Income posting',                            false),
  (20, 'CREDIT_BALANCE_REFUND',                    'Refund of an overpaid credit balance',      true),
  (21, 'MERCHANT_ISSUED_REFUND',                   'Merchant-issued refund',                    true),
  (22, 'PAYOUT_REFUND',                            'Payout refund',                             true),
  (23, 'GOODWILL_CREDIT',                          'Goodwill credit to the client',             true),
  (24, 'CHARGE_REFUND',                            'Refund of a charge',                        true),
  (25, 'CHARGEBACK',                               'Chargeback of a previous repayment',        true),
  (26, 'CHARGE_ADJUSTMENT',                        'Adjustment to a charge',                    false),
  (27, 'CHARGE_OFF',                               'Loan charged off',                          false),
  (28, 'DOWN_PAYMENT',                             'Down payment at disbursement',              true),
  (29, 'REAGE',                                    'Loan re-aged',                              false),
  (30, 'REAMORTIZE',                               'Loan re-amortized',                         false),
  (31, 'INTEREST_PAYMENT_WAIVER',                  'Interest payment waived',                   false),
  (32, 'ACCRUAL_ACTIVITY',                         'Accrual activity posting',                  false),
  (33, 'INTEREST_REFUND',                          'Interest refunded to the client',           true),
  (34, 'ACCRUAL_ADJUSTMENT',                       'Adjustment to an accrual',                  false),
  (35, 'CAPITALIZED_INCOME',                       'Income capitalized into principal',         false),
  (36, 'CAPITALIZED_INCOME_AMORTIZATION',          'Amortization of capitalized income',        false),
  (37, 'CAPITALIZED_INCOME_ADJUSTMENT',            'Adjustment to capitalized income',          false),
  (38, 'CONTRACT_TERMINATION',                     'Contract terminated',                       false),
  (39, 'CAPITALIZED_INCOME_AMORTIZATION_ADJUSTMENT','Adjustment to capitalized income amortization', false),
  (40, 'BUY_DOWN_FEE',                             'Buy-down fee',                              false),
  (41, 'BUY_DOWN_FEE_ADJUSTMENT',                  'Adjustment to a buy-down fee',              false),
  (42, 'BUY_DOWN_FEE_AMORTIZATION',                'Amortization of a buy-down fee',            false),
  (43, 'BUY_DOWN_FEE_AMORTIZATION_ADJUSTMENT',     'Adjustment to buy-down fee amortization',   false),
  (44, 'DISCOUNT_FEE',                             'Discount fee',                              false),
  (45, 'DISCOUNT_FEE_AMORTIZATION',                'Amortization of a discount fee',            false),
  (46, 'DISCOUNT_FEE_ADJUSTMENT',                  'Adjustment to a discount fee',              false),
  (47, 'DISCOUNT_FEE_AMORTIZATION_ADJUSTMENT',     'Adjustment to discount fee amortization',   false);
