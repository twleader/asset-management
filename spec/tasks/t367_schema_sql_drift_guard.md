# [t367] 重產 `db/schema.sql` 稽核基準線，並以 `spec-check.sh` B10 建立機械防漂移閘門

**對應 Requirements:** Requirement 103（`db/schema.sql` 稽核基準線必須與運行中 DB 機械同步——漂移須在下一次 `scripts/spec-check.sh` 被機械指出，且兩支 auditor 對該檔互相矛盾的指引須統一）
**前置任務:** 無
**Liquibase changeset:** 無（本任務不建表、不改任何 changeset。**嚴禁**碰 `backend/src/main/resources/db/changelog/` 底下任何檔案——連 `--comment` 註解都算進 Liquibase checksum，改動會導致 `ValidationFailed` → business-services crash loop）

> **編號說明（連續避讓兩次）**：
> - 第一次：撰寫當下 Task 364／Requirement 100 由 `yuanta-securities-api-093d9b` 在途佔用、Task 365／Requirement 101 由 `trading-radar-valuation-label-cadb86` 在途佔用，故避開兩者、選用 366／102；**兩者其後皆已 landed**（本分支已合入）。
> - 第二次：366／102 在第三輪 spec 審查時被發現**同時被 `export-to-blog-0f382e`（交易雷達「匯出到 blog」）的工作區佔用**（其 `spec/requirements.md` 已寫入 `### Requirement 102／Task 366`、尚未 commit）。兩邊皆未 landed，依專案「主動避讓、不爭號」慣例由本任務再讓，續編為 **Task 367／Requirement 103**。讓號當下全機 57 個 worktree 掃描：367／103 均無人佔用。
> `scripts/spec-check.sh` 的 B1 撞號檢查以 `git merge-base HEAD origin/main` 三方比對，**看不到任何尚未 merge 的平行 worktree**，`BLOCK: 0` 不能當成沒撞號的證據；編號前必須另行掃描全機 worktree（本次實測 56 個）。

## 背景

### 這個檔在做什麼

`db/schema.sql` 是 repo 根目錄 `db/` 下唯一的檔案（`db/init/` 被 `.gitignore` 排除、本機才有）。它的檔頭自述用途是：

> 供程式碼審查與規格稽核比對「Entity / Liquibase changelog / 實際 DB」三者是否一致。

它是真正的基準線 `db/init/01_dump.sql`（完整 `pg_dump`，含真實個人財務資料，因資安 Requirement 29 被 `.gitignore` 排除）「去除全部資料」後的可版控鏡像。它**刻意不會被執行**——放在 `db/` 而非 compose 掛載的 `db/init/`，故不參與 DB 初始化。本任務不改變這個性質。

### 現在的錯誤行為（實測，2026-08-23）

`db/schema.sql` 靠人工重產、沒有任何機械保證，已大幅落後運行中的 `asset-postgres`：

- `db/schema.sql` 有 **74** 個 `CREATE TABLE`；運行中 DB 有 **85** 張表。反向為空——沒有任何一張表只存在於鏡像。
- **缺的 11 張表**（皆已註冊進 `db.changelog-master.yaml` 且 `databasechangelog` 顯示已套用）：

  | 表名 | 建立它的 changeset |
  |---|---|
  | `treasury_yield_batch` | `v1.92.0-treasury-yield-daily.sql` |
  | `treasury_yield_daily` | `v1.92.0-treasury-yield-daily.sql` |
  | `stock_dividend_snapshot` | `v1.94.0-radar-event-observations.sql` |
  | `stock_dividend_snapshot_event` | `v1.94.0-radar-event-observations.sql` |
  | `stock_dividend_fetch_observation` | `v1.94.0-radar-event-observations.sql` |
  | `etf_nav_observation` | `v1.95.0-etf-nav-observation.sql` |
  | `twse_institutional_daily` | `v1.96.0-radar-market-observations.sql` |
  | `stock_dividend_fetch_attempt` | `v1.100.0-radar-dividend-fetch-attempt.sql` |
  | `export_schedule_time` | `v1.102.0-export-schedule-multi-time.sql` |
  | `commodity_export_schedule_time` | `v1.103.0-commodity-export-schedule-multi-time.sql` |
  | `realized_gain_export_schedule_time` | `v1.104.0-realized-gain-export-schedule-multi-time.sql` |

- **漂移不只在表名**，**7 張既有表**的欄位／預設值／索引也已不同（這是為什麼本任務的檢查必須全文比對，不能只比表名）：

  | 既有表 | 鏡像少了什麼／錯在哪 |
  |---|---|
  | `commodity_price_history` | 缺 `provider`／`fetched_at`／`source_available_at`／`source_url` 四欄、`CHECK ck_commodity_source_time`、索引 `idx_commodity_price_available` |
  | `stock_dividend_history` | 缺 `event_key`／`event_status`／`ex_rights_date` 三欄與索引 `idx_dividend_event_key`；`source` 實際已由 `varchar(50)` 放寬為 `varchar(128)`；`uk_dividend_event` 在鏡像中仍是 **4 欄舊版**，運行中 DB 已是 **10 欄**（`v1.111.0-dividend-event-uniqueness.sql`，較 `v1.99.0` 的 9 欄多一個 `COALESCE(ex_rights_date, …)`） |
  | `daily_market_analysis` | 缺 `factor_groups` |
  | `deposit_type` | 缺 `withdrawal_order` |
  | `market_analysis_setting` | 缺 `engine`；`model` 預設值仍是已下架的 `claude-opus-4-8`（實際為 `claude-opus-5`） |
  | `portfolio_advice_setting` | 缺 `engine` |
  | `trading_radar_notification_setting` | 缺 `action_policy_version` |

- 依檔頭指令重產後，相對現檔的差異為 **新增 765 行、刪除 9 行**。9 行刪除中有 6 行只是「原本的最後一欄少了逗號」這種格式位移（有新欄位接在其後），3 行是上述 `source` 位數、`model` 預設值與舊版 `uk_dividend_event` 的真實變更。**沒有任何一張表被移除。**

### 這份漂移正在讓稽核者誤判——而且兩支 auditor 的指引互相矛盾

