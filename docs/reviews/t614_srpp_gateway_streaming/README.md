# Task 614：並行大型 SRPP 報告 gateway 串流修復

兩個 consumer 各讀大型不可變報告時，Nginx 預設 response buffering 把兩份 body 寫入共用 16 MiB tmpfs。容量不足時雖已送出 HTTP 200，body 仍可能中斷。修復僅在既有 exact 台股／美股 report-calculations location 各設定 `proxy_buffering off`；tmpfs、其他資源、timeout、headers、method／route、BFF驗證、政策 hash 與 Swagger 維持。

## 隔離傳輸驗證

執行 `python3 scripts/tests/srpp-report-gateway-streaming-test.py --output <evidence-dir>`，使用本機既有 nginx:alpine、python:3-alpine images，唯一命名的 internal Docker network、無 host port 的 synthetic upstream 與實際 Nginx。Nginx 維持 non-root、read-only、cap-drop ALL、no-new-privileges 及 16 MiB /tmp；沒有啟動另一套正式 stack，也未讀 DB／憑證／正式投資資料。測試結束所有容器及 network 均清除。

兩份 synthetic JSON 分別為 12,582,972（Codex）與 12,582,973（Claude）bytes。兩個 client 同時要求回應後停讀 4 秒，讓 buffering-on 控制組實際爭用 temp；兩個 route 各跑獨立控制組及修復組。

| 設定 | Route | Codex 實收 | Claude 實收 | 結果 |
|---|---|---:|---:|---|
| buffering on 控制組 | 台股 | 71,680 | 12,582,973 | HTTP 200 後截斷；proxy_temp 容量錯誤 |
| buffering on 控制組 | 美股 | 71,680 | 12,582,973 | HTTP 200 後截斷；proxy_temp 容量錯誤 |
| buffering off 修復組 | 台股 | 12,582,972 | 12,582,973 | 兩份 Content-Length／SHA256／JSON 全一致，無容量錯誤 |
| buffering off 修復組 | 美股 | 12,582,972 | 12,582,973 | 兩份 Content-Length／SHA256／JSON 全一致，無容量錯誤 |

每組另實際驗證 POST／PUT／DELETE 回 405 且 Allow=GET、GET descendant 回404。完整逐份 hash、status、長度及矩陣見 result.json，Nginx 原始測試 log 同目錄保留。

## 其他驗證

- `ruby scripts/tests/docker-external-api-openapi-test.rb`：PASS，gateway/OpenAPI union、closed schema 與文件 mirrors 維持。
- `bash scripts/tests/configure-tailscale-api-gateway-test.sh`：9 tests PASS，既有 handler 與 HTTPS／401／405／404 矩陣通過。
- spec 與 arch 各首輪獨立 Codex CLI review PASS，critical/major/minor 皆0；spec-check BLOCK0/CHECK0，兩項本機gate均已記錄。
- authority check保持原 D226 bundle `15ca8f63f3adcfe8ce912c684d4d46d5924f98eb6d657cc605522118a0408092` PASS；金融公式與Swagger身分未改。
- 正式部署與實機 Tailscale 回讀另由發布工作接續；隔離測試不宣稱漏報已補產，不寄信或交易。
