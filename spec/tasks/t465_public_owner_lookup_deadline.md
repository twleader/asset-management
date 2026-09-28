# [t465] 公開帳號查找逾時與部署指引修正

**對應 Requirements:** Requirement 140（五條個人資料公開 API 的帳號選擇與隔離）
**前置任務:** t416、t454
**Liquibase changeset:** 無

## 背景

全專案第 1 輪審查確認：五條個人資料 public GET 的 configured-admin lookup 缺少總期限，business 接受連線卻不回應時會等到 gateway 60 秒逾時；既有 by-email lookup 已有 5 秒期限。兩份 active run-stack 指引另保留把舊 clone `.env` 覆蓋 main 的指令，路由清單亦分別停在 12／5 條，與現行 14 條不符。

## 要做什麼

- [x] 465.1 `LatestAssetsPublicService.getLatest`、`PublicPortfolioAdviceService.latest`、`PublicTradingRadarService.today/stock`、`PublicTransactionHistoryService.current` 的 configured-admin lookup 都以現有 5 秒常數設總期限。到期取消 lookup，回各端點既有 Unavailable 例外的 503 application/problem+json，assets／advice／radar detail「主要管理者不可用」，transactions detail「交易紀錄服務暫時不可用」；保留各自既有 advice、title、status，不繼續資料讀取、不重試、不洩漏內部資料。
- [x] 465.2 將期限限制於 owner lookup。assets/advice 只新增 TimeoutException mapping，保留其他既有 error；radar/transactions 使用原有 onErrorMap。保留 null／空白 email 預設 owner、by-email 行為、身分清除、tenant headers、成功 bytes、資料 downstream timeout/relay。不改共用 BusinessUserClient 或已正確的 PublicSrppDailyContextService，不寫使用者資料、不觸發券商或通知。
- [x] 465.3 以虛擬時間與 stub WebClient 測試五個入口的 5 秒邊界、lookup cancellation、零 data downstream 及 HTTP 503 ProblemDetail；保留既有成功／email／tenant tests。OpenAPI 五個 operation 與503描述標明雙分支5秒，version 1.15.1、schema/status/path集合不變；同步契約測試版號與產生的兩份Swagger文件。
- [x] 465.4 同步 Codex／Claude run-stack：14 exact routes（13 GET＋既有 crawler rescan POST），包含 commodity-prices、srpp/daily-context。移除 clone→main `.env` 複製；以動態 main worktree 的設定作基準，無值輸出的比較及必要的 main→隔離驗收 worktree 設定同步，不改 main secrets。驗證 container effective config 僅報相同／不同，不列秘密值。保留各 harness 指定模型與BFF恢復規則。

## 驗證

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/render-9090-openapi-docs.rb
bash scripts/spec-check.sh
git diff --check
```

使用 run-stack 指定模型，feature 驗收僅重建/recreate bff（部署設定以 main 為基準、秘密值不可輸出），等待健康；從實際 9090 五支正常GET及非法email 400驗目前回應，SRPP registry 空的既有409保持，讀取回應不揭露帳戶內容。不得為重現逾時而中斷正式 business 或改DB；503與取消由隔離stub測試提供。完成no-ff merge/push後再從乾淨main重建bff，確認Compose workdir/image digest與remote SHA。文件改動不需重建其他服務。

## 完成報告

四個 BFF service 已補 configured-admin 5 秒期限，保留既有 advice 與資料 relay；新增 10 個 deadline／HTTP 測試。修前 5 個虛擬時間案例全部重現掛住，修後 BFF 全套 579 項通過。規格審查第二次無 findings，diff-scoped 架構查核無 findings；spec-check BLOCK 0／CHECK 0。OpenAPI 1.15.1 與兩份產生文件已同步。

feature 已由指定 run-stack 代理重建／recreate BFF並healthy；五個GET=200、非法email=400、SRPP全零hash=409 POLICY_UNSUPPORTED。JAR四個修正class的SHA256與feature target/classes一致、內嵌OpenAPI為1.15.1。部署以main設定為基準，缺少的內部token僅由運行中容器安全傳入Compose程序，main設定未改；未輸出token。

全專案1輪確認3項，按停止規則修後不再全專案複審，未進入第5輪。部署驗收另發現Tailscale13-handler缺既有SRPP route，將在main部署時按既有14-route契約補齊並讀回，不變更SRPP規則或排程。main merge/push、remote SHA及main部署的最後證據在本次任務完成回覆提供，審查範圍與finding ledger保留於docs/reviews/2026-09-28-project-review.md。
