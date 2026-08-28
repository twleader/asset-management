#!/bin/sh
# 放進 nginx:alpine 官方鏡像的 /docker-entrypoint.d/ 擴充點，容器啟動時自動執行。
#
# 若 host 尚未透過 scripts/generate-self-signed-frontend-cert.sh 提供憑證，
# 這裡先產生一組 localhost-only 的預設自簽憑證，確保 docker compose up 在
# 未手動設定 HTTPS 憑證時也能正常啟動（僅供本機測試，不含使用者的對外 IP/網域）。
# 已存在的憑證不會被覆蓋。
set -eu

CERT_DIR=/run/secrets/frontend-tls
CERT="$CERT_DIR/fullchain.pem"
KEY="$CERT_DIR/privkey.pem"

if [ -f "$CERT" ] && [ -f "$KEY" ]; then
  exit 0
fi

echo "[frontend-tls] 找不到 $CERT / $KEY，產生預設自簽憑證（localhost / 127.0.0.1）" >&2
mkdir -p "$CERT_DIR"
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$KEY" -out "$CERT" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
  >/dev/null 2>&1
chmod 600 "$KEY"
chmod 644 "$CERT"
echo "[frontend-tls] 已產生預設憑證；若要用於對外公網 IP／網域，請於 host 執行 scripts/generate-self-signed-frontend-cert.sh <IP或網域> 後 recreate 本容器" >&2
