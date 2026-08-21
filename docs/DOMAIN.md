# Fineract Domain Model — Stage 0

Reference notes for the natural-language query agent built over Apache Fineract's
core banking schema. Everything below was read out of this repository at commit
`f4f927ea`; every claim carries a file path and line number so it can be
re-checked rather than trusted.

Scope of this document: understand the schema. No application code, no DDL, no
seed data. Stage 1 extracts a subset into a Flyway migration.

---

## 1. Where the schema actually lives

Fineract's tenant schema is defined in Liquibase XML, not in `schema.sql` and not
in JPA annotations. Three things need to be understood before reading it.

### 1.1 The changelog tree

```
fineract-provider/src/main/resources/db/changelog/tenant/
├── initial-switch-changelog-tenant.xml   ← runs parts/0001 + parts/0002
├── changelog-tenant.xml                  ← runs parts/0003 … parts/0247  (245 includes)
├── final-changelog-tenant.xml            ← runs parts/0146 + parts/0241  (2 includes, deferred)
├── parts/                                ← 249 files
└── upgrades/                             ← 0000_upgrade_to_1.5.xml, 0000_upgrade_to_1.6.xml
```

Module changelogs live outside `fineract-provider`, in the Gradle modules that
own the domain:

| Module | Path | Files | Numbering |
|---|---|---|---|
| `fineract-loan` | `src/main/resources/db/changelog/tenant/module/loan/parts/` | 35 | 1001–1035 |
| `fineract-savings` | `src/main/resources/db/changelog/tenant/module/savings/parts/parts/` | 5 | 2001–2005 |
| `fineract-progressive-loan` | `src/main/resources/db/changelog/tenant/module/progressiveloan/parts/` | 3 | 5001–5003 |

Note the doubled `parts/parts/` in the savings path — that is genuinely how it is
on disk, not a typo here.

The `parts/` filenames run 0001 through **0247**, not 0249, even though there are
249 files: two numbers are used twice
(`0242_fix_group_summary_report_parameters.xml` /
`0242_update_m_tellers_unique_key.xml`, and `0243_add_client_lifecycle_event_configuration.xml`
/ `0243_remove_mix_module.xml`). Liquibase does not care — ordering comes from the
`<include>` order in `changelog-tenant.xml`, not from the filename. Anything that
sorts these files by name to reconstruct history will get those two pairs wrong.

### 1.2 `0001_initial_schema.xml` is a baseline, not the schema

`fineract-provider/src/main/resources/db/changelog/tenant/initial-switch-changelog-tenant.xml:25-27`:

```xml
<include file="parts/0001_initial_schema.xml" relativeToChangelogFile="true" context="initial_switch"/>
<include file="parts/0002_initial_data.xml"   relativeToChangelogFile="true" context="initial_switch"/>
<!-- The first 2 changelog files are ran with the initial_switch context to handle the Flyway -> Liquibase migration -->
```

That comment is the key fact. Files 0001 and 0002 are a **frozen snapshot of the
schema as it stood when Fineract migrated from Flyway to Liquibase** — they run
only under the `initial_switch` context, and they have not been edited to reflect
later changes. Everything since then is an incremental migration on top.

The drift on top of that baseline:

| Source | Migrations after the baseline |
|---|---|
| `changelog-tenant.xml` | 245 |
| `final-changelog-tenant.xml` | 2 |
| `fineract-loan` module | 35 |
| `fineract-savings` module | 5 |
| `fineract-progressive-loan` module | 3 |
| **Total** | **290** |

(249 part files − 2 baseline files = 247 = 245 + 2. ✓)

The switch is dated precisely. `parts/0001_initial_schema.xml` was created by
commit `45bed0a` on **2022-02-09**, *"FINERACT-1498: Switching from Flyway to
Liqubase migrations"* — the commit that also deletes the Flyway machinery from
`TenantDatabaseUpgradeService.java`. It corresponds to the Fineract 1.6 era;
`upgrades/` still contains `0000_upgrade_to_1.5.xml` and
`0000_upgrade_to_1.6.xml`.

The file has been touched by exactly **three** commits in its lifetime:

```
2022-02-09  FINERACT-1498: Switching from Flyway to Liqubase migrations
2026-01-08  [#FINERACT-2422] standardize use of aging vs. ageing (#5289)
2026-01-09  Revert "[#FINERACT-2422] standardize use of aging vs. ageing (#5289)"
```

The second was reverted the next day, so the baseline's content is unchanged
since February 2022 — over four years of schema evolution living entirely in the
migrations layered on top of it, and none of it in the file that looks like the
schema. That is a stronger statement than "stale": the file is not merely out of
date, it is frozen by design, and anything read from it alone is the schema as it
stood at the Flyway cutover.

**Concrete example of drift on a table we care about.** `m_loan` is created at
`parts/0001_initial_schema.xml:1995` with **30** `*_derived` columns. It is then
altered by 34 later changelogs. Two more `_derived` columns arrive in
`parts/0057_add_principal_adjustments_to_loan.xml` and
`fineract-loan/.../1017_add_fee_and_penalty_adjustments_to_loan.xml`
(`principal_adjustments_derived`, `fee_adjustments_derived`). Audit columns
arrive at `parts/0020_add_audit_entries.xml:59-73` — and note that file carries
**two** changesets for the same columns, one `context="mysql"` using `DATETIME`
and one `context="postgresql"` using `TIMESTAMP WITH TIME ZONE`.

The full list of changelogs touching `m_loan` after the baseline:

```
parts/0020, 0024, 0057, 0062, 0064, 0074, 0077, 0079, 0105, 0117, 0119, 0126,
      0142, 0170, 0171, 0174, 0178, 0185, 0207, 0232, 0233
fineract-loan/1003, 1007, 1010, 1012, 1015, 1017, 1019, 1022, 1023, 1026, 1029, 1031
```

**This is why the locked decision to generate DDL by running Liquibase against a
throwaway Postgres and dumping the result is correct.** Reading `0001` and
transcribing it produces a schema that is roughly five years out of date and
missing columns the arrears logic reads. Reading `0001` plus 290 diffs by hand is
not a thing a person does correctly.

### 1.3 Per-database changesets

Many changelogs branch on `context="mysql"` / `context="postgresql"` and emit
different DDL. `parts/0029_add_delinquency_buckets.xml` is the clearest case: the
MySQL block is `:25`, the Postgres block is `:135`, and they differ in column
type (`DATETIME` vs `TIMESTAMP WITH TIME ZONE`). Any dump must therefore be taken
from a **Postgres** run — a MySQL run produces a schema Neon will accept but that
does not match what Fineract would actually create.

---

## 2. Tables in scope

Fifteen core tables plus the FK closure needed to make them insertable. Line
numbers below are `createTable` positions in
`fineract-provider/src/main/resources/db/changelog/tenant/parts/0001_initial_schema.xml`
unless another file is named.

### 2.1 Organisation

| Table | Line | Notes |
|---|---|---|
| `m_office` | 2863 | `id`, `parent_id`, `hierarchy`, `external_id`, `name`, `opening_date` |
| `m_staff` | 4317 | `is_loan_officer`, `office_id`, `display_name`, `is_active`, `joining_date` |
| `m_currency` | 1255 | `code` (PK-ish, unique VARCHAR(3)), `decimal_places`, `display_symbol`, `name` |

`m_organisation_currency` (`:2919`) is the per-tenant allow-list of currency
codes; `m_currency` is the global catalogue.

**`m_office.hierarchy` is a materialised path**, and this is the single most
useful structural fact about the org tree.
`fineract-core/src/main/java/org/apache/fineract/organisation/office/domain/Office.java:188-199`:

```java
public void generateHierarchy() {
    if (this.parent != null) {
        this.hierarchy = this.parent.hierarchyOf(getId());
    } else {
        this.hierarchy = ".";
    }
}

private String hierarchyOf(final Long id) {
    return this.hierarchy + id.toString() + ".";
}
```

Root office is `"."` (seeded at `parts/0002_initial_data.xml:1163`). A child of
office 1 is `".1."`. A grandchild under office 2 is `".1.2."`.

The consequence: **"this office and everything under it" is a `LIKE` prefix
match, not a recursive CTE.** Fineract does exactly this throughout —
`fineract-provider/.../DatatableUtil.java:228`:

```sql
join m_office o on o.id = <alias>.office_id and o.hierarchy like ?
```

and in the built-in reports (`parts/0002_initial_data.xml:12490`):

```sql
from m_office o
join m_office ounder on ounder.hierarchy like concat(o.hierarchy, '%')
```

Depth is derivable without a join:
`LENGTH(hierarchy) - LENGTH(REPLACE(hierarchy, '.', '')) - 1`. Fineract's own
reports use precisely that expression to indent office names.

For the agent this is worth exposing deliberately: an office rollup tool that
does `hierarchy LIKE :prefix || '%'` answers "totals for the Eastern region
including branches" in one index-friendly predicate. A naive `office_id = ?` only
answers "that one branch", which is usually not what the question meant.

### 2.2 Parties

| Table | Line | Notes |
|---|---|---|
| `m_client` | 857 | `account_no`, `status_enum` (default 300), `office_id` NOT NULL, `staff_id`, `display_name` NOT NULL, `activation_date`, `date_of_birth`, `legal_form_enum`, `mobile_no` unique |

`display_name` is NOT NULL and is the field to show; `firstname`/`lastname` are
nullable because an entity client uses `fullname` instead. `office_id` is NOT
NULL — every client belongs to exactly one office, so office rollups over clients
are a plain join.

### 2.3 Lending

