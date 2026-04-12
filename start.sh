#!/bin/bash

export PATH="$PATH:/usr/local/apache-maven/apache-maven-3.9.11/bin"
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && source "$NVM_DIR/nvm.sh"
nvm use 22 2>/dev/null || true

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ""
echo "╔══════════════════════════════════════╗"
echo "║        資產管理系統  v1.0.0          ║"
echo "╚══════════════════════════════════════╝"
echo ""

# 啟動 Backend
echo "📦 啟動 Spring Boot Backend..."
cd "$SCRIPT_DIR/backend"
mvn spring-boot:run -q > /tmp/asset-backend.log 2>&1 &
BACKEND_PID=$!

echo "⏳ 等待 Backend 啟動..."
for i in {1..40}; do
  sleep 1
  if curl -s http://localhost:8080/api/snapshots > /dev/null 2>&1; then
    echo "✅ Backend 已就緒 (port 8080)"
    break
  fi
  [ $i -eq 40 ] && echo "❌ Backend 啟動超時" && cat /tmp/asset-backend.log | tail -20 && exit 1
done

# 啟動 Frontend
echo "🌐 啟動 Vue Frontend..."
cd "$SCRIPT_DIR/frontend"
npm run dev > /tmp/asset-frontend.log 2>&1 &
FRONTEND_PID=$!
sleep 4

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║  ✅ 資產管理系統已啟動！                    ║"
echo "║                                              ║"
echo "║  🌐 前端界面:   http://localhost:5173        ║"
echo "║  🔧 後端 API:   http://localhost:8080        ║"
echo "║  🗄️  H2 Console: http://localhost:8080/h2-console ║"
echo "║                                              ║"
echo "║  📥 首次使用：點選「資產快照」→「匯入Excel」║"
echo "║     選擇 ~/Downloads/資產總值.xlsx           ║"
echo "╚══════════════════════════════════════════════╝"
echo ""
echo "按 Ctrl+C 停止所有服務"

cleanup() {
  echo ""
  echo "正在停止服務..."
  kill $BACKEND_PID $FRONTEND_PID 2>/dev/null
  echo "已停止"
  exit 0
}

trap cleanup INT TERM
wait
