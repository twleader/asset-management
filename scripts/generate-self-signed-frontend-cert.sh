#!/usr/bin/env bash
#
# generate-self-signed-frontend-cert.sh — 為前端 Nginx 產生自簽 HTTPS 憑證
#
# 用途：本機沒有正式網域、只用固定 IP（或內部網域）對外提供 HTTPS 時，用這支腳本產生
# 一組自簽憑證，寫入 ${FRONTEND_TLS_SECRETS_DIR_HOST:-./secrets/frontend-tls}/。
# frontend 容器啟動時若該目錄已有 fullchain.pem / privkey.pem 會直接使用，不會覆蓋。
#
# 用法：
#   scripts/generate-self-signed-frontend-cert.sh <IP 或網域> [--force] [--days N]
#
# 範例：
#   scripts/generate-self-signed-frontend-cert.sh 220.134.32.108
#
# 產生後需 recreate frontend 容器套用：
#   docker compose up -d --force-recreate frontend
#
# 自簽憑證不是任何憑證機構簽發，瀏覽器連線會顯示「不受信任」警告，屬預期行為。
# 是否要把本機對外開放（路由器 port forwarding、防火牆規則）不在本腳本範圍內，
# 需自行於路由器／防火牆完成，並自行評估對外暴露的風險。

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
用法：scripts/generate-self-signed-frontend-cert.sh <IP 或網域> [--force] [--days N]

產生前端 Nginx 用的自簽 HTTPS 憑證，寫入
${FRONTEND_TLS_SECRETS_DIR_HOST:-./secrets/frontend-tls}/（fullchain.pem + privkey.pem）。

範例：
  scripts/generate-self-signed-frontend-cert.sh 220.134.32.108
EOF
  exit 1
}

[[ $# -ge 1 ]] || usage

SUBJECT_HOST=$1
shift

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

OUT_DIR="${FRONTEND_TLS_SECRETS_DIR_HOST:-./secrets/frontend-tls}"
mkdir -p "$OUT_DIR"
CERT="$OUT_DIR/fullchain.pem"
KEY="$OUT_DIR/privkey.pem"

if { [[ -f "$CERT" ]] || [[ -f "$KEY" ]]; } && [[ "$FORCE" -ne 1 ]]; then
  echo "ERROR: $CERT 或 $KEY 已存在；要覆蓋請加 --force" >&2
  exit 1
fi

if [[ "$SUBJECT_HOST" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  SAN="IP:${SUBJECT_HOST},IP:127.0.0.1,DNS:localhost"
else
  SAN="DNS:${SUBJECT_HOST},IP:127.0.0.1,DNS:localhost"
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/frontend-tls-cert.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

tmp_key="$work_dir/privkey.pem"
tmp_cert="$work_dir/fullchain.pem"

openssl req -x509 -nodes -newkey rsa:2048 -days "$DAYS" \
  -keyout "$tmp_key" -out "$tmp_cert" \
  -subj "/CN=${SUBJECT_HOST}" \
  -addext "subjectAltName=${SAN}"

mv "$tmp_key" "$KEY"
mv "$tmp_cert" "$CERT"
chmod 600 "$KEY"
chmod 644 "$CERT"

cat <<EOF
已產生自簽憑證：
  $CERT
  $KEY
  有效期限：${DAYS} 天
  Subject Alternative Name：${SAN}

套用方式：
  docker compose up -d --force-recreate frontend

瀏覽器連到 https://${SUBJECT_HOST} 會顯示「不受信任」警告，這是自簽憑證的正常現象，
需手動選擇「進階 → 繼續前往」或將本憑證加入系統／瀏覽器信任清單。

若要讓外部網際網路連進來，還需自行完成路由器 443（如需要也含 80）port forwarding
與防火牆規則設定，本腳本不會、也不能代為變更；請自行評估對外開放的風險。
EOF
