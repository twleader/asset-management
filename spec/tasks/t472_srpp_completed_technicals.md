# Task 472：SRPP 完成日技術事實 API

**對應 Requirements:** Requirement 174（SRPP 完成日技術事實批次唯讀 API）
**前置任務:** 無
**Liquibase changeset:** v1.141.0-srpp-completed-technicals-error-log-catalog.sql

## 背景

9090 單檔報價有最新成交量，但圖表沒有逐日成交量；`COMPLETED_TECHNICALS` 因政策 registry 未登錄仍為 `UNAVAILABLE`。已保存 `stock_price_history` 含逐日 OHLCV，部分新股只有一年多，不能一律要求十年。SRPP 需要資產系統預算完成日技術事實，再由 LLM 依既有規則判斷。

## 要做什麼

- [ ] 472.1 增加 global no-tenant、exact、read-only `GET /api/public/srpp/completed-technicals`。必填 `market=台股|美股`、嚴格 ISO `asOf`（早於市場本地今日）、`stockCodes`（逗號分隔 1–40 個唯一代號）；拒絕未知／重複 query 與 body。BFF 不做公式，business 不外呼、不寫入、不碰券商或個人資產。
- [ ] 472.2 business 每檔讀取截至 asOf 的兩年 OHLCV 和 active 權息事件，用同一整段序列既有還原服務。尾列缺 asOf 時整列 `UNAVAILABLE`；其他指標分別按足額標準視窗計算：MA5、Wilder RSI14、EMA12/26 加 EMA9 signal 的 MACD、Bollinger20 母體標準差、Wilder ADX14 ±DI、OBV 最近 20 日變化、當日量／前 20 日均量、近一曆年位置。某指標缺資料時 null 並列原因，不以 0、縮短參數或盤中列補足；每檔附來源期間、筆數、價基、實際套用事件日期、來源 hash、公式版本，不宣稱權息事件完整。
- [ ] 472.3 加入第 18 條 exact 9090 route，並同步 gateway、Tailscale、frontend deny、安全 allowlist、API log catalog、OpenAPI schema/examples、Markdown 鏡像及 route tests。新回應只含市場事實，不含決策分數、買賣指令或交易許可；原有 owner-scoped `COMPLETED_TECHNICALS` 模組維持既有狀態。
- [ ] 472.4 SRPP 接入文件明列 calendar-first、選定 asOf、核對每檔日期/status/缺口、與既有硬門檻及反證合併；不以 API 數值單獨授權交易。

## 驗證

先執行 `bash scripts/spec-check.sh`；固定合成資料單元測試覆蓋足額、短歷史、缺量、缺日與價基。執行 `mvn -q -f backend/pom.xml test`、`mvn -q -f bff/pom.xml test`、`ruby scripts/render-9090-openapi-docs.rb --check`、`ruby scripts/tests/docker-external-api-openapi-test.rb`、`bash scripts/tests/configure-tailscale-api-gateway-test.sh`。重建 business、BFF、gateway 容器後，唯讀讀取 9090 指定既有完成日標的；未實際重建時如實標為未部署。

## 完成報告

待驗收後填入測試與部署證據。