| Table | Line | Notes |
|---|---|---|
| `m_product_loan` | 3088 | `short_name` VARCHAR(4) unique, `name` unique, `currency_code`, principal/interest min-max-default, `delinquency_bucket_id` (added `parts/0029:255`) |
| `m_loan` | 1995 | the account. See §5. |
| `m_loan_repayment_schedule` | 2498 | one row per installment |
| `m_loan_transaction` | 2695 | the cash/accounting ledger |
| `m_loan_transaction_repayment_schedule_mapping` | 2737 | which transaction paid which installment |
| `m_loan_arrears_aging` | 2213 | batch-computed arrears snapshot. See §6. |
| `m_loan_charge` | 2236 | fees and penalties attached to a loan |

**`m_loan` identity and linkage** (`:1995` ff): `account_no` VARCHAR(20) unique,
`external_id` unique, `client_id` **nullable**, `group_id` nullable, `product_id`,
`fund_id`, `loan_officer_id` → `m_staff`, `loan_status_id` SMALLINT NOT NULL,
`loan_type_enum` SMALLINT NOT NULL, `currency_code`, `principal_amount`,
`approved_principal`, `grace_on_arrears_ageing` SMALLINT nullable.

There is **no `office_id` on `m_loan`**. A loan's office is reached through
`m_client.office_id` (or `m_group.office_id` for group loans). `m_loan_transaction`
*does* carry `office_id` (`:2702`, NOT NULL) — that is the transaction's booking
office, which can differ from the client's office after a transfer. For "total
outstanding principal by office", join through the client; for "transaction
volume by office", use the transaction's own column. These give different answers
and both are legitimate — the agent should pick deliberately.

**`m_loan_repayment_schedule`** (`:2498`): `loan_id`, `fromdate`, `duedate` NOT
NULL, `installment` SMALLINT NOT NULL, and then for each of the four money
buckets (principal, interest, fee_charges, penalty_charges) a `*_amount` column
plus `*_completed_derived`, `*_writtenoff_derived`, and — for interest, fee and
penalty — `*_waived_derived`. Note the asymmetry: **there is no
`principal_waived_derived`.** Principal can be written off but not waived. Any
"outstanding on this installment" expression must not invent that column.

Also on the schedule row: `completed_derived` BOOLEAN NOT NULL (the obligations-met
flag the arrears job filters on), `obligations_met_on_date`,
`accrual_interest_derived` / `accrual_fee_charges_derived` /
`accrual_penalty_charges_derived`, `total_paid_in_advance_derived`,
`total_paid_late_derived`.

Outstanding per installment, matching Fineract's own arithmetic
(`LoanArrearsAgeingUpdateHandler.java:114-120`):

```sql
principal_amount        - principal_completed_derived - principal_writtenoff_derived
interest_amount         - interest_completed_derived  - interest_writtenoff_derived  - interest_waived_derived
fee_charges_amount      - fee_charges_completed_derived     - fee_charges_writtenoff_derived     - fee_charges_waived_derived
penalty_charges_amount  - penalty_charges_completed_derived - penalty_charges_writtenoff_derived - penalty_charges_waived_derived
```

Every one of those columns is nullable, which is why Fineract wraps each in
`COALESCE(..., 0)`. Omitting the COALESCE turns a whole installment's outstanding
into NULL and silently drops it from a `SUM`.

**`m_loan_transaction`** (`:2695`): `loan_id`, `office_id` NOT NULL,
`payment_detail_id`, **`is_reversed` BOOLEAN NOT NULL**, `external_id` unique,
`transaction_type_enum` SMALLINT NOT NULL, `transaction_date` DATE NOT NULL,
`amount` DECIMAL(19,6) NOT NULL, then the allocation columns
`principal_portion_derived`, `interest_portion_derived`,
`fee_charges_portion_derived`, `penalty_charges_portion_derived`,
`overpayment_portion_derived`, `unrecognized_income_portion`,
`outstanding_loan_balance_derived`, plus `submitted_on_date` NOT NULL,
`manually_adjusted_or_reversed`, `created_date`, `appuser_id`.

`amount` is the gross transaction; the `*_portion_derived` columns are how the
transaction processor split it across buckets. They should sum to `amount` for a
repayment, but that invariant is maintained in Java, not by a constraint.

**`m_loan_transaction_repayment_schedule_mapping`** (`:2737`) is the join between
the two: `loan_transaction_id`, `loan_repayment_schedule_id`, `amount`, and the
same four `*_portion_derived` columns. This is what makes "which payment cleared
installment 7" answerable. It is a genuine many-to-many — one payment can span
several installments and one installment can be cleared by several payments.

**`m_loan_charge`** (`:2236`): `loan_id`, `charge_id`, `is_penalty` BOOLEAN NOT
NULL, `charge_time_enum`, `due_for_collection_as_of_date`, `amount`,
`amount_paid_derived`, `amount_waived_derived`, `amount_writtenoff_derived`,
`amount_outstanding_derived` NOT NULL, `is_paid_derived`, `waived`, `is_active`.
`is_penalty` is the fee/penalty discriminator — the same table holds both.

### 2.4 Delinquency

All four created in `parts/0029_add_delinquency_buckets.xml`, Postgres variants:

| Table | Line | Notes |
|---|---|---|
| `m_delinquency_range` | 136 | `classification` VARCHAR(100) unique NOT NULL, `min_age_days` NOT NULL, `max_age_days` **nullable** |
| `m_delinquency_bucket` | 165 | `name` unique NOT NULL |
| `m_delinquency_bucket_mappings` | 188 | `delinquency_range_id`, `delinquency_bucket_id` |
| `m_loan_delinquency_tag_history` | 214 | `delinquency_range_id`, `loan_id`, `addedon_date` NOT NULL, `liftedon_date` nullable |

FKs at `parts/0029:245-254`; `m_product_loan.delinquency_bucket_id` added at
`parts/0029:255-263`. All four carry the newer audit quartet — `created_by`,
`created_on_utc`, `last_modified_by`, `last_modified_on_utc`, all NOT NULL — plus
`version`. That matters for seeding: these are not nullable, so seed rows need
real values.

`max_age_days` being nullable is the open-ended top range (`180+ days`). The
lookup honours it explicitly —
`fineract-loan/.../delinquency/service/DelinquencyWritePlatformServiceHelper.java:66-70`:

```java
if (delinquencyRange.getMaximumAgeDays() == null) { // Last Range in the Bucket
    if (delinquencyRange.getMinimumAgeDays() <= overdueDays) {
```

`m_loan_delinquency_tag_history` is an **open-interval history table**: the
current classification of a loan is the row with `liftedon_date IS NULL`. Reading
it without that filter returns every classification the loan has ever held and
double-counts loans.

### 2.5 Savings

| Table | Line | Notes |
|---|---|---|
| `m_savings_product` | 3942 | `name`/`short_name` unique, `deposit_type_enum` default 100, rates, posting period |
| `m_savings_account` | 3625 | see below |
| `m_savings_account_transaction` | 3838 | see below |

`m_savings_account` (`:3625`): `account_no` unique, `client_id` **nullable**,
`group_id`, `product_id`, `field_officer_id`, `status_enum` SMALLINT NOT NULL
default 300, `sub_status_enum`, `account_type_enum` default 1, `deposit_type_enum`
default 100, the lifecycle date/user pairs, currency, interest configuration,
`allow_overdraft` + `overdraft_limit`, and the rollups:
`total_deposits_derived`, `total_withdrawals_derived`,
`total_withdrawal_fees_derived`, `total_fees_charge_derived`,
`total_penalty_charge_derived`, `total_annual_fees_derived`,
`total_interest_earned_derived`, `total_interest_posted_derived`,
`total_overdraft_interest_derived`, `total_withhold_tax_derived`, and
**`account_balance_derived` DECIMAL(19,6) NOT NULL default 0** (`:3709`).

`account_balance_derived` is the balance to read for "savings balance over X".
It is a rollup maintained by the Java write path — see §5.

Note the field is `field_officer_id` on savings but `loan_officer_id` on loans.
Same concept, two names. Fineract's column naming is not consistent and the
locked decision to keep names verbatim means we inherit that.

`m_savings_account_transaction` (`:3838`): `savings_account_id`, `office_id` NOT
NULL, `payment_detail_id`, `transaction_type_enum` SMALLINT NOT NULL,
**`is_reversed` BOOLEAN NOT NULL**, `transaction_date` DATE NOT NULL, `amount`
NOT NULL, `overdraft_amount_derived`, `balance_end_date_derived`,
`balance_number_of_days_derived`, `running_balance_derived`,
`cumulative_balance_derived`, `created_date` NOT NULL, `is_manual`,
`release_id_of_hold_amount`, `ref_no` unique.

There is no signed amount and no debit/credit column — **direction is carried
entirely by `transaction_type_enum`.** `SUM(amount)` over savings transactions is
meaningless. The credit/debit mapping lives in Java at
`fineract-core/.../savings/SavingsAccountTransactionType.java:36-54` as a
`TransactionEntryType` on each constant, and is a strong argument for the
`r_savings_transaction_type` reference table (§7) carrying that direction.

### 2.6 FK closure (needed to insert, not to query)

| Table | Line | Why |
|---|---|---|
| `m_appuser` | 607 | every audit column (`created_by`, `submittedon_userid`, `appuser_id`, …) FKs here |
| `m_fund` | 1620 | `m_loan.fund_id` |
| `m_code` / `m_code_value` | 1127 / 1140 | `*_cv_id` columns: `gender_cv_id`, `loanpurpose_cv_id`, `writeoff_reason_cv_id`, `client_type_cv_id`, … |
| `m_organisation_currency` | 2919 | tenant currency allow-list |

