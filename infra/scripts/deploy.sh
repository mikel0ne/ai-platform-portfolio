#!/usr/bin/env bash
# =============================================================================
# deploy.sh — despliega el L1 Support Copilot en Kubernetes (k3s)
# =============================================================================
# Usa el chart genérico infra/charts/generic-app con los values files de cada
# componente. Crea el namespace, sincroniza los Secrets desde el .env compartido
# (+ override local) y hace helm upgrade --install idempotente.
#
# Uso:
#   ./infra/scripts/deploy.sh --component backend
#   ./infra/scripts/deploy.sh --component frontend  --tag 1.0.1
#   ./infra/scripts/deploy.sh --component all       --skip-build
#   ./infra/scripts/deploy.sh --component backend   --skip-secrets --dry-run
#   ./infra/scripts/deploy.sh --uninstall
#
# Flags:
#   --component   backend | frontend | all   (obligatorio salvo con --uninstall)
#   --tag         Tag de la imagen (default: 1.0.0)
#   --skip-build  No hace docker build/push, solo helm
#   --skip-secrets  No toca los Secrets de K8s
#   --dry-run     Renderiza el chart con helm template, no aplica nada
#   --uninstall   helm uninstall de los dos releases y borra el namespace
# =============================================================================
set -euo pipefail

# ── Constantes del proyecto ──────────────────────────────────────────────────
readonly NAMESPACE="l1-copilot"
readonly RELEASE_BACKEND="l1-copilot-backend"
readonly RELEASE_FRONTEND="l1-copilot-frontend"
readonly IMAGE_BACKEND="registry.local/l1-copilot-backend"
readonly IMAGE_FRONTEND="registry.local/l1-copilot-frontend"

# Mapping <K8s Secret name> -> lista de claves a buscar como env vars.
# Para añadir un Secret nuevo: añade una entrada y nombra las env vars en .env.
declare -rA SECRETS_BACKEND=(
  [gemini-api-key]="GEMINI_API_KEY"
  [postgres-credentials]="POSTGRES_USER POSTGRES_PASSWORD"
)
# El frontend no consume Secrets (build estático Angular).
declare -rA SECRETS_FRONTEND=()

# ── Resolución de rutas ──────────────────────────────────────────────────────
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_ROOT="$(cd "${INFRA_DIR}/.." && pwd)"
readonly CHART_DIR="${INFRA_DIR}/charts/generic-app"
readonly NAMESPACE_MANIFEST="${INFRA_DIR}/namespace-l1-copilot.yaml"
readonly VALUES_BACKEND="${INFRA_DIR}/values-l1-copilot-backend.yaml"
readonly VALUES_FRONTEND="${INFRA_DIR}/values-l1-copilot-frontend.yaml"
readonly ENV_SHARED="${INFRA_DIR}/.env.l1-copilot"
readonly ENV_LOCAL="${INFRA_DIR}/.env.l1-copilot.local"

# ── Logging ──────────────────────────────────────────────────────────────────
log()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

# ── Parseo de argumentos ─────────────────────────────────────────────────────
COMPONENT=""
TAG="1.0.0"
SKIP_BUILD="false"
SKIP_SECRETS="false"
DRY_RUN="false"
UNINSTALL="false"

