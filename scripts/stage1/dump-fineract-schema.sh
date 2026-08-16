#!/usr/bin/env bash
#
# Stage 1, step 1: produce a real Fineract tenant schema by running Fineract's
# own Liquibase changelogs against a throwaway Postgres, then dumping the result.
#
# This exists because reading 0001_initial_schema.xml is not enough: that file is
# a frozen Flyway->Liquibase baseline with 290 migrations layered on top. See
# docs/DOMAIN.md section 1.2.
#
# Usage:
#   export SCRATCH_DB_URL='postgresql://user:pass@host/dbname?sslmode=require'
#   ./scripts/stage1/dump-fineract-schema.sh
#
# Output: build/stage1/fineract-schema-full.sql
#
set -euo pipefail

LIQUIBASE_VERSION="${LIQUIBASE_VERSION:-4.31.1}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/build/stage1"
TOOL_DIR="$REPO_ROOT/build/stage1/tools"

if [[ -z "${SCRATCH_DB_URL:-}" ]]; then
  echo "ERROR: SCRATCH_DB_URL is not set." >&2
  echo "Expected: postgresql://user:pass@host/dbname?sslmode=require" >&2
  exit 1
fi

# --- derive JDBC url + credentials from the libpq url -----------------------
# Note: assumes the password contains no '@'. Neon's generated passwords do not.
_rest="${SCRATCH_DB_URL#*://}"
_creds="${_rest%%@*}"
_hostpart="${_rest#*@}"
DB_USER="${_creds%%:*}"
DB_PASS="${_creds#*:}"
JDBC_URL="jdbc:postgresql://${_hostpart}"

mkdir -p "$OUT_DIR" "$TOOL_DIR"

# --- fetch the Liquibase CLI (ships with the Postgres JDBC driver) ----------
LB="$TOOL_DIR/liquibase-$LIQUIBASE_VERSION/liquibase"
if [[ ! -x "$LB" ]]; then
  echo ">> downloading Liquibase $LIQUIBASE_VERSION"
  mkdir -p "$TOOL_DIR/liquibase-$LIQUIBASE_VERSION"
  curl -fsSL \
    "https://github.com/liquibase/liquibase/releases/download/v${LIQUIBASE_VERSION}/liquibase-${LIQUIBASE_VERSION}.tar.gz" \
    | tar -xz -C "$TOOL_DIR/liquibase-$LIQUIBASE_VERSION"
  chmod +x "$LB"
fi

# --- classpath: every module whose changelogs the master pulls in -----------
# db.changelog-master.xml includes module changelogs by *classpath* path, not by
# relative path, so each owning module's resources dir must be on the classpath.
CP="$REPO_ROOT/scripts/stage1"
for m in provider loan investor savings progressive-loan command-jdbc \
         working-capital-loan loan-origination; do
  d="$REPO_ROOT/fineract-$m/src/main/resources"
  [[ -d "$d" ]] || { echo "ERROR: missing $d" >&2; exit 1; }
  CP="${CP:+$CP:}$d"
done

# Our own master, NOT fineract-provider's db.changelog-master.xml. See the
# header comment in stage1-changelog-master.xml for why: upstream's master
# pulls in the tenant-store changelogs, whose <customChange> elements reference
# Fineract Java classes a standalone CLI cannot load.
CHANGELOG="stage1-changelog-master.xml"

# --- contexts ---------------------------------------------------------------
# Only ONE context matters now, and it is the one that fails silently:
#
#   postgresql     - 135 changesets are gated on context="postgresql" and their
#                    MySQL twins on context="mysql". This is a Liquibase
#                    *context*, not a dbms= attribute, and Fineract injects it at
#                    runtime from the JDBC connection
#                    (DatabaseAwareMigrationContextProvider:30). Omitting it
#                    skips all 135 and still exits 0.
#
#   initial_switch - required, and the reason is counter-intuitive. Parts 0001
#                    and 0002 (980 + 43 changesets, the entire baseline schema)
#                    carry no context of their own; they are gated by the
#                    context="initial_switch" on the <include> elements in
#                    initial-switch-changelog-tenant.xml:25-26. Without it,
#                    Liquibase reports "Context mismatch: 1140", creates no
#                    tables, and then dies on the first INSERT in part 0003.
#
# Nothing in our changelog tree is gated on !initial_switch, so both contexts can
# be passed in a single pass; ordering comes from include order in
# stage1-changelog-master.xml.
echo ">> liquibase update (contexts=postgresql,initial_switch)"
"$LB" \
  --classpath="$CP" \
  --changelog-file="$CHANGELOG" \
  --url="$JDBC_URL" \
  --username="$DB_USER" \
  --password="$DB_PASS" \
  --contexts="postgresql,initial_switch" \
  update

# --- dump -------------------------------------------------------------------
command -v pg_dump >/dev/null 2>&1 || {
  echo "ERROR: pg_dump not found. Install with:" >&2
  echo "  sudo apt-get update && sudo apt-get install -y postgresql-client" >&2
  exit 1
}

DUMP="$OUT_DIR/fineract-schema-full.sql"
echo ">> pg_dump --schema-only -> $DUMP"
pg_dump --schema-only --no-owner --no-privileges --no-comments \
        --dbname="$SCRATCH_DB_URL" > "$DUMP"

# --- sanity checks ----------------------------------------------------------
# A Liquibase run that skipped the postgresql context still exits 0, so verify
# the result rather than trusting the exit code.
echo ""
echo "=== verification ==="
tables=$(grep -c '^CREATE TABLE' "$DUMP" || true)
echo "tables created: $tables"

missing=0
for t in m_office m_staff m_currency m_client m_product_loan m_loan \
         m_loan_repayment_schedule m_loan_transaction \
         m_loan_transaction_repayment_schedule_mapping m_loan_arrears_aging \
         m_loan_charge m_delinquency_range m_delinquency_bucket \
         m_delinquency_bucket_mappings m_loan_delinquency_tag_history \
         m_savings_product m_savings_account m_savings_account_transaction; do
  if grep -q "CREATE TABLE public.$t " "$DUMP"; then
    echo "  ok      $t"
  else
    echo "  MISSING $t"
    missing=$((missing + 1))
  fi
done

if [[ "$missing" -gt 0 ]]; then
  echo ""
  echo "FAILED: $missing in-scope table(s) missing." >&2
  echo "The usual cause is a dropped 'postgresql' context." >&2
  exit 1
fi

# The delinquency tables only exist in the postgresql-context branch of
# changelog 0029, so their presence is the sharpest proof the context applied.
echo ""
echo "OK: all 18 in-scope tables present in $DUMP"
