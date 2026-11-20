# [t614] 修復並行大型 SRPP 報告在 gateway 被截斷

**對應 Requirements:** Requirement 187（台股不可變報告讀取）、199（美股不可變報告讀取）
**前置任務:** t613、t542
**Liquibase changeset:** 無

## 背景

2026-11-20 Tailscale 並行 Codex/Claude 讀約 9 MB 報告，Nginx 在 16 MiB tmpfs 中緩衝兩份 body，error.log 出現 proxy_temp pwritev 短寫；其中一份 HTTP 200 但只有約 1.4 MB，單獨重讀成功。原因是 gateway buffer temp 容量競爭，與政策包或 JSON 內容無關。

## 要做什麼

- [ ] 614.1 僅在 api-gateway/nginx.conf 既有 exact GET /api/public/srpp/report-calculations 與 /api/public/srpp/us-report-calculations 兩個 location 設 proxy_buffering off，直接串流 BFF 已驗證回應；不新增 route／method／host port，不變 timeout/header/allowlist/權限、owner/schema/Swagger/bundle。
- [ ] 614.2 保留 16 MiB tmpfs、現有所有 buffer/resource 設定，不藉加大容量掩蓋並行問題；不改金融 calculator／producer／BFF validator，GET純讀與不可下單不變。
- [ ] 614.3 新增隔離 Docker 真 Nginx 傳輸測試：使用 synthetic Python HTTP upstream 及 JSON fixture，每份至少 9 MB，兩個慢 client 同時發送；Nginx tmpfs 固定 16 MiB。buffering on baseline 必須實際觀察 proxy_temp 容量／短寫錯誤及至少一份回應不完整；off 修復後兩份 body 皆能 JSON parse、Content-Length 與預期 SHA256 一致，且無容量錯誤。
- [ ] 614.4 分別覆蓋 TW/US route 的雙 consumer 並行，含 HTTP 非 GET／descendant 拒絕；既有 scripts/tests/docker-external-api-openapi-test.rb 及 configure-tailscale-api-gateway-test.sh 通過，證明 route集合與method矩陣維持。
- [ ] 614.5 測試不讀正式 DB／憑證、不建立第二套正式 stack、不綁 host port；容器／network 唯一命名且 finally 清理，保存每輪長度/hash/status及 Nginx error log。實作完成經獨立架構審查與父工作主驗後才進 Git；正式部署另由發布工作僅重建 gateway。

## 驗證

先跑 spec-check 與獨立 spec review；實作後執行新增真並行傳輸回歸、既有兩支 route/method/Tailscale 契約測試及 git diff --check；保留隔離測試證據，再對完整 diff 做獨立架構審查。此任務不補跑報告、不寄信、不交易。

## 完成報告

待實作驗收。
