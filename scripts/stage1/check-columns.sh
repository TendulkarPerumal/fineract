#!/usr/bin/env bash
#
# Validate that every column named in an INSERT statement actually exists in
# V1__fineract_schema.sql.
#
# The seed is hand-authored against a 32-table schema nobody memorises, and a
# wrong column name fails at apply time with a one-line error after a partial
# run. This catches all of them at once, without a database.
#
# Usage: ./scripts/stage1/check-columns.sh <sql-file> [<sql-file> ...]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCHEMA="$REPO_ROOT/ai-agent/src/main/resources/db/migration/V1__fineract_schema.sql"
REFS="$REPO_ROOT/ai-agent/src/main/resources/db/migration/V2__reference_tables.sql"

[[ -f "$SCHEMA" ]] || { echo "ERROR: $SCHEMA not found" >&2; exit 1; }
[[ $# -ge 1 ]] || { echo "usage: $0 <sql-file> [...]" >&2; exit 1; }

# table<TAB>column, from every CREATE TABLE in the schema and the r_* migration.
awk '
{ sub(/\r$/, "") }
/^CREATE TABLE / {
    t = $0
    sub(/^CREATE TABLE (public\.)?/, "", t)
    sub(/[ (].*$/, "", t)
    intbl = 1
    next
}
intbl && /^\);/ { intbl = 0; next }
intbl {
    c = $0
    sub(/^[ \t]+/, "", c)
    # skip table-level constraint lines
    if (c ~ /^(PRIMARY|UNIQUE|CHECK|CONSTRAINT|FOREIGN)/) next
    sub(/[ \t].*$/, "", c)
    if (c != "") print t "\t" c
}' "$SCHEMA" "$REFS" 2>/dev/null | sort -u > /tmp/schema-cols.tsv

status=0
for f in "$@"; do
  echo "=== $f ==="
  # Each "INSERT INTO <table> (<cols>)" - the column list may wrap lines.
  awk '
  { sub(/\r$/, "") }
  /INSERT INTO/ { collecting = 1; buf = "" }
  collecting { buf = buf " " $0 }
  collecting && /\)/ {
      # first ")" closes the column list
      stmt = buf
      sub(/^.*INSERT INTO[ \t]+/, "", stmt)
      tbl = stmt; sub(/[ \t(].*$/, "", tbl)
      cols = stmt
      if (index(cols, "(") == 0) { collecting = 0; next }
      sub(/^[^(]*\(/, "", cols)
      sub(/\).*$/, "", cols)
      n = split(cols, a, ",")
      for (i = 1; i <= n; i++) {
          c = a[i]
          gsub(/[ \t\n]/, "", c)
          if (c != "") print tbl "\t" c
      }
      collecting = 0
  }' "$f" | sort -u > /tmp/used-cols.tsv

  while IFS=$'\t' read -r tbl col; do
    [[ -n "$tbl" ]] || continue
    if ! grep -qxF "$tbl	$col" /tmp/schema-cols.tsv; then
      echo "  BAD  $tbl.$col"
      status=1
    fi
  done < /tmp/used-cols.tsv

  n=$(wc -l < /tmp/used-cols.tsv)
  [[ "$status" -eq 0 ]] && echo "  ok   $n column references all resolve"
done

exit "$status"
