# [t547] 發布台美政經低權重交易量調節 authority

**對應 Requirements:** Requirements 187／193（SRPP 唯讀報告計算與精確 authority 發布）
**前置任務:** t545
**Liquibase changeset:** 無

## 背景

使用者要求台美政經評分低權重影響實際買賣建議。現行 D-204 正常評分僅供說明；SRPP 將以 D-207／政策 v2.56.0／模型 v3.22.0 局部取代政經分項限制。資產管理須同步可稽核權威，但既有 API 尚無這個後置調量 scope，不能把新機制冒充不可變包原始輸出。

## 要做什麼

- [x] 547.1 唯一來源為 managed feature `/private/tmp/srpp-politics-decision-20261104` 通過來源檢查及獨立審查的最終 bytes。僅「台美政經互動」−2..+2，每分 5%、最多 10%，BUY 同向、可選獲利 SELL 反向；delta=floor(base_units*abs(score)*0.05)，再裁切原容量與可賣上限。缺來源／未評維持原量。固定量、必要減碼／論點失效／期限退出、硬閘及限價維持，兩市場連動及六項風險不混算。報告端工具保留 base／delta／final 與來源，取整無變化亦具名揭露。
- [x] 547.2 來源凍結前須附 SRPP D-207 正負分 BUY／獲利 SELL、整張取整、上限裁切、必要退出排除、偽造 final 拒絕及報告雙格式接線測試；至少一筆 final≠base 證明有實際買賣量作用。來源凍結後才以實際 model／manifest 讀回 bundle、版本及 hash。backend decision model／manifest 逐位元同步，以既有 Python 工具重產 same-input 與 long-short-exit golden、原 Ruby stdout 重產受追蹤 insert-only registration SQL；正式 policy document 只用原 generator 的 canonical targets projection，targets 沒變時 bytes 必須一致。未涉本次調量的 expected 金融輸出、formula manifest、API／schema／Swagger 不變；必要 metadata 差異逐項解釋。不得手改 hash／expected 解封差異，不新增 API、DB schema 或 producer。新調量依具名 scope 缺口在 SRPP 本機計算，原 immutable API 數值保留。
- [x] 547.3 在 resources 寫入前先通過 spec-check 與另一支 subagent 的 spec 對抗審查，記錄 spec-review-pass。實作由 subagent 執行；跑原 authority checker、兩份 golden check、Ruby registration regression、來源 release gate regression 及聚焦 Java authority／report calculator 測試。實作後由另一支 subagent 依完整 diff 查架構規範，修復本輪新增問題。DecisionPolicyAuthorityTest 現行 D-206／2.55.0 身分斷言須同步 D-207／2.56.0，不能保留過時預期或放寬為任意值。
- [x] 547.4 沿既有授權的跨專案完整發布鏈，先完成可做的離線建置／測試，再各 feature commit、main no-ff merge、push 與 remote-ref readback；自已合併 SQL 執行 insert-only registry，四欄全文核對且既有 rows 不更新或刪除。登錄後立即以新 bundle 與原 Swagger 精確 hash 從 SRPP 主機 GET report-calculations，保存 HTTP 原文；即使當時409／503亦如實保存，不當成已接受。
- [x] 547.5 只從乾淨 asset main 與原 main .env／掛載 rebuild 及 force-recreate business-services；保留其餘服務。部署前唯讀確認當日 analysis 已完成／已寄送、指數已最新或原設定不補跑、所有已到時 enabled 匯出排程今日已執行、DIVIDEND_BACKFILL_ENABLED=false、適用備份已存在、PROCESSING=0，並確認快照／到期在途款之 startup roll 無需執行；避免跨入原啟用的寄送／匯出分鐘，不改排程或設定。若無法證明無補寄信／外抓，先查明原因，不以啟動引發副作用來驗收。自然內部 report producer 保持原行為並如實觀察，不手動觸發。只保存去識別相等判定／hash，不輸出秘密。重啟前後核對 container/image、其他容器、effective env／mounts 與 main .env hash；有界等待 healthy 最多120秒、9090 safe GET 最多30秒，失敗保留原回應，不自動重啟其他服務。
- [x] 547.6 執行 JAR 中 model／manifest／Swagger／SQL／formal document 與合併 bytes 全文一致；registry 的 policy_bundle_sha256／formula_version／policy_document／formula_manifest 精確讀回。SRPP 主機三項唯讀連線檢查及最終 hash 計算包 HTTP 原文與獨立契約驗證分開保存。正式採用須有合法交易日／時窗、完整 required_reads、同 owner 直接 L0 與獨立 verifier，其他情況只算診斷且記 NOT_YET_EXECUTED；503 CONTEXT_NOT_READY 不算API接受。不得重跑或補報告、建草稿、寄信、交易、refresh 或手動 producer。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-politics-decision-20261104 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-politics-decision-20261104 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-politics-decision-20261104 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-politics-decision-20261104 python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /private/tmp/srpp-politics-decision-20261104
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root /private/tmp/srpp-politics-decision-20261104
git diff --check
```

正式 serve 驗收：只 business-services rebuild／recreate，五份 JAR authority bytes、registry 四欄全文及舊 rows、部署設定及其他容器保持，再從 SRPP 主機 GET 9090，保留 HTTP 與可用包獨立驗證。

## 完成報告

2026-11-04（Asia/Taipei），D-207／政策v2.56.0／模型v3.22.0已完成兩專案落地、registry、部署及精確hash API完整性驗收。政策微調不代表已證明報酬改善，正式下一輪報告採用仍為 **NOT_YET_EXECUTED**。

- 最終bundle `d62be7ac5898fecaf6523c3f0dd4abf96ece9003ca45ea0e7b90a48ec8265295`；manifest原始檔 `a494024922b106c762f8ad51abb7d8a0c289c0c20aa01c074f10510318bd37e2`；model `f22fdcc35e96c965bc0d6d5775121be8f7bafdfe7385f3264b0af86664fc516e`；Published Swagger `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`。model／manifest與SRPP凍結來源逐位元相同，targets／formula／Swagger維持。
- SRPP 93項Python＋100項JavaScript、三consumer／rules／runtime通過；獨立來源審查critical0／major0／minor0。Asset Java42項、Ruby13項／307 assertions、release regression4項通過，spec-check BLOCK0／CHECK0；golden37案僅bundle metadata變化。離線 `12股→13股` 是SYNTHETIC_OFFLINE_FIXTURE，不是真實部位、交易建議或正式採用。
- 程式與權威發布時，SRPP feature `1fda2339cb9020bcc23a3ed64fcf58e28d99fd5e`、main／remote `e8fde60d7114c21a97760b0b0dc8c0351c5f7694`；asset feature `622f787e25dc37d2325aed0150e62d07f355baee`、main／remote `9e75e23c7440aedd982415897144181742a3e582`。兩repo均no-ff merge並精確讀回remote，SRPP實際工作區已同步並保留未追蹤檔案。此後文件收尾另行提交，不回填自引用SHA。
- Registry 30→31列，新row四欄精確相同、舊30列全文保持。登錄後立即GET為409並保留，部署初為503 CONTEXT_NOT_READY；22:37:34原背景流程自然發布新hash兩consumer FINAL包後，Claude／Codex各一次GET均200、獨立verifier PASS。coverage均7 PARTIAL／5 UNAVAILABLE／0 COMPLETE，adopted_modules={}、trade_authorization=false；只算INTEGRITY_ACCEPTED_DIAGNOSTIC_ONLY。
- 只build／force-recreate business-services一次：container `4614d68392e03671f4a2b74a45dd1420e4643afd53a3f4ce6a1294fb8ad62507`，image `sha256:48ffc9beb0ccc3c7b0e44b74a8572c60edff8cd68d4ef7414165162733bd0b69`，JAR `1a283cc4e973986cdb4ae32963d17b2802efc418288cdd123b46d7b8136b8db1`。五份JAR authority與已合併來源全文一致，healthy／BFF UP；52項effective env、main .env、mounts及其餘8個容器保持。啟動預期skip紀錄完整、意外動作日誌計數0；未手動producer、補做報告、建草稿、寄信、刷新或交易。
- SRPP主機日曆／TWSE／台股報價三項連線全200；四個host日報入口PASS，Codex兩prompt及非prompt設定、Claude兩host檔案已核對。Claude遠端保存指令／guest實際掛載及執行仍為 **REMOTE_SCHEDULE_UNVERIFIED**，不宣稱遠端部署或下一輪實跑完成。
- 去識別公開摘要：`reports/political-decision-release-20261104/RELEASE.md`；私人證據：`/private/tmp/politics-decision-release-20261104/`。私人HTTP／資產／owner及環境原文不提交。
