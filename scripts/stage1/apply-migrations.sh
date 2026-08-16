#!/usr/bin/env bash
#
# Drop, recreate and rebuild the agent database from the migrations.
#
# Stage 2 introduces Flyway, which will own this. Until then the migrations are
# plain SQL applied in order, and a from-scratch rebuild is the only way to be
# sure the files - not the history of what was run by hand - are the source of
# truth.
#
# Usage:
#   export SCRATCH_DB_URL='postgresql://user:pass@host/neondb?sslmode=require'
#   ./scripts/stage1/apply-migrations.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$REPO_ROOT/ai-agent/src/main/resources/db/migration"
DB_NAME="${DB_NAME:-fineract_agent}"

[[ -n "${SCRATCH_DB_URL:-}" ]] || { echo "ERROR: SCRATCH_DB_URL is not set" >&2; exit 1; }

# CREATE/DROP DATABASE cannot run through Neon's pooled endpoint, which runs
# PgBouncer in transaction mode.
DIRECT_URL="${SCRATCH_DB_URL//-pooler/}"
AGENT_URL="$(echo "$SCRATCH_DB_URL" | sed "s|/[^/?]*?|/$DB_NAME?|")"

echo ">> target: $(echo "$AGENT_URL" | sed 's|://[^@]*@|://***@|' | sed 's|?.*||')"
echo ">> dropping and recreating $DB_NAME"
psql "$DIRECT_URL" -v ON_ERROR_STOP=1 -q -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)"
psql "$DIRECT_URL" -v ON_ERROR_STOP=1 -q -c "CREATE DATABASE $DB_NAME"

for f in "$MIGRATIONS"/V*.sql; do
    echo ">> applying $(basename "$f")"
    psql "$AGENT_URL" -v ON_ERROR_STOP=1 -q -f "$f"
done

echo ""
echo "=== row counts ==="
psql "$AGENT_URL" -q -c "
SELECT 'm_client' t, count(*) FROM m_client
UNION ALL SELECT 'm_loan', count(*) FROM m_loan
UNION ALL SELECT 'm_loan_repayment_schedule', count(*) FROM m_loan_repayment_schedule
UNION ALL SELECT 'm_loan_transaction', count(*) FROM m_loan_transaction
UNION ALL SELECT 'm_loan_arrears_aging', count(*) FROM m_loan_arrears_aging
UNION ALL SELECT 'm_loan_delinquency_tag_history', count(*) FROM m_loan_delinquency_tag_history
UNION ALL SELECT 'm_savings_account', count(*) FROM m_savings_account
UNION ALL SELECT 'm_savings_account_transaction', count(*) FROM m_savings_account_transaction
ORDER BY 1"

echo ""
echo "=== consistency checks (every violations count must be 0) ==="
psql "$AGENT_URL" -v ON_ERROR_STOP=1 -f "$REPO_ROOT/scripts/stage1/verify-seed.sql"
