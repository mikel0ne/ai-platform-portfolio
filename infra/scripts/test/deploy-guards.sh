#!/usr/bin/env bash
# Runs deploy.sh against fake kubectl/helm/docker shims in a temp dir: no cluster is touched.
# Usage: bash infra/scripts/test/deploy-guards.sh
set -uo pipefail

SRC_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy.sh"
TMP_BASE="$(cygpath -u "$(cygpath -w -l "${TEMP:-/tmp}")" 2>/dev/null || echo "${TMPDIR:-/tmp}")"
WORK="${TMP_BASE}/deploy-guards.$$"
if ! { mkdir "${WORK}" && cd "${WORK}"; } 2>/dev/null; then
  rm -rf "${WORK}" 2>/dev/null
  WORK="$(dirname "${SRC_SCRIPT}")/test/.work.$$"
  mkdir "${WORK}" || exit 1
fi
trap 'cd /; rm -rf "${WORK}"' EXIT
FAILURES=0

setup_tree() {
  rm -rf "${WORK}/tree" "${WORK}/bin"
  local t="${WORK}/tree" b="${WORK}/bin"
  # Relative mkdir -p: with Git Bash on Windows an absolute one fails on the already existing %TEMP% ancestor.
  ( cd "${WORK}" && mkdir -p tree/infra/scripts tree/infra/projects tree/infra/charts/generic-app tree/demo/backend tree/demo/corpus bin )
  : > "${t}/demo/corpus/doc.md"
  cp "${SRC_SCRIPT}" "${t}/infra/scripts/deploy.sh"
  printf '<project><version>1.0.0</version></project>\n' > "${t}/demo/backend/pom.xml"
  : > "${t}/infra/namespace-demo.yaml"
  : > "${t}/infra/values-demo-backend.yaml"
  cat > "${t}/infra/projects/demo.env" <<'DESC'
NAMESPACE="demo"
INGRESS_HOST="demo.local"
IMAGE_BACKEND="registry.local/demo-backend"
IMAGE_FRONTEND="registry.local/demo-frontend"
CONTEXT_BACKEND="demo/backend"; DOCKERFILE_BACKEND="docker/backend.Dockerfile"
CORPUS_DIR="demo/corpus"
OPTIONAL_SECRET_VARS=(OPT_KEY)
declare -gA SECRETS_BACKEND=(
  [demo-main]="REQ_USER REQ_PASS"
  [demo-opt]="OPT_KEY"
)
declare -gA SECRETS_FRONTEND=()
DESC
  cat > "${b}/kubectl" <<'SHIM'
#!/usr/bin/env bash
echo "kubectl $*" >> "${CALLS_LOG}"
case "$1 $2" in
  "get secret")
    key="${!#}"; key="${key##*.data.}"; key="${key%\}}"
    [[ -f "${EXISTING_FILE:-/nonexistent}" ]] && sed -n "s/^${key}=//p" "${EXISTING_FILE}"
    exit 0 ;;
  "create secret")
    for a in "$@"; do [[ "$a" == --from-env-file=* ]] && cat "${a#--from-env-file=}"; done ;;
  "apply -f")
    [[ "$3" == "-" ]] && cat >> "${APPLIED_FILE}" ;;