`m_appuser` has NOT NULL `username`, `firstname`, `lastname`, `password`, `email`
and several NOT NULL booleans — a seed needs one real system user row before any
audited insert will land.

---

## 3. Entity diagram

```
                          ┌──────────────────┐
                          │    m_office      │
                          │  id, parent_id   │
                          │  hierarchy '.1.' │◄──┐ self-ref (materialised path)
                          └────────┬─────────┘   │
                                   │ 1        ───┘
                    ┌──────────────┼──────────────┐
                    │ *            │ *            │ *
             ┌──────▼──────┐  ┌────▼─────┐  ┌─────▼──────────────┐
             │  m_client   │  │ m_staff  │  │ m_loan_transaction │
             │ office_id   │  │office_id │  │ office_id (booking)│
             │ status_enum │  │is_loan_  │  └─────▲──────────────┘
             └──┬───────┬──┘  │ officer  │        │ *
        1       │       │  1  └────▲─────┘        │
    ┌───────────┘       └────────┐ │              │
    │ *                          │ * │ loan_officer_id
┌───▼───────────────────┐   ┌────▼───▼──────────────────┐
│  m_savings_account    │   │        m_loan             │
│  client_id (nullable) │   │  client_id  (NULLABLE!)   │
│  product_id           │   │  group_id                 │
│  status_enum          │   │  product_id ──────────────┼──► m_product_loan
│  account_balance_     │   │  fund_id ─────────────────┼──► m_fund
│    derived  ◄─rollup  │   │  loan_status_id           │    │
└───┬───────────────────┘   │  loan_type_enum           │    │ delinquency_bucket_id
    │ 1                     │  grace_on_arrears_ageing  │    ▼
    │                       │  ~40 *_derived  ◄─rollups │  ┌──────────────────┐
    │ *                     └──┬────────┬────────┬──────┘  │m_delinquency_    │
┌───▼────────────────────┐     │ 1      │ 1      │ 1       │    bucket        │
│ m_savings_account_     │     │        │        │         └────────┬─────────┘
│      transaction       │     │        │        │                  │ *
│ transaction_type_enum  │     │        │        │         ┌────────▼─────────┐
│   ⇒ direction (Java)   │     │        │        │         │m_delinquency_    │
│ is_reversed  ⚑         │     │        │        │         │ bucket_mappings  │
│ running_balance_derived│     │        │        │         └────────┬─────────┘
└────────────────────────┘     │        │        │                  │ *
                               │        │        │         ┌────────▼─────────┐
   ┌───────────────────────────┘        │        │         │m_delinquency_    │
   │ *                                  │ *      │ 0..1    │     range        │
┌──▼────────────────────────┐  ┌────────▼─────┐  │         │ classification   │
│ m_loan_repayment_schedule │  │m_loan_charge │  │         │ min_age_days     │
│ installment, duedate      │  │ is_penalty   │  │         │ max_age_days NULL│
│ *_amount                  │  │ amount_out.. │  │         └────────▲─────────┘
│ *_completed_derived       │  └──────────────┘  │                  │ *
│ *_writtenoff_derived      │                    │         ┌────────┴─────────┐
│ *_waived_derived (no      │        ┌───────────▼──────┐  │m_loan_delinquency│
│   principal_waived!)      │        │m_loan_arrears_   │  │  _tag_history    │
│ completed_derived ⚑       │        │     aging        │  │ loan_id          │
└──────────┬────────────────┘        │ loan_id (PK=FK)  │  │ addedon_date     │
           │ *                       │ *_overdue_derived│  │ liftedon_date ⚑  │
           │                         │ overdue_since_   │  └──────────────────┘
┌──────────▼──────────────────────┐  │   date_derived   │
│ m_loan_transaction_repayment_   │  │ ⚠ BATCH-FILLED   │
│      schedule_mapping           │  └──────────────────┘
│ loan_transaction_id             │
│ loan_repayment_schedule_id      │        ⚑ = filter that is easy to forget
│ amount, *_portion_derived       │            and silently changes the answer
└──────────▲──────────────────────┘
           │ *
┌──────────┴──────────────────────┐        m_currency ─── currency_code (VARCHAR(3),
│      m_loan_transaction         │                        denormalised onto m_loan,
│ transaction_type_enum (10=ACCRUAL│                       m_savings_account, products —
│   is NOT cash)                  │                        joined by code, not by id)
│ is_reversed ⚑                   │
│ amount + *_portion_derived      │        m_appuser ──── every audit column
└─────────────────────────────────┘        m_code_value ─ every *_cv_id column
```

---

## 4. Loan lifecycle

### 4.1 The status values

`fineract-core/src/main/java/org/apache/fineract/portfolio/loanaccount/domain/LoanStatus.java:26-39`.
Twelve constants: eleven real statuses plus an `INVALID(0)` sentinel that
`fromInt` returns for any unrecognised value (`:44-63`) and that should never be
persisted.

| ID | Constant | Meaning |
|---|---|---|
| 0 | `INVALID` | sentinel; not a real state |
| 100 | `SUBMITTED_AND_PENDING_APPROVAL` | application entered, awaiting decision |
| 200 | `APPROVED` | approved, not yet disbursed |
| 300 | `ACTIVE` | disbursed and running |
| 303 | `TRANSFER_IN_PROGRESS` | mid client-transfer between offices |
| 304 | `TRANSFER_ON_HOLD` | transfer paused |
| 400 | `WITHDRAWN_BY_CLIENT` | client withdrew before approval |
| 500 | `REJECTED` | institution rejected the application |
| 600 | `CLOSED_OBLIGATIONS_MET` | paid off normally |
| 601 | `CLOSED_WRITTEN_OFF` | written off as a loss |
| 602 | `CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT` | closed, balance moved to a new loan |
| 700 | `OVERPAID` | repaid more than owed; credit balance outstanding |

`isClosed()` (`:94-96`) means 600, 601 **or** 602. It does **not** include 700 —
an overpaid loan is settled but not "closed" in Fineract's vocabulary, and a
question like "how many loans closed last quarter" needs to state which it means.

`isActiveOrAwaitingApprovalOrDisbursal()` (`:118-120`) = 100, 200 or 300. That is
the "still a live application or account" predicate.

### 4.2 State diagram

```
                        ┌─────────────────────────────────┐
                        │ 100 SUBMITTED_AND_PENDING_      │
       apply ──────────►│      APPROVAL                   │
                        └───┬──────────┬─────────┬────────┘
                   approve  │   reject │  withdraw│
                            ▼          ▼          ▼
                     ┌────────────┐ ┌──────────┐ ┌────────────────────┐
                     │200 APPROVED│ │500       │ │400 WITHDRAWN_BY_   │
                     └──┬──────┬──┘ │ REJECTED │ │     CLIENT         │
                        │      │    └──────────┘ └────────────────────┘
             undo appr. │      │ disburse            (terminal)
                        │      ▼
                        │  ┌──────────────────────────────────┐
                        │  │           300 ACTIVE             │
                        │  │                                  │
                        │  │  ⚠ arrears / delinquency live    │
                        │  │    HERE and only here.           │
                        │  │    They are NOT a status.        │
                        │  └──┬────┬─────┬──────┬─────┬───────┘
                        │     │    │     │      │     │
              ┌─────────┘     │    │     │      │     └──── transfer client
              │               │    │     │      │              │
              ▼               │    │     │      │              ▼
        (back to 100)         │    │     │      │      ┌───────────────────┐
                              │    │     │      │      │303 TRANSFER_IN_   │
             obligations met  │    │     │      │      │     PROGRESS      │
                              ▼    │     │      │      └────┬──────────┬───┘
                  ┌──────────────┐ │     │      │           │          │
                  │600 CLOSED_   │ │     │      │      hold │          │ complete
                  │ OBLIGATIONS_ │ │     │      │           ▼          │
                  │     MET      │ │     │      │   ┌───────────────┐  │
                  └──────────────┘ │     │      │   │304 TRANSFER_  │  │
                                   │     │      │   │    ON_HOLD    │  │
                        write off  │     │      │   └───────┬───────┘  │
                                   ▼     │      │           │          │
                     ┌───────────────┐   │      │           └──────────┤
                     │601 CLOSED_    │   │      │                      ▼
                     │  WRITTEN_OFF  │   │      │              (back to 300)
                     └───────┬───────┘   │      │
                             │           │      │ overpay
                  recovery   │           │      ▼
                  repayment  │           │  ┌──────────────┐
                  (type 8)   │           │  │700 OVERPAID  │
                  stays 601 ─┘           │  └──────┬───────┘
                                         │         │ credit balance refund
                        reschedule out   │         ▼
                                         ▼    (back to 600)
                            ┌──────────────────────────┐
                            │602 CLOSED_RESCHEDULE_    │
                            │   OUTSTANDING_AMOUNT     │
                            └──────────────────────────┘
```

### 4.3 There is no OVERDUE status

This is the fact that most changes how the agent must be built.

Look at the table in §4.1 again: **there is no `OVERDUE`, no `IN_ARREARS`, no
`DELINQUENT`, no `NPA` status.** A loan that is 200 days late is
`loan_status_id = 300`, exactly like a loan that is perfectly current.

Fineract's own arrears job proves it —
`LoanArrearsAgeingUpdateHandler.java:136`:

```java
insertSqlStatementBuilder.append(" WHERE ml.loan_status_id = 300 ");// active
```

The arrears population job's *entire* candidate set is status 300. Lateness is
then derived from the schedule, not from the status.

Practical consequences:

1. `WHERE loan_status_id = 'OVERDUE'` — or any variant — is unanswerable. If the
   LLM emits it, the tool layer must reject it rather than return zero rows. Zero
   rows reads as "no loans in arrears", which is a confidently wrong answer.
