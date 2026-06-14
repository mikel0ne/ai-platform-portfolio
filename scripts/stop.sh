#!/usr/bin/env bash
# =============================================================================
#  scripts/stop.sh — Para el Backend y el Frontend arrancados con start.sh
# =============================================================================

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPTS_DIR")"
PID_FILE="$ROOT_DIR/.running.pids"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo -e "${YELLOW}[STOP]${NC} Deteniendo servicios..."

if [[ ! -f "$PID_FILE" ]]; then
  echo -e "${YELLOW}[INFO]${NC} No hay fichero de PIDs (.running.pids). ¿Ya están parados?"
  # Intentar matar por nombre de proceso como fallback
  pkill -f "spring-boot:run" 2>/dev/null && echo -e "  ${RED}✗${NC} Spring Boot detenido"
  pkill -f "ng serve"         2>/dev/null && echo -e "  ${RED}✗${NC} Angular detenido"
  exit 0
fi

while IFS= read -r pid; do
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo -e "  ${RED}✗${NC} PID $pid detenido"
  else
    echo -e "  ${YELLOW}~${NC} PID $pid ya no existe"
  fi
done < "$PID_FILE"

rm -f "$PID_FILE"
echo -e "${GREEN}[OK]${NC} Servicios parados."