| 位置（行號為動工前實測，若已漂移請以實際 grep 為準） | 現行文字 | 問題 |
|---|---|---|
| `.claude/agents/spec-auditor.md:24` | 「**DB 現況的唯一基準是 `db/schema.sql`（pg_dump）**，不是 `db/changelog/**`。」 | 要求審查者採信落後 11 張表的檔案 |
| `.claude/agents/spec-auditor.md:37`（「資料來源正確性」列） | 「對 DB 現況的斷言**必須引用 `db/schema.sql`**」 | 同上 |
| `.claude/agents/arch-auditor.md:42` | 「DB 現況不要查 `db/changelog/**`，**也不要盡信 `db/schema.sql`**……實測已落後（截至 Task 245 仍缺 `crawler_export_setting`、`asset_transaction`）」 | 與上兩列直接對立 |
| `.claude/agents/arch-auditor.md:151` | 「不要用 `db/changelog/**` 或**落後的 `db/schema.sql`** 推測 DB 現況。」 | 同上 |
| `scripts/spec-check.sh:201`（B8 的 CHECK 文字） | 「注意 db/schema.sql 只是離線鏡像、已知落後（Task 241 實測缺 crawler_export_setting／asset_transaction），**不可當基準線**。」 | 那兩張表**現在都已存在於鏡像中**（`db/schema.sql:146` `asset_transaction`、`:469` `crawler_export_setting`）。**而且這段文字實際印不出來**——見下一節 |
| `spec/steering/structure.md:19-22`（`db/` 樹狀說明） | 「**不是可信基準線**……截至 Task 245 仍缺 `crawler_export_setting` 與 `asset_transaction`」 | 例證已失效 |
| `spec/tasks/README.md:100-102`（自足性自查段） | 「只能當離線參考，**不是可信基準線**……截至 Task 245 它仍沒有 `crawler_export_setting`／`asset_transaction`」 | 例證已失效。**注意 103–106 行的「但運行中 DB 也不等於 main 的現況」整段要保留** |
| `spec/design.md:5949`（`index_export_schedule` 段） | 「`db/schema.sql` **缺少這張歷史表，不能用它斷言現況**。」 | 三張 `index_export_schedule*` 表現在都在鏡像裡（各 `grep -c` 均為 1），該句已不成立，且與同檔 849 行的新口徑打架 |

> **`spec/design.md` 第 849 行起那段已於本任務的 spec 階段改寫完成**，實作階段只需複驗；第 5949 行那句由 367.9 處理。

### B8 的觸發條件本身壞掉——必須連同修好

`scripts/spec-check.sh:24` 是 `set -uo pipefail`，而 `added_lines()`（第 66–72 行附近）的最後一段 pipeline 是：

```bash
untracked_files | grep -E "…" | while IFS= read -r uf; do …; done
```

工作樹沒有未追蹤檔時，`untracked_files` 輸出 0 行 → `grep -E` 回 1 → `pipefail` 讓整個函式回 1 → B8 的 `if added_lines 'spec/**' | grep -qE 'db/changelog/.*\.sql'`（第 200 行）**恆為 false**。

實測（在本 worktree、工作樹乾淨時）：

```bash
$ untracked_files | wc -l
0
$ untracked_files | grep -E "spec/.*" | while IFS= read -r x; do :; done; echo $?
1                       # ← pipefail 下整條函式回 1
$ git diff origin/main...HEAD -- 'spec/**' | grep -E '^\+' | grep -cE 'db/changelog/.*\.sql'
13                      # ← 本分支有 13 行命中 B8 的樣式（此數字每改一次 spec 就會變，判準是 >0）
$ bash scripts/spec-check.sh | grep -c 'CHECK │'
0                       # ← 但 B8 一次都沒印出來
```

`added_lines()` 的另一個使用點（B5，第 169 行）走命令替換、不看離開碼，因此不受影響——**B8 是唯一受害者**。

這件事讓 367.7 的「改 B8 文字」在不修 bug 的前提下效果為零，所以 367.7 把修 bug 列為硬性要求。

### 為什麼修法是「spec-check 加一項」而不是 CI 或檔頭註記

- repo 內**沒有 `.github/`**，本專案不存在任何 CI runner；即使加了，CI runner 也接觸不到本機的 `asset-postgres` 容器，跑不了這個比對。
- 只在檔頭加「產生時表數」是換一種人工比對，沒有強制力——本專案已經用了三年的「在文件裡寫提醒」，實測失效（13 支任務檔各自寫過告誡或反向引用，口徑從未統一，漂移照樣累積到 11 張表）。檔頭表數仍然要寫，但只當腳本可離線自檢的附加條件。
- `scripts/spec-check.sh` 是本專案唯一每個任務都會實際跑到的機械證據閘門，而且**已有同型前例**：B9（9090 gateway／OpenAPI 契約）就是「呼叫 `scripts/tests/` 下的獨立測試腳本，依離開碼決定 BLOCK」。本任務照抄這個形狀。

### 三項刻意接受的限制（實作時不要試圖繞過，也不要在報告裡宣稱已解決）

1. **B10 是 lagging 檢查。** 全樹實際執行 `spec-check.sh` 的只有 `/spec-review` 這條路徑的兩份 skill 定義（`.claude/skills/spec-review/SKILL.md`、`.agents/skills/spec-review/SKILL.md`）；`scripts/git-hooks/commit-msg:75` 只在錯誤訊息裡提到它、**並不執行**。那條路徑跑在實作**之前**，漂移卻產生於實作**之後**，所以 B10 攔到的是「上一輪沒重產的漂移」。
2. **`asset-postgres` 是全機 56 個 worktree 共用的可變狀態。** 別的 worktree 跑過 `/run-stack` 就會把它尚未 merge 的 changeset 套進共用 DB，讓與你無關的漂移出現在你的 `spec-check`。**這是 367.6 要做嚴重度分流的唯一理由**，不要把分流簡化掉。
3. **367.10 的補償措施強度低於 commit-msg hook**，限制 1 的時間差因此仍然存在。完成報告不得宣稱時間差已消除。

## 要做什麼

- [x] **367.1 重產 `db/schema.sql`。** 指令：

  ```bash
  cd <本 worktree 根目錄>
  docker exec asset-postgres pg_dump -U assets -d assets \
    --schema-only --no-owner --no-privileges \
    | grep -v '^\\restrict\|^\\unrestrict' > /tmp/fresh-schema.sql
  ```

  - **不要照抄現行檔頭第 19 行的 `> db/schema.sql`。** 那個重導向會在 pipeline 啟動時就把 `db/schema.sql` 連同檔頭一起截斷，第 20 行的「接著把本段標頭補回檔首」屆時已無來源（只能 `git show` 撈回）。367.3 會把這個檔頭指令本身一併修掉。
  - `grep` 樣式的反斜線**必須寫成 `\\`**。`pg_dump` 16 會輸出兩行帶隨機 token 的 `\restrict` / `\unrestrict`，每次執行都不同；不濾掉的話每次重產都會出現假 diff。單一 `\r` 會被 `grep` 當成字面 `r`、濾不掉。
  - 已實測：連跑兩次 `pg_dump`（濾除後）**位元級完全相同**，輸出中無 `setval`、無時間戳，故「逐位元比對」是成立的不變條件。
  - 接著把現有檔案的專案檔頭（現況為第 1–26 行，第 26 行恰為 `--`；第 27 行起是 `pg_dump` 本體，本體首行亦為 `--`、第 28 行為 `-- PostgreSQL database dump`）接到 `/tmp/fresh-schema.sql` 之前，寫回 `db/schema.sql`。**不要手改 `pg_dump` 本體的任何一個字元。**

