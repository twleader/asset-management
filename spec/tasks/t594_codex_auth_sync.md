# [t594] 同步最新 Codex 訂閱登入並區分登入與費用錯誤

**對應 Requirements:** Requirement 208（設定中心情境模擬與訂閱AI解讀）
**前置任務:** t592、t593
**Liquibase changeset:** 無

## 背景

正常Codex已更新登入，但bridge仍使用初次複製的過期auth，401被誤顯示為費用未知。需沿用最新有效訂閱登入，保留不額外付費硬閘。

## 要做什麼

## Task 594：Codex 最新訂閱登入同步與錯誤分類

1. 取消一次複製且永久沿用私有codex-home/auth.json的行為。每次Codex能力探測與每次實際執行均從目前使用者正常~/.codex/auth.json安全讀取最新登入，缺失或不安全即關閉，絕不回退舊副本。只接受同使用者擁有、非symlink的私有常規檔，限制大小、以開啟後fstat及前後identity檢查避免替換/部分寫入。來源只讀，不能chmod、覆寫、refresh或改動一般CLI登入。
2. 每個操作建立0700暫存CODEX_HOME及0600 auth.json，僅投影同一來源的有效access token與已核對account identity；使用CLI支援的chatgptAuthTokens外部登入模式，id_token可使用同一access JWT、refresh_token固定空，不攜帶原refresh token、API key、一般設定或工具。解析必要JWT exp與帳號claim只作拒絕過期/不一致的本機前置檢查，不能作訂閱授權證據。操作完成/失敗/timeout清理所有子程序與暫存憑證。舊私有登入不再讀取，亦不自動刪改使用者既有檔案。
3. 真正推論前的原生CLI account/rateLimits/read、model/list與exec必須共用同一不可變登入投影；source在這期間變更不能造成檢查A而執行B。metadata快取不能授權推論，每次立即重新驗證。保留personal subscription、ordinaryUsageAllowed、credits.hasCredits=false、unlimited=false、額度未用盡、固定provider、無API key、無額外付費/自動購買/fallback與所有工具隔離限制。外部token refresh要求一律停止，不獨立刷新登入。
4. 新增閉合安全碼AI_AUTH_REQUIRED（尚未登入、來源無法安全讀取或格式無效）與AI_AUTH_EXPIRED（access過期、可信結構化401/明確失效或外部刷新要求）；API登入仍AI_SUBSCRIPTION_REQUIRED，網路/未知RPC/費用證據缺漏仍AI_BILLING_UNVERIFIED，額度耗盡沿原碼。不得憑任意stderr文字猜401，不能公開原始錯誤、帳號或token。backend安全碼白名單與前端安全文案同步，明示請在一般Codex CLI重新登入，費用未知另顯示未呼叫；數值模擬與其他工具保持原行為。
5. 範圍為host bridge與測試/操作文件、Java安全碼白名單/測試、前端安全文案/測試；無DB、BFF wire、9090、金融公式或政策變更。測試涵蓋登入輪替、舊副本不可回退、不安全檔/過期/錯帳、無refresh token或來源寫入、同snapshot proof+exec、併行隔離及清理、401與費用未知區分、費用拒絕零推論與原安全閘回歸。實際部署重啟host，重建並recreate business-services及frontend，實測新有效Codex能力與一次訂閱解讀、跨層安全錯誤碼及既有數值結果不變。完成獨立規格/架構審查、測試、部署驗證後feature commit、main no-ff merge與push，host最終來源指main。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -m unittest discover -s scripts/tests -p 'test_srpp_local_ai_bridge.py'
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppSettingsLocalAiTest test
cd frontend && npm test
```

以run-stack既有正式設定重建recreate business-services/frontend並安裝host bridge；能力GET、一次Codex訂閱解讀、正式TLS與既有LOCAL/AI結果hash回讀。憑證只留private暫存，不進測試收據。獨立spec與arch審查皆通過後執行兩段式Git發布，驗main與remote一致、host來源main及部署健康。

## 完成報告

待實作，最終測試/部署證據保存docs/reviews/t594_codex_auth_sync；不保存登入、帳號或模型輸出原文。
