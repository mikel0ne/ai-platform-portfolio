#!/usr/bin/env bash
# =============================================================================
#  scripts/start.sh - starts the L1 Copilot backend (Spring Boot) and frontend (Angular)
#  Usage: ./scripts/start.sh  (from the repo root)
#       bash scripts/start.sh
# =============================================================================

set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

# ── Directories ──────────────────────────────────────────────────────────────
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPTS_DIR")"
BACKEND_DIR="$ROOT_DIR/l1-copilot/backend"
FRONTEND_DIR="$ROOT_DIR/l1-copilot/frontend"

# ── PID file for stop.sh ─────────────────────────────────────────────────────
PID_FILE="$ROOT_DIR/.running.pids"

# ── Cleanup on Ctrl+C ────────────────────────────────────────────────────────
cleanup() {
  echo -e "\n${YELLOW}[STOP]${NC} Stopping processes..."
  if [[ -f "$PID_FILE" ]]; then
    while IFS= read -r pid; do
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" && echo -e "  ${RED}✗${NC} PID $pid stopped"
      fi
    done < "$PID_FILE"
    rm -f "$PID_FILE"
  fi
  echo -e "${GREEN}[OK]${NC} Everything stopped."
  exit 0
}
trap cleanup SIGINT SIGTERM

# ── Application settings ─────────────────────────────────────────────────────
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export GEMINI_API_KEY="${GEMINI_API_KEY:-}"

# ── Pre-flight checks ────────────────────────────────────────────────────────
echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN}  L1 Support Copilot - local start${NC}"
echo -e "${CYAN}============================================================${NC}\n"

command -v node >/dev/null 2>&1 || { echo -e "${RED}[ERROR]${NC} 'node' not found on PATH."; exit 1; }
command -v npm  >/dev/null 2>&1 || { echo -e "${RED}[ERROR]${NC} 'npm' not found on PATH."; exit 1; }

# ── Install frontend dependencies when missing ───────────────────────────────
if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo -e "${YELLOW}[INFO]${NC} node_modules not found, running npm ci..."
  npm ci --prefix "$FRONTEND_DIR"
fi

# ── Drop the previous PID file ───────────────────────────────────────────────
rm -f "$PID_FILE"

# ── Backend (Spring Boot, port 8079) ─────────────────────────────────────────
echo -e "${GREEN}[BACK]${NC} Starting Spring Boot on http://localhost:8079 ..."
(
  cd "$BACKEND_DIR"
  ./mvnw spring-boot:run > "$ROOT_DIR/backend.log" 2>&1
) &
BACK_PID=$!
echo "$BACK_PID" >> "$PID_FILE"
echo -e "  Backend PID: ${CYAN}$BACK_PID${NC} (logs in backend.log)"

# ── Frontend (Angular, port 4199) ────────────────────────────────────────────
echo -e "${GREEN}[FRONT]${NC} Starting Angular on http://localhost:4199 ..."
(
  cd "$FRONTEND_DIR"
  npm start > "$ROOT_DIR/frontend.log" 2>&1
) &
FRONT_PID=$!
echo "$FRONT_PID" >> "$PID_FILE"
echo -e "  Frontend PID: ${CYAN}$FRONT_PID${NC} (logs in frontend.log)\n"

# ── Wait for the backend ─────────────────────────────────────────────────────
echo -e "${YELLOW}[WAIT]${NC} Waiting for the backend to start..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8079/api/actuator/health > /dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC} Backend ready ✓"
    break
  fi
  if ! kill -0 "$BACK_PID" 2>/dev/null; then
    echo -e "${RED}[ERROR]${NC} The backend failed. Check backend.log"
    tail -20 "$ROOT_DIR/backend.log"
    cleanup
  fi
  sleep 2
done

# ── Wait for the frontend ────────────────────────────────────────────────────
echo -e "${YELLOW}[WAIT]${NC} Waiting for the frontend to start..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:4199 > /dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC} Frontend ready ✓"
    break
  fi
  if ! kill -0 "$FRONT_PID" 2>/dev/null; then
    echo -e "${RED}[ERROR]${NC} The frontend failed. Check frontend.log"
    tail -20 "$ROOT_DIR/frontend.log"
    cleanup
  fi
  sleep 2
done

# ── Ready ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}============================================================${NC}"
echo -e "${GREEN}  ✓ Everything is up${NC}"
echo -e "  Backend  → ${CYAN}http://localhost:8079/api${NC}"
echo -e "  Swagger  → ${CYAN}http://localhost:8079/api/swagger-ui/index.html${NC}"
echo -e "  Frontend → ${CYAN}http://localhost:4199${NC}"
echo -e "${CYAN}============================================================${NC}"
echo -e "${YELLOW}  Press Ctrl+C to stop both services${NC}\n"

# ── Keep the script alive ────────────────────────────────────────────────────
wait "$BACK_PID" "$FRONT_PID"