2. "Show me overdue loans" must be translated into either an arrears computation
   (§6) or a `m_loan_arrears_aging` lookup, both of which additionally filter
   `loan_status_id = 300`.
3. `m_loan.is_npa` BOOLEAN NOT NULL (`:2181`) exists and looks like it should
   help. It is a separate NPA-provisioning flag on its own schedule, not an
   arrears indicator. Do not use it to answer arrears questions.

The design implication for Stage 3: `query_loans` should not accept a free-form
status string. It should accept an enum constrained to the twelve real values,
and arrears should be a **separate boolean/threshold parameter** — because
arrears is orthogonal to status, not a value of it.

---

## 5. The `_derived` rollup pattern

`m_loan` carries roughly forty `*_derived` columns — 30 in the baseline
(`parts/0001_initial_schema.xml:1995-2212`) plus later additions. They come in
families:

```
principal_{disbursed,repaid,writtenoff,outstanding}_derived
interest_{charged,repaid,waived,writtenoff,outstanding}_derived
fee_charges_{charged,repaid,waived,writtenoff,outstanding}_derived
penalty_charges_{charged,repaid,waived,writtenoff,outstanding}_derived
total_{expected_repayment,repayment,expected_costofloan,costofloan,
       waived,writtenoff,outstanding,overpaid,recovered}_derived
guarantee_amount_derived, total_charges_due_at_disbursement_derived
principal_adjustments_derived   (parts/0057)
fee_adjustments_derived         (fineract-loan/1017)
```

Same pattern on `m_loan_repayment_schedule` (`:2498`), `m_loan_charge` (`:2236`),
`m_savings_account` (`:3625`, ending in `account_balance_derived`),
`m_loan_transaction` (`*_portion_derived`), and
`m_loan_transaction_repayment_schedule_mapping` (`:2737`).

### 5.1 Who maintains them

**Fineract's Java write path, and nothing else.** Grepping the changelogs for
`createTrigger`, `createProcedure` or generated-column DDL on these tables finds
nothing. They are ordinary nullable or defaulted numeric columns that the domain
layer recomputes and re-persists inside the command transaction.

This is a deliberate, defensible choice on Fineract's part: the allocation of a
repayment across principal / interest / fees / penalties depends on the product's
transaction-processing strategy, which is pluggable Java. It cannot be expressed
as a trigger, so the rollups are written by the same code that decides the
allocation.

### 5.2 Consequence 1 — the seed must be consistent by construction

We are not running Fineract. Nothing will recompute these columns for us. So for
every loan the seed creates, the following must hold at insert time, because
nothing will ever repair it:

- `m_loan.principal_repaid_derived` = Σ `principal_portion_derived` over that
  loan's non-reversed transactions
- `m_loan.principal_outstanding_derived` = `principal_disbursed_derived` −
  `principal_repaid_derived` − `principal_writtenoff_derived`
- `m_loan.total_outstanding_derived` = the four `*_outstanding_derived` summed
- `m_loan_repayment_schedule.*_completed_derived` per installment = Σ the matching
  `*_portion_derived` in `m_loan_transaction_repayment_schedule_mapping`
- `m_loan_repayment_schedule.completed_derived` = true **iff** that installment's
  outstanding is zero — and `obligations_met_on_date` set when it is
- `m_savings_account.account_balance_derived` = Σ credits − Σ debits over
  non-reversed transactions, by `transaction_type_enum` direction
- `m_savings_account_transaction.running_balance_derived` = the running total in
  `transaction_date`, `id` order

The practical approach for Stage 1: generate transactions first, then **compute**
the rollups from them rather than writing both independently. Two independently
authored numbers will disagree.

This is also exactly what the Stage 5 eval suite must check first. An eval that
computes expected answers in plain SQL against an internally inconsistent seed
measures nothing — it will agree with a broken agent. A handful of
consistency-invariant assertions belong in CI ahead of the agent evals.

### 5.3 Consequence 2 — the agent's DB user must be read-only

Because the invariants are maintained by application code we are not running, any
write from our service leaves the database permanently inconsistent, with no
process to detect or repair it. A single stray `UPDATE m_loan SET ...` silently
corrupts every subsequent answer.

Add to that the ordinary reason — the agent constructs queries from LLM output,
so SQL injection and prompt injection converge on the same attack surface — and a
read-only role is not a hardening extra, it is the correct default:

```sql
CREATE ROLE agent_ro LOGIN PASSWORD '...';
GRANT CONNECT ON DATABASE fineract TO agent_ro;
GRANT USAGE ON SCHEMA public TO agent_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO agent_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO agent_ro;
```

Flyway migrations run as the owner; the application runs as `agent_ro`
(Stage 6). Worth also setting `default_transaction_read_only = on` on the role so
the guarantee holds even if a grant is missed.

---

## 6. Arrears and delinquency

These are two different things and Fineract implements them in two different
places. Conflating them is the most likely source of a confidently wrong answer.

- **Arrears** is arithmetic: *how much is past due, and since when.*
- **Delinquency** is classification: *which named bucket does that age fall into.*

### 6.1 Arrears — the real SQL

`fineract-loan/src/main/java/org/apache/fineract/portfolio/loanaccount/jobs/updateloanarrearsageing/LoanArrearsAgeingUpdateHandler.java`.

`updateLoanArrearsAgeingDetailsForAllLoans()` (`:64-77`) first **truncates the
whole table** (`:53-55`) and then repopulates it:

```java
private void truncateLoanArrearsAgingDetails() {
    jdbcTemplate.execute("truncate table m_loan_arrears_aging");
}
```

`buildQueryForInsertAgeingDetails()` (`:112-148`) assembles this INSERT. The four
overdue expressions are at `:114-120`; reassembled and formatted, the statement is:

```sql
INSERT INTO m_loan_arrears_aging(
    loan_id, principal_overdue_derived, interest_overdue_derived,
    fee_charges_overdue_derived, penalty_charges_overdue_derived,
    total_overdue_derived, overdue_since_date_derived)
SELECT ml.id AS loanId,
    SUM(COALESCE(mr.principal_amount,0)
        - COALESCE(mr.principal_completed_derived,0)
        - COALESCE(mr.principal_writtenoff_derived,0))          AS principal_overdue_derived,
    SUM(COALESCE(mr.interest_amount,0)
        - COALESCE(mr.interest_writtenoff_derived,0)
        - COALESCE(mr.interest_waived_derived,0)
        - COALESCE(mr.interest_completed_derived,0))            AS interest_overdue_derived,
    SUM(COALESCE(mr.fee_charges_amount,0)
        - COALESCE(mr.fee_charges_writtenoff_derived,0)
        - COALESCE(mr.fee_charges_waived_derived,0)
        - COALESCE(mr.fee_charges_completed_derived,0))         AS fee_charges_overdue_derived,
    SUM(COALESCE(mr.penalty_charges_amount,0)
        - COALESCE(mr.penalty_charges_writtenoff_derived,0)
        - COALESCE(mr.penalty_charges_waived_derived,0)
        - COALESCE(mr.penalty_charges_completed_derived,0))     AS penalty_charges_overdue_derived,
    <the four sums added together>                              AS total_overdue_derived,
    MIN(mr.duedate)                                             AS overdue_since_date_derived
FROM m_loan ml
INNER JOIN m_loan_repayment_schedule mr ON mr.loan_id = ml.id
LEFT JOIN m_product_loan_recalculation_details prd ON prd.product_id = ml.product_id
WHERE ml.loan_status_id = 300                                     -- active  (:136)
  AND mr.completed_derived IS FALSE                               --         (:140)
  AND mr.duedate < (DATE '<business date>'
                    - COALESCE(ml.grace_on_arrears_ageing, 0) * INTERVAL '1 day')  -- (:141-143)
  AND (prd.arrears_based_on_original_schedule = false
       OR prd.arrears_based_on_original_schedule IS NULL)         --         (:144-145)
GROUP BY ml.id
```

(The date arithmetic is emitted by `sqlGenerator.subDate(...)`, so the exact
rendering is dialect-specific; the semantics above are the Postgres form.)

Five things to take from this:

1. **`MIN(mr.duedate)` over unpaid installments is the arrears anchor.** Days in
   arrears is `business_date − MIN(unpaid duedate)`.
2. **It is not "days since last payment."** A borrower who pays something every
   week but never enough to clear installment #3 has zero days since last payment
   and a growing arrears age. The two metrics diverge exactly on the
   partially-paying borrowers that arrears reporting exists to find.
3. **`grace_on_arrears_ageing` is per loan** (`m_loan:2180`, SMALLINT nullable)
   and shifts the threshold, so grace is applied *before* the loan appears at all
   — a loan with 10 days grace and 5 days late does not appear in the table.
4. **`completed_derived IS FALSE`** is what makes "unpaid" mean unpaid. Without
   it, cleared installments contribute nothing to the sums but *do* drag
   `MIN(duedate)` back to installment #1, aging every loan to its origination.
5. Loans on products with `arrears_based_on_original_schedule = true` are excluded
   here and handled by a second path (`:150-197`) reading
   `m_loan_repayment_schedule_history` at max `version`. For the portfolio we're
   seeding, keeping every product on the standard path removes this branch
   entirely — a scope decision worth stating rather than discovering later.

Our own arrears query, derived from the same source and not dependent on the
batch table being populated:

