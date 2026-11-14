# Task 586／587：SRPP 設定與短線觀察驗收證據

本證據包僅記錄 **OFFLINE／隔離 PREFLIGHT**。正式 Git 落地、registry 登錄、正式容器部署、業務 API 驗收及排程採用皆為 **NOT_YET_EXECUTED**；不可據此宣稱已上線。狀態快照見 [status.json](proofs/status.json)。

## 交付範圍

Catalog V2 共 **76 欄**，情境引擎為 `SRPP_SCENARIO_CASHFLOW_V2`：生日與退休給付、退休前薪資／年終績效節慶獎金、大額支出、緊急與生活費準備金、月頻牛熊敏感度及 16 個短線草稿參數。原 model／payroll JSON 位元組與正式金融設定均未改。新準備金、流動性、市況支出與月末再平衡修正 V1 情境 baseline，因此 V2 整體金融結果不要求等於 V1。

短線 16 欄為 `NOT_MODELED`，不納入月頻退休報酬；保存草稿不代表參數已套用正式雷達。當前清單沿同 owner 正式雷達 SHORT 方向，只供觀察，未證明部位帳本、資金、深度及全部 SRPP 採用閘門通過。月頻均線／合成總報酬、aggregate 資產桶與合成交易時段不能替代日頻證據或交易授權。

## 凍結身分

| 項目 | SHA-256 |
|---|---|
| Bundle | `fc31e0979d7279cc3f1237e9ea91500d8a48958ed67f9b1d66f9e420ff996bda` |
| Model 原始位元組 | `6d26483bcb530e26d8686bbaa565e805bfe137efa1d6db7a8671cedb796620aa` |
| Payroll JSON 原始位元組 | `c146cb1f0fb9c19778075a376bb7e08a7f501e4e5839da594c19e5c3948637e5` |
| Catalog 原始位元組 | `fef178c38b6c9b1d71f8809416777540811f09255235d8d286b5f111336af2bb` |
| Insert-only registry SQL | `5dff438c23c99b5f102a402c3999981277b79b0c1227ec85c707a49e9644f156` |
| 隔離 worker 合成結果 canonical hash | `e9a96995e3142f5403281cf3dd2d858843cc6b94419d820170a719917a9ed5f8` |

Model／payroll JSON 與 main 原始位元組相同；backend embedded authority、runtime、catalog 與 payroll mirror 已由原工具準備及核對。Bundle hash 不等同 manifest 檔案本身的 hash；完整各產物指紋見 [authority-artifacts.json](proofs/authority-artifacts.json)、[python-source.json](proofs/python-source.json)。SQL 僅產生為受追蹤檔，尚未套用。

## 有意義的驗證

- **Python 40／40**：封閉型別、成對給付覆寫、歷史 CPI 代理初始化、生日到齡、offcycle 獎金、退休日已收與應收、名目轉換、大額事件、準備金守恆與不足、月末信號、五批限制及短線只改草稿不改退休數值。[測試紀錄](proofs/python-tests.log)
- **預設 payroll 完整結果不變**：計算 hash 維持 `3a53085499036e700359191fe16ca19da9f11e653272d83e6b7a3c9666f90084`。新增 offcycle 支援讓 `pre_retirement_cashflow.py` 的 source hash 合法改變，沒有偽填舊 hash；原預設 ledger 及金融結果不變。
- **111＋15 個正式金融 golden cases 全部內容不變**，僅 bundle 身分及上述一個 calculator source hash 更新；其餘 calculator hashes 不變。[逐項比對](proofs/golden-parity.json)
- **Backend authority／registry／payroll／golden 80／80**、Ruby 登錄 13 tests／307 assertions、Python release gate 4／4 均通過；原 authority gate 與兩份 golden check 通過。[Backend 摘要](proofs/authority-backend-tests.json)、[authority gate](proofs/authority-check.log)、[登錄測試](proofs/registration-tests.log)、[基礎登錄測試](proofs/base-registration-tests.log)、[release gate 測試](proofs/authority-gate-tests.log)
- **V1 相容性**：backend／BFF 原 V1 wire fixture 與 main bytes 完全相同，canonical hash 仍為 `79d17b6863696707c5c0733c7c214fcbb7136aa78d8a25592697c5f7a3288f9f`；保留舊歷史，不補造 V2 欄。[核對證據](proofs/v1-wire-compatibility.json)
- **BFF 分組修正 8／8 相關測試**：包含 loopback upstream 的實際 HTTP、BFF 新增三個 observationBuckets、移除新增投影後原 envelope 完全相同，以及列序／null 價格／不可變列表與 owner 錯誤保留。Vue 直接 render BFF 分組。[測試摘要](proofs/bff-observation-buckets-tests.json)
- **最終瀏覽器合成驗收**：使用真 Python／編譯後 BFF 結果及短線 buckets fixture 與合成雷達，確認草稿保存回讀、金額千分位、權重 99／100／101%、結果時程及短線展開；原權重16.4顯示無浮點尾數，保存時保持來源原精度。權重合計以彩色滿版狀態顯示。這不是正式 owner／服務採用驗收。[UI 證據](proofs/ui-synthetic-acceptance.json)、[原權重與保存精度](proofs/weights-original-precision.json)、[正式參數合成 BFF buckets](proofs/short-formal-actual-bff.json)、[草稿預覽合成 BFF buckets](proofs/short-draft-actual-bff.json)

