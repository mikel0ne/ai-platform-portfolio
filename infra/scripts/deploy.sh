#!/usr/bin/env bash
# =============================================================================
# deploy.sh — despliega un proyecto del monorepo en Kubernetes (k3s)
# =============================================================================
# Script GENÉRICO: lo específico de cada proyecto vive en
# infra/projects/<project>.env (namespace, imágenes, secrets, rutas de ingress,
# contexto de build). Los nombres de values/namespace/rbac/.env se derivan por
# CONVENCIÓN del nombre de proyecto:
#
#   infra/values-<project>-backend.yaml      infra/values-<project>-frontend.yaml
#   infra/namespace-<project>.yaml           infra/rbac-<project>.yaml (opcional)
#   infra/.env.<project>  (+ .env.<project>.local, gitignored)
#
# Usa el chart genérico infra/charts/generic-app. Crea el namespace, aplica el
# RBAC si existe, sincroniza los Secrets desde el .env y hace helm upgrade
# --install idempotente.
#
# Uso:
#   ./infra/scripts/deploy.sh --project autoops    --component backend
#   ./infra/scripts/deploy.sh --project l1-copilot --component all --tag 1.0.2
#   ./infra/scripts/deploy.sh --project autoops    --component frontend --skip-build
#   ./infra/scripts/deploy.sh --project autoops    --uninstall
#
# Flags:
#   --project       Nombre del proyecto (obligatorio). Debe existir infra/projects/<p>.env
#   --component     backend | frontend | all   (obligatorio salvo con --uninstall)
#   --tag           Tag de la imagen (default: 1.0.0)
#   --skip-build    No hace docker build/push, solo helm
#   --skip-secrets  No toca los Secrets de K8s
#   --dry-run       Renderiza el chart con helm template, no aplica nada
#   --uninstall     helm uninstall de los releases y borra el namespace
# =============================================================================
set -euo pipefail

# ── Resolución de rutas ──────────────────────────────────────────────────────
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly REPO_ROOT="$(cd "${INFRA_DIR}/.." && pwd)"
readonly CHART_DIR="${INFRA_DIR}/charts/generic-app"