```sql
SELECT ml.id,
       MIN(mr.duedate)                                  AS overdue_since,
       :business_date - MIN(mr.duedate)                 AS days_in_arrears,
       SUM(COALESCE(mr.principal_amount,0)
           - COALESCE(mr.principal_completed_derived,0)
           - COALESCE(mr.principal_writtenoff_derived,0)) AS principal_overdue
FROM m_loan ml
JOIN m_loan_repayment_schedule mr ON mr.loan_id = ml.id
WHERE ml.loan_status_id = 300
  AND mr.completed_derived IS FALSE
  AND mr.duedate < :business_date - COALESCE(ml.grace_on_arrears_ageing,0) * INTERVAL '1 day'
GROUP BY ml.id
HAVING :business_date - MIN(mr.duedate) > :threshold_days
```

### 6.2 Delinquency — classification

`fineract-loan/.../delinquency/service/LoanDelinquencyDomainServiceImpl.java:50-140`
computes the same age in Java rather than SQL: it walks
`loan.getRepaymentScheduleInstallments()`, skips installments where
`isObligationsMet()`, takes the oldest whose due date is before the business date
(`:78-95`), and then at `:122-131`:

```java
overdueDays = DateUtils.getDifferenceInDays(overdueSinceDate, businessDate);
if (overdueDays < 0) { overdueDays = 0L; }
...
LocalDate delinquentStartDate = overdueSinceDate.plusDays(graceDays.longValue());
```

Note `:68-70`: loans that are pending approval, approved, closed or overpaid
return `CollectionData.template()` — an empty result. Only ACTIVE loans have
delinquency, same as arrears.

That `overdueDays` number is then matched against the product's bucket —
`DelinquencyWritePlatformServiceHelper.java:55-86`. Ranges are sorted by
`minAgeDays` (`:64`), then the **first** matching range wins (`break` at `:71`
and `:78`), with the null-`maxAgeDays` range treated as open-ended. If
`overdueDays <= 0` the loan's tag is cleared (`:58-61`).

So: **arrears is the number, delinquency is the label put on the number**, and
the label depends on which bucket the loan's *product* is attached to. Two loans
that are both 45 days late can carry different classifications if their products
use different buckets. "How many delinquent loans" is therefore ambiguous in a
way "how many loans over 60 days in arrears" is not, and the agent should prefer
the arrears framing and say which it used.

### 6.3 Business date, not `CURRENT_DATE`

Fineract has a configurable logical business date.
`fineract-core/.../database/DatabaseSpecificSQLGenerator.java:114-122`:

```java
public String currentBusinessDate() {
    if (databaseTypeResolver.isMySQL()) {
        return format("DATE('%s')", DateUtils.getBusinessLocalDate().format(...));
    } else if (databaseTypeResolver.isPostgreSQL()) {
        return format("DATE '%s'", DateUtils.getBusinessLocalDate().format(...));
    }
    ...
}
```

The date is **interpolated as a literal from the JVM**, not read as
`CURRENT_DATE` by Postgres. It is stored in `m_business_date`
(`parts/0015_add_business_date.xml:26-44`) keyed by `type`, and gated by the
`enable_business_date` configuration flag inserted at `:46-55` — **disabled by
default**, in which case the business date falls back to the tenant date.

For us: the agent must have **one** notion of "today" and must state it. Options
are (a) read `m_business_date`, matching Fineract; (b) inject a fixed date via
config, making evals deterministic; (c) use `CURRENT_DATE`, making today's answer
depend on when it runs.

(b) is the right call for a portfolio project with an eval suite — a seeded
portfolio plus a pinned business date means "loans over 60 days in arrears" has
one correct answer forever, so eval failures are agent regressions rather than
calendar drift. Seed `m_business_date` with the same pinned value so the schema
stays honest, and surface the date in every answer so it is never ambiguous which
"today" was used.

---

## 7. The `r_*` reference tables

### 7.1 Correction to the premise, and why the decision still stands

The locked decision says Fineract keeps enum meanings only in Java enums. **That
is not quite right, and the real situation is a stronger argument for the same
decision.**

Fineract *does* ship an enum lookup table: `r_enum_value`, created at
`parts/0001_initial_schema.xml:4834-4850`, shape
`(enum_name, enum_id, enum_message_property, enum_value, enum_type)` with a
composite PK on `(enum_name, enum_id)`. It is seeded with 159 rows starting at
`parts/0002_initial_data.xml:10826`, and Fineract's own reports join to it —
`parts/0002_initial_data.xml:12504`:

```sql
left join r_enum_value st on st.enum_name = "loan_status_id" and st.enum_id = l.loan_status_id
```

The problem is that it is a **stale, partial, and in one case wrong** copy of the
Java enums. It sits in `0002_initial_data.xml`, which is baseline data under the
`initial_switch` context, and nothing has kept it current:

| `enum_name` | Rows seeded | Java constants | Status |
|---|---|---|---|
| `loan_status_id` | 10 | 12 | missing 303, 304 |
| `loan_transaction_type_enum` | 19 | 47 | missing 20–47 (28 types) |
| `loan_type_enum` | 2 | 6 | missing 0, 3 (JLG), 4 (GLIM), 5 (GSIM) |
| `status_enum` (client) | 4 | 8 | missing 303, 304, 700, 800 |
| `savings_transaction_type_enum` | 16 | 21 | missing 10, 18, 20, 21 — **and `enum_id = 19` is labelled `WITHHOLD_TAX`, but Java says 18 is WITHHOLD_TAX and 19 is ESCHEAT** |
| savings account status | 0 | 11 | not seeded at all |
| `deposit_type_enum` | 0 | 5 | not seeded at all |

The savings row is an outright bug: joining `r_enum_value` to
`m_savings_account_transaction.transaction_type_enum` returns "WITHHOLD_TAX" for
escheat transactions and NULL for actual withholding tax. Anything downstream of
that join is wrong, not merely incomplete.

So the decision to add our own `r_*` tables is correct — but the defensible
rationale is not "Fineract has no such table". It is:

> Fineract ships `r_enum_value`, but it is baseline data frozen at the
> Flyway→Liquibase switch, covering 10 of 12 loan statuses, 19 of 47 loan
> transaction types, and mislabelling two savings transaction types. Grounding an
> agent on it would produce answers that are wrong in a way that looks right. We
> therefore derive purpose-built reference tables directly from the Java enums,
> which are the actual source of truth, and enforce agreement in CI.

That is a better interview answer than the original premise, because it shows the
alternative was examined rather than assumed away.

### 7.2 Shape and the no-FK decision

Shape stays `(id INT PRIMARY KEY, code VARCHAR, description VARCHAR)` — simpler
than `r_enum_value`'s `(enum_name, enum_id)` composite, one table per enum, so
the LLM sees seven small well-named tables rather than one polymorphic one it has
to filter by string.

**No FK from the `m_*` `_enum` columns to the `r_*` tables.** Reasons:

1. Fineract's write path does not know these tables exist. A real Fineract
   instance writing `loan_status_id = 303` against an FK that lacks 303 would fail
   an insert that upstream considers valid. Our tables must not be able to break
   the system they describe.
2. `_enum` columns are SMALLINT/INT, and some carry `0`/`INVALID` sentinels and
   historical values no longer in the Java enum. An FK would reject legitimately
   stored history.
3. It preserves the locked decision to keep Fineract's model verbatim. Adding
   constraints Fineract does not have is a change to the model, dressed up as
   documentation.

**A CI check instead** — a test that fails the build when any distinct `_enum`
value in the seeded data lacks a matching `r_*` row, and, separately, when the
`r_*` seed disagrees with the Java enum it mirrors:

```sql
SELECT DISTINCT l.loan_status_id
FROM m_loan l LEFT JOIN r_loan_status r ON r.id = l.loan_status_id
WHERE r.id IS NULL;   -- must return zero rows
```

This catches the same class of bug as an FK, at build time rather than insert
time, without constraining a schema we do not own. It is also the check that
would have caught `r_enum_value`'s savings mislabel.

### 7.3 Values to seed

**`r_loan_status`** — `LoanStatus.java:28-39`

| id | code | description |
|---|---|---|
| 0 | INVALID | Invalid / unrecognised status sentinel |
| 100 | SUBMITTED_AND_PENDING_APPROVAL | Submitted and awaiting approval |
| 200 | APPROVED | Approved, not yet disbursed |
| 300 | ACTIVE | Active — disbursed and being repaid |
| 303 | TRANSFER_IN_PROGRESS | Client transfer in progress |
| 304 | TRANSFER_ON_HOLD | Client transfer on hold |
| 400 | WITHDRAWN_BY_CLIENT | Withdrawn by client before approval |
| 500 | REJECTED | Rejected by the institution |
| 600 | CLOSED_OBLIGATIONS_MET | Closed — obligations met |
| 601 | CLOSED_WRITTEN_OFF | Closed — written off |
| 602 | CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT | Closed — outstanding rescheduled |
| 700 | OVERPAID | Overpaid — credit balance outstanding |

**`r_loan_transaction_type`** — `LoanTransactionType.java:26-85`. 47 constants;
**id 11 does not exist** (removed upstream) — the gap is real and the seed must
not invent a value for it.

