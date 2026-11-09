#!/usr/bin/env bash
# =============================================================================
# deploy.sh: deploys one project of the monorepo to Kubernetes (k3s)
# =============================================================================
# Generic script: everything project-specific lives in
# infra/projects/<project>.env (namespace, images, secrets, ingress paths,
# build context). Values, namespace, RBAC and env file names follow from the
# project name by convention:
#
#   infra/values-<project>-backend.yaml      infra/values-<project>-frontend.yaml
#   infra/namespace-<project>.yaml           infra/rbac-<project>.yaml (optional)
#   infra/.env.local  (+ .env.<project>.local, both gitignored)
#
# Uses the generic chart infra/charts/generic-app: creates the namespace, applies
# the RBAC when present, syncs the Secrets from the env files and runs an idempotent
# helm upgrade --install.
#
# Usage:
#   ./infra/scripts/deploy.sh --project autoops    --component backend
#   ./infra/scripts/deploy.sh --project autoops    --component backend --bump minor
#   ./infra/scripts/deploy.sh --project l1-copilot --component all --tag 1.0.2
#   ./infra/scripts/deploy.sh --project autoops    --component frontend --skip-build
#   ./infra/scripts/deploy.sh --project autoops    --uninstall
#
# Flags:
#   --project       Project name (required). infra/projects/<p>.env must exist
#   --component     backend | frontend | all   (required unless --uninstall)
#   --tag           Image tag (default: version from pom.xml/package.json)
#   --bump          major | minor | patch   (bumps the manifest version and uses it as tag)
#   --skip-build    No docker build/push, helm only
#   --skip-secrets  Leaves the Kubernetes Secrets alone
#   --dry-run       Renders the chart with helm template, applies nothing
#   --uninstall     helm uninstall of the releases and deletes the namespace
# =============================================================================
set -euo pipefail

# ── Paths ────────────────────────────────────────────────────────────────────
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

# ── Arguments ────────────────────────────────────────────────────────────────
PROJECT=""
COMPONENT=""
TAG=""
BUMP=""
SKIP_BUILD="false"
SKIP_SECRETS="false"
DRY_RUN="false"
UNINSTALL="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project)      PROJECT="${2:?--project needs a value}"; shift 2 ;;
    --component)    COMPONENT="${2:?--component needs a value}"; shift 2 ;;
    --tag)          TAG="${2:?--tag needs a value}"; shift 2 ;;
    --bump)         BUMP="${2:?--bump needs a value}"; shift 2 ;;
    --skip-build)   SKIP_BUILD="true"; shift ;;
    --skip-secrets) SKIP_SECRETS="true"; shift ;;
    --dry-run)      DRY_RUN="true"; shift ;;
    --uninstall)    UNINSTALL="true"; shift ;;
    -h|--help)      usage ;;
    *) err "Unknown flag: $1"; usage ;;
  esac
done

# ── Project descriptor ───────────────────────────────────────────────────────
[[ -n "${PROJECT}" ]] || { err "--project <name> is required"; usage; }
readonly PROJECT_ENV="${INFRA_DIR}/projects/${PROJECT}.env"
[[ -f "${PROJECT_ENV}" ]] || { err "Descriptor ${PROJECT_ENV} not found"; exit 1; }
# shellcheck disable=SC1090
source "${PROJECT_ENV}"

: "${NAMESPACE:?The descriptor must define NAMESPACE}"
: "${IMAGE_BACKEND:?The descriptor must define IMAGE_BACKEND}"
: "${IMAGE_FRONTEND:?The descriptor must define IMAGE_FRONTEND}"

# ── Paths derived by convention ──────────────────────────────────────────────
readonly RELEASE_BACKEND="${PROJECT}-backend"
readonly RELEASE_FRONTEND="${PROJECT}-frontend"
readonly VALUES_BACKEND="${INFRA_DIR}/values-${PROJECT}-backend.yaml"
readonly VALUES_FRONTEND="${INFRA_DIR}/values-${PROJECT}-frontend.yaml"
readonly NAMESPACE_MANIFEST="${INFRA_DIR}/namespace-${PROJECT}.yaml"
readonly RBAC_MANIFEST="${INFRA_DIR}/rbac-${PROJECT}.yaml"
readonly ENV_LOCAL="${INFRA_DIR}/.env.${PROJECT}.local"

# ── Pre-flight checks ────────────────────────────────────────────────────────
require_bin() {
  command -v "$1" >/dev/null 2>&1 || { err "'$1' is not on PATH"; exit 1; }
}
require_bin kubectl
require_bin helm
[[ "${SKIP_BUILD}" == "true" ]] || require_bin docker

if [[ "${UNINSTALL}" != "true" && -z "${COMPONENT}" ]]; then
  err "--component backend|frontend|all is required (or --uninstall)"; usage
fi
if [[ "${UNINSTALL}" != "true" && ! "${COMPONENT}" =~ ^(backend|frontend|all)$ ]]; then
  err "Invalid value for --component: ${COMPONENT}"; exit 1