esac
exit 0
SHIM
  for tool in helm docker; do
    printf '#!/usr/bin/env bash\necho "%s $*" >> "${CALLS_LOG}"\nexit 0\n' "${tool}" > "${b}/${tool}"
  done
  chmod +x "${b}"/*
  : > "${WORK}/calls.log"; : > "${WORK}/applied.txt"
}

run_deploy() {
  PATH="${WORK}/bin:${PATH}" CALLS_LOG="${WORK}/calls.log" APPLIED_FILE="${WORK}/applied.txt" \
    EXISTING_FILE="${EXISTING_FILE:-}" \
    bash "${WORK}/tree/infra/scripts/deploy.sh" --project demo --component backend --tag 9.9.9 "$@" \
    > "${WORK}/out.txt" 2>&1
}

check() {
  local name="$1" cond="$2"
  if eval "${cond}"; then
    printf 'PASS  %s\n' "${name}"
  else
    printf 'FAIL  %s\n' "${name}"; FAILURES=$((FAILURES + 1))
    sed 's/^/      | /' "${WORK}/out.txt" "${WORK}/calls.log"
  fi
}

calls_match() { grep -qE "$1" "${WORK}/calls.log"; }

# (a) required variable empty: abort, nothing touched, no values leaked
setup_tree
printf 'REQ_USER=alice\nREQ_PASS=\nOPT_KEY=\n' > "${WORK}/tree/infra/.env.local"
run_deploy; rc=$?
check "a: exit code is not 0"                    '[[ ${rc} -ne 0 ]]'
check "a: no kubectl/helm/docker call at all"    '[[ ! -s "${WORK}/calls.log" ]]'
check "a: error names the variable and the file" 'grep -q "REQ_PASS" "${WORK}/out.txt" && grep -q ".env.local" "${WORK}/out.txt"'
check "a: the set variable is not listed"        '! grep -q "REQ_USER" "${WORK}/out.txt"'

# (b) only the optional one is empty: warns and continues
setup_tree
printf 'REQ_USER=alice\nREQ_PASS=secret-pass\nOPT_KEY=\n' > "${WORK}/tree/infra/.env.local"
run_deploy; rc=$?
check "b: exit code 0"                           '[[ ${rc} -eq 0 ]]'
check "b: warns about the optional variable"     'grep -q "Optional variables empty.*OPT_KEY" "${WORK}/out.txt"'
check "b: helm upgrade ran"                      'calls_match "^helm upgrade"'

# (c) --allow-empty degrades the error to a warning
setup_tree
printf 'REQ_USER=alice\nREQ_PASS=\nOPT_KEY=\n' > "${WORK}/tree/infra/.env.local"
run_deploy --allow-empty; rc=$?
check "c: exit code 0"                           '[[ ${rc} -eq 0 ]]'
check "c: warns with --allow-empty"              'grep -q "allow-empty.*REQ_PASS" "${WORK}/out.txt"'
check "c: helm upgrade ran"                      'calls_match "^helm upgrade"'

# (d) everything present: normal flow, secrets applied, order build -> secrets -> helm
setup_tree
printf 'REQ_USER=alice\nREQ_PASS=secret-pass\nOPT_KEY=optval\n' > "${WORK}/tree/infra/.env.local"
run_deploy; rc=$?
check "d: exit code 0"                           '[[ ${rc} -eq 0 ]]'
check "d: no warnings about empty values"        '! grep -qi "empty" "${WORK}/out.txt"'
check "d: docker build+push and helm ran"        'calls_match "^docker build" && calls_match "^docker push" && calls_match "^helm upgrade"'
check "d: both secrets applied with their values" 'grep -q "^REQ_PASS=secret-pass$" "${WORK}/applied.txt" && grep -q "^OPT_KEY=optval$" "${WORK}/applied.txt"'

# (e) an existing non-empty cluster value is not overwritten by an empty one
setup_tree
printf 'REQ_USER=alice\nREQ_PASS=\nOPT_KEY=\n' > "${WORK}/tree/infra/.env.local"
printf 'REQ_PASS=%s\nOPT_KEY=\n' "$(printf 'cluster-pass' | base64)" > "${WORK}/existing.txt"
EXISTING_FILE="${WORK}/existing.txt" run_deploy --allow-empty; rc=$?
check "e: exit code 0"                           '[[ ${rc} -eq 0 ]]'
check "e: cluster value kept in the applied Secret" 'grep -q "^REQ_PASS=cluster-pass$" "${WORK}/applied.txt"'
check "e: warns that the value is kept"          'grep -q "REQ_PASS is empty: keeping" "${WORK}/out.txt"'
check "e: the value is never printed"            '! grep -q "cluster-pass" "${WORK}/out.txt"'
check "e: still-empty optional key stays empty"  'grep -q "^OPT_KEY=$" "${WORK}/applied.txt"'

printf '\n%s failure(s)\n' "${FAILURES}"
[[ ${FAILURES} -eq 0 ]]