| id | code | | id | code |
|---|---|---|---|---|
| 0 | INVALID | | 24 | CHARGE_REFUND |
| 1 | DISBURSEMENT | | 25 | CHARGEBACK |
| 2 | REPAYMENT | | 26 | CHARGE_ADJUSTMENT |
| 3 | CONTRA | | 27 | CHARGE_OFF |
| 4 | WAIVE_INTEREST | | 28 | DOWN_PAYMENT |
| 5 | REPAYMENT_AT_DISBURSEMENT | | 29 | REAGE |
| 6 | WRITEOFF | | 30 | REAMORTIZE |
| 7 | MARKED_FOR_RESCHEDULING | | 31 | INTEREST_PAYMENT_WAIVER |
| 8 | RECOVERY_REPAYMENT | | 32 | ACCRUAL_ACTIVITY |
| 9 | WAIVE_CHARGES | | 33 | INTEREST_REFUND |
| 10 | ACCRUAL | | 34 | ACCRUAL_ADJUSTMENT |
| *(11 absent)* | — | | 35 | CAPITALIZED_INCOME |
| 12 | INITIATE_TRANSFER | | 36 | CAPITALIZED_INCOME_AMORTIZATION |
| 13 | APPROVE_TRANSFER | | 37 | CAPITALIZED_INCOME_ADJUSTMENT |
| 14 | WITHDRAW_TRANSFER | | 38 | CONTRACT_TERMINATION |
| 15 | REJECT_TRANSFER | | 39 | CAPITALIZED_INCOME_AMORTIZATION_ADJUSTMENT |
| 16 | REFUND | | 40 | BUY_DOWN_FEE |
| 17 | CHARGE_PAYMENT | | 41 | BUY_DOWN_FEE_ADJUSTMENT |
| 18 | REFUND_FOR_ACTIVE_LOAN | | 42 | BUY_DOWN_FEE_AMORTIZATION |
| 19 | INCOME_POSTING | | 43 | BUY_DOWN_FEE_AMORTIZATION_ADJUSTMENT |
| 20 | CREDIT_BALANCE_REFUND | | 44 | DISCOUNT_FEE |
| 21 | MERCHANT_ISSUED_REFUND | | 45 | DISCOUNT_FEE_AMORTIZATION |
| 22 | PAYOUT_REFUND | | 46 | DISCOUNT_FEE_ADJUSTMENT |
| 23 | GOODWILL_CREDIT | | 47 | DISCOUNT_FEE_AMORTIZATION_ADJUSTMENT |

Worth carrying an extra `is_cash_movement` boolean on this table (not part of the
locked `(id, code, description)` shape, so flag it as a deliberate extension if
kept). Types 10, 32, 34, 36, 39, 42, 45, 47 are accounting entries, not money —
see §9.

**`r_loan_type`** — `AccountType.java:31-36` (this is `m_loan.loan_type_enum`)

| id | code | description |
|---|---|---|
| 0 | INVALID | Invalid sentinel |
| 1 | INDIVIDUAL | Individual loan |
| 2 | GROUP | Group loan |
| 3 | JLG | Joint liability group loan, held in a group context |
| 4 | GLIM | Group loan with individual monitoring |
| 5 | GSIM | Group savings with individual monitoring |

**`r_client_status`** — `ClientStatus.java:28-35` (this is `m_client.status_enum`)

| id | code | description |
|---|---|---|
| 0 | INVALID | Invalid sentinel |
| 100 | PENDING | Pending activation |
| 300 | ACTIVE | Active |
| 303 | TRANSFER_IN_PROGRESS | Transfer in progress |
| 304 | TRANSFER_ON_HOLD | Transfer on hold |
| 600 | CLOSED | Closed |
| 700 | REJECTED | Rejected |
| 800 | WITHDRAWN | Withdrawn |

Client status uses 700 = REJECTED and 800 = WITHDRAWN. Loan status uses 500 =
REJECTED and 400 = WITHDRAWN. **The same word maps to different numbers on the
two entities** — a strong reason for separate reference tables rather than one
shared status table, and a plausible LLM error to guard against.

**`r_savings_account_status`** — `SavingsAccountStatusType.java:29-39`

| id | code | description |
|---|---|---|
| 0 | INVALID | Invalid sentinel |
| 100 | SUBMITTED_AND_PENDING_APPROVAL | Submitted and awaiting approval |
| 200 | APPROVED | Approved, not yet activated |
| 300 | ACTIVE | Active |
| 303 | TRANSFER_IN_PROGRESS | Transfer in progress |
| 304 | TRANSFER_ON_HOLD | Transfer on hold |
| 400 | WITHDRAWN_BY_APPLICANT | Withdrawn by applicant |
| 500 | REJECTED | Rejected |
| 600 | CLOSED | Closed |
| 700 | PRE_MATURE_CLOSURE | Closed before maturity |
| 800 | MATURED | Matured |

**`r_savings_transaction_type`** — `SavingsAccountTransactionType.java:35-54`.
The `TransactionEntryType` in the third constructor argument gives the direction;
carry it as a column, because without it `m_savings_account_transaction.amount`
cannot be aggregated at all.

| id | code | direction | description |
|---|---|---|---|
| 0 | INVALID | — | Invalid sentinel |
| 1 | DEPOSIT | CREDIT | Deposit |
| 2 | WITHDRAWAL | DEBIT | Withdrawal |
| 3 | INTEREST_POSTING | CREDIT | Interest posted to the account |
| 4 | WITHDRAWAL_FEE | DEBIT | Fee charged on withdrawal |
| 5 | ANNUAL_FEE | DEBIT | Annual account fee |
| 6 | WAIVE_CHARGES | — | Charge waived |
| 7 | PAY_CHARGE | DEBIT | Charge paid |
| 8 | DIVIDEND_PAYOUT | CREDIT | Dividend paid out |
| 10 | ACCRUAL | — | Accrual entry, not cash |
| 12 | INITIATE_TRANSFER | — | Transfer initiated |
| 13 | APPROVE_TRANSFER | — | Transfer approved |
| 14 | WITHDRAW_TRANSFER | — | Transfer withdrawn |
| 15 | REJECT_TRANSFER | — | Transfer rejected |
| 16 | WRITTEN_OFF | — | Written off |
| 17 | OVERDRAFT_INTEREST | DEBIT | Interest charged on overdraft |
| 18 | WITHHOLD_TAX | DEBIT | Tax withheld |
| 19 | ESCHEAT | DEBIT | Escheated to the state |
| 20 | AMOUNT_HOLD | DEBIT | Amount placed on hold |
| 21 | AMOUNT_RELEASE | CREDIT | Held amount released |

Ids 9 and 11 do not exist. Note again that **18/19 are inverted in Fineract's own
`r_enum_value` seed** — this table is the corrected version, and the CI check
should assert it against the Java enum.

**`r_deposit_type`** — `DepositAccountType.java:30-34` (this is
`deposit_type_enum` on both `m_savings_product` and `m_savings_account`)

| id | code | description |
|---|---|---|
| 0 | INVALID | Invalid sentinel |
| 100 | SAVINGS_DEPOSIT | Savings account |
| 200 | FIXED_DEPOSIT | Fixed deposit |
| 300 | RECURRING_DEPOSIT | Recurring deposit |
| 400 | CURRENT_DEPOSIT | Current account |

Both columns default to 100 (`m_savings_product:3953`, `m_savings_account:3649`).
If the seed only creates ordinary savings, everything is 100 — but the column
must still be filtered on, because "savings balance" over a fixed deposit means
something different from a passbook balance.

---

## 8. Target questions mapped to joins

The four questions below are the acceptance targets. Each is written as the join
path the corresponding Stage 3 tool must produce.

### 8.1 "Which loans are more than 60 days in arrears?" → `get_arrears_summary`

```sql
SELECT c.display_name, o.name AS office, l.account_no,
       MIN(s.duedate)                    AS overdue_since,
       :business_date - MIN(s.duedate)   AS days_in_arrears,
       SUM(COALESCE(s.principal_amount,0)
           - COALESCE(s.principal_completed_derived,0)
           - COALESCE(s.principal_writtenoff_derived,0)) AS principal_overdue
FROM m_loan l
JOIN m_client c ON c.id = l.client_id
JOIN m_office o ON o.id = c.office_id
JOIN m_loan_repayment_schedule s ON s.loan_id = l.id
WHERE l.loan_status_id = 300
  AND s.completed_derived IS FALSE
  AND s.duedate < :business_date - COALESCE(l.grace_on_arrears_ageing,0) * INTERVAL '1 day'
GROUP BY c.display_name, o.name, l.account_no
HAVING :business_date - MIN(s.duedate) > 60
ORDER BY days_in_arrears DESC
```

Traps: 60 days is a `HAVING` on the aggregate, not a `WHERE` on `duedate` —
filtering `duedate < business_date - 60` in the `WHERE` clause instead answers a
different and wrong question (it drops recent installments from the sums while
still reporting the loan). The inner `JOIN m_client` silently excludes group
loans; that is a decision to state, not a default to inherit (§9.4).

### 8.2 "Total outstanding principal by office" → `get_office_totals`

```sql
SELECT o.id, o.name,
       COUNT(DISTINCT l.id)                        AS active_loans,
       SUM(l.principal_outstanding_derived)        AS outstanding_principal
FROM m_office o
JOIN m_client c ON c.office_id = o.id
JOIN m_loan   l ON l.client_id = c.id
WHERE l.loan_status_id = 300
GROUP BY o.id, o.name
ORDER BY outstanding_principal DESC
```

`m_loan` has no `office_id`, so the office arrives via the client (§2.3). This is
the per-office ("this branch only") reading. For the hierarchical reading —
"Eastern region including its branches" — the shape is:

```sql
FROM m_office root
JOIN m_office o ON o.hierarchy LIKE root.hierarchy || '%'
JOIN m_client c ON c.office_id = o.id
JOIN m_loan   l ON l.client_id = c.id
WHERE root.id = :office_id AND l.loan_status_id = 300
```

Both are correct answers to different questions. The tool should take an explicit
`include_sub_offices` flag rather than guessing, and the answer should say which
it used.

### 8.3 "Clients with more than one active loan" → `get_client_portfolio`

