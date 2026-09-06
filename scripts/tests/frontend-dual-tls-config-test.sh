#!/usr/bin/env bash
# Contract test for Requirement 142 / Task 419.  It does not start Docker or contact public endpoints.
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/frontend-dual-tls-test.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local text=$2
  grep -F -- "$text" "$file" >/dev/null || fail "$file does not contain: $text"
}

assert_pair() {
  local pair_dir=$1
  [[ -f "$pair_dir/fullchain.pem" ]] || fail "missing certificate: $pair_dir/fullchain.pem"
  [[ -f "$pair_dir/privkey.pem" ]] || fail "missing private key: $pair_dir/privkey.pem"
}

make_pair() {
  local pair_dir=$1
  mkdir -p "$pair_dir"
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout "$pair_dir/privkey.pem" -out "$pair_dir/fullchain.pem" \
    -subj '/CN=test-localhost' \
    -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
    >/dev/null 2>&1
}

run_entrypoint() {
  local required=$1
  local public_source=$2
  local local_source=$3
  local public_runtime=$4
  local local_runtime=$5
  local public_host_path=$6
  local local_host_path=$7

  env \
    FRONTEND_PUBLIC_TLS_REQUIRED="$required" \
    FRONTEND_PUBLIC_TLS_SOURCE_DIR="$public_source" \
    FRONTEND_LOCAL_TLS_SOURCE_DIR="$local_source" \
    FRONTEND_PUBLIC_TLS_RUNTIME_DIR="$public_runtime" \
    FRONTEND_LOCAL_TLS_RUNTIME_DIR="$local_runtime" \
    FRONTEND_PUBLIC_TLS_SOURCE_HOST_PATH="$public_host_path" \
    FRONTEND_LOCAL_TLS_SOURCE_HOST_PATH="$local_host_path" \
    sh "$repo_root/frontend/docker-entrypoint-tls.sh"
}

nginx_conf="$repo_root/frontend/nginx.conf"
nginx_app_conf="$repo_root/frontend/nginx-app.conf"
compose_file="$repo_root/docker-compose.yml"
entrypoint_file="$repo_root/frontend/docker-entrypoint-tls.sh"
generator_file="$repo_root/scripts/generate-self-signed-frontend-cert.sh"

bash -n "$generator_file"
sh -n "$entrypoint_file"

assert_contains "$nginx_conf" 'listen 80 default_server;'
assert_contains "$nginx_conf" 'listen 443 ssl default_server;'
assert_contains "$nginx_conf" 'server_name localhost 127.0.0.1;'
assert_contains "$nginx_conf" 'ssl_certificate     /run/frontend-tls/local/fullchain.pem;'
assert_contains "$nginx_conf" 'server_name asset-management.asuscomm.com;'
assert_contains "$nginx_conf" 'ssl_certificate     /run/frontend-tls/public/fullchain.pem;'
assert_contains "$nginx_conf" 'include /etc/nginx/includes/frontend-app.conf;'
assert_contains "$nginx_app_conf" 'location /oauth2/ {'
assert_contains "$nginx_app_conf" 'location /login/oauth2/ {'
assert_contains "$nginx_app_conf" 'location = /logout {'
assert_contains "$nginx_app_conf" 'location = /api/public/trading-radar/today { return 404; }'
assert_contains "$compose_file" '${FRONTEND_TLS_SECRETS_DIR_HOST:-./secrets/frontend-tls}:/run/secrets/frontend-tls-source:ro'
assert_contains "$compose_file" '${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}:/run/secrets/frontend-local-tls-source:ro'
assert_contains "$compose_file" 'FRONTEND_PUBLIC_TLS_SOURCE_HOST_PATH: ${FRONTEND_TLS_SECRETS_DIR_HOST:-./secrets/frontend-tls}'
assert_contains "$compose_file" 'FRONTEND_LOCAL_TLS_SOURCE_HOST_PATH: ${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}'
assert_contains "$compose_file" '/run/frontend-tls:rw,noexec,nosuid,size=1m'
assert_contains "$repo_root/frontend/Dockerfile" 'COPY nginx-app.conf /etc/nginx/includes/frontend-app.conf'
assert_contains "$repo_root/secrets/frontend-local-tls/.gitignore" '*.pem'