usage() {
  cat <<'EOF'
deploy.sh — despliega el L1 Support Copilot en Kubernetes (k3s)

Usa el chart genérico infra/charts/generic-app con los values files de cada
componente. Crea el namespace, sincroniza los Secrets desde el .env compartido
(+ override local) y hace helm upgrade --install idempotente.

Uso:
  ./infra/scripts/deploy.sh --component backend
  ./infra/scripts/deploy.sh --component frontend  --tag 1.0.1
  ./infra/scripts/deploy.sh --component all       --skip-build
  ./infra/scripts/deploy.sh --component backend   --skip-secrets --dry-run
  ./infra/scripts/deploy.sh --uninstall

Flags:
  --component     backend | frontend | all   (obligatorio salvo con --uninstall)
  --tag           Tag de la imagen (default: 1.0.0)
  --skip-build    No hace docker build/push, solo helm
  --skip-secrets  No toca los Secrets de K8s
  --dry-run       Renderiza el chart con helm template, no aplica nada
  --uninstall     helm uninstall de los releases y borra el namespace
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --component)    COMPONENT="${2:?--component requiere valor}"; shift 2 ;;
    --tag)          TAG="${2:?--tag requiere valor}"; shift 2 ;;
    --skip-build)   SKIP_BUILD="true"; shift ;;
    --skip-secrets) SKIP_SECRETS="true"; shift ;;
    --dry-run)      DRY_RUN="true"; shift ;;
    --uninstall)    UNINSTALL="true"; shift ;;
    -h|--help)      usage ;;
    *) err "Flag desconocido: $1"; usage ;;
  esac
done

# ── Validaciones previas ─────────────────────────────────────────────────────
require_bin() {
  command -v "$1" >/dev/null 2>&1 || { err "Falta '$1' en PATH"; exit 1; }
}
require_bin kubectl
require_bin helm
[[ "${SKIP_BUILD}" == "true" ]] || require_bin docker

if [[ "${UNINSTALL}" != "true" && -z "${COMPONENT}" ]]; then
  err "Debes indicar --component backend|frontend|all (o --uninstall)"; usage
fi
if [[ "${UNINSTALL}" != "true" && ! "${COMPONENT}" =~ ^(backend|frontend|all)$ ]]; then
  err "Valor inválido para --component: ${COMPONENT}"; exit 1
fi

# ── Carga de variables de entorno ────────────────────────────────────────────
load_env() {
  [[ -f "${ENV_SHARED}" ]] || { err "No se encuentra ${ENV_SHARED}"; exit 1; }
  set -a
  # shellcheck disable=SC1090
  source "${ENV_SHARED}"
  if [[ -f "${ENV_LOCAL}" ]]; then
    # shellcheck disable=SC1090
    source "${ENV_LOCAL}"
  else
    warn "No hay ${ENV_LOCAL} — usando solo valores compartidos."
    warn "Copia infra/.env.l1-copilot.local.example y rellena tu GEMINI_API_KEY."
  fi
  set +a
}

# ── Namespace ────────────────────────────────────────────────────────────────
ensure_namespace() {
  log "Asegurando namespace '${NAMESPACE}'"
  kubectl apply -f "${NAMESPACE_MANIFEST}" >/dev/null
  ok "Namespace listo"
}

# ── Secrets ──────────────────────────────────────────────────────────────────
# Crea o actualiza un Secret K8s de tipo Opaque a partir de la lista de claves
# que se le pasa. Usa `kubectl apply` para que sea idempotente.
sync_one_secret() {
  local secret_name="$1"
  shift
  local -a literals=()
  local key value missing=0
  for key in "$@"; do
    value="${!key-}"
    if [[ -z "${value}" ]]; then
      warn "  · ${key} vacía (Secret '${secret_name}')"
      missing=1
    fi
    literals+=("--from-literal=${key}=${value}")
  done
  if [[ ${missing} -eq 1 ]]; then
    warn "  → Continuo aplicando el Secret con valores vacíos; rellénalos en .env.l1-copilot.local"
  fi

  kubectl create secret generic "${secret_name}" \
    --namespace "${NAMESPACE}" \
    --dry-run=client -o yaml \
    "${literals[@]}" \
  | kubectl apply -f - >/dev/null
  ok "  Secret '${secret_name}' (${#literals[@]} claves)"
}