```sql
SELECT c.id, c.display_name, o.name AS office,
       COUNT(*)                             AS active_loans,
       SUM(l.principal_outstanding_derived) AS outstanding_principal
FROM m_client c
JOIN m_office o ON o.id = c.office_id
JOIN m_loan   l ON l.client_id = c.id AND l.loan_status_id = 300
WHERE c.status_enum = 300
GROUP BY c.id, c.display_name, o.name
HAVING COUNT(*) > 1
ORDER BY active_loans DESC
```

Note the two different 300s: `c.status_enum = 300` is `ClientStatus.ACTIVE`,
`l.loan_status_id = 300` is `LoanStatus.ACTIVE`. Same number, different enums,
different tables. Coincidence, not a shared vocabulary.

### 8.4 "Clients with an active loan and savings balance over X" → `get_client_portfolio`

```sql
SELECT c.id, c.display_name, o.name AS office,
       COUNT(DISTINCT l.id)                   AS active_loans,
       SUM(DISTINCT sa.account_balance_derived) AS savings_balance
FROM m_client c
JOIN m_office o          ON o.id = c.office_id
JOIN m_loan   l          ON l.client_id = c.id AND l.loan_status_id = 300
JOIN m_savings_account sa ON sa.client_id = c.id
                          AND sa.status_enum = 300
                          AND sa.deposit_type_enum = 100
GROUP BY c.id, c.display_name, o.name
HAVING SUM(DISTINCT sa.account_balance_derived) > :threshold
```

This is the fan-out trap: a client with 2 active loans and 2 savings accounts
produces 4 rows. `SUM(sa.account_balance_derived)` over that join double-counts
the savings. `SUM(DISTINCT ...)` is a fragile fix — it also collapses two
accounts that genuinely hold the same balance. The correct shape aggregates
savings in a subquery first:

```sql
JOIN (SELECT client_id, SUM(account_balance_derived) AS bal
      FROM m_savings_account
      WHERE status_enum = 300 AND deposit_type_enum = 100
      GROUP BY client_id) s ON s.client_id = c.id
```

This is worth an explicit eval case in Stage 5: fan-out double-counting is a
mistake an LLM makes readily and one that produces a plausible-looking number
rather than an error.

---

## 9. Known traps

### 9.1 `is_reversed` is a flag, not a delete

`m_loan_transaction.is_reversed` (`:2706`) and
`m_savings_account_transaction.is_reversed` (`:3852`) are both BOOLEAN NOT NULL.
Reversed transactions **stay in the table** — that is the point, it is an audit
trail. Every aggregate over either table must carry `AND is_reversed IS FALSE`,
or a reversed ₹50,000 disbursement is counted as real money.

`m_loan_transaction` also has `manually_adjusted_or_reversed` (`:2731`), a
distinct flag for adjusted transactions. It is not a synonym; check what a given
question needs.

This is the single easiest way for the agent to produce a number that is wrong
but plausible. It belongs in the tool implementations as a non-negotiable
predicate the LLM cannot override — not as an instruction in the prompt.

### 9.2 ACCRUAL (type 10) is not cash

`LoanTransactionType.ACCRUAL(10)` — `LoanTransactionType.java:40-44`, whose own
comment reads *"Transaction represents an Accrual (For either interest, charge or
a penalty"*. It is an accounting entry recognising income, not a movement of
money. It appears in `m_loan_transaction` alongside real repayments.

The accrual family is larger than type 10 alone: **32** (ACCRUAL_ACTIVITY), **34**
(ACCRUAL_ADJUSTMENT), **36/39** (CAPITALIZED_INCOME_AMORTIZATION and its
adjustment), **42/43** (BUY_DOWN_FEE_AMORTIZATION and its adjustment), **45/47**
(DISCOUNT_FEE_AMORTIZATION and its adjustment). All are non-cash.

"How much did this client repay" means types **2** (REPAYMENT), **5**
(REPAYMENT_AT_DISBURSEMENT), **8** (RECOVERY_REPAYMENT), **28** (DOWN_PAYMENT),
and arguably the refund/goodwill credits (20–23). It does not mean
`SUM(amount) WHERE loan_id = ?`.

The `is_cash_movement` column suggested in §7.3 exists to make this filterable
rather than memorised.

### 9.3 `m_loan_arrears_aging` is batch-populated — empty ≠ no arrears

`LoanArrearsAgeingUpdateHandler.java:53-55` truncates the table, then repopulates
it. Between those two statements the table is empty. In a database that has never
run the job — **exactly our situation, since we are not running Fineract** — it
is empty permanently.

An agent that answers arrears questions by reading `m_loan_arrears_aging` will
return "no loans in arrears" for a portfolio full of defaults. That failure is
silent: the query succeeds, returns zero rows, and the LLM reports the good news.

Two mitigations, and take both:

1. **Compute arrears from `m_loan_repayment_schedule`** using the query in §6.1.
   That works regardless of whether the batch table is populated and matches
   Fineract's own definition, since it is Fineract's own SQL.
2. **If the table is read at all, treat empty as suspicious.** A tool that finds
   zero rows in `m_loan_arrears_aging` while active loans with past-due
   installments exist should return an explicit "stale/unpopulated batch table"
   signal, not an empty list. This is a good concrete case for the Stage 4
   reliability work: a retrieval failure that is indistinguishable from a valid
   empty result unless you check for it.

Stage 1 should still populate `m_loan_arrears_aging` in the seed, computed from
the schedule, so the table is realistic and so the two paths can be
cross-validated in the eval suite.

Note also `m_loan_arrears_aging.loan_id` is declared `autoIncrement="true"` and
primary key (`:2214-2216`) while simultaneously being the FK to `m_loan` — an
oddity in the baseline DDL. The dumped schema will carry it; do not rely on the
sequence.

### 9.4 `m_loan.client_id` is nullable

`m_loan:2005` — `client_id` is nullable, because a group loan hangs off
`group_id` (`:2006`) instead. Same on `m_savings_account.client_id` (`:3635`).

`JOIN m_client c ON c.id = l.client_id` therefore **silently drops every group
loan**. On a seeded portfolio with no group loans this is invisible; in review, or
against real Fineract data, it is a wrong answer with no error.

For Stage 1, seeding individual loans only is a legitimate scope decision — but
it must be a *stated* decision (README + this document), not an accident. The
tools should still use `LEFT JOIN` where a client is not required, and any
client-scoped tool should carry `AND l.client_id IS NOT NULL` explicitly so the
restriction is visible in the SQL rather than implied by the join type.

### 9.5 FK closure: `m_appuser`, `m_fund`, `m_code_value`

Extracting fifteen tables does not give fifteen tables' worth of dependencies.
Audit columns across the schema FK to `m_appuser` (`:607`) — including
`m_loan.created_by` added at `parts/0020:59-73` with its FK at `parts/0020:74+`.
`m_loan.fund_id` → `m_fund` (`:1620`). Every `*_cv_id` column → `m_code_value`
(`:1140`) → `m_code` (`:1127`): `gender_cv_id`, `loanpurpose_cv_id`,
`writeoff_reason_cv_id`, `client_type_cv_id`, `client_classification_cv_id`,
`closure_reason_cv_id`, `reject_reason_cv_id`, `withdraw_reason_cv_id`.

Two viable approaches for Stage 1:

- **Keep the FKs and seed the parents.** Higher fidelity, more seed work, and the
  database enforces referential integrity — which is worth having given §5.2.
- **Drop the FKs and null the columns.** Smaller, but discards the integrity
  guarantee at exactly the point where we are hand-constructing data that nothing
  will validate.

Keep them. The seed needs one `m_appuser`, a handful of `m_code`/`m_code_value`
rows, and one or two `m_fund` rows — small work for a real constraint, and the
`m_appuser` row is needed anyway for the NOT NULL audit columns on the
delinquency tables (§2.4).

### 9.6 `DECIMAL(19,6)` → `BigDecimal`, never `double`

Every money column in the schema is `DECIMAL(19, 6)` — `m_loan.principal_amount`,
every `*_derived`, `m_loan_transaction.amount`,
`m_savings_account.account_balance_derived`, all of it. Six decimal places, not
two: Fineract stores more precision than it displays, and per-currency display
scale lives in `m_currency.decimal_places` / `m_organisation_currency`.

Map to `java.math.BigDecimal` end to end. Any `double` or `float` in the read
path introduces representation error that will show up as a penny-off eval
failure that looks like an agent bug.

Two further points specific to this project:

- **Rounding for presentation is a display concern.** Aggregate at full precision,
  round once at the end using the currency's `decimal_places`. Rounding per-row
  before summing gives a different total.
- **Serialising `BigDecimal` into the LLM prompt.** Jackson will emit
  `1234.560000`. Format money as a string with the currency code before it reaches
  the model — an LLM asked to compare `1234.560000` and `987.500000` is doing
  string-shaped arithmetic. Better still, do all arithmetic in SQL or Java and
  give the model only finished figures. That is the §Stage-4 "facts injected from
  DB, never generated" principle applied concretely.

### 9.7 `date` vs `datetime`, and Neon on UTC

The schema mixes three temporal kinds deliberately:

- **Business dates are `date`**: `m_loan_repayment_schedule.duedate`,
  `m_loan_transaction.transaction_date`, `m_loan.disbursedon_date`,
  `m_savings_account_transaction.transaction_date`. No time, no zone.
- **Audit timestamps are `datetime`** in the baseline
  (`m_loan_transaction.created_date:2732`) and were progressively converted:
  `parts/0023_use_the_proper_date_or_datetime_type.xml` fixed columns that were
  the wrong kind, `parts/0117_set_datetime_precision.xml` and
  `parts/0241_set_datetime_precision.xml` moved them to microsecond precision
  (`datetime(6)` / `timestamp(6)`).
