# [t395] 已實現損益明細同步——依富邦淨損益新增且冪等保留 `realized_gain`

**對應 Requirements:** Requirement 130
**前置任務:** t405 的本次窄範圍 owner／explicit-account 基礎
**Liquibase changeset:** 新增下一個可用版號，為 `realized_gain` 加入同步來源去重欄位與 partial unique index，並重產 `db/schema.sql`

## 已核實資料與刻意的帳務表示

[富邦官方已實現損益文件](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/RealizedPnLDetail/) 證實每筆成交回一列，包含日期、代號、買賣別、成交量／價、委託類型與 `realized_profit`／`realized_loss`；兩者不會同時有值，且損益已含手續費與交易稅。來源沒有可持久化的 fill id、逐筆淨收款或原始買進成本。

本次以使用者要求的資料庫已實現損益為目標，採明確、可重算的表示：僅接受 `Stock`／`Sell`，`tradeDate=sourceDate`、`proceeds=filledPrice×filledQty`（毛成交額，money HALF_UP scale 2）、`reportedNetPnl=realizedProfit-realizedLoss`、`investmentCost=proceeds-reportedNetPnl`。因此現有頁面的 `proceeds-investmentCost` 恆等於富邦回報的已含費稅淨損益；`investmentCost` 是此來源表示的**調節成本基礎**，不是聲稱已取得原始買進成本。若 cost 為負、精度／範圍超出既有欄位，整批拒絕，不猜值或截斷。

## 要做什麼

- [ ] **395.1 來源與 binding gate。** `POST /internal/realized-gains/read` 固定以 selected account 呼叫 `realized_gains_and_loses`，不收 account selector。Python 同次 capture 驗 `Result.is_success`、list、raw branch/account、日期、`Stock/Sell`、數量、正價格、非負 profit/loss；兩者同時正值、future sourceDate、缺欄或不合格一律 fail closed。成功 envelope 要有 `accountBindingExplicit=true`、queryDate、capture observedAt、HMAC fingerprint 與 canonical rows；raw account/branch/token 不跨 Python。false/missing/non-boolean binding 不可進 writer。

- [ ] **395.2 專用 owner、排程與交易邊界。** 使用獨立 `FUBON_REALIZED_GAIN_SYNC_ENABLED`、四個 Asia/Taipei cron `08:00/13:45/19:30/22:00`、single-flight 與既有 internal manual route（dryRun 預設 true）。owner 只由 t405 的 `FUBON_SYNC_OWNER_EMAIL` 決定，必為同一 configured ACTIVE ADMIN，無任何 fallback。HTTP、local name lookup 與所有來源驗證均在 transaction 外。dry-run 絕不取得 DB write lock 或寫入。

- [ ] **395.3 最小可稽核去重 schema 與固定 fingerprint。** migration 只在 `realized_gain` 加 nullable `sync_source varchar(32)`、`sync_fingerprint char(64)`、`sync_occurrence integer`。DB `CHECK` 必須是「三欄皆 null」或「`sync_source='FUBON_REALIZED_GAIN_SYNC'`、fingerprint 符合 `^[0-9a-f]{64}$`、occurrence >= 1」二擇一；手動既有列維持全 null。partial unique index 固定為 `(owner_user_id, sync_source, sync_fingerprint, sync_occurrence) WHERE sync_source IS NOT NULL`。fingerprint 只由 Java writer 在 strict normalized DTO 上產生，Python 不輸出或持久化它；它固定對 UTF-8 的 v1 `key=value` tuple 做 SHA-256：依下列列出順序、不排序：`version=FUBON_REALIZED_GAIN_SYNC_V1`、`sourceDate=YYYY-MM-DD`、`stockNo=<strict normalized code>`、`buySell=Sell`、`orderType=Stock`、`filledQty=<base-10 integer>`、`filledPrice=<BigDecimal.stripTrailingZeros().toPlainString(), zero=0>`、`realizedProfit=<scale-0 canonical integer>`、`realizedLoss=<scale-0 canonical integer>`；segments 之間恰為一個 LF byte `0x0a`，最後一段後無 LF，輸出小寫 64-hex。Java pure test 的固定 golden vector（2026-09-06／2330／Sell／Stock／1000／123.45／1000／0）必得 `c4cfb726c52f0a061986cf3fa0a02ce90885d0105298d10a4063674cb160f574`；Python tests 只核對 strict normalized fields。tuple 不含 account、branch、token、名稱、owner 或任一 local DB 欄位。不得新增 raw account、secret、ledger FK 或以可修改的 `asset_transaction` 作來源證明。