fi

# ── Environment ──────────────────────────────────────────────────────────────
#   1) infra/.env.local            local secrets shared by every project (gitignored)
#   2) infra/.env.<project>.local  optional per-project overrides (gitignored)
readonly ENV_SHARED_LOCAL="${INFRA_DIR}/.env.local"
load_env() {
  if [[ ! -f "${ENV_SHARED_LOCAL}" ]]; then
    err "Missing ${ENV_SHARED_LOCAL}: cp infra/.env.local.example infra/.env.local and fill it in"
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "${ENV_SHARED_LOCAL}"
  if [[ -f "${ENV_LOCAL}" ]]; then
    # shellcheck disable=SC1090
    source "${ENV_LOCAL}"
  fi
  set +a
}

# ── Automatic versioning ─────────────────────────────────────────────────────
get_version_from_manifest() {
  local component="$1"
  local manifest_file
  case "${component}" in
    backend)
      manifest_file="${REPO_ROOT}/${PROJECT}/backend/pom.xml"
      [[ -f "${manifest_file}" ]] || { err "${manifest_file} not found"; return 1; }
      sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' "${manifest_file}" | head -1
      ;;
    frontend)
      manifest_file="${REPO_ROOT}/${PROJECT}/frontend/package.json"
      [[ -f "${manifest_file}" ]] || { err "${manifest_file} not found"; return 1; }
      sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${manifest_file}"
      ;;
  esac
}

bump_version() {
  local version="$1"
  local bump_type="$2"
  local major minor patch
  IFS='.' read -r major minor patch <<< "${version}"
  patch=${patch:-0}

  case "${bump_type}" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) err "bump_type must be major|minor|patch"; return 1 ;;
  esac
  echo "${major}.${minor}.${patch}"
}

update_version_in_manifest() {
  local component="$1"
  local new_version="$2"
  local manifest_file
  case "${component}" in
    backend)
      manifest_file="${REPO_ROOT}/${PROJECT}/backend/pom.xml"
      sed -i "0,/<version>[^<]*<\/version>/{s/<version>[^<]*<\/version>/<version>${new_version}<\/version>/}" "${manifest_file}"
      ok "pom.xml version set to ${new_version}"
      ;;
    frontend)
      manifest_file="${REPO_ROOT}/${PROJECT}/frontend/package.json"
      sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"${new_version}\"/" "${manifest_file}"
      ok "package.json version set to ${new_version}"
      ;;
  esac
}

apply_version_bump() {
  local component="$1"
  if [[ -z "${BUMP}" ]]; then
    return
  fi
  log "Applying '${BUMP}' bump to ${component}"
  local current_version
  current_version=$(get_version_from_manifest "${component}") || return 1
  local new_version
  new_version=$(bump_version "${current_version}" "${BUMP}") || return 1
  log "  ${current_version} → ${new_version}"
  if [[ "${DRY_RUN}" == "true" ]]; then
    warn "  (dry-run: manifest left unchanged)"
  else
    update_version_in_manifest "${component}" "${new_version}"
  fi
  TAG="${new_version}"
}

# ── Namespace + RBAC ─────────────────────────────────────────────────────────
ensure_namespace() {
  log "Ensuring namespace '${NAMESPACE}'"
  kubectl apply -f "${NAMESPACE_MANIFEST}" >/dev/null
  ok "Namespace ready"
  if [[ -f "${RBAC_MANIFEST}" ]]; then
    log "Applying RBAC (${RBAC_MANIFEST##*/})"
    kubectl apply -f "${RBAC_MANIFEST}" >/dev/null
    ok "RBAC applied"
  fi
}

# ── Secrets ──────────────────────────────────────────────────────────────────
# Creates or updates an Opaque Secret from the given keys; kubectl apply keeps it idempotent.
sync_one_secret() {
  local secret_name="$1"
  shift
  local -a literals=()
  local key value missing=0
  for key in "$@"; do
    value="${!key-}"
    if [[ -z "${value}" ]]; then
      warn "  · ${key} is empty (Secret '${secret_name}')"
      missing=1
    fi
    literals+=("--from-literal=${key}=${value}")
  done
  if [[ ${missing} -eq 1 ]]; then
    warn "  → Applying the Secret with empty values; fill them in infra/.env.local"
  fi

  kubectl create secret generic "${secret_name}" \
    --namespace "${NAMESPACE}" \
    --dry-run=client -o yaml \
    "${literals[@]}" \
  | kubectl apply -f - >/dev/null
  ok "  Secret '${secret_name}' (${#literals[@]} keys)"
}