- **Newer audit columns are timezone-aware**: `created_on_utc` and
  `last_modified_on_utc` are `TIMESTAMP WITH TIME ZONE` under the Postgres
  context (`parts/0020:66-73`, `parts/0029:141+`) and plain `DATETIME` under
  MySQL. Another reason the dump must come from a Postgres run (§1.3).

The failure mode with a hosted Postgres: Neon runs UTC, Codespaces may not, and
Cloud Run will. If a `LocalDate` is converted through a `Timestamp` anywhere in
the read path, a `duedate` of the 1st can render as the previous month's 31st for
any client behind UTC — turning a not-yet-due installment into an overdue one and
changing arrears answers by a day at every month boundary.

Rules for Stage 2 onward:

- `date` columns → `java.time.LocalDate`. Never `java.util.Date`,
  `java.sql.Timestamp`, or anything with a zone.
- `timestamp with time zone` → `java.time.OffsetDateTime` / `Instant`.
- Set the JVM to UTC explicitly (`-Duser.timezone=UTC`) in the Dockerfile so
  Codespaces and Cloud Run agree.
- Pin the business date (§6.3) rather than deriving it from a clock, which turns
  a whole class of untestable-by-timing bugs into a config value.

Off-by-one date bugs are the classic silent failure in lending systems and are
worth an explicit eval case — a loan whose next installment is due exactly on the
pinned business date should be **not** in arrears.

---

## 10. Decisions

| # | Decision | Rationale | Rejected alternative |
|---|---|---|---|
| 1 | Generate Flyway DDL by running Fineract's Liquibase against a throwaway Postgres and dumping it | `0001_initial_schema.xml` is a frozen Flyway→Liquibase baseline with 290 migrations layered on top; `m_loan` alone is altered by 34 of them (§1.2). Hand-transcription cannot be correct | Transcribing the changelog XML by hand — produces a ~5-year-stale schema missing columns the arrears logic reads |
| 2 | Dump from a **Postgres** Liquibase run, not MySQL | Many changesets branch on `context="mysql"` / `context="postgresql"` with different types — `DATETIME` vs `TIMESTAMP WITH TIME ZONE` (§1.3) | A MySQL run — Neon would accept the result, but it would not be the schema Fineract creates |
| 3 | Keep Fineract's column names verbatim, including the inconsistent ones | Portfolio credibility rests on working against the real model. `loan_officer_id` vs `field_officer_id`, `duedate` vs `due_for_collection_as_of_date` — renaming means the project no longer demonstrates handling real-world schema | Cleaning up names for LLM friendliness — makes the schema comprehension problem, which is the actual demo, disappear |
| 4 | Add seven purpose-built `r_*` tables derived from the Java enums | Fineract's own `r_enum_value` exists but is stale baseline data: 10/12 loan statuses, 19/47 transaction types, savings types 18 and 19 mislabelled (§7.1). Grounding on it yields answers wrong in a way that looks right | (a) Using `r_enum_value` as-is — measurably wrong. (b) Enum-to-string in Java only — leaves the DB unreadable and the LLM ungrounded |
| 5 | No FK from `m_*` `_enum` columns to `r_*` tables; a CI check instead | Fineract's write path does not know these tables exist; an FK could reject inserts upstream considers valid, and adding constraints Fineract lacks changes the model (§7.2) | An FK — enforces at insert time but breaks compatibility with the system being modelled |
| 6 | Compute arrears from `m_loan_repayment_schedule`, not from `m_loan_arrears_aging` | The batch table is truncate-and-repopulate and is never populated without Fineract running (§9.3). Computing from the schedule uses Fineract's own SQL and always works | Reading the batch table — returns zero rows, which reads as "no arrears" rather than as a failure |
| 7 | Also seed `m_loan_arrears_aging` consistently, and treat "empty" as a failure signal | Keeps the schema realistic and lets the eval suite cross-validate two independent arrears paths | Leaving it empty — loses the cross-check and models a state real Fineract is never in |
| 8 | Pin the business date in config; seed `m_business_date` to match | Fineract uses a configurable logical business date interpolated from the JVM, not `CURRENT_DATE` (§6.3). Pinning makes evals deterministic so failures are regressions, not calendar drift | `CURRENT_DATE` — every eval answer decays daily and "60 days in arrears" has no stable ground truth |
| 9 | Seed rollups computed from transactions, never authored independently | ~40 `_derived` columns on `m_loan` are maintained by Fineract's Java write path with no triggers (§5). Two independently written numbers will disagree, and nothing will repair them | Hand-authoring both — produces a database that is internally inconsistent, against which evals measure nothing |
| 10 | Agent's DB user is read-only, with `default_transaction_read_only` | Rollup invariants are maintained by code we are not running, so any write corrupts permanently and undetectably; and the agent builds queries from LLM output (§5.3) | A read-write user with discipline in code — one stray UPDATE, no detection, no repair path |
| 11 | Keep the FK closure (`m_appuser`, `m_fund`, `m_code`/`m_code_value`) and seed the parents | Referential integrity is worth most precisely where data is hand-constructed and nothing validates it; the `m_appuser` row is needed anyway for NOT NULL audit columns on the delinquency tables (§9.5) | Dropping FKs and nulling the columns — smaller seed, discards the guarantee at the point of maximum risk |
| 12 | Individual loans only in the Stage 1 seed; state it explicitly | `m_loan.client_id` is nullable for group loans, so client joins silently drop them (§9.4). Restricting scope is fine; discovering the restriction in review is not | Silently inheriting the restriction from inner joins — a wrong answer with no error |
| 13 | Savings in scope alongside lending *(pre-locked)* | Makes the cross-product question (§8.4) available, which is where fan-out double-counting shows up — a genuinely LLM-hard failure worth demonstrating | Lending only — simpler, but loses the most interesting eval case |
| 14 | `is_reversed IS FALSE` and cash-type filters live in tool code, not in the prompt | The LLM cannot be relied on to remember a filter that changes results silently rather than erroring (§9.1, §9.2) | Prompt instructions — works most of the time, which is the worst failure profile |
| 15 | `BigDecimal` end to end; format money to strings before the prompt | Every money column is `DECIMAL(19,6)`; `double` yields penny-off failures that look like agent bugs, and raw `1234.560000` in a prompt invites string-shaped arithmetic (§9.6) | `double` for convenience, or serialising raw decimals to the model |
| 16 | `LocalDate` for `date` columns; JVM pinned to UTC in the Dockerfile | Neon runs UTC, Codespaces may not, Cloud Run does; a zone conversion shifts `duedate` by a day and flips arrears at every month boundary (§9.7) | Default JVM timezone with `java.util.Date` — the classic silent lending-system bug |
| 17 | Office rollups take an explicit `include_sub_offices` flag using the `hierarchy` prefix match | `m_office.hierarchy` is a materialised path (§2.1); "this branch" and "this branch and below" are both valid readings of the same question and give different numbers | Guessing one reading — right half the time, with no way for the reader to tell which |

---

## Source index

Everything above traces to these files.

**Schema**
- `fineract-provider/src/main/resources/db/changelog/tenant/parts/0001_initial_schema.xml` — baseline DDL; table line numbers throughout §2
- `.../parts/0002_initial_data.xml` — baseline data; office seed `:1163`, `r_enum_value` seed from `:10826`, report SQL `:12490`, `:12504`
- `.../parts/0015_add_business_date.xml:26-55` — `m_business_date`, `enable_business_date` config
- `.../parts/0020_add_audit_entries.xml:59-73` — `m_loan` audit columns, mysql/postgresql split
- `.../parts/0023_use_the_proper_date_or_datetime_type.xml`, `.../parts/0117_set_datetime_precision.xml`, `.../parts/0241_set_datetime_precision.xml` — temporal type corrections
- `.../parts/0029_add_delinquency_buckets.xml` — delinquency tables; mysql `:25`, postgresql `:135`, FKs `:245`, `m_product_loan.delinquency_bucket_id` `:255`
- `.../parts/0057_add_principal_adjustments_to_loan.xml`, `fineract-loan/.../1017_add_fee_and_penalty_adjustments_to_loan.xml` — later `_derived` columns
- `.../tenant/changelog-tenant.xml`, `.../final-changelog-tenant.xml`, `.../initial-switch-changelog-tenant.xml:25-27` — changelog wiring

**Java**
- `fineract-core/.../portfolio/loanaccount/domain/LoanStatus.java:26-63, 94-120`
- `fineract-loan/.../portfolio/loanaccount/domain/LoanTransactionType.java:26-85`
- `fineract-core/.../portfolio/client/domain/ClientStatus.java:28-35`
- `fineract-core/.../portfolio/accountdetails/domain/AccountType.java:31-36`
- `fineract-core/.../portfolio/savings/domain/SavingsAccountStatusType.java:29-39`
- `fineract-core/.../portfolio/savings/SavingsAccountTransactionType.java:35-54`
- `fineract-core/.../portfolio/savings/DepositAccountType.java:30-34`
- `fineract-core/.../organisation/office/domain/Office.java:188-199`
- `fineract-core/.../infrastructure/core/service/database/DatabaseSpecificSQLGenerator.java:114-122`
- `fineract-loan/.../loanaccount/jobs/updateloanarrearsageing/LoanArrearsAgeingUpdateHandler.java:53-197`
- `fineract-loan/.../delinquency/service/LoanDelinquencyDomainServiceImpl.java:50-140`
- `fineract-loan/.../delinquency/service/DelinquencyWritePlatformServiceHelper.java:55-86`