- [x] **367.2 更新檔頭的「產生資訊」。** 現行第 25 行為：

  ```
  -- 產生資訊：PostgreSQL 16.14 / pg_dump 16.14，來源 asset-postgres 容器，2026-08-09
  ```

  改為同時載明產生日期與**產生當下的 `CREATE TABLE` 張數**，例如：

  ```
  -- 產生資訊：PostgreSQL 16.14 / pg_dump 16.14，來源 asset-postgres 容器，2026-08-23
  -- 產生當下表數：<N> 張 CREATE TABLE（對照：SELECT count(*) FROM pg_tables WHERE schemaname='public';）
  ```

  `<N>` 必須是實際數出來的（`grep -c '^CREATE TABLE' db/schema.sql`），**不得照抄本任務檔背景段寫的 85**——動工當下若有別的 worktree 又套了新 changeset，實際值會不同。
  **格式必須讓「`產生當下表數：<數字> 張`」可被穩定 grep 出來**，367.5 的腳本要解析它。

- [x] **367.3 修正並補充檔頭的「重新產生」段（第 16–23 行）。**
  - （a）第 19 行的 `> db/schema.sql` 改為 `> /tmp/fresh-schema.sql`；第 20 行的「接著把本段標頭補回檔首」改寫成可執行的步驟（例如：「再把本檔第 1 行至『`-- PostgreSQL database dump`』前一行的檔頭，接到 `/tmp/fresh-schema.sql` 之前寫回 `db/schema.sql`」）。
  - （b）補一小段說明：本檔與運行中 DB 的同步由 `scripts/tests/schema-sql-drift-test.sh` 逐位元比對、由 `scripts/spec-check.sh` 的 B10 執行。**措辭必須與「lagging 檢查」一致**——寫「改動 schema 後未重產本檔，會在**下一次** `spec-check` 被指出」，不得寫成「立即攔下」。

- [x] **367.4 新增 `scripts/tests/schema-sql-drift-test.sh`（獨立可執行）。** 語意：「`db/schema.sql` 去除專案檔頭後，是否逐位元等於此刻重跑 `pg_dump` 的輸出」。硬性要求：

  - **全文比對，不是只比表名。** 背景段已證實漂移大量發生在欄位、預設值與索引定義上；只比表名會漏掉 `commodity_price_history` 四欄、`stock_dividend_history` 的 `source` 位數與 `uk_dividend_event` 這一整類。
  - **檔頭／`pg_dump` 本體的分界不得寫死行號。** 以「檔案中第一個**整行等於** `-- PostgreSQL database dump` 的行」定位（**整行相等，不是 substring**——檔尾有 `-- PostgreSQL database dump complete`，用 substring 會定位錯），本體起點為其**前一行**；並斷言該前一行內容恰為 `--`，否則視為檔頭結構被破壞、以離開碼 `1` 失敗並說明原因。
  - **離開碼三態**：
    - `0` — 同步。
    - `1` — 已證實漂移，含：本體與 `pg_dump` 輸出不同、檔頭結構破壞、367.5 的檔頭表數不符或缺該行。
    - `2` — 無法查證：`docker` 指令不存在、`asset-postgres` 容器不在運行、或 `pg_dump` 失敗。此狀態**不得**回 `1`。
  - **所有 `docker`／`pg_dump` 呼叫都必須顯式捕捉離開碼並轉成 `2`。** 既有 `scripts/tests/price-cache-monotonic-write-redis-contract-test.sh:2` 用的是 `set -euo pipefail`，照抄就會讓 `docker exec … pg_dump` 失敗時以 docker 的離開碼（1 或 125）結束腳本，B10 的分流會把它誤記為 BLOCK，直接違反 367.6。同理 `diff` 有差異時回 1 是正常的，比對處必須用 `if ! diff …` 或顯式 `|| true` 包住，不得讓 `set -e` 中斷。
  - **漂移時輸出必須可直接行動**，至少含：僅存在於運行中 DB 的表、僅存在於 `db/schema.sql` 的表、差異行數（新增／刪除各幾行）、完整的重產指令（與 367.1 相同那段）。
  - **`pg_dump`／server 版本註解行的特別處理。** 本體第 6–7 行是 `-- Dumped from database version 16.14` 與 `-- Dumped by pg_dump version 16.14`，兩行都在比對範圍內。postgres 映像小版本升級（16.14→16.15）會讓 schema 一字未改也判為漂移。**不要把這兩行濾掉**（會失去版本可追溯性），但當差異**只發生在這兩行**時，輸出必須明示「差異僅在 `pg_dump`／server 版本註解行，成因為 postgres 映像升級，重產即可」，離開碼仍為 `1`。
  - 沿用 `scripts/tests/` 既有慣例：`#!/usr/bin/env bash`、以 `repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"` 定位 repo、輸出 `PASS:` / `FAIL:` 行、暫存檔用 `mktemp` 並以 `trap ... EXIT` 清掉。
  - 容器名允許以環境變數覆寫（沿用 `${T350_REDIS_CONTAINER:-asset-redis}` 的慣例），預設 `asset-postgres`，變數名用 `SCHEMA_DRIFT_CONTAINER`。

- [x] **367.5 腳本同時驗證檔頭宣告的張數（純離線檢查）。** 讀 367.2 寫進檔頭的「產生當下表數：N 張」，與 `db/schema.sql` 實際的 `CREATE TABLE` 行數比對。**不一致、或檔頭根本沒有這一行，一律以離開碼 `1` 失敗**（缺行時訊息說明「檔頭缺產生當下表數宣告，請依 367.2 補上」）。這段必須排在**任何 `docker` 呼叫之前**並在失敗時直接 `exit 1`——否則沒開 Docker 時會先被 `2` 蓋掉，這個離線分支永遠測不到。

- [x] **367.6 `scripts/spec-check.sh` 新增 B10（含嚴重度分流）。** 位置放在既有 B9（9090 gateway／OpenAPI）之後、`echo "════ 結果 ════"` 之前。骨架：

  ```bash
  # ── B10. db/schema.sql 稽核基準線是否仍等於運行中 DB ──────────────
  DRIFT_TEST='scripts/tests/schema-sql-drift-test.sh'
  DRIFT_HINT='處置：先查 SELECT filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5（注意是 filename 欄、不是 id 欄——一個 .sql 可含多個 changeset id），逐一 git cat-file -e origin/main:<該檔> 確認是否都在 origin/main；查無時先排除「版號避讓」情形（本專案一年 12 次避讓，改 id 會讓 Liquibase 重跑，DB 會留下同 slug、僅差一個 minor 版號的舊紀錄，那不算未 merge 的 schema）。確認確有 main 沒有的 schema 物件才停下回報，不得以重產把別人未 merge 的 schema 帶進 main。'
  if [ ! -f "$DRIFT_TEST" ]; then
    block "缺 $DRIFT_TEST，db/schema.sql 無機械防漂移"
  else
    drift_output=$(bash "$DRIFT_TEST" 2>&1)
    drift_status=$?
    printf '%s\n' "$drift_output"
    case "$drift_status" in
      0) ;;
      2) check "db/schema.sql 是否漂移無法查證（asset-postgres 未運行或 docker 不可用）——本次不判定，待容器起來後重跑" ;;
      *)
        if printf '%s\n' "$DIFF_FILES" | grep -qE '^db/schema\.sql$|^backend/src/main/resources/db/changelog/'; then
          block "db/schema.sql 未通過漂移檢查（成因見上方完整訊息）——本次變更已碰到 schema／changelog，須先處理。$DRIFT_HINT"
        else
          check "db/schema.sql 未通過漂移檢查（成因見上方完整訊息）。本次變更未碰 schema／changelog，漂移可能來自其他 worktree 尚未 merge 的 changeset。$DRIFT_HINT"
        fi
        ;;
    esac
  fi
  ```

  硬性要求：
  - **`drift_status=$?` 必須緊接在指令賦值的下一行**——中間插任何指令都會讓 `$?` 變成那個指令的離開碼。
  - **BLOCK 與 CHECK 兩種訊息都要帶 `$DRIFT_HINT`。** 本任務自己就必然走 BLOCK 分支（它一定會改 `db/schema.sql`），指引只寫在 CHECK 分支等於沒寫。
  - **fallback 訊息不得寫死成「與運行中 DB 不一致」**：離開碼 `1` 也可能來自檔頭表數不符（此時本體與 DB 完全同步）。
  - **分流不得簡化掉**（理由見「三項刻意接受的限制」第 2 條）。
  - `scripts/spec-check.sh:24` 是 `set -uo pipefail`（**沒有 `-e`**），子腳本回 1／2 不會中斷；`case` 三分支的變數都已賦值，不會觸發 `-u`。`printf '%s\n' "$DIFF_FILES" | grep -qE` 這個寫法不會踩到 367.7 要修的那顆 `pipefail` 地雷（`printf` 是 builtin、輸出必定非空），B6 第 183 行已有同型可用前例。