- [ ] **395.4 寫前比對、multiplicity 與同資料零寫。** `FubonRealizedGainWriter` 在獨立 `REQUIRES_NEW(timeout=30)` transaction 先以 `app_user FOR UPDATE` fresh 重驗 expected owner，再取得 `LOCK TABLE realized_gain IN SHARE ROW EXCLUSIVE MODE`，才查詢／新增 gain。每個 fingerprint group 先計算來源 multiplicity；新 sync row 的 occurrence 一律選該 fingerprint 現有 sync occurrence 集合中最小未使用的正整數。既有同步列的 source/mapped tuple 任一欄不一致即 `EXISTING_DATA_CONFLICT`、整批 rollback、絕不覆寫；來源縮小時舊列不得刪除。既有手動列以完整等價 tuple 計數：同 owner、assetCode、market=`台股`、currency=`TWD`、broker=`富邦證券`、tradeDate、shares（數值比較）、salePrice（數值比較）、proceeds（scale-2）、investmentCost（scale-2）、exchangeRate=null；`assetName` 明確排除，因它只是本地解析且不在富邦來源。incoming `filledPrice` 必能無捨入轉為既有 salePrice scale-4，否則整批拒絕；proceeds/investmentCost 依既定公式以 money HALF_UP scale-2 比較。manual equivalent 加上 valid existing sync row 的數量若已滿足該 group multiplicity，全部 `ALREADY_REPRESENTED`、零 INSERT/UPDATE；不足時才用最小未使用 occurrence 補齊。partial unique index 是跨 JVM race 的第二層保護；constraint／flush／commit failure 全批 rollback，不 catch 後繼續。

- [ ] **395.5 新列內容與資料隔離。** 新列固定 owner、`market=台股`、`currency=TWD`、`broker=富邦證券`、source date、canonical shares／sale price／money、`exchangeRate=null` 與同步去重欄位。名稱只使用 local stock master；查無名稱時以 stock code 作 `assetName`，不外查。不得新增／修改 asset transaction、snapshot、deposit、holding 或任何既有 realized gain；不刪來源本輪未回傳的舊列。

- [ ] **395.6 結果、目錄與測試。** 結果只回 sanitized outcome、rowCount、insertedCount、alreadyRepresentedCount、reason；SUCCESS 只在真 commit 後。BFF API inventory 將「已實現損益明細查詢」標示為已串接，說明為「富邦回報淨損益的調節成本基礎與同資料略過」，不把它說成原始成本帳或完整成交 ledger。Python/backend/BFF tests 必涵蓋 strict binding、官方字段、公式、兩筆相同來源列的 occurrence、同資料重跑零寫、既有相同手動列略過、既有同步列矛盾拒絕、two-writer race、owner race、dry-run、rollback 與 BFF 文案／計數。驗證全程維持 `FUBON_ENABLED=false` 及 realized flag false，不登入或查詢真人券商、下單、匯撥或執行 non-dry-run manual sync。

## 完成標準

完整 fixture source → canonical mapping → local PostgreSQL `realized_gain` 路徑必證明：來源值正確顯示為已實現淨損益、相同資料完全不寫入、不同／缺少 occurrence 才新增、任何不一致或失敗不改既有資料。完成後才可標示 API 已串接；這不等於取得了券商原始成本或完整交易流水。
