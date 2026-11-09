#!/usr/bin/env bash
# =============================================================================
# seed-wiki-content.sh
# Publishes or updates in Wiki.js the pages versioned in wiki/content/**/*.txt.
# Thin wrapper: the logic lives in seed-wiki-content.js (Node, native fetch) because
# building JSON with multi-line markdown by hand in bash+curl is fragile to escape;
# Node is already a dependency of the repo (Angular frontends).
#
# Variables:
#   WIKIJS_API_KEY   (required; loaded from infra/.env.local)
#   WIKI_BASE_URL     (default: http://wiki.local)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

for env_file in "${REPO_ROOT}/infra/.env.local"; do
  if [ -f "$env_file" ]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
done

command -v node >/dev/null 2>&1 || { echo "[x] node not found on PATH." >&2; exit 1; }
[ -n "${WIKIJS_API_KEY:-}" ] || { echo "[x] WIKIJS_API_KEY is missing (define it in infra/.env.local)." >&2; exit 1; }

exec node "${SCRIPT_DIR}/seed-wiki-content.js" "$@"
