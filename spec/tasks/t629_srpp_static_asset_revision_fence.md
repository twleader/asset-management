# [t629] 修復同快照 ID 持股更新後 latest SRPP 計算包仍回傳舊內容

**對應 Requirements:** Requirement 187（SRPP 不可變日報計算包的同持有人完整靜態資產一致性補充）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-24 Codex午報直接資產與較早計算包內嵌資產使用相同可變snapshot ID，但19個富邦台股row ID更換、9列市值更新，完整L0驗證正確回STATIC_L0_MISMATCH，D-207無已驗owner而停止，核心/附錄草稿未建立。富邦同步每次無條件刪除重建同snapshot全部台股列，latest FINAL未檢查目前靜態資產全文。公開回歸fixture只保存明示DEIDENTIFIED_DERIVED合成等價結構，全部身分/名稱/代碼/數量/金額替換；真實原始事故僅由SRPP_INCIDENT_DIR指向ignored原檔離線驗證，輸出限status/hash/count，原文與實額不進Git。

## 要做什麼

- [x] 629.1 保留富邦writer原 owner/latest悲觀鎖、日期/owner/broker檢查、空庫存語意與實際價/券商成本/配息公式；唯一既有股票scope identity原地更新保留row ID，刪除消失或歧義舊列、新增全新或歧義取代列，禁止累加成本/股數。重複輸入股票必須拒絕，不可重複重用entity。
- [x] 629.2 共用完整靜態snapshot正規化和SHA：DB小數轉成既有capture十進位字串，保留null、每個欄位、共同detail投影的穩定全序（deposits/funds按id，stocks按displayOrder null最後再id）、完整陣列順序/row identity；忽略的只有snapshot外的generatedAt/liveAssets。assets source revision為snapshot ID加此SHA；現有source payload SHA與完整fingerprint不放寬。
- [x] 629.3 新增SrppReportAssetRevision service，以明示owner查最新DB快照，再以已載入entity取得完整detail純讀投影，比對包內sources.assets.snapshot；不得查券商、行情、producer、寫入或計算。latest GET完成既有完整owner/consumer/日期/slot/policy/swagger/content驗證後才比對，不符/缺資料/讀失敗回既有503 CONTEXT_NOT_READY。不指定run時才啟用；固定run immutable讀回不變且不得放寬原scope/hash驗證。
- [x] 629.4 以DEIDENTIFIED_DERIVED合成等價snapshot建立公開離線fixture；另以SRPP_INCIDENT_DIR讀ignored事故原文並僅輸出status/hash/count回歸；測試同ID不同內容拒絕、完整欄位/銀行/基金/holding/decimal/null變動、正匹配、owner-explicit query、固定run舊bytes仍可讀、新source內容建立新immutable base/packages與既有BLOCKED不解除。
- [x] 629.5 獨立spec/arch本機Codex審查、相關Java/PostgreSQL測試，跨專案authority/registry/部署由本輪release operator整合。合法時窗外不得live report GET、重跑報告、建立草稿或宣稱正式日報採用；原事故紀錄保持不變。

- [x] 629.6 保留reservation全部查詢語意，改用metadata cursor搭配完整驗過的不可變摘要memory cache；首次每ref仍完整envelope/hash/body identity驗證，熱tick同鍵不重取body，legacy/report共享entry；metadata/body競爭整批失敗。key綁owner/id/shared/scope/consumer/inputHash/contentHash，命中返回deepCopy。最多262144 global entry、每owner65536 entry、96MiB（canonical摘要bytes加每鍵512bytes固定權重），容量來源為3owner*90day*295tick=79650base，90日不是query截斷；全範圍metadata遍歷成功後依實際可見ref prune同owner absent/changed cache key，週內子範圍不prune。滿載拒絕新admission而保留熱entry、log hit/miss/admission-rejected/entries/bytes，不保證任意owner/歷史量仍60s；未cache來源仍完整重讀，不丟來源、不LRU全thrash。冷啟動首次掃描仍可能慢於日報重取額度；僅既有producer自然暖cache，無新增排程/手動觸發/持久化cache/schema。測冷熱/競爭/79650ref高基數/小容量飽和/prune/全部預留不變。