# The host generator must never manufacture a public-host certificate or overwrite public source by default.
generator_output_dir="$test_dir/generator-output"
FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST="$generator_output_dir" \
  "$generator_file" localhost --days 1 >/dev/null
assert_pair "$generator_output_dir"
generator_san="$(openssl x509 -in "$generator_output_dir/fullchain.pem" -noout -ext subjectAltName)"
[[ "$generator_san" == *'DNS:localhost'* ]] || fail 'generator certificate is missing DNS:localhost'
[[ "$generator_san" == *'IP Address:127.0.0.1'* ]] || fail 'generator certificate is missing IP Address:127.0.0.1'
if FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST="$test_dir/rejected" \
  "$generator_file" asset-management.asuscomm.com --days 1 >/dev/null 2>&1; then
  fail 'generator accepted an external hostname'
fi

public_source="$test_dir/public-source"
local_source="$test_dir/local-source"
make_pair "$public_source"
make_pair "$local_source"

# Production mode succeeds only with absolute source paths and both supplied pairs.
public_runtime="$test_dir/runtime-success/public"
local_runtime="$test_dir/runtime-success/local"
run_entrypoint true "$public_source" "$local_source" "$public_runtime" "$local_runtime" \
  "$public_source" "$local_source"
assert_pair "$public_runtime"
assert_pair "$local_runtime"
cmp -s "$public_source/fullchain.pem" "$public_runtime/fullchain.pem" || fail 'public certificate was not copied intact'
cmp -s "$local_source/fullchain.pem" "$local_runtime/fullchain.pem" || fail 'local certificate was not copied intact'

# Production mode must fail closed for a relative host path and for either missing source pair.
if run_entrypoint true "$public_source" "$local_source" "$test_dir/runtime-relative/public" "$test_dir/runtime-relative/local" \
  './secrets/frontend-tls' "$local_source" >/dev/null 2>&1; then
  fail 'required mode accepted a relative public source path'
fi
if run_entrypoint true "$test_dir/missing-public" "$local_source" "$test_dir/runtime-missing-public/public" "$test_dir/runtime-missing-public/local" \
  "$test_dir/missing-public" "$local_source" >/dev/null 2>&1; then
  fail 'required mode accepted a missing public source pair'
fi
if run_entrypoint true "$public_source" "$test_dir/missing-local" "$test_dir/runtime-missing-local/public" "$test_dir/runtime-missing-local/local" \
  "$public_source" "$test_dir/missing-local" >/dev/null 2>&1; then
  fail 'required mode accepted a missing local source pair'
fi

# Pure local development may generate two independent localhost-only runtime fallbacks without writing sources.
fallback_public_runtime="$test_dir/runtime-fallback/public"
fallback_local_runtime="$test_dir/runtime-fallback/local"
run_entrypoint false "$test_dir/no-public-source" "$test_dir/no-local-source" \
  "$fallback_public_runtime" "$fallback_local_runtime" '' '' >/dev/null 2>&1
assert_pair "$fallback_public_runtime"
assert_pair "$fallback_local_runtime"
fallback_san="$(openssl x509 -in "$fallback_public_runtime/fullchain.pem" -noout -ext subjectAltName)"
[[ "$fallback_san" == *'DNS:localhost'* ]] || fail 'fallback certificate is missing DNS:localhost'
[[ "$fallback_san" == *'IP Address:127.0.0.1'* ]] || fail 'fallback certificate is missing IP Address:127.0.0.1'
if [[ -e "$test_dir/no-public-source/fullchain.pem" || -e "$test_dir/no-local-source/fullchain.pem" ]]; then
  fail 'entrypoint wrote a fallback into a certificate source directory'
fi

echo 'PASS: frontend dual TLS config contract'