sync_secrets() {
  local component="$1"
  if [[ "${SKIP_SECRETS}" == "true" ]]; then
    warn "--skip-secrets set, Secrets left alone"
    return
  fi
  local ref="SECRETS_${component^^}"
  if ! declare -p "${ref}" >/dev/null 2>&1; then
    ok "  (no Secrets configured for ${component})"
    return
  fi
  log "Syncing Secrets for '${component}'"
  local -n SECRETS_REF="${ref}"
  if [[ ${#SECRETS_REF[@]} -eq 0 ]]; then
    ok "  (no Secrets configured for ${component})"
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
    warn "--skip-build set, no docker build/push for '${component}'"
    return
  fi
  local image dockerfile context
  case "${component}" in
    backend)
      image="${IMAGE_BACKEND}"
      dockerfile="${DOCKERFILE_BACKEND}"
      context="${CONTEXT_BACKEND}"
      # The RAG corpus (CORPUS_DIR, relative to the repo root) is copied into the build
      # context so the Dockerfile bakes it into /app/corpus; without one the folder is empty.
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
      # The frontend Dockerfile expects nginx.conf in its context; the shared one lives in docker/.
      cp "${REPO_ROOT}/docker/frontend-nginx.conf" "${REPO_ROOT}/${context}/nginx.conf"
      ;;
  esac
  log "Build & push: ${image}:${TAG}  (context: ${context})"
  ( cd "${REPO_ROOT}" && docker build -t "${image}:${TAG}" -f "${dockerfile}" "${context}" )
  ( cd "${REPO_ROOT}" && docker push "${image}:${TAG}" )
  ok "Image available in the local registry"
}

# ── Helm ─────────────────────────────────────────────────────────────────────
helm_deploy() {
  local component="$1"
  local release values
  case "${component}" in
    backend)  release="${RELEASE_BACKEND}";  values="${VALUES_BACKEND}"  ;;
    frontend) release="${RELEASE_FRONTEND}"; values="${VALUES_FRONTEND}" ;;
  esac
  [[ -f "${values}" ]] || { err "Values file ${values} not found"; exit 1; }

  if [[ "${DRY_RUN}" == "true" ]]; then
    log "helm template ${release} (dry-run)"
    helm template "${release}" "${CHART_DIR}" \
      --namespace "${NAMESPACE}" \
      -f "${values}" \
      --set image.tag="${TAG}"
    ok "Template rendered (not applied)"
    return
  fi

  log "helm upgrade --install ${release}"
  # Helm 4 uses server-side apply: after a manual kubectl edit the upgrade fails on field-manager
  # conflicts unless Helm may reclaim ownership of the fields it manages.
  helm upgrade --install "${release}" "${CHART_DIR}" \
    --namespace "${NAMESPACE}" \
    -f "${values}" \
    --set image.tag="${TAG}" \
    --force-conflicts \
    --wait --timeout 3m
  ok "Release '${release}' deployed"
}

# ── Uninstall ────────────────────────────────────────────────────────────────
uninstall_all() {
  log "Uninstalling the helm releases of '${PROJECT}'"
  helm uninstall "${RELEASE_BACKEND}"  --namespace "${NAMESPACE}" 2>/dev/null || true
  helm uninstall "${RELEASE_FRONTEND}" --namespace "${NAMESPACE}" 2>/dev/null || true
  log "Deleting namespace '${NAMESPACE}'"
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found
  ok "Cleanup finished"
}

# ── Orchestration ────────────────────────────────────────────────────────────
deploy_component() {
  local component="$1"
  printf '\n\033[1;36m═══ %s · %s ═══\033[0m\n' "${PROJECT}" "${component^^}"
  apply_version_bump "${component}"
  build_and_push "${component}"
  sync_secrets   "${component}"
  helm_deploy    "${component}"
}

main() {
  if [[ "${UNINSTALL}" == "true" ]]; then
    uninstall_all
    return
  fi

  if [[ -n "${BUMP}" && -z "${TAG}" ]]; then
    if ! [[ "${BUMP}" =~ ^(major|minor|patch)$ ]]; then
      err "--bump must be major|minor|patch"; exit 1
    fi
  fi

  load_env
  ensure_namespace

  if [[ -z "${TAG}" && -z "${BUMP}" ]]; then
    if [[ "${COMPONENT}" == "backend" || "${COMPONENT}" == "all" ]]; then
      TAG=$(get_version_from_manifest backend) || TAG="0.0.0"
      log "TAG taken from the backend: ${TAG}"
    elif [[ "${COMPONENT}" == "frontend" ]]; then
      TAG=$(get_version_from_manifest frontend) || TAG="0.0.0"
      log "TAG taken from the frontend: ${TAG}"
    fi
  fi

  case "${COMPONENT}" in
    backend)  deploy_component backend  ;;
    frontend) deploy_component frontend ;;
    all)      deploy_component backend; deploy_component frontend ;;
  esac

  printf '\n'
  ok "'${PROJECT}' deployed to namespace '${NAMESPACE}'"
  log "Ingress (after infra/scripts/setup-app-ingress.sh --project ${PROJECT}):"
  log "  http://${INGRESS_HOST}/   backend paths: ${INGRESS_BACKEND_PATHS:-/api}"
  log "Check: kubectl get pods -n ${NAMESPACE}"
}

main "$@"
