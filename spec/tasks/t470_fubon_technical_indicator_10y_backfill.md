# [t470] 最近三日新增技術指標歷史缺口查證

**對應 Requirement:** 172
**查證日期:** 2026-10-02（Asia/Taipei）
**持久層／changeset:** 無；新增範圍為空，不執行回補。

## 查證範圍

「最近三天」固定為 Asia/Taipei `[2026-09-30 00:00:00, 2026-10-03 00:00:00)` 三個日曆日。只取 `origin/main` first-parent 在此窗首次納入的 merge commits，以 merge commit 和 first parent 的 tree diff 判定實際納入內容；重複 merge/rebase/cherry-pick 按 first-parent 首次納入去重。

查到以下 merge commits：

| Merge | 變更主題 | 技術指標新增 |
|---|---|---|
| `777505b9` | 富邦歷史日 K／分鐘 K 回補 | 無；新增 candle/history 路徑，不是 indicator profile |
| `40aab454` | 富邦歷史回補落地紀錄 | 無 |
| `d0b79e12` | 富邦歷史回補最終驗證紀錄 | 無 |
| `0f6a29a9` | SRPP 按需唯讀 API | 無 |
| `5ae462ff` | 備份目的地與逾時規格 | 無 |
| `e367b0fe` | 行情時效與回補計數修正 | 無 |

因此新增 indicator/profile/timeframe 集合為空。Task408／Task461 的既有指標不在本時間窗，不能因為它們的技術 facts 缺漏而擴大本次範圍。唯讀查詢於 2026-10-02 顯示 `stock_technical_indicator=0` 列、`fubon_technical_capture_member=0` 列；此為既有資料現況，不構成本 task 所定義的近期新增範圍。

## 工作項目

- [x] 470.1 固定台北時間三日窗，檢視 `origin/main` first-parent merge tree diffs，確認六筆合併均無新增 indicator/profile/timeframe。
- [x] 470.2 判定近期新增集合為空；因此本 task 沒有可對應的指標去查詢富邦／TWSE／TPEx 技術歷史 endpoint，也不把 K 線來源深度外推為指標深度。
- [x] 470.3 唯讀核對既有富邦技術事實表為 0 列；不據此將 Task408／Task461 既有 indicators 加進範圍。
- [x] 470.4 因新增集合為空，不新增 API、adapter、runner、campaign、receipt、writer、schema 或 Liquibase migration；不執行回補。
- [x] 470.5 保持零 Redis、雷達、排程、帳戶及交易副作用；沒有任何本 task 新增事實可寫入。
- [x] 470.6 報告結論：這三日沒有新增技術指標，故沒有符合本 task 範圍的資料可補；不宣稱既有指標資料具有完整十年 coverage。
- [x] 470.7 `scripts/spec-check.sh`：BLOCK 0、CHECK 0；`git diff --check` 通過。此 task 僅為唯讀查證與規格修正，無服務程式變更或回補 campaign。

## 未來非空集合的來源分類規則

若另有一個明確日期窗確認新增指標，才可套用以下回補規則：

- `AVAILABLE`：僅指成功官方回應實際列出的 sourceDate。
- `NO_DATA`：僅指符合官方成功 schema 的回應明確回空集合。
- `SOURCE_UNAVAILABLE`：僅指官方契約定義的明確來源不可用狀態。
- `UNSUPPORTED`：僅指官方文件明確宣告不支援該 profile/timeframe/history；盤中十年歷史不支援時記 `reason=TEN_YEAR_HISTORY_UNSUPPORTED`。
- `HISTORY_DEPTH_UNKNOWN`：日期可查但最早支援日或指定舊區段能力未經官方文件／實際回應證明。
- `FAILED`：transport、HTTP、schema 或 persistence error。404、5xx、timeout、未文件化錯誤或空 body 不得推成 `NO_DATA`／`UNSUPPORTED`；較新日期有資料也不能證明更早歷史深度。

只有新增集合非空且至少一項官方歷史 response 嚴格驗證成功，才評估必要的 runner、receipt、writer 或 migration。先驗證既有 writer 支援的 profile、provider、payload 與 immutable constraints；僅對欠缺能力做最小擴充。若沒有官方可用來源，僅交付唯讀來源／缺口報告，不新增持久層。富邦 technical API 的 `from/to` 參數不構成十年深度保證；歷史 candles 文件亦不可作 technical indicator 深度證明。目標十年只能稱 requested range，不能在缺少逐 profile/symbol 官方日期證據時宣稱已覆蓋十年。