sync_secrets() {
  local component="$1"
  if [[ "${SKIP_SECRETS}" == "true" ]]; then
    warn "--skip-secrets activo, no toco los Secrets"
    return
  fi
  log "Sincronizando Secrets para '${component}'"
  local -n SECRETS_REF="SECRETS_${component^^}"
  if [[ ${#SECRETS_REF[@]} -eq 0 ]]; then
    ok "  (sin Secrets configurados para ${component})"
    return
  fi
  local secret_name keys
  for secret_name in "${!SECRETS_REF[@]}"; do
    keys="${SECRETS_REF[${secret_name}]}"
    # shellcheck disable=SC2086
    sync_one_secret "${secret_name}" ${keys}
  done
}

# ── Build & push ─────────────────────────────────────────────────────────────
build_and_push() {
  local component="$1"
  if [[ "${SKIP_BUILD}" == "true" ]]; then
    warn "--skip-build activo, no hago docker build/push de '${component}'"
    return
  fi
  local image dockerfile context
  case "${component}" in
    backend)
      image="${IMAGE_BACKEND}"
      dockerfile="docker/backend.Dockerfile"
      context="backend"
      ;;
    frontend)
      image="${IMAGE_FRONTEND}"
      dockerfile="docker/frontend.Dockerfile"
      context="frontend"
      # El Dockerfile espera nginx.conf en el contexto; vive en docker/.
      cp "${REPO_ROOT}/docker/frontend-nginx.conf" "${REPO_ROOT}/frontend/nginx.conf"
      ;;
  esac
  log "Build & push: ${image}:${TAG}"
  ( cd "${REPO_ROOT}" && docker build -t "${image}:${TAG}" -f "${dockerfile}" "${context}" )
  ( cd "${REPO_ROOT}" && docker push "${image}:${TAG}" )
  ok "Imagen disponible en el registry local"
}

# ── Helm ─────────────────────────────────────────────────────────────────────
helm_deploy() {
  local component="$1"
  local release values
  case "${component}" in
    backend)  release="${RELEASE_BACKEND}";  values="${VALUES_BACKEND}"  ;;
    frontend) release="${RELEASE_FRONTEND}"; values="${VALUES_FRONTEND}" ;;
  esac

  if [[ "${DRY_RUN}" == "true" ]]; then
    log "helm template ${release} (dry-run)"
    helm template "${release}" "${CHART_DIR}" \
      --namespace "${NAMESPACE}" \
      -f "${values}" \
      --set image.tag="${TAG}"
    ok "Plantilla renderizada (no aplicada)"
    return
  fi

  log "helm upgrade --install ${release}"
  helm upgrade --install "${release}" "${CHART_DIR}" \
    --namespace "${NAMESPACE}" \
    -f "${values}" \
    --set image.tag="${TAG}" \
    --wait --timeout 3m
  ok "Release '${release}' desplegado"
}

# ── Uninstall ────────────────────────────────────────────────────────────────
uninstall_all() {
  log "Uninstall de los releases helm"
  helm uninstall "${RELEASE_BACKEND}"  --namespace "${NAMESPACE}" 2>/dev/null || true
  helm uninstall "${RELEASE_FRONTEND}" --namespace "${NAMESPACE}" 2>/dev/null || true
  log "Borrando namespace '${NAMESPACE}'"
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found
  ok "Limpieza completada"
}

# ── Orquestación ─────────────────────────────────────────────────────────────
deploy_component() {
  local component="$1"
  printf '\n\033[1;36m═══ %s ═══\033[0m\n' "${component^^}"
  build_and_push "${component}"
  sync_secrets   "${component}"
  helm_deploy    "${component}"
}

main() {
  if [[ "${UNINSTALL}" == "true" ]]; then
    uninstall_all
    return
  fi

  load_env
  ensure_namespace

  case "${COMPONENT}" in
    backend)  deploy_component backend  ;;
    frontend) deploy_component frontend ;;
    all)      deploy_component backend; deploy_component frontend ;;
  esac

  printf '\n'
  ok "Despliegue completado en namespace '${NAMESPACE}'"
  log "Acceso (tras ejecutar infra/scripts/06-setup-app-ingress.sh):"
  log "  http://l1-copilot.local/      (frontend)"
  log "  http://l1-copilot.local/api   (backend)"
  log "Comprueba: kubectl get pods -n ${NAMESPACE}"
}

main "$@"
