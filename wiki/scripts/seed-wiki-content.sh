#!/usr/bin/env bash
# =============================================================================
# seed-wiki-content.sh
# Publica/actualiza en Wiki.js las páginas versionadas en wiki/content/**/*.txt.
# Wrapper fino: la lógica vive en seed-wiki-content.js (Node, usa fetch nativo)
# porque construir JSON con markdown multilínea a mano en bash+curl es frágil
# por escapado; Node ya es una dependencia del repo (frontend Angular).
#
# Variables reconocidas:
#   WIKIJS_API_KEY   (obligatoria; se carga de infra/.env.local)
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

command -v node >/dev/null 2>&1 || { echo "[x] node no encontrado en el PATH." >&2; exit 1; }
[ -n "${WIKIJS_API_KEY:-}" ] || { echo "[x] Falta WIKIJS_API_KEY (defínela en infra/.env.local)." >&2; exit 1; }

exec node "${SCRIPT_DIR}/seed-wiki-content.js" "$@"