# ── Logging ──────────────────────────────────────────────────────────────────
log()  { printf '\033[1;34m▸\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

usage() {
  sed -n '2,33p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 1
}

# ── Parseo de argumentos ─────────────────────────────────────────────────────
PROJECT=""
COMPONENT=""
TAG="1.0.0"
SKIP_BUILD="false"
SKIP_SECRETS="false"
DRY_RUN="false"
UNINSTALL="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project)      PROJECT="${2:?--project requiere valor}"; shift 2 ;;
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

# ── Carga del descriptor de proyecto ─────────────────────────────────────────
[[ -n "${PROJECT}" ]] || { err "Debes indicar --project <nombre>"; usage; }
readonly PROJECT_ENV="${INFRA_DIR}/projects/${PROJECT}.env"
[[ -f "${PROJECT_ENV}" ]] || { err "No existe el descriptor ${PROJECT_ENV}"; exit 1; }
# shellcheck disable=SC1090
source "${PROJECT_ENV}"

: "${NAMESPACE:?El descriptor debe definir NAMESPACE}"
: "${IMAGE_BACKEND:?El descriptor debe definir IMAGE_BACKEND}"
: "${IMAGE_FRONTEND:?El descriptor debe definir IMAGE_FRONTEND}"

# ── Rutas derivadas por convención ───────────────────────────────────────────
readonly RELEASE_BACKEND="${PROJECT}-backend"
readonly RELEASE_FRONTEND="${PROJECT}-frontend"
readonly VALUES_BACKEND="${INFRA_DIR}/values-${PROJECT}-backend.yaml"
readonly VALUES_FRONTEND="${INFRA_DIR}/values-${PROJECT}-frontend.yaml"
readonly NAMESPACE_MANIFEST="${INFRA_DIR}/namespace-${PROJECT}.yaml"
readonly RBAC_MANIFEST="${INFRA_DIR}/rbac-${PROJECT}.yaml"
readonly ENV_SHARED="${INFRA_DIR}/.env.${PROJECT}"
readonly ENV_LOCAL="${INFRA_DIR}/.env.${PROJECT}.local"

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
# Orden (cada uno sobreescribe al anterior):
#   1) .env.<project>        compartido versionado (placeholders, no sensible)
#   2) .env.local            secretos COMPARTIDOS por todos los proyectos (gitignored)
#                            → p.ej. una única GEMINI_API_KEY para todo el portfolio
#   3) .env.<project>.local  overrides locales del proyecto (gitignored), opcional
readonly ENV_SHARED_LOCAL="${INFRA_DIR}/.env.local"
load_env() {
  [[ -f "${ENV_SHARED}" ]] || { err "No se encuentra ${ENV_SHARED}"; exit 1; }
  set -a
  # shellcheck disable=SC1090
  source "${ENV_SHARED}"
  if [[ -f "${ENV_SHARED_LOCAL}" ]]; then
    # shellcheck disable=SC1090
    source "${ENV_SHARED_LOCAL}"
  fi
  if [[ -f "${ENV_LOCAL}" ]]; then
    # shellcheck disable=SC1090
    source "${ENV_LOCAL}"
  fi
  if [[ ! -f "${ENV_SHARED_LOCAL}" && ! -f "${ENV_LOCAL}" ]]; then
    warn "No hay ${ENV_SHARED_LOCAL} ni ${ENV_LOCAL} — usando solo valores compartidos."
    warn "Crea infra/.env.local con tu GEMINI_API_KEY (compartida por todos los proyectos)."
  fi
  set +a
}

# ── Namespace + RBAC ─────────────────────────────────────────────────────────
ensure_namespace() {
  log "Asegurando namespace '${NAMESPACE}'"
  kubectl apply -f "${NAMESPACE_MANIFEST}" >/dev/null
  ok "Namespace listo"
  if [[ -f "${RBAC_MANIFEST}" ]]; then
    log "Aplicando RBAC (${RBAC_MANIFEST##*/})"
    kubectl apply -f "${RBAC_MANIFEST}" >/dev/null
    ok "RBAC aplicado"
  fi
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
    warn "  → Aplico el Secret con valores vacíos; rellénalos en .env.${PROJECT}.local"
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
  local ref="SECRETS_${component^^}"
  if ! declare -p "${ref}" >/dev/null 2>&1; then
    ok "  (sin Secrets configurados para ${component})"
    return
  fi
  log "Sincronizando Secrets para '${component}'"
  local -n SECRETS_REF="${ref}"
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
      dockerfile="${DOCKERFILE_BACKEND}"
      context="${CONTEXT_BACKEND}"
      # El corpus del RAG (CORPUS_DIR del descriptor, relativo a la raíz) se copia
      # al contexto de build para que el Dockerfile lo hornee en /app/corpus.
      # Si el proyecto no tiene corpus, se hornea un directorio vacío (COPY no falla).
      rm -rf "${REPO_ROOT}/${context}/corpus"
      if [[ -n "${CORPUS_DIR:-}" && -d "${REPO_ROOT}/${CORPUS_DIR}" ]]; then
        cp -r "${REPO_ROOT}/${CORPUS_DIR}" "${REPO_ROOT}/${context}/corpus"
      else
        mkdir -p "${REPO_ROOT}/${context}/corpus"
      fi
      ;;
    frontend)
      image="${IMAGE_FRONTEND}"
      dockerfile="${DOCKERFILE_FRONTEND}"
      context="${CONTEXT_FRONTEND}"
      # El Dockerfile del frontend espera nginx.conf en el contexto; vive en docker/.
      cp "${REPO_ROOT}/docker/frontend-nginx.conf" "${REPO_ROOT}/${context}/nginx.conf"
      ;;
  esac
  log "Build & push: ${image}:${TAG}  (contexto: ${context})"
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
  [[ -f "${values}" ]] || { err "No existe el values ${values}"; exit 1; }

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
  # --force-conflicts: Helm 4 usa server-side apply por defecto. Si un kubectl set/edit
  # manual tocó antes el mismo recurso, queda como field-manager en conflicto y el
  # upgrade falla; Helm debe poder reafirmar la propiedad de los campos que gestiona.
  helm upgrade --install "${release}" "${CHART_DIR}" \
    --namespace "${NAMESPACE}" \
    -f "${values}" \
    --set image.tag="${TAG}" \
    --force-conflicts \
    --wait --timeout 3m
  ok "Release '${release}' desplegado"
}

# ── Uninstall ────────────────────────────────────────────────────────────────
uninstall_all() {
  log "Uninstall de los releases helm de '${PROJECT}'"
  helm uninstall "${RELEASE_BACKEND}"  --namespace "${NAMESPACE}" 2>/dev/null || true
  helm uninstall "${RELEASE_FRONTEND}" --namespace "${NAMESPACE}" 2>/dev/null || true
  log "Borrando namespace '${NAMESPACE}'"
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found
  ok "Limpieza completada"
}

# ── Orquestación ─────────────────────────────────────────────────────────────
deploy_component() {
  local component="$1"
  printf '\n\033[1;36m═══ %s · %s ═══\033[0m\n' "${PROJECT}" "${component^^}"
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
  ok "Despliegue de '${PROJECT}' completado en namespace '${NAMESPACE}'"
  log "Ingress (tras infra/scripts/setup-app-ingress.sh --project ${PROJECT}):"
  log "  http://${INGRESS_HOST}/   y rutas backend: ${INGRESS_BACKEND_PATHS:-/api}"
  log "Comprueba: kubectl get pods -n ${NAMESPACE}"
}

main "$@"
