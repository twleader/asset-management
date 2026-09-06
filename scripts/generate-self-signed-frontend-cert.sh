#!/usr/bin/env bash
#
# generate-self-signed-frontend-cert.sh — 產生 localhost/loopback 開發用自簽憑證
#
# 此工具只寫入 ${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}/，
# 絕不接觸 public certificate source。它產生的憑證不受一般瀏覽器信任，僅供本機開發或
# 手動建立本機 trust 前測試使用；public hostname 必須使用既有的 public-trusted chain。

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
用法：scripts/generate-self-signed-frontend-cert.sh [localhost|127.0.0.1] [--force] [--days N]

產生 localhost／127.0.0.1 專用自簽憑證，寫入
${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}/（fullchain.pem + privkey.pem）。

此工具不能產生 public hostname 憑證；對外網域必須提供受公開信任的 certificate chain。
EOF
  exit 1
}

if [[ $# -gt 0 && "$1" != --* ]]; then
  case "$1" in
    localhost|127.0.0.1)
      shift
      ;;
    *)
      echo "ERROR: 此工具只接受 localhost 或 127.0.0.1，不可產生 external hostname 憑證。" >&2
      usage
      ;;
  esac
fi

FORCE=0
DAYS=825
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)
      FORCE=1
      shift
      ;;
    --days)
      [[ $# -ge 2 ]] || usage
      DAYS=$2
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

command -v openssl >/dev/null 2>&1 || {
  echo 'ERROR: 需要 openssl（macOS 可 brew install openssl，或直接使用系統內建版本）。' >&2
  exit 1
}

OUT_DIR="${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}"
mkdir -p "$OUT_DIR"
CERT="$OUT_DIR/fullchain.pem"
KEY="$OUT_DIR/privkey.pem"

if { [[ -f "$CERT" ]] || [[ -f "$KEY" ]]; } && [[ "$FORCE" -ne 1 ]]; then
  echo "ERROR: $CERT 或 $KEY 已存在；要覆蓋請加 --force" >&2
  exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/frontend-local-tls-cert.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

tmp_key="$work_dir/privkey.pem"
tmp_cert="$work_dir/fullchain.pem"
SAN='DNS:localhost,IP:127.0.0.1'

openssl req -x509 -nodes -newkey rsa:2048 -days "$DAYS" \
  -keyout "$tmp_key" -out "$tmp_cert" \
  -subj '/CN=localhost' \
  -addext "subjectAltName=${SAN}" \
  >/dev/null 2>&1

mv "$tmp_key" "$KEY"
mv "$tmp_cert" "$CERT"
chmod 600 "$KEY"
chmod 644 "$CERT"

cat <<EOF
已產生 localhost／loopback 自簽憑證：
  $CERT
  $KEY
  有效期限：${DAYS} 天
  Subject Alternative Name：${SAN}

此憑證只屬於本機開發。若要讓 https://localhost/ 正常驗證，請將它或其簽發 CA
依你的作業系統／瀏覽器流程加入本機信任；它不能且不得用於 public hostname。

套用方式：
  FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST="$OUT_DIR" docker compose up -d --force-recreate frontend
EOF
