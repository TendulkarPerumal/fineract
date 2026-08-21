# Failures

Everything that broke while building this, what caused it, and what it changed.

Nineteen of them. Kept because the interesting content of a project like this is
not the code that worked first time. Several are traps anyone integrating
Spring AI 2.0, Spring Boot 4, Liquibase or Neon will hit, and the most
instructive share one shape: **the failure was silent**.

---

## The silent ones

These produced no error. They are the reason `StartupInfoLogger` exists.

### 1. Flyway never ran, and nothing said so

**Symptom:** none. The app started, served requests, returned correct data.

**Cause:** Spring Boot 4 split auto-configuration into per-integration modules.
`flyway-core` on the classpath no longer activates it — you need
`spring-boot-starter-flyway`. Every `spring.flyway.*` property was silently
ignored and `flyway_schema_history` was never created.

The data looked right only because the migrations had been applied by hand with
`psql`. This would have surfaced for the first time on Cloud Run, against a
database where nobody had run anything by hand.

**Fix:** the starter. And `StartupInfoLogger` now resolves the Flyway bean and
logs the applied migration count, or logs an error naming this exact cause.

**Lesson:** a dependency that is present but inert is worse than one that is
missing. Assert at startup that the thing you depend on is actually wired.

### 2. An empty environment variable became `localhost`

**Symptom:** `Connection refused` from a database URL that looked configured.

**Cause:** `AGENT_DB_URL` was a plain shell variable, so it died with the
terminal that set it. In a second terminal the derived value collapsed to
`jdbc:postgresql://` — no host — and the Postgres driver resolves a hostless URL
to localhost rather than rejecting it.

**Fix:** `~/.agent-env` sourced from `.bashrc` via the devcontainer, and a
startup warning when the datasource points at localhost.

**Lesson:** Maven only inherits *exported* variables. A variable that exists in
one terminal and not another is indistinguishable at runtime from one that is
wrong.

### 3. Office and arrears were perfectly correlated in the seed

**Symptom:** none from the tests. The agent volunteered it, unprompted: it
reported the correct count of loans over 60 days in arrears and added that all
of them were in Western Branch and Eastern had none.

**Cause:** client office was assigned by `i % 10` and the arrears bucket was
also driven by `i % 10`. Eastern Branch structurally *could not* contain a loan
in arrears. Loans above 190 reuse client `i-190`, and 190 is divisible by 10, so
even those preserved the residue.

**Fix:** office keyed on `i % 7`, coprime with 10.

**Lesson:** correlated pseudo-random attributes in synthetic data produce
degenerate answers that look fine in aggregate. Also: the agent found this
because the tool returned enough context for it to describe the *shape* of the
result, not just a total.

---

## Getting a real schema out of Fineract

### 4. A custom Liquibase changeset needed classes the CLI did not have

**Error:** `Unexpected error running Liquibase:
org.apache.fineract.infrastructure.core.service.migration.TenantPasswordEncryptionTask`

**Cause:** the master changelog gates the tenant-store changelogs with
`context="tenant_store_db AND initial_switch"` on `<include>`. The legacy
`context` attribute does not evaluate a boolean expression, so passing
`initial_switch` was enough to match it and pull in changelogs containing
`<customChange>` elements referencing Fineract Java classes.

**Fix:** our own master changelog listing only the tenant and module changelogs.
Structure is enforceable; that attribute is not.

### 5. `--contexts=postgresql` alone filtered out the entire baseline

**Error:** `Context mismatch: 1140`, then `relation "public.stretchy_parameter"
does not exist`.

**Cause:** parts `0001` and `0002` contain 980 + 43 changesets with **no context
of their own** — they are gated by `context="initial_switch"` on the `<include>`.
Without that context all 1023 were filtered, so no tables were created and part
`0003` inserted into nothing.

**Fix:** `--contexts=postgresql,initial_switch`.

**Also worth knowing:** the mysql/postgres split is a Liquibase *context*
(135 changesets), not a `dbms=` attribute. Fineract injects `postgresql` at
runtime from the JDBC connection, so it is invisible in the XML — and a CLI run
that omits it skips all 135 and **still exits 0**.

### 6. Spring properties in a changelog

**Error:** `syntax error at or near "$"` on
`${fineract.tenant.rounding-mode}`.

**Cause:** not a Liquibase property. Fineract supplies it through
`spring.liquibase.parameters.*`, so under a standalone CLI it is never
substituted and the literal text reaches Postgres as SQL.

**Fix:** defined in our master changelog. The other 15 `${fineract.tenant.*}`
placeholders are all in tenant-store, which we exclude.

### 7. `pg_dump` refused to dump the server

**Error:** `aborting because of server version mismatch: server 18.4, pg_dump 16.14`

**Cause:** Neon tracks current Postgres; Ubuntu 24.04 ships client 16. "Install
postgresql-client" was wrong advice.

**Fix:** the script now picks the newest `pg_dump` on the machine, compares it
against `SHOW server_version`, and fails with PGDG install instructions.

### 8. Liquibase could not create its own bookkeeping table

**Error:** `no schema has been selected to create in`

**Cause:** Neon's `neondb_owner` role has an **empty `search_path`**, so there
was no default schema to create `DATABASECHANGELOG` in.

**Fix:** pin `--default-schema-name=public`. The read-only role script sets
`search_path` explicitly for the same reason.

---

## Parsing and tooling

### 9. The pruner produced an empty migration

**Cause:** `pg_dump` precedes every object with a `--` comment banner. Those
lines do not end in `;`, so they accumulated into the statement buffer and every
statement appeared to begin with `--` rather than its keyword.