- [x] **367.7 修好 B8——先修 bug，再改文字。** 兩件事都要做，只做後者等於沒做（理由見背景段「B8 的觸發條件本身壞掉」）。
  - **（a）修 bug（硬性）**：讓 `added_lines()` 的離開碼不再決定 B8 是否觸發。最小改法二擇一：在 `added_lines()` 末尾補一行 `return 0`；或把 B8 改成
    `b8_hit=$(added_lines 'spec/**' | grep -cE 'db/changelog/.*\.sql' || true); if [ "${b8_hit:-0}" -gt 0 ]; then`。
    改完必須**實證會印出來**：本分支的 spec 新增行本來就有多行命中該樣式（第三輪審查當下實測 13 行；**驗收判準是「命中 ≥1 行且 B8 的 CHECK 確實印出」，不得寫死行數**），跑 `bash scripts/spec-check.sh` 應該看到 B8 的 CHECK 出現（B8 是 CHECK 不是 BLOCK，不影響離開碼）。
  - **（b）改文字**：B8 的主題（「spec 引用了 `db/changelog/*.sql` 描述 DB 現況」）與 CHECK 級別**維持不變**；只改其中對 `db/schema.sql` 的描述——
    - 刪掉「只是離線鏡像、已知落後（Task 241 實測缺 crawler_export_setting／asset_transaction），不可當基準線」（那兩張表現在都在鏡像裡：`db/schema.sql:146`／`:469`）。
    - 改述為：`db/schema.sql` 由 B10 機械查核與運行中 DB 的同步，可用來查欄位型別／位數／nullable／索引。
    - **新增**（B8 現行文字沒有這一句，不是「保留」）：運行中 DB 可能已套用其他 worktree 尚未 merge 的 changeset，涉及「main 現況」的斷言仍須複驗 `databasechangelog`。文字可取自 `spec/tasks/README.md` 既有段落。
  - **（c）順帶**把 B10 與**遺漏的 B8** 一併補進 `scripts/spec-check.sh` 檔頭的檢查清單註解（第 9–16 行；現況是 B7 之後直接跳到 B9）。

- [x] **367.8 統一兩支 auditor 的口徑。** 修改 `.claude/agents/spec-auditor.md`（第 24 行「專案陷阱」第 2 點、第 37 行「資料來源正確性」列）與 `.claude/agents/arch-auditor.md`（第 42 行「專案陷阱」第 1 點、第 151 行「不要做的事」），四處改成同一套敘述：

  > `db/schema.sql` 由 `scripts/spec-check.sh` B10 機械查核與運行中 DB 的同步，可作為欄位型別／位數／nullable／預設值／索引的離線查證依據。但它反映的是**運行中 DB**，而運行中 DB 可能含其他 worktree 尚未 merge 的 changeset；涉及「main 現況」的斷言仍須以 `docker exec asset-postgres psql -U assets -d assets -c '\d <table>'` 並比對 `databasechangelog` 尾端複驗。`db/changelog/**` 一律不得用於描述現況（那裡有永不執行的 changeset）。

  **只替換該處中關於 `db/schema.sql` 的那一句，不得整段覆蓋。** 被指定的四處有兩處還帶著別的、仍然有效的規範，一律原樣保留：
  - `spec-auditor.md:37`「資料來源正確性」同一格裡還有「即時價只能從 Redis、收盤價只讀 `stock_price_history`、business-services 不直連外部行情 API」與「已改為 DB 可設定的排程時點不得在 spec 裡寫死成字面時間（Task 195：『寫死正是本次漂移成因』）」。
  - `arch-auditor.md:42`「專案陷阱」第 1 點裡還有「查不到（容器沒跑）就在報告寫『無法查證』，**不要用 changelog 推測**」。

  （`.agents/`——Codex 側的 skill 副本——實測沒有 auditor 檔、也零命中 `schema.sql`，不需同步。）

- [x] **367.9 同步其餘四份現行指引文件。**
  - `spec/steering/structure.md:19-22` 的 `db/` 樹狀說明 → 367.8 的口徑，移除「截至 Task 245 仍缺 `crawler_export_setting` 與 `asset_transaction`」。
  - `spec/tasks/README.md:100-102` 的自足性自查段 → 同上。**第 103–106 行「但運行中 DB 也不等於 main 的現況」整段必須原樣保留**，那正是新口徑要強調的部分。
  - `spec/design.md:5949`（`index_export_schedule` 段）末句「`db/schema.sql` 缺少這張歷史表，不能用它斷言現況」→ 該三張 `index_export_schedule*` 表現在都在鏡像裡（各 `grep -c '^CREATE TABLE public.index_export_schedule…'` 均為 1），此句已不成立。改寫時**保留它真正要防的意思**：本檔是 v1.88.0 **遷移後**的狀態，不能用來推測遷移前的欄位形貌；要確認 parent 是否已套用仍須查 `databasechangelog` 與 `\d index_export_schedule`。
  - `scripts/README.md` 兩處：（i）`spec-check.sh` 那一列逐項列舉了檢查名稱（「編號撞號／重號、Liquibase changeset 版號碰撞與冪等性、宣稱的測試類是否存在、文件計數漂移，並執行 9090 gateway/OpenAPI 防漂移契約」），加了 B10 之後會過期，須補上 B10；（ii）同檔的測試腳本表（已登錄 `tests/configure-tailscale-api-gateway-test.sh` 與 `tests/docker-external-api-openapi-test.rb`）須新增 `tests/schema-sql-drift-test.sh` 一列。本任務既然已更新 `CLAUDE.md` 的同性質工具表，就不能漏掉更正式的這一份。
  - **移除的只是「把過期數字／例證當現況」的敘述；作為歷史脈絡的引用可以保留**（例如 `spec/design.md` 第 849 行起已改寫段落中的「Task 245 當時僅 55 張 `CREATE TABLE`」不要刪）。

