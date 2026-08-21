#!/usr/bin/env bash
#
# Runs once when the Codespace is created.
#
# Everything here was a manual step that cost a debugging round trip at least
# once. The environment variables in particular: they were shell variables, so
# they died with the terminal that set them, and a second terminal produced a
# JDBC URL with no host - which the Postgres driver silently resolves to
# localhost rather than rejecting.
set -euo pipefail

echo ">> installing postgresql-client-18"
# Ubuntu ships client 16, and pg_dump refuses to dump a server newer than
# itself. Neon tracks current Postgres, so the distro package is not enough.
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list > /dev/null
sudo apt-get update -qq
sudo apt-get install -y -qq postgresql-client-18

echo ">> writing ~/.agent-env"
# Outside the repo on purpose: it holds a database password, and a file inside
# the working tree is one "git add -A" away from being public.
cat > "$HOME/.agent-env" <<'ENV'
# Derived from the SCRATCH_DB_URL Codespaces secret. Source this in any shell:
#   source ~/.agent-env
if [ -n "${SCRATCH_DB_URL:-}" ]; then
    export AGENT_DB_URL="$(echo "$SCRATCH_DB_URL" | sed 's|/neondb?|/fineract_agent?|')"
    export AGENT_DB_JDBC_URL="jdbc:postgresql://$(echo "$AGENT_DB_URL" | sed 's|.*@||')"
    export AGENT_DB_USER="$(echo "$AGENT_DB_URL" | sed 's|.*://||; s|:.*||')"
    export AGENT_DB_PASSWORD="$(echo "$AGENT_DB_URL" | sed 's|.*://[^:]*:||; s|@.*||')"
fi
# psql pages multi-row output and waits for a keypress, which looks like a hang.
export PSQL_PAGER=cat
ENV

# Source it from every new shell, so the "which terminal am I in" problem
# cannot come back.
if ! grep -q 'agent-env' "$HOME/.bashrc" 2>/dev/null; then
    echo '[ -f "$HOME/.agent-env" ] && source "$HOME/.agent-env"' >> "$HOME/.bashrc"
fi

echo ">> checking required secrets"
for secret in SCRATCH_DB_URL GEMINI_API_KEY; do
    if [ -z "${!secret:-}" ]; then
        echo "   MISSING: $secret"
        echo "   Add it at Settings > Secrets and variables > Codespaces, then rebuild."
    else
        echo "   ok: $secret"
    fi
done

echo ">> done. In a new terminal: source ~/.agent-env && cd ai-agent && mvn spring-boot:run"