原 golden generator 首次因 feature worktree 缺少 ignored 2026 calendar 而拒絕；之後只複製既有本機 calendar 供離線計算，未抓外部資料或 refresh。[來源與 hash 收據](proofs/offline-calendar-dependency.json)

## 隔離 worker 實際 HTTP

獨立映像為 `codex-srpp-simulator-preflight:t586-t587-fc31e097-20261114`，未覆蓋正式 tag；執行時 `network=none`、唯讀根檔案系統、無 host port、無憑證／資料 volume。依工具可選模型限制，使用既定的繼承主代理模型備援。

實際 `/health`、`/internal/health`、catalog 76 欄、validate 及 100 paths／seed 31415 的 V2 simulate 全通過；重跑結果一致，canonical hash 與離線 fixture 完全相同。九種畸形／重複 member／非有限值／hash／bool／symbols／唯讀 ladder／path count 錯誤依約回 400／409／422。暫存容器已移除、映像與證據保留；正式 **10 個容器**的 ID、image、啟動時間與 restart count，以及正式 image tags 前後完全相同。[HTTP 與保護證據](proofs/worker-preflight.json)、[隔離設定](proofs/worker-isolation.json)

### 最新 BFF 映像／JAR 前置證據

目前證據是 `codex-t586-bff-preflight:fc31e0979d72-buckets`，image `sha256:fd069a1627212e063c73b6525de79fc50248709c68000dbfd40ba5a8047c55b7`。已核對 JAR 含實際 `SrppShortTermPresentation`／`PageEnvelope`／`ObservationBucket` 與 controller class，API resources bytes 相同；此暫存容器只建立後取出 JAR，**未啟動為正式服務**。正式容器與 tags 未動，暫存容器已移除。[最新 buckets 映像證據](proofs/bff-observation-buckets-image-preflight.json)

舊 BFF 映像證據若有保留，僅作修正前歷史，不是目前 buckets 實作的驗收依據；實際 HTTP 分組證據來自上列 controller／loopback 測試，不將未啟動的映像冒稱已 serve。

## 獨立審查與待完成工作

Task 586 最終 [spec 第 3 輪](reviews/t586-spec-round3.md)、Task 587 最終 [spec 第 2 輪](reviews/t587-spec-round2.md) 均 PASS。先前輪次保留供檢視修正證據。

[架構第 1 輪](reviews/arch-round1.md) 為 PASS／2 minor；[第 2 輪](reviews/arch-round2.md) 為 CHANGES_REQUESTED／1 major／2 minor；最終[第 3 輪](reviews/arch-round3.md) 已 **PASS：critical 0／major 0／minor 2**。第 2 輪 BFF 觀察分組 major 已獨立確認修復並撤下，未重新推導方向或改寫價格；[處理紀錄](reviews/arch-handled-round2.md) 另保留測試與最終 UI 修正證據。已記錄架構 gate 前綴 `784740c96694`、spec gate 前綴 `a6a3ff723af2`。

兩項 minor **保留、未修正**：短線 DTO 價格仍為 String，來源以 `BigDecimal.toPlainString()` 保留原精度／scale／null，未經浮點轉換；日期與時間戳 DTO 仍為 String，已有 ISO 日期驗證，維持已審 wire／canonical hash 與來源 offset。本輪審查未發現精度損壞或日期輸出錯誤證據，但仍明列與專案 `BigDecimal`、`LocalDate`／`Instant` 型別慣例的差異，不宣稱已修。最終架構 PASS 只證明本輪唯讀審查結論，不替代正式部署或排程採用。

正式發布完成後，另增具體 Git／registry／部署／exact-hash 業務 API 與日曆時窗證據；不能把本包的離線測試或隔離容器結果改標為正式日報採用。全文未執行交易、寄信或手動重跑正式報告。