- [x] 629.7 金融OpenAPI升1.30.0，report-calculations description明載latest純讀當前同owner DB完整static快照比對、不符/缺失503 CONTEXT_NOT_READY；fixed calculationRunId僅歷史immutable scope驗證，不受當前static fence。503描述同步，不新增route/欄位/寫入，沿Task504產生三mirror、SRPP identity/runtime/bundle及asset authority，最終新hash獨立驗收。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonInventoryWriterTest,AssetServiceTest,LatestAssetsServiceTest,SrppAssetSnapshotIdentityTest,SrppReportAssetRevisionTest,SrppReportPrivateIncidentReplayTest,SrppReportReadServiceTest,SrppReportRepositoryPostgresTest,SrppReportReservationCaptureTest,SrppReportReservationCacheTest,SrppReportReservationCachePostgresTest,SrppReportFingerprintTest,DecisionInputCaptureAdapterTest,SrppReportModuleCalculatorTest,SrppPolicyRegistryServiceTest,SrppPolicyDocumentTest,SrppReportShortUsCalculatorTest test
```

正式驗收由發布流程從乾淨main重建business-services並force-recreate，同步/登錄相同SRPP規則包後，核對容器內authority原文與9090純讀連線；不在取數時窗外使用report GET或建立日報。下一合法時窗必驗直接L0對帳、同owner新包靜態全文、D-207及核心/附錄各一份未寄出Gmail草稿的RAW-MIME/HTML/plain/profile讀回。

## 完成報告

本次修復、測試與正式發布已完成。完整直接L0對帳、STATIC_L0_MISMATCH拒絕、D-207持有人驗證與投資規則維持原要求；原事故紀錄保留INCOMPLETE，未補造報告或改寫交付結果。

Fubon唯一既有持股改為原列更新，完整靜態投影使用穩定排序與內容revision；latest完整same-owner fence、固定run原文讀回及metadata／已驗摘要cache均已實作。cache的missing候選從scan-start既有owner cached keys取得，集合只縮不長、不刪掃描中新admissions。冷cache仍須完整首次驗證；不宣稱任意歷史量可在60秒內完成。

公開fixture為DEIDENTIFIED_DERIVED合成等價資料，全部身分、名稱、代碼、日期、股數、成本與金額已替換，非原始bytes。真實事故原文不進公開Git，僅由SRPP_INCIDENT_DIR指向ignored原檔離線回歸；本輪已實際執行，確認19個row identity及9個市值差異，完整static與same-owner fence拒絕維持，輸出僅status／hash／count。未設定該env時私人回歸明示skip，不影響公開CI。

驗證與審查結果：

- backend最新suite inventory為223 tests：222通過、1既有可選實際wire-size probe因未設srpp.report.size-probe而skip。包括真原文、去識別fixture、79650參照壓測、cold／hot／prune／飽和／owner、PostgreSQL cursor與新舊immutable包回歸。
- 首次完整222 tests命令exit0；cache尾修後40 tests再驗為39通過、同一可選size probe skip，命令exit0。首輪編譯import撞名與合成fixture非canonical小數已修，未沿用失敗log宣稱成功。
- BFF 47 tests全部通過、0 skip，含backend producer wire正例與重複阻擋碼攻擊；最後backend package建置exit0。
- 獨立本機Codex spec第2輪PASS，公開fixture邊界修正後第3輪再PASS；combined architecture第1輪PASS，皆為0 critical／0 major／0 minor。機械spec-check為BLOCK0。

正式來源落地已完成feature commit、main no-ff merge、push與遠端精確讀回；下表記錄本次實作與部署來源提交，後續純完成文件提交不改變這些來源身分。

| 專案 | 實作feature commit | 實作main merge commit |
|---|---|---|
| asset-management | d47e0c9c3ae3835d972daa95e9a68bee9781686a | 2cea122889d0f89d8e15e5cb57abf36b5ce4e96d |
| SRPP | 5f49e8a0f4d957b63572f8a82f13fcf4a6797b21 | 0f8768a8a99deb6f8c0bbe0a294a192597c0b4aa |

正式規則包為`f1af47959e490b84ba47e722071ab66e183a18f018fd37f9c38a193bfbe7dbc9`；金融OpenAPI為1.30.0，Swagger SHA-256為`39422085f4de6a758dff50de934a3a41590625aed5a70975c7010b55ef690bec`。Task504鏡像、consumer身分、runtime、bundle與authority已完整同步；兩份golden共111／15 cases的金融輸入輸出與calculator hashes維持，原始bytes僅替換bundle identity。

正式registry以insert-only新增本次bundle，筆數由76至77；新row全文與受追蹤generator產物相符，既有76筆row未變，未UPDATE或DELETE歷史。

business-services已從乾淨main重建並force-recreate。新container為`3e37c9b7117b08ff5dc944dfc67caf708eec441f038767c61c0033006639b006`，實際image為`sha256:646211f4bd9110586696b182d4e9f9a72e5fb7a3fb07b44e07301bd46e20edb8`，健康狀態為healthy。運行JAR內5份authority全文及26個變更class bytes均與已驗收來源／產物精確一致；47個部署env keys一致，9個無關container保持原ID，未公開secret值。

2026-11-24台北23:13:53，已從正式SRPP主機與實際Python環境執行verify_9090.py。三個GET皆HTTP 200且JSON／非空內容驗證PASS：交易日曆365列、台股狀態AVAILABLE；TWSE日線21列；台股報價23列，23列皆有正價格。此結果只證明正式部署後的9090唯讀連線與內容，不代表當輪金融採用或草稿交付。23:14:30自然背景觀察尚未見新package metadata或cache日誌，未手動觸發producer，不宣稱已量得熱cache的自然runtime效能。

正式report-calculations、當輪L0／D-207採用及核心／附錄Gmail草稿驗收仍為NOT_YET_EXECUTED，原因是本輪已過合法取數時窗。下一候選交易日為2026-11-25，仍須當輪重新確認本機交易日曆；僅於09:05與12:00原排程合法時窗，驗證新hash、完整直接L0、同owner靜態全文及D-207，並分別建立一份未寄出的核心與附錄DRAFT，完成RAW-MIME、HTML／plain與profile讀回。原35分鐘取數截止、核心凍結與報告時限均保留，不以夜間維護收據替代此驗收。
