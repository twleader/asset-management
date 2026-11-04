# D-207 台美政經低權重交易量調節發布驗收

2026-11-04（Asia/Taipei），截至22:38的程式、規則包、部署與精確hash API驗收完成；正式下一輪報告採用仍為 **NOT_YET_EXECUTED**。

## 實際作用與邊界

- 規則為 D-207／政策 v2.56.0／模型 v3.22.0。只使用已驗證「台美政經互動」−2至+2分，每分5%、最多增減10%；BUY同向、可選獲利SELL反向。兩個跨市場走勢分項仍作原用途，不混入六項風險。
- 以 `floor(base_units * abs(score) * 0.05)` 計算離散調整量，再受原部位、共享資金容量及可賣上限約束；限價維持。小量取整或上限裁切沒有變化時具名揭露。固定量機制、必要減碼、論點失效及期限退出不受此調節，缺來源或未評維持原量。
- 原不可變API計算結果保留；新scope尚未由API供應，由SRPP具名本機確定性工具執行後置調量，保存base／delta／final及來源驗證。報告主要買賣指示與HTML、純文字審計內容須一致。沒有新增下單權限，也不宣稱10%是最佳權重或已證明獲利改善。
- `12股→13股` 是 **SYNTHETIC_OFFLINE_FIXTURE**：離線測試經既有D-186計算器、D-207轉接與雙格式檢核證明final可不同於base。它不是真實部位、實際交易建議或正式報告採用。

## 驗證

- SRPP實作測試93項Python、100項JavaScript通過；三consumer、rules與runtime檢查通過。獨立來源審查critical／major／minor均0；審查者另獨立執行89項Python及100項JavaScript。已處理主要指示數量／方向／限價一致性、共享容量新鮮度、用途／資產類別來源綁定、CLI sidecar及跨軌fingerprint等發現。
- Asset Java四類共42項、Ruby兩組共13項／307 assertions、release gate regression4項均通過；spec-check BLOCK0／CHECK0。兩份golden共37案僅bundle身分變化，全部金融expected及原calculator hashes相同；formal targets、formula manifest與Swagger bytes維持。原authority checker與Swagger renderer／identity check通過。

## Git與來源身分

以下為程式與權威發布當時的完整身分，feature與main均已push並精確讀回remote；main均為兩個parent的no-ff merge。後續僅文件收尾，不改這批執行資源或為自引用SHA重建image。

| 專案 | feature commit／remote feature | main commit／remote main |
| --- | --- | --- |
| SRPP | `1fda2339cb9020bcc23a3ed64fcf58e28d99fd5e` | `e8fde60d7114c21a97760b0b0dc8c0351c5f7694` |
| asset-management | `622f787e25dc37d2325aed0150e62d07f355baee` | `9e75e23c7440aedd982415897144181742a3e582` |

SRPP實際執行工作區同步至上述main，原未追蹤檔案保留，受追蹤檔案乾淨。

| 身分 | SHA-256 |
| --- | --- |
| Policy bundle | `d62be7ac5898fecaf6523c3f0dd4abf96ece9003ca45ea0e7b90a48ec8265295` |
| Manifest原始檔 | `a494024922b106c762f8ad51abb7d8a0c289c0c20aa01c074f10510318bd37e2` |
| Model原始檔 | `f22fdcc35e96c965bc0d6d5775121be8f7bafdfe7385f3264b0af86664fc516e` |
| Published Swagger | `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4` |
| Registration SQL | `0c07c2cc39cbf368bd9e1dec8697c88af8a161720a13cc66d44eadc20a893f23` |

## 登錄與部署

- 22:33以已合併的原generator SQL執行insert-only登錄，registry 30→31列。新row的bundle、formula version、policy document及formula manifest四欄精確相同；原30列全文保持，沒有UPDATE／DELETE。
- 只從乾淨asset main及原正式設定build／force-recreate business-services一次，container `4614d68392e03671f4a2b74a45dd1420e4643afd53a3f4ce6a1294fb8ad62507`，image `sha256:48ffc9beb0ccc3c7b0e44b74a8572c60edff8cd68d4ef7414165162733bd0b69`，健康狀態healthy，BFF為UP。
- 運行JAR SHA-256為 `1a283cc4e973986cdb4ae32963d17b2802efc418288cdd123b46d7b8136b8db1`。JAR中的model、manifest、Published Swagger、registration SQL及formal policy document五份authority與已合併來源逐位元相同。
- 52項effective env與先前容器／main一致，main `.env`與mounts維持；其餘8個容器ID及image保持。
- 啟動觀察具備台美備份已有durable record、指數皆最新略過、分析無需補跑及快照 `rolled=0 skipped=3 failed=0` 的預期紀錄；意外啟動動作日誌計數0。未手動呼叫producer，未補做報告、建草稿、寄信、refresh或交易。

## 精確hash 9090回讀

- 登錄後立即以新hash讀report-calculations，首次HTTP409，沒有當成API接受。
- 部署後首次及有界重試曾回HTTP503 `CONTEXT_NOT_READY`，原HTTP收據保留。之後22:37:34由原背景流程自然發布Claude／Codex各一份新hash FINAL包，DB發布紀錄先於最終GET；沒有手動觸發producer。
- 22:37兩consumer各以相同新bundle及Published Swagger精確hash GET，均HTTP200，完整獨立契約與身分驗證PASS。狀態為 `INTEGRITY_ACCEPTED_DIAGNOSTIC_ONLY`，coverage均7 PARTIAL／5 UNAVAILABLE／0 COMPLETE，`adopted_modules={}`、`trade_authorization=false`。
- SRPP實際主機的唯讀日曆、TWSE日線與台股報價連線檢查均HTTP200且內容非空。這是連線證據，不能替代計算包或正式採用驗收。
- 本輪為時窗外維護，未執行同輪直接L0及完整正式required_reads，正式下一輪採用仍為 **NOT_YET_EXECUTED**。HTTP200、FINAL與完整性驗證均不代表取得可執行交易指示。

## 排程入口與尚未證實範圍

Codex兩個既有automation prompt已逐位元讀回且非prompt設定保留；Claude兩份host入口與來源bytes一致。四個host日報入口核對PASS，兩個台股watchdog入口亦PASS。Claude遠端保存指令及guest實際掛載／執行仍為 **REMOTE_SCHEDULE_UNVERIFIED**；host檔案一致不能證明遠端已部署或下一輪已執行。

私人完整驗收證據保留於 `/private/tmp/politics-decision-release-20261104/`。本摘要只記去識別結果，不提交HTTP／資產原文、owner資料、秘密或環境設定原文。
