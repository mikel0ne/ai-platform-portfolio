#!/usr/bin/env bash
# =============================================================================
#  scripts/verify-in-docker.sh - runs `mvn -B clean verify` of the backends in a Linux container
#  Usage: ./scripts/verify-in-docker.sh [autoops|l1-copilot|all]   (default: all)
#  Why: on Windows 11 build 26300 Selector.open() fails, so @SpringBootTest cannot start natively.
# =============================================================================

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPTS_DIR")"
if MIXED_DIR="$(cd "$ROOT_DIR" && pwd -W 2>/dev/null)"; then ROOT_DIR="$MIXED_DIR"; fi
IMAGE="${MAVEN_IMAGE:-maven:3.9-eclipse-temurin-17}"
M2_VOLUME="${M2_VOLUME:-m2-portfolio}"
TARGET="${1:-all}"

case "$TARGET" in
  autoops)    PROJECTS=(autoops) ;;
  l1-copilot) PROJECTS=(l1-copilot) ;;
  all)        PROJECTS=(autoops l1-copilot) ;;
  *) echo "Usage: $0 [autoops|l1-copilot|all]" >&2; exit 2 ;;
esac

command -v docker >/dev/null || { echo "docker not found in PATH" >&2; exit 1; }

status=0
for project in "${PROJECTS[@]}"; do
  echo "[verify] ${project}: mvn -B clean verify in ${IMAGE}"
  if ! MSYS_NO_PATHCONV=1 docker run --rm \
      -v "${ROOT_DIR}:/w" -v "${M2_VOLUME}:/root/.m2" \
      -w "/w/${project}/backend" "${IMAGE}" mvn -B clean verify; then
    echo "[verify] ${project}: FAILED" >&2
    status=1
  fi
done
exit "$status"