- [x] **367.10 `spec/tasks/README.md` 的「檔案結構」任務檔模板新增一條收尾約定。** 加在模板的 `## 驗證` 段（不是「自足性自查」清單——那份清單的讀者是寫任務檔的人、時機在寫檔當下，收尾那一刻不會再讀到）。文字大意：**任務若動到 `backend/src/main/resources/db/changelog/**`，驗證／收尾步驟必須包含重產 `db/schema.sql`（`bash scripts/tests/schema-sql-drift-test.sh` 應回 0）。** 這是刻意不改 `commit-msg` hook 的補償措施，強度低於 hook，見「三項刻意接受的限制」第 3 條。
  先例：`spec/tasks/t290_tw_official_close_reconciliation.md:46` 就已經寫過「同步 `db/schema.sql`」這種收尾要求，本條是把個案慣例升為模板。

- [x] **367.11 複驗 `spec/design.md` 第 849 行起的段落。** 該段已於 spec 階段改寫，實作階段只需確認：(a) 它描述的機制與最終落地的腳本／B10 一致；(b) 措辭沒有對現況說謊（「保證同步」這類絕對措辭已改為「在每次 `spec-check` 時機械查核」，並已載明 lagging 與分流兩項性質）。

- [x] **367.12 不得做的事（逐條都是硬性禁令）：**
  - 不新增／修改／刪除任何 Liquibase changeset，不碰 `backend/src/main/resources/db/changelog/` 底下任何檔案（含註解——會讓 checksum 失效造成 crash loop）。
  - 不新增資料表、不改任何 Entity、不改任何 API 路徑、不改 9090／Tailscale 路由、不新增 `@Scheduled`。
  - **不改 `scripts/git-hooks/commit-msg` 的觸發清單。** 把「staged 含 `db/changelog/**` → 必須同 commit 附 `db/schema.sql`」加進 hook 確實能綁住產生漂移的那個 commit，但那是使用者所列三個選項之外的第四種機制；本任務刻意只採用一種閘門，補償措施是 367.10。若實作時認為非加不可，**停下來回報，不要自行加**。
  - 不把 `db/schema.sql` 搬到 `db/init/`，也不讓它以任何方式參與 DB 初始化。
  - **不修改已完成的歷史任務檔。** 全樹實測共 13 支引用 `db/schema.sql`：`t231`／`t242`／`t245`／`t287`／`t290`／`t295`／`t330`／`t335`／`t342`／`t345`／`t356`／`t357`／`t360`。也不改 `spec/tasks.md` 中 Task 201／204.9b 的完成記錄。那些是派工當下的事實紀錄，改寫等同竄改歷史。
  - **不要裸跑 `git clean -fd`**——會刪掉未追蹤但重要的 `db/init/` 等目錄。要復原改動只用 `git checkout -- <path>`。

## 驗證

全部在本 worktree 根目錄執行。**所有預期數字一律以實跑值為準，不得照抄本檔寫的 85。**

**（0）改動前先記下舊文字的基準命中數（367.7b／367.8／367.9 的驗收基準）**

舊文字有多種寫法（「**不是**可信基準線」「**不可當**基準線」「不要盡信」「唯一基準是」「落後的」「必須引用」「截至 Task 245 仍缺」…），只用單一樣式的「零命中」grep 會靜默假通過。先跑：

```bash
grep -ranE '不是可信基準線|不可當基準線|不要盡信|唯一基準是 `db/schema\.sql`|落後的 `db/schema\.sql`|必須引用 `db/schema\.sql`|Task 241 實測缺|截至 Task 245 仍缺|截至 Task 245 它仍沒有|缺少這張歷史表' \
  .claude/agents/ spec/steering/ spec/tasks/README.md spec/design.md scripts/spec-check.sh | tee /tmp/schema-legacy-before.txt | wc -l
```

動工前實測為 **10 行**，分布在 **8 個位置**（`tasks/README.md` 與 `structure.md` 各跨兩行）：

```
.claude/agents/spec-auditor.md:24     唯一基準是 db/schema.sql
.claude/agents/spec-auditor.md:37     必須引用 db/schema.sql
.claude/agents/arch-auditor.md:42     不要盡信 db/schema.sql
.claude/agents/arch-auditor.md:151    落後的 db/schema.sql
spec/tasks/README.md:100-101          不是可信基準線 ＋ 截至 Task 245 它仍沒有…
spec/steering/structure.md:19-20      不是可信基準線 ＋ 截至 Task 245 仍缺…
scripts/spec-check.sh:201             Task 241 實測缺… ＋ 不可當基準線
spec/design.md:5949                   缺少這張歷史表
```

把實跑值抄進完成報告。**全部改完後同一條指令必須輸出 0**（若動工當下實跑不是 10，以實跑為準，但每一個位置都必須被改到）。

**（1）重產結果本身**

```bash
FILE_N=$(grep -c '^CREATE TABLE' db/schema.sql)
DB_N=$(docker exec asset-postgres psql -U assets -d assets -tAc \
  "SELECT count(*) FROM pg_tables WHERE schemaname='public';" | tr -d ' ')
echo "file=$FILE_N db=$DB_N"; [ "$FILE_N" = "$DB_N" ] && echo OK || echo MISMATCH
```

```bash
for t in twse_institutional_daily etf_nav_observation stock_dividend_snapshot \
         stock_dividend_snapshot_event stock_dividend_fetch_observation \
         stock_dividend_fetch_attempt treasury_yield_batch treasury_yield_daily \
         export_schedule_time commodity_export_schedule_time \
         realized_gain_export_schedule_time; do
  printf '%-40s %s\n' "$t" "$(grep -c "^CREATE TABLE public\.$t " db/schema.sql)"
done
```
11 行全部必須是 `1`。

```bash
grep -n 'source character varying(128)' db/schema.sql
grep -n "DEFAULT 'claude-opus-5'" db/schema.sql
grep -A12 'CREATE UNIQUE INDEX uk_dividend_event' db/schema.sql | head -14
grep -c 'ck_commodity_source_time\|action_policy_version\|withdrawal_order\|factor_groups\|idx_dividend_event_key\|idx_commodity_price_available' db/schema.sql
```
前兩項各須有命中；`uk_dividend_event` 必須是 **10 欄版**（含 `COALESCE(ex_rights_date, …)` 與 `COALESCE(event_key, …)`）；最後一項計數 ≥ 6。

**（2）漂移測試本身（同步狀態下應通過）**

```bash
bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"
```
預期 `PASS` 且 `exit=0`。

**（3）漂移＋歸屬本次 → 腳本回 1、B10 BLOCK → 還原**

