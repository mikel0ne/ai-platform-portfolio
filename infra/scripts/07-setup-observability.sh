#!/usr/bin/env bash
# =============================================================================
# 07-setup-observability.sh
# Instala el stack de LOGS compartido (Loki + Promtail + Grafana) en el
# namespace 'observability' de k3s. Recoge los logs de todo el clúster.
#
# Usa el chart grafana/loki-stack con infra/observability/loki-stack-values.yaml.
# Expone Grafana por Traefik en http://grafana.local (admin / GRAFANA_ADMIN_PASSWORD de infra/.env.local).
#
# Requisitos: Rancher Desktop / k3s corriendo, kubectl y helm disponibles.
# =============================================================================

set -euo pipefail

NS="observability"
RELEASE="loki"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OBS_DIR="${SCRIPT_DIR}/../observability"
VALUES="${OBS_DIR}/loki-stack-values.yaml"
ENV_FILE="$(cd "${OBS_DIR}/.." && pwd)/.env.local"
if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi
: "${GRAFANA_ADMIN_PASSWORD:?Define GRAFANA_ADMIN_PASSWORD in infra/.env.local (see infra/.env.local.example)}"
GRAFANA_INGRESS="${OBS_DIR}/grafana-ingress.yaml"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requisitos
# =============================================================================
step "Verificando requisitos..."
command -v kubectl &>/dev/null || fail "'kubectl' no encontrado."
command -v helm &>/dev/null    || fail "'helm' no encontrado."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$VALUES" ]] || fail "No encuentro $VALUES"
ok "kubectl, helm y k3s disponibles"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Asegurando namespace '$NS'..."
kubectl get namespace "$NS" &>/dev/null && ok "Namespace '$NS' ya existe" || {
  kubectl create namespace "$NS"; ok "Namespace '$NS' creado"
}

# =============================================================================
# 2. Repo Helm de Grafana
# =============================================================================
step "Añadiendo el repo Helm de Grafana..."
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update grafana >/dev/null
ok "Repo grafana actualizado"

# =============================================================================
# 3. Instalar / actualizar loki-stack
# =============================================================================
step "Instalando loki-stack (Loki + Promtail + Grafana)..."
helm upgrade --install "$RELEASE" grafana/loki-stack \
  --set-string grafana.adminPassword="${GRAFANA_ADMIN_PASSWORD}" \
  --namespace "$NS" \
  -f "$VALUES" \
  --wait --timeout 5m
ok "loki-stack desplegado"

# =============================================================================
# 4. Ingress de Grafana (Traefik)
# =============================================================================
step "Aplicando Ingress de Grafana (grafana.local)..."
kubectl apply -f "$GRAFANA_INGRESS"
ok "Ingress 'grafana' aplicado"

# =============================================================================
# 5. Estado
# =============================================================================
step "Pods en '$NS':"
kubectl get pods -n "$NS"

# =============================================================================
# 6. Resumen
# =============================================================================
echo ""
echo "============================================================"
echo "  Observabilidad (logs) desplegada en namespace '$NS'"
echo "============================================================"
echo "  Grafana   : http://grafana.local   (admin / GRAFANA_ADMIN_PASSWORD)"
echo "  Loki      : loki.${NS}.svc.cluster.local:3100 (datasource ya configurado)"
echo "  Promtail  : DaemonSet — recoge logs de TODOS los namespaces"
echo "------------------------------------------------------------"
echo "  Resuelve el host (una vez, como Admin):"
echo "    infra/scripts/add-host.ps1 -Hostname grafana.local"
echo "------------------------------------------------------------"
echo "  En Grafana → Explore → datasource Loki. Ejemplos de query:"
echo '    {namespace="autoops"}'
echo '    {namespace="autoops", pod=~"autoops-backend.*"} |= "ERROR"'
echo "============================================================"