Two smaller traps in the same script: awk cannot take a regex *constant* as a
function parameter, and awk implementations disagree on whether `.` matches a
newline — so anything matching across lines is now index/substr based.

### 10. `--` inside an XML comment

**Error:** `The string "--" is not permitted within comments`

**Cause:** a comment documenting a `--contexts` flag. My own validation step had
silently no-opped because Python was not installed, so it was not caught.

**Lesson:** a validation step that skips when its tool is missing is worse than
no validation step, because it reports success.

---

## Spring AI and Gemini

### 11. The model id was retired

**Error:** `404 This model models/gemini-2.5-flash is no longer available to
new users`

**Cause:** 2.5 Flash is retired to new API keys (full deprecation 2026-10-16).

**Fix:** `gemini-3.5-flash`, overridable with `GEMINI_MODEL` so the next
retirement is an environment change rather than a rebuild.

**Lesson:** model ids are perishable infrastructure. Treat them like a pinned
dependency version, not a constant.

### 12. A SQL error became a Jackson crash

**Error:** `Unrecognized token 'PreparedStatementCallback': was expecting
(JSON String, Number, Array, Object...)`

**Cause:** the tool threw a `SQLException`; Spring AI handed the exception
*message* back as the tool result; the Gemini client called `parseJsonToMap` on
`PreparedStatementCallback; bad SQL grammar [...]`. The request died with a
stack trace pointing at Jackson, burying the real cause.

**Fix:** tools return `ToolResult(ok, error, rowCount, rows)` and never throw.
Failures stay inside the protocol where the model can read and correct them.

Argument errors are returned verbatim because they name the fix; database errors
are logged server-side and reported generically — feeding raw SQL error text
into a prompt is how a schema ends up quoted in a user-visible answer.

### 13. NULL bind parameters have no type

**Error:** `bad SQL grammar` — underneath, Postgres cannot infer a type for a
NULL parameter in `(? IS NULL OR ...)`.

**Cause:** the optional-filter pattern used in three queries. It fails whenever
the optional argument is omitted, which is the common case.

**Fix:** `CAST(:param AS text)`, which also avoids the `:param::type` form that
Spring's named-parameter parser can misread.

### 14. The first working answer took 187 seconds

**Cause, in order of size:**

1. **Thinking tokens.** Gemini 3.x reasons before answering, and that reasoning
   is billed and waited on. This workload does not need it — the hard thinking
   is already encoded in the tools and the schema.
2. **Counting in the model instead of the database.** "How many loans are over
   60 days in arrears" went through the *detail* query, shipping all 63 loans
   into the conversation so the model could count them.
3. **Answer length.** It enumerated account numbers nobody asked for, and output
   tokens are generated serially.

**Fix:** `thinking-level: LOW`, a `get_arrears_totals` tool that counts in SQL,
`max-output-tokens`, and `max-rows` reduced from 50 to 20.

**Lesson, and the reason `CallMetrics` exists:** "it takes three minutes" is not
actionable. Splitting a request into database time and model time is what
decides whether the problem is the query, the payload, or the reasoning budget.

---

## Environment

### 15. A Windows clone that looked corrupt

**Symptom:** `git status` reported ~7900 files as deleted immediately after a
clean clone.

**Cause:** Fineract has paths longer than the 260-character Windows limit.

**Fix:** `git clone -c core.longpaths=true`.

### 16. Scripts committed without the executable bit

**Symptom:** `Permission denied` after a fresh `git pull`.

**Fix:** `git update-index --chmod=+x`. The mode belongs in the index, not in
each machine's shell history.

---

## Testing the thing

### 17. The database URL was the one the provider displays

**Error:** `'url' must start with "jdbc"`, thrown during bean creation, several
frames inside Hikari.

**Cause:** `AGENT_DB_JDBC_URL` held Neon's connection string,
`postgresql://user:pass@host/db?sslmode=require`. Spring wants
`jdbc:postgresql://host/db?...` with credentials as separate properties.
Pasting the URL your provider shows you is the obvious thing to do.

**Fix:** `DatabaseUrlNormaliser` accepts either shape and converts before the
context starts. A URL that is neither now fails immediately with a message
showing both accepted forms.

**Second-order bug, caught by the test rather than by running it:**
`URI.create("postgresql://")` throws `IllegalArgumentException` *before* the
host check runs, so the hostless case — the shape an empty environment variable
produces — would still have surfaced as an opaque parse error. The guard had a
hole, and only a test asserting the *type* of failure found it.

**Lesson:** the same class of failure appeared three times in this project
(empty variable, wrong terminal, wrong URL shape). Each time the fix was
narrower than the cause. Accepting both shapes is the first fix that actually
closes it.

### 18. The eval suite measured the circuit breaker

**Symptom:** none yet — caught by reading, before it produced a misleading
number.

**Cause:** the suite called `ResilientQueryAgent`, which opens its circuit after
four consecutive failures and then fails every call for a minute. Across a
30-question batch, a handful of transient provider errors would cascade into a
wave of `ERROR` outcomes, and the reported pass rate would describe the breaker
rather than the agent.

**Fix:** the suite calls `QueryAgent` directly. The breaker is correct for a
live endpoint and wrong for a batch.

**Lesson:** production resilience and batch measurement want opposite
behaviour. Wrapping the thing under test in the protections that guard it in
production means measuring the protections.

### 19. A validation step that skipped looked like a passing one

Twice. The XML comment check no-opped because Python was not installed, and CI
would have reported success while never running the evals.

Both are the same shape as failure 1: a check that does not run is worse than no
check, because it reports success. The CI workflow now prints an explicit notice
naming what it did not verify, and the eval suite is annotated so a missing API
key skips it visibly rather than silently.