```bash
cp db/schema.sql /tmp/schema-backup.sql
python3 - <<'PY'
import io
p='db/schema.sql'; s=io.open(p,encoding='utf-8').read()
old='CREATE TABLE public.twse_institutional_daily ('
assert s.count(old)==1
io.open(p,'w',encoding='utf-8').write(s.replace(old,'CREATE TABLE public.zzz_injected_drift (',1))
PY
bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"
bash scripts/spec-check.sh; echo "spec-check exit=$?"
cp /tmp/schema-backup.sql db/schema.sql && rm /tmp/schema-backup.sql
```
預期腳本 `exit=1`，輸出同時出現「僅存在於運行中 DB：`twse_institutional_daily`」與「僅存在於 db/schema.sql：`zzz_injected_drift`」兩份清單（改名比單純刪表更嚴格，兩個方向一次驗到）。
`spec-check` 預期出現 B10 的 **BLOCK**（`DIFF_FILES` 含 `db/schema.sql`）、離開碼 1，且訊息含 `$DRIFT_HINT` 的複驗指引。

**（4）漂移＋不歸屬本次 → B10 CHECK（端到端，不得用單測正則代替）**

`scripts/spec-check.sh` 吃 `[base-ref]` 參數（第 26 行）；`BASE=HEAD` 時三點 diff 為空，`DIFF_FILES` 只剩未提交／未追蹤檔。**最省事的做法是在 367.1 重產「之前」、367.4／367.6 已完成時先跑一次**——那時 `db/schema.sql` 本來就漂移：

```bash
bash scripts/spec-check.sh HEAD; echo "exit=$?"
```
預期看到 B10 的 **CHECK**（不是 BLOCK），且離開碼 0（CHECK 不影響離開碼）。

> **不要拿「變更檔數: 0」當判準。** `DIFF_FILES`（`spec-check.sh:49-51`）併入了 `git diff`、`git diff --cached` 與未追蹤新檔，所以在這個時點它至少含 `scripts/spec-check.sh`（已加 B10）與新檔 `scripts/tests/schema-sql-drift-test.sh`，變更檔數不會是 0。**判準是「`DIFF_FILES` 不含 `db/schema.sql`、也不含 `backend/src/main/resources/db/changelog/`」→ B10 必須是 CHECK。** 若想讓變更檔數真的歸零，先把 `scripts/` 的變更 commit 再跑。
> 若已經重產完才想補驗，改為：注入檔頭表數差異 → `git commit --no-verify -m tmp -- db/schema.sql` → `bash scripts/spec-check.sh HEAD` → `git reset --soft HEAD^` → 從備份還原。

**（5）無法查證 → 腳本回 2、B10 CHECK**

不要真的把容器停掉（全機共用一套 stack，會影響其他 worktree）。用假容器名：

```bash
SCHEMA_DRIFT_CONTAINER=asset-postgres-does-not-exist \
  bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"
```
預期 `exit=2`，訊息為「無法查證」。B10 側須確認記為 CHECK 而非 BLOCK。

**（6）檔頭張數宣告的守門（367.5）——注入值必須由實跑取得**

```bash
cp db/schema.sql /tmp/schema-backup.sql
n=$(grep -c '^CREATE TABLE' db/schema.sql)
sed -i '' "s/產生當下表數：${n} 張/產生當下表數：$((n-1)) 張/" db/schema.sql
grep -q "產生當下表數：$((n-1)) 張" db/schema.sql || echo "!! 注入失敗，本項驗證無效"
bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"
cp /tmp/schema-backup.sql db/schema.sql && rm /tmp/schema-backup.sql
```
預期 `exit=1`，且訊息指向檔頭宣告不符、**不得**宣稱「與運行中 DB 不一致」（此時本體仍完全同步）。
`grep -q` 那行是必要的：`sed` 沒匹配到不會報錯、會以 exit 0 靜默結束，沒有這道驗證會把「從沒測到」誤記成通過。

**（7）B8 修好後真的會印出來（367.7a）**

```bash
bash scripts/spec-check.sh 2>&1 | grep 'CHECK │'
```
本分支的 spec 新增行有多行命中 `db/changelog/.*\.sql`（實跑 `git diff origin/main...HEAD -- 'spec/**' | grep -E '^\+' | grep -cE 'db/changelog/.*\.sql'` 取當下值，判準是 >0），修好後這裡必須看到 B8 的 CHECK（本次引用屬「哪個 changeset 建了這張表」的正當用途，依 B8 自己的豁免條款可忽略內容，但**必須看得到它被印出來**）。

**（8）還原後的完整 spec-check（最終狀態）**

```bash
bash scripts/spec-check.sh; echo "exit=$?"
```
必須 `BLOCK: 0`、`exit=0`（B8 的 CHECK 會出現，屬預期）。特別確認 B7（Requirements 計數）沒有 BLOCK。

**若最終仍出現 B10 BLOCK**（表示重產與驗收之間又有別的 worktree 套了 changeset）：

```bash
docker exec asset-postgres psql -U assets -d assets -tAc \
  "SELECT filename FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 10"
# 逐一確認對應檔在 origin/main（注意用 filename 欄，不是 id 欄）：
git cat-file -e origin/main:<上面查到的 filename> && echo IN_MAIN || echo NOT_MAIN
```

**判讀規則（照字面只看「在不在 main」會有假陽性）：**

- `filename` 全部 `IN_MAIN` → 重產一次即可。
- 出現 `NOT_MAIN` 時**先排除版號避讓**：本專案一年內有 12 次編號避讓，改 changeset id 會讓 Liquibase 認成新 migration 重跑，DB 因此留下「同 slug、僅差一個 minor 版號」的舊紀錄。實測今天就有兩筆：`v1.109.0-dividend-ex-rights-date`（main 上是 `v1.110.0-dividend-ex-rights-date`）與 `v1.108.0-model-lineup-refresh`（main 上是 `v1.109.0-model-lineup-refresh`），另有 `v1.78.0-stock-alert-group-constraints`／`v1.88.0-index-export-multi-time-market-migrate`（後者是 id ≠ 檔名的例子）。**這些都不算未 merge 的 schema。**
- 排除之後仍有 main 沒有的 schema 物件 → **停下回報，不得重產。**

**（9）本任務不涉及可執行程式碼，故不需 `/run-stack`。**

`db/schema.sql`、`scripts/**`、`spec/**`、`.claude/agents/**`、`CLAUDE.md` 都不進任何 container image，也不影響任何運行中服務。**但仍須確認沒有誤觸實作檔**：

```bash
git status --short
git diff --stat
```
變更清單中不得出現 `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、`docker-compose.yml` 底下的任何檔案（`backend/src/main/resources/db/changelog/` 尤其不得出現）。

## 完成報告

**實作日期：** 2026-08-23（本 worktree：`.claude/worktrees/schema-sql-regenerate-drift-check-633729`）

### 實際改了哪些檔

`git status --short`：

```
 M .claude/agents/arch-auditor.md
 M .claude/agents/spec-auditor.md
 M db/schema.sql
 M scripts/README.md
 M scripts/spec-check.sh
 M spec/design.md
 M spec/steering/structure.md
 M spec/tasks/README.md
