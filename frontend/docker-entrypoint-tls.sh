#!/bin/sh
# 放進 nginx:alpine 官方鏡像的 /docker-entrypoint.d/ 擴充點，容器啟動時自動執行。
#
# Host 憑證目錄一律 read-only mount 到 *-source；Nginx 只讀 tmpfs 內的 runtime copy。
# 這可避免 compose 從不同 worktree 啟動時，把 localhost fallback 寫進 public certificate source。
set -eu
umask 077

PUBLIC_SOURCE_DIR="${FRONTEND_PUBLIC_TLS_SOURCE_DIR:-/run/secrets/frontend-tls-source}"
LOCAL_SOURCE_DIR="${FRONTEND_LOCAL_TLS_SOURCE_DIR:-/run/secrets/frontend-local-tls-source}"
PUBLIC_RUNTIME_DIR="${FRONTEND_PUBLIC_TLS_RUNTIME_DIR:-/run/frontend-tls/public}"
LOCAL_RUNTIME_DIR="${FRONTEND_LOCAL_TLS_RUNTIME_DIR:-/run/frontend-tls/local}"
PUBLIC_SOURCE_HOST_PATH="${FRONTEND_PUBLIC_TLS_SOURCE_HOST_PATH:-}"
LOCAL_SOURCE_HOST_PATH="${FRONTEND_LOCAL_TLS_SOURCE_HOST_PATH:-}"

case "${FRONTEND_PUBLIC_TLS_REQUIRED:-false}" in
  true)
    TLS_REQUIRED=true
    ;;
  false|'')
    TLS_REQUIRED=false
    ;;
  *)
    echo "[frontend-tls] FRONTEND_PUBLIC_TLS_REQUIRED must be true or false" >&2
    exit 1
    ;;
esac

pair_present() {
  [ -f "$1/fullchain.pem" ] && [ -f "$1/privkey.pem" ]
}

absolute_path() {
  case "$1" in
    /*) return 0 ;;
    *) return 1 ;;
  esac
}

require_absolute_path() {
  variable_name=$1
  value=$2
  if [ -z "$value" ] || ! absolute_path "$value"; then
    echo "[frontend-tls] $variable_name must be an absolute host path when FRONTEND_PUBLIC_TLS_REQUIRED=true" >&2
    exit 1
  fi
}

require_pair() {
  label=$1
  source_dir=$2
  if ! pair_present "$source_dir"; then
    echo "[frontend-tls] missing $label certificate pair at $source_dir; provide fullchain.pem and privkey.pem" >&2
    exit 1
  fi
}

copy_pair() {
  source_dir=$1
  runtime_dir=$2

  mkdir -p "$runtime_dir"
  cp "$source_dir/fullchain.pem" "$runtime_dir/fullchain.pem"
  cp "$source_dir/privkey.pem" "$runtime_dir/privkey.pem"
  chmod 644 "$runtime_dir/fullchain.pem"
  chmod 600 "$runtime_dir/privkey.pem"
}

generate_local_fallback() {
  label=$1
  runtime_dir=$2

  echo "[frontend-tls] $label source pair missing; generating localhost-only development fallback in $runtime_dir" >&2
  mkdir -p "$runtime_dir"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "$runtime_dir/privkey.pem" -out "$runtime_dir/fullchain.pem" \
    -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    >/dev/null 2>&1
  chmod 600 "$runtime_dir/privkey.pem"
  chmod 644 "$runtime_dir/fullchain.pem"
}

prepare_pair() {
  label=$1
  source_dir=$2
  runtime_dir=$3

  if pair_present "$source_dir"; then
    copy_pair "$source_dir" "$runtime_dir"
  else
    generate_local_fallback "$label" "$runtime_dir"
  fi
}

if [ "$TLS_REQUIRED" = true ]; then
  # Both entries are required for this system's production dual-login deployment.
  require_absolute_path FRONTEND_PUBLIC_TLS_SOURCE_HOST_PATH "$PUBLIC_SOURCE_HOST_PATH"
  require_absolute_path FRONTEND_LOCAL_TLS_SOURCE_HOST_PATH "$LOCAL_SOURCE_HOST_PATH"
  require_pair public "$PUBLIC_SOURCE_DIR"
  require_pair local "$LOCAL_SOURCE_DIR"
fi

prepare_pair public "$PUBLIC_SOURCE_DIR" "$PUBLIC_RUNTIME_DIR"
prepare_pair local "$LOCAL_SOURCE_DIR" "$LOCAL_RUNTIME_DIR"
