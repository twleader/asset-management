# [t400] 富邦庫存來源日期依官方格式解析後對帳

**對應 Requirements:** Requirement 90（庫存兩側來源身分先驗與整批一致性）
**前置任務:** t352 已有 Python 唯讀庫存 adapter
**Liquibase changeset:** 無

## 背景

main `5197d42a` 的 `portfolio.py` 直接把 raw `date` 與 queryDate 的 ISO 字串比較。官方 Inventory 回應範例的日期為 `YYYY/MM/DD`；因此兩側同日資料仍可能被拒絕為 `STALE_SOURCE_DATE`。本任務恢復既有要求：解析來源自己的日期後驗同一個台北 queryDate，再做 HMAC／identity／數量對帳，不從本機日期回填來源欄位。

2026-08-30 已唯讀核對官方 [Inventory](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/Inventories/) 與 [UnrealizedData](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/UnrealizedPnLDetail/) 文件。本任務不登入真人帳號，官方文件與 fake fixture 不當成真人回應驗證。

## 要做什麼

- [x] **400.1 只修庫存日期與必要測試。** production 編輯限 `fubon-broker-service/src/fubon_broker_service/portfolio.py`；沿用既有 `normalization.strict_vendor_date` 純函式，不另寫寬鬆日期解析器。改動既有測試／fixture 前先確認沒有其他 worktree 未提交同檔。不得更動有重疊修改的 `quotes.py`、共用規格文件、其他 endpoint、SDK 權限、排程、功能旗標、DB 或 Redis。
- [x] **400.2 來源日期先驗。** 每個非空 inventory／unrealized raw row 的 `date` 必须是非空字串，按嚴格 `YYYY/MM/DD` 解析成 `datetime.date` 並與捕捉的台北 queryDate 比較；不接受 ISO、短年月日、前後空白、不存在的日期或數值。缺失身分仍拒絕，格式無效用消毒的 `PortfolioError` 表達（可用 `INVALID_SOURCE_DATE`），合法但非 queryDate 保留 `STALE_SOURCE_DATE`。任一側任一列失敗即整批拒絕，HMAC callback 尚不得被呼叫。`RawIdentity.source_date` 使用 typed date；normalized queryDate 仍輸出 ISO，wire DTO 欄位與 batch seed 語意不變。
- [x] **400.3 保留其餘對帳規則。** queryDate 在呼叫前後仍須同台北日；兩支帳務回應必為 success 與 concrete list，account／branch 必與同一 accounting capture 的 selected account 精確相同，identity 集合須相等且不得重複，只接受既有 Stock／Buy 範圍，整股加零股與未實現持股數守恆，shares／成本精度與 emptyConfirmed 規則不變。不得降低條件、接受缺日期、將任何 stale 值標為今日或更換 HMAC。
- [x] **400.4 測試正常與拒絕路徑。** 修正既有成功 fixture 為官方斜線日期。以 fake gateway 驗證同日兩側成功且 ISO queryDate 不變、閏日成功、兩側各自 stale／invalid／missing 日期、非法 ISO raw、跨午夜 queryDate rollover、wrong account／branch 在 HMAC 前拒絕。保留全部既有 portfolio／account capture／redaction 測試；不只驗解析器本身，不呼叫真人 SDK。

## 驗證

```bash
bash scripts/spec-check.sh
PYTHONPATH=fubon-broker-service/src /tmp/fubon-venv-t395/bin/python -m pytest -q fubon-broker-service/tests
git diff --check
```

完整測試之外，由協調者從 feature worktree rebuild／recreate `fubon-broker-service`，核對 image／Python source provenance、health 及 disabled functional 狀態。保持 `FUBON_ENABLED=false` 與既有 secrets mount，不執行真人 read POST 或測試訂閱；Docker 正常不能取代官方 shape 的 fake 成功／失敗測試。這只是全面重構中的獨立修復，未解決的 SDK 生命週期、其他架構工作與 Task 394／395 仍須另外處理；不得提前刪除原分支。

## 完成報告

本任務已實作，並通過隔離測試、獨立程式審查及 feature Docker 驗收；提交與 main 合併紀錄以 Git history 為準。

- 只修改 `portfolio.py` 與兩個既有測試／fixture 檔。兩側 raw 日期先經 `strict_vendor_date` 轉為 `date`，再對照台北 queryDate；任一列格式錯誤或過期時，在 HMAC 前拒絕整批。normalized ISO wire、批次摘要與其餘對帳條件不變。
- Focused portfolio **59 tests**；完整 Python **715 tests（原 676＋新增 39）**，均為零失敗、錯誤、跳過。相同凍結來源在 Linux amd64 Docker test target 以 `--network none` 重跑，亦為 715 passed；這是同一套測試的環境覆核，不重複加總。
- 新增案例涵蓋兩側官方格式、閏日、台北與 UTC 日期不同、缺漏／無效／過期日期、後列失敗、空／非空批次跨午夜，以及 HMAC 與錯誤訊息界線。獨立程式審查三檔完整 diff，critical／major／minor 皆為 0。
- feature Docker image 重建與 container recreate 後 healthy；容器內全部 **21 個 Python source** 與已測試來源逐位元相同。`GET /internal/health` 為 200；`GET /internal/config` 保持 503／`DISABLED`。沒有真人券商呼叫、同步 POST、訂閱或功能旗標變更；Python 仍沒有 DB／Redis 存取。
- 本任務沒有新增 API 能力，正式目錄保留已交付的 52 筆／15 已串接。SDK 生命週期、其他架構重構及 Task394／395 仍須另行處理；原 Claude branch 仍保留。

本機證據位於 `/tmp/asset-takeover-20260830/broad-fubon-audit/` 的 `python-date-implementation/handoff.json`、`python-date-implementation/linux-test-run.json`、`python-date-implementation/code-review-result.json` 及 `feature-acceptance.json`；測試本身均隨本任務版控。
