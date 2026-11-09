#!/usr/bin/env bash
# =============================================================================
#  scripts/stop.sh - stops the backend and frontend started with start.sh
# =============================================================================

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPTS_DIR")"
PID_FILE="$ROOT_DIR/.running.pids"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo -e "${YELLOW}[STOP]${NC} Stopping services..."

if [[ ! -f "$PID_FILE" ]]; then
  echo -e "${YELLOW}[INFO]${NC} No PID file (.running.pids). Already stopped?"
  # Fall back to the process names
  pkill -f "spring-boot:run" 2>/dev/null && echo -e "  ${RED}✗${NC} Spring Boot stopped"
  pkill -f "ng serve"         2>/dev/null && echo -e "  ${RED}✗${NC} Angular stopped"
  exit 0
fi

while IFS= read -r pid; do
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    echo -e "  ${RED}✗${NC} PID $pid stopped"
  else
    echo -e "  ${YELLOW}~${NC} PID $pid no longer exists"
  fi
done < "$PID_FILE"

rm -f "$PID_FILE"
echo -e "${GREEN}[OK]${NC} Services stopped."

