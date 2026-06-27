#!/usr/bin/env bash
# =============================================================================
#  scripts/start.sh — Arranca Backend (Spring Boot) + Frontend (Angular)
#  Uso: ./scripts/start.sh  (desde la raíz del repo)
#       bash scripts/start.sh
# =============================================================================

set -euo pipefail

# ── Colores ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

# ── Directorios ──────────────────────────────────────────────────────────────
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPTS_DIR")"
BACKEND_DIR="$ROOT_DIR/l1-copilot/backend"
FRONTEND_DIR="$ROOT_DIR/l1-copilot/frontend"

# ── Fichero de PIDs para el stop ─────────────────────────────────────────────
PID_FILE="$ROOT_DIR/.running.pids"

# ── Función de limpieza al hacer Ctrl+C ──────────────────────────────────────
cleanup() {
  echo -e "\n${YELLOW}[STOP]${NC} Deteniendo procesos..."
  if [[ -f "$PID_FILE" ]]; then
    while IFS= read -r pid; do
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" && echo -e "  ${RED}✗${NC} PID $pid detenido"
      fi
    done < "$PID_FILE"
    rm -f "$PID_FILE"
  fi
  echo -e "${GREEN}[OK]${NC} Todo parado. ¡Hasta luego!"
  exit 0
}
trap cleanup SIGINT SIGTERM

# ── Configuración de la aplicación ──────────────────────────────────────────
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export GEMINI_API_KEY="${GEMINI_API_KEY:-}"

# ── Comprobaciones previas ───────────────────────────────────────────────────
echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN}  L1 Support Copilot — Arranque local${NC}"
echo -e "${CYAN}============================================================${NC}\n"

command -v mvn  >/dev/null 2>&1 || { echo -e "${RED}[ERROR]${NC} 'mvn' no encontrado en PATH."; exit 1; }
command -v node >/dev/null 2>&1 || { echo -e "${RED}[ERROR]${NC} 'node' no encontrado en PATH."; exit 1; }
command -v npm  >/dev/null 2>&1 || { echo -e "${RED}[ERROR]${NC} 'npm' no encontrado en PATH."; exit 1; }

# ── Instalar dependencias del frontend si hace falta ────────────────────────
if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo -e "${YELLOW}[INFO]${NC} node_modules no encontrado — ejecutando npm install..."
  npm install --prefix "$FRONTEND_DIR"
fi

# ── Limpiar PID file anterior ────────────────────────────────────────────────
rm -f "$PID_FILE"

# ── Backend (Spring Boot — puerto 8079) ──────────────────────────────────────
echo -e "${GREEN}[BACK]${NC} Arrancando Spring Boot en http://localhost:8079 ..."
(
  cd "$BACKEND_DIR"
  mvn spring-boot:run > "$ROOT_DIR/backend.log" 2>&1
) &
BACK_PID=$!
echo "$BACK_PID" >> "$PID_FILE"
echo -e "  PID backend: ${CYAN}$BACK_PID${NC} — logs en backend.log"

# ── Frontend (Angular — puerto 4199) ─────────────────────────────────────────
echo -e "${GREEN}[FRONT]${NC} Arrancando Angular en http://localhost:4199 ..."
(
  cd "$FRONTEND_DIR"
  npm start > "$ROOT_DIR/frontend.log" 2>&1
) &
FRONT_PID=$!
echo "$FRONT_PID" >> "$PID_FILE"
echo -e "  PID frontend: ${CYAN}$FRONT_PID${NC} — logs en frontend.log\n"

# ── Esperar a que el backend esté listo ──────────────────────────────────────
echo -e "${YELLOW}[WAIT]${NC} Esperando a que el backend arranque..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8079/api/actuator/health > /dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC} Backend listo ✓"
    break
  fi
  if ! kill -0 "$BACK_PID" 2>/dev/null; then
    echo -e "${RED}[ERROR]${NC} El backend ha fallado. Revisa backend.log"
    tail -20 "$ROOT_DIR/backend.log"
    cleanup
  fi
  sleep 2
done

# ── Esperar a que el frontend esté listo ─────────────────────────────────────
echo -e "${YELLOW}[WAIT]${NC} Esperando a que el frontend arranque..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:4199 > /dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC} Frontend listo ✓"
    break
  fi
  if ! kill -0 "$FRONT_PID" 2>/dev/null; then
    echo -e "${RED}[ERROR]${NC} El frontend ha fallado. Revisa frontend.log"
    tail -20 "$ROOT_DIR/frontend.log"
    cleanup
  fi
  sleep 2
done

# ── Listo ─────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}============================================================${NC}"
echo -e "${GREEN}  ✓ Todo arrancado correctamente${NC}"
echo -e "  Backend  → ${CYAN}http://localhost:8079/api${NC}"
echo -e "  Swagger  → ${CYAN}http://localhost:8079/api/swagger-ui/index.html${NC}"
echo -e "  Frontend → ${CYAN}http://localhost:4199${NC}"
echo -e "${CYAN}============================================================${NC}"
echo -e "${YELLOW}  Pulsa Ctrl+C para detener ambos servicios${NC}\n"

# ── Mantener el script vivo ───────────────────────────────────────────────────
wait "$BACK_PID" "$FRONT_PID"
