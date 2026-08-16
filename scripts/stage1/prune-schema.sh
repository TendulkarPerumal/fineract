#!/usr/bin/env bash
#
# Stage 1, step 2: reduce the 281-table Liquibase dump to the tables the agent
# needs, as a Flyway migration.
#
# The table set is NOT a hand-maintained list. It is the 18 in-scope tables from
# docs/DOMAIN.md section 2, plus the transitive closure of their foreign keys,
# recomputed from the dump on every run. Hand-listing it would go stale the
# first time upstream adds a FK, and the failure mode would be a migration that
# does not apply.
#
# Usage: ./scripts/stage1/prune-schema.sh
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DUMP="$REPO_ROOT/build/stage1/fineract-schema-full.sql"
WORK="$REPO_ROOT/build/stage1"
OUT_DIR="$REPO_ROOT/ai-agent/src/main/resources/db/migration"
OUT="$OUT_DIR/V1__fineract_schema.sql"

[[ -f "$DUMP" ]] || { echo "ERROR: $DUMP not found; run dump-fineract-schema.sh first" >&2; exit 1; }
mkdir -p "$OUT_DIR"

# The 18 in-scope tables (docs/DOMAIN.md section 2).
SEEDS="m_office m_staff m_currency m_client m_product_loan m_loan
       m_loan_repayment_schedule m_loan_transaction
       m_loan_transaction_repayment_schedule_mapping m_loan_arrears_aging
       m_loan_charge m_delinquency_range m_delinquency_bucket
       m_delinquency_bucket_mappings m_loan_delinquency_tag_history
       m_savings_product m_savings_account m_savings_account_transaction"

# --- 1. foreign-key edge list ----------------------------------------------
awk '
{ sub(/\r$/, "") }
/^ALTER TABLE ONLY public\./ { t=$0; sub(/^ALTER TABLE ONLY public\./,"",t); sub(/[ ;]*$/,"",t); cur=t; next }
/ADD CONSTRAINT .* FOREIGN KEY .* REFERENCES public\./ {
  r=$0; sub(/.*REFERENCES public\./,"",r); sub(/\(.*/,"",r); gsub(/[ ;]/,"",r);
  if (cur != "" && r != "") print cur "\t" r
}' "$DUMP" | sort -u > "$WORK/fk-edges.tsv"

# --- 2. transitive closure --------------------------------------------------
echo "$SEEDS" | tr -s ' \n' '\n' | grep -v '^$' | sort -u > "$WORK/seed-tables.txt"

awk -F'\t' -v SEEDFILE="$WORK/seed-tables.txt" '
BEGIN { while ((getline s < SEEDFILE) > 0) if (s != "") want[s]=1; close(SEEDFILE) }
{ dep[$1] = dep[$1] " " $2 }
END {
  changed=1
  while (changed) { changed=0
    for (t in want) { m=split(dep[t], d, " ")
      for (i=1;i<=m;i++) if (d[i] != "" && !(d[i] in want)) { want[d[i]]=1; changed=1 } } }
  for (t in want) print t
}' "$WORK/fk-edges.tsv" | sort > "$WORK/keep-tables.txt"

n_seed=$(wc -l < "$WORK/seed-tables.txt")
n_keep=$(wc -l < "$WORK/keep-tables.txt")
echo ">> in-scope: $n_seed   after FK closure: $n_keep"
echo ">> closure added: $(comm -13 "$WORK/seed-tables.txt" "$WORK/keep-tables.txt" | tr '\n' ' ')"

# --- 3. emit ----------------------------------------------------------------
awk -v KEEPFILE="$WORK/keep-tables.txt" -f "$REPO_ROOT/scripts/stage1/prune.awk" "$DUMP" > "$OUT"
echo ">> wrote $OUT ($(wc -l < "$OUT") lines)"

# --- 4. verify --------------------------------------------------------------
missing=0
while read -r t; do
  grep -q "^CREATE TABLE public\.$t " "$OUT" || { echo "  MISSING TABLE $t"; missing=$((missing+1)); }
done < "$WORK/keep-tables.txt"
[[ "$missing" -eq 0 ]] || { echo "FAILED: $missing table(s) missing from output" >&2; exit 1; }

# Every FK target must be a table we actually create, or the migration will not
# apply. This is the check that makes the closure trustworthy rather than assumed.
awk '/REFERENCES public\./ { r=$0; sub(/.*REFERENCES public\./,"",r); sub(/\(.*/,"",r); gsub(/[ ;\r]/,"",r); print r }' "$OUT" \
  | sort -u > "$WORK/fk-targets-in-output.txt"
if comm -23 "$WORK/fk-targets-in-output.txt" "$WORK/keep-tables.txt" | grep -q .; then
  echo "FAILED: FK targets outside the pruned set:" >&2
  comm -23 "$WORK/fk-targets-in-output.txt" "$WORK/keep-tables.txt" >&2
  exit 1
fi
echo "OK: all FK targets resolve within the pruned set ($n_keep tables)"