?? scripts/tests/schema-sql-drift-test.sh
```

`git diff --stat`（未含未追蹤新檔）：

```
 .claude/agents/arch-auditor.md |   4 +-
 .claude/agents/spec-auditor.md |   4 +-
 db/schema.sql                  | 802 ++++++++++++++++++++++++++++++++++++++++-
 scripts/README.md              |   3 +-
 scripts/spec-check.sh          |  38 +-
 spec/design.md                 |   4 +-
 spec/steering/structure.md     |  12 +-
 spec/tasks/README.md           |  17 +-
 8 files changed, 854 insertions(+), 30 deletions(-)
```

新檔 `scripts/tests/schema-sql-drift-test.sh`（已 `chmod +x`）。
**未觸及** `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、
`docker-compose.yml`，尤其未觸及 `backend/src/main/resources/db/changelog/`（367.12 硬性禁令）。

### 驗證輸出

**（0）舊文字基準命中數 → 改動前 10 行 / 改動後 0 行**

改動前（8 個位置、10 行，與本任務檔背景段預測一致）：

```
.claude/agents/spec-auditor.md:24     唯一基準是 db/schema.sql
.claude/agents/spec-auditor.md:37     必須引用 db/schema.sql
.claude/agents/arch-auditor.md:42     不要盡信 db/schema.sql
.claude/agents/arch-auditor.md:151    落後的 db/schema.sql
spec/tasks/README.md:100-101          不是可信基準線 ＋ 截至 Task 245 它仍沒有…
spec/steering/structure.md:19-20      不是可信基準線 ＋ 截至 Task 245 仍缺…
scripts/spec-check.sh:201             Task 241 實測缺… ＋ 不可當基準線
spec/design.md:5949                   缺少這張歷史表
```

改完後同一條 grep 輸出 `0`（`/tmp/schema-legacy-after.txt` 為空）。八個位置全部改到。

**（1）重產結果本身**

```
file=85 db=85
OK
```

11 張原本缺的表 `grep -c` 全部為 `1`：`twse_institutional_daily`／`etf_nav_observation`／
`stock_dividend_snapshot`／`stock_dividend_snapshot_event`／`stock_dividend_fetch_observation`／
`stock_dividend_fetch_attempt`／`treasury_yield_batch`／`treasury_yield_daily`／
`export_schedule_time`／`commodity_export_schedule_time`／`realized_gain_export_schedule_time`。

欄位／預設值／索引：

```
2006:    source character varying(128),
1344:    model character varying(64) DEFAULT 'claude-opus-5'::character varying NOT NULL,
```

`uk_dividend_event` 已是 10 欄版（含 `COALESCE(ex_rights_date, '1970-01-01'::date)` 與
`COALESCE(event_key, ''::character varying)`）。
`ck_commodity_source_time|action_policy_version|withdrawal_order|factor_groups|idx_dividend_event_key|idx_commodity_price_available`
合計 `grep -c` ＝ **8**（門檻 ≥6）。

本體（去專案檔頭）相對舊檔的差異實測為 **新增 765 行、刪除 9 行**，與背景段預測完全相同；
9 行刪除中 6 行是「原本最後一欄少了逗號」的格式位移，3 行是 `source varchar(50)`、
`model` 舊預設值 `claude-opus-4-8`、舊 4 欄版 `uk_dividend_event`。沒有任何表被移除。

**（2）同步狀態下的漂移測試 → `exit=0`**

```
PASS: db/schema.sql（去除專案檔頭後）逐位元等於 asset-postgres 此刻的 pg_dump 輸出
      表數：85 張（檔頭宣告一致）
exit=0
```

**（3）漂移＋歸屬本次 → 腳本 `exit=1`、B10 BLOCK、spec-check `exit=1`**

把 `CREATE TABLE public.twse_institutional_daily (` 改名為 `zzz_injected_drift` 後：

```
FAIL: db/schema.sql 與運行中 DB（asset-postgres）的 schema 不一致 —— 稽核基準線已漂移

  差異行數：新增 1 行、刪除 1 行（相對現行 db/schema.sql）
  僅存在於運行中 DB（db/schema.sql 缺這些表）：
    - public.twse_institutional_daily
  僅存在於 db/schema.sql（運行中 DB 沒有這些表）：
    - public.zzz_injected_drift
exit=1
```

`bash scripts/spec-check.sh`：

```
變更檔數: 10（含未追蹤新檔）
BLOCK │ db/schema.sql 未通過漂移檢查（成因見上方完整訊息）——本次變更已碰到 schema／changelog，須先處理。處置：先查 SELECT filename FROM databasechangelog …（完整 $DRIFT_HINT）
BLOCK: 1   CHECK: 1
spec-check exit=1
```

還原後重跑腳本 `exit=0`。

**（4）漂移＋不歸屬本次 → B10 CHECK、spec-check `exit=0`**

在 367.1 重產**之前**、367.4／367.6 已完成時跑 `bash scripts/spec-check.sh HEAD`：

```
變更檔數: 2（含未追蹤新檔）        ← 只有 scripts/spec-check.sh 與新檔 scripts/tests/schema-sql-drift-test.sh，
                                     DIFF_FILES 不含 db/schema.sql、也不含 db/changelog/
CHECK │ db/schema.sql 未通過漂移檢查（成因見上方完整訊息）。本次變更未碰 schema／changelog，
        漂移可能來自其他 worktree 尚未 merge 的 changeset。處置：…（完整 $DRIFT_HINT）
BLOCK: 0   CHECK: 1
exit=0
```

**（5）無法查證 → 腳本 `exit=2`、B10 CHECK（未停任何容器，用假容器名）**

```
$ SCHEMA_DRIFT_CONTAINER=asset-postgres-does-not-exist bash scripts/tests/schema-sql-drift-test.sh
SKIP: 容器 asset-postgres-does-not-exist 不在運行（docker inspect 回「查無此容器」）—— db/schema.sql 是否漂移「無法查證」
      可用 SCHEMA_DRIFT_CONTAINER 覆寫容器名。
exit=2

$ SCHEMA_DRIFT_CONTAINER=asset-postgres-does-not-exist bash scripts/spec-check.sh
CHECK │ db/schema.sql 是否漂移無法查證（asset-postgres 未運行或 docker 不可用）——本次不判定，待容器起來後重跑
BLOCK: 0   CHECK: 2
spec-check exit=0                 ← 記為 CHECK 而非 BLOCK
```

**（6）檔頭張數宣告守門 → `exit=1`，訊息不宣稱「與運行中 DB 不一致」**

實跑取得 `n=85`，注入 84 並以 `grep -q` 確認注入成功後：

```
FAIL: db/schema.sql 檔頭宣告「產生當下表數：84 張」，但檔案實際有 85 個 CREATE TABLE
      —— 檔案疑似被手改而未重產。此為離線檢查，本項失敗並不代表與運行中 DB 不一致。
exit=1
```

還原後重跑 `exit=0`。

**（7）B8 修好後真的印得出來**

`git diff origin/main...HEAD -- 'spec/**' | grep -E '^\+' | grep -cE 'db/changelog/.*\.sql'` ＝ **12**（>0）。
`bash scripts/spec-check.sh 2>&1 | grep 'CHECK │'` 確實印出 B8 的 CHECK，且文字已改為新口徑
（移除 `crawler_export_setting`／`asset_transaction` 過期例證，改述為「由 B10 機械查核」，
並新增「運行中 DB 可能已套用其他 worktree 尚未 merge 的 changeset，涉及 main 現況的斷言仍須複驗
`databasechangelog` 尾端」）。修正前同一條指令輸出 0 行。

**（8）最終完整 spec-check**

```
════ spec-check ════
base: origin/main (2d2524fd)  head: claude/schema-sql-regenerate-drift-check-633729
變更檔數: 13（含未追蹤新檔）

CHECK │ spec 引用了 db/changelog/*.sql —— …（B8，預期出現）
PASS: 9090 gateway/OpenAPI 九路 parity、response manifest、parameters、examples 與 refs 完整
PASS: db/schema.sql（去除專案檔頭後）逐位元等於 asset-postgres 此刻的 pg_dump 輸出
      表數：85 張（檔頭宣告一致）

════ 結果 ════
BLOCK: 0   CHECK: 1
exit=0
```

B7（Requirements 計數）無 BLOCK。無 B10 BLOCK，故不需走「重產與驗收之間別人又套 changeset」的
`databasechangelog` 判讀流程。

**（9）未誤觸實作檔**

`git status --short` 與 `git diff --stat` 見上；以
`grep -E '^(backend/|bff/|external-materials-service/|frontend/|api-gateway/|docker-compose\.yml)'`
過濾變更清單，輸出為空。本任務不涉及可執行程式碼，未跑 `/run-stack`、未停或重啟任何容器。

### 與原計畫的偏差

1. **驗證(4) 實際觸發的是「檔頭缺表數宣告」這一關，不是本體 diff。** 該時點 `db/schema.sql`
   尚未重產、檔頭也還沒有 367.2 的「產生當下表數：N 張」，而 367.5 的離線檢查刻意排在所有
   docker 呼叫之前，所以腳本在那一關就回 `1`。離開碼與 B10 的分流結果（CHECK、`exit=0`）仍完全
   符合驗證(4) 的判準（`DIFF_FILES` 不含 `db/schema.sql` 與 `db/changelog/`）。**本體漂移導致
   BLOCK 的端到端情境由驗證(3) 補足**，兩者合計涵蓋 AC13 要求的四種結局。
2. **367.7(a) 兩種最小改法都採用，而非二擇一。** 除了在 `added_lines()` 末尾補 `return 0`，
   B8 也改成 `b8_hit=$(added_lines 'spec/**' | grep -cE '…' || true)` 再判斷。只補 `return 0`
   時，`added_lines | grep -qE` 的 `grep -q` 會在第一個匹配就結束、可能讓上游收到 SIGPIPE，
   在 `pipefail` 下整條 pipeline 仍可能回 141；`grep -c` 會讀完全部輸入，不存在這個殘留風險。
   兩者皆為任務檔列出的合法改法，同時採用不擴大變更範圍。
3. **367.11 複驗時發現 `spec/design.md` 該段殘留一處避讓前的舊編號。** 段落開頭已正確寫
   「Requirement 103／Task 367」，但同段中間仍寫「**Task 366 起**改由 `scripts/tests/…`」。
   依 367.11(b)「措辭不得對現況說謊」一併改為「**Task 367 起**」。該段其餘內容（lagging、
   嚴重度分流、Task 245 當時 55 張的歷史脈絡）複驗與最終落地的腳本／B10 完全一致，未改動。
   同段引用的兩項 schema 事實也在重產後複驗仍成立：`stock_price_history` 的
   `close_price numeric(15,4) NOT NULL`／`open|high|low_price numeric(15,4)` 可空／`volume bigint` 可空，
   `exchange_rate_history` 的 `buy_rate`／`sell_rate` 皆 `numeric(10,4)` 且皆 nullable。
4. **檔頭由 26 行擴為 44 行**（367.3 的三步驟重產指令＋新增的「防漂移閘門」段），因此
   `git diff --numstat -- db/schema.sql` 是 `788 / 14`；扣掉檔頭後的本體差異才是任務檔預測的
   `765 / 9`（已於上方驗證(1) 實測確認）。本體未手改任何一個字元。
5. **`scripts/README.md` 的 `spec-check.sh` 那一列順帶補上了 B8**（原本連 B8 都沒列），
   與 367.7(c) 對 `spec-check.sh` 檔頭註解「補上遺漏的 B8」同一理由。

### 三項刻意接受的限制（未解決，也不宣稱已解決）

1. B10 是 **lagging** 檢查：`spec-check.sh` 只在 `/spec-review` 這條路徑、實作**之前**被執行，
   漂移卻產生於實作**之後**，故它攔到的是「上一輪沒重產的漂移」。**時間差仍然存在。**
2. `asset-postgres` 是全機多個 worktree 共用的可變狀態，別人未 merge 的 changeset 會出現在
   你的比對結果裡——這正是 367.6 嚴重度分流存在的理由，分流未被簡化。
3. 367.10 寫進 `spec/tasks/README.md` 任務檔模板的收尾約定，**強度低於 commit-msg hook**；
   依 367.12 未改 `scripts/git-hooks/commit-msg` 的觸發清單。時間差未消除。

### arch-auditor 查證（實作後，唯讀）

`arch-auditor` 以本次完整 diff（13 支檔，含未追蹤新檔全文）查證：**critical 0 / major 0 / minor 1**。

它獨立複驗（未採信實作者敘述）的項目：禁區零誤觸（`backend/`／`bff/`／`external-materials-service/`／
`frontend/`／`api-gateway/`／`docker-compose.yml`／`db/changelog/` 全無變更）；`db/schema.sql` 仍不參與 DB
初始化（compose 僅掛 `./db/init`）且無任何 runtime 參照、內容零資料；新腳本 11 種失敗模式逐一實測全部落在
三態契約內（含無 `docker` 指令、`pg_dump` 失敗、路徑含空白）；`spec-check.sh` 的 `drift_status=$?` 緊鄰、
空 `DIFF_FILES` 不誤命中、`b8_hit` 既不恆觸發也不恆不觸發；自行重跑 `pg_dump` 比對得 IDENTICAL；逐一查證
`databasechangelog` 的 130 個 filename，8 筆查無者全部是已知的版號避讓，**確認重產沒有把任何未 merge 的
schema 帶進 main**。

**唯一 minor 已修**：`.claude/agents/arch-auditor.md`（兩處）、`.claude/agents/spec-auditor.md`、
`spec/steering/structure.md` 的新口徑只搬了「運行中 DB ≠ main 現況」這一半，漏掉 `spec/design.md` 同批
新增、且明文「引用前要知道」的 **lagging** 性質。這對 `arch-auditor` 特別要緊——它的執行時機在實作**之後**，
正好落在「剛動完 changelog、`/run-stack` 已套用、但 `db/schema.sql` 尚未重產」的視窗，依新口徑直接引用會
得到「查無該表」的錯誤斷言。四處均已補上「B10 是 lagging 檢查、在裡面查不到某張表不等於它不存在」。
