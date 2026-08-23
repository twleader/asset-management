# [t368] `db/schema.sql` 定性為 DB schema 的唯一標準——刪掉所有「仍須另尋來源複驗」與「停下回報、不得重產」的後門

**對應 Requirements:** Requirement 104（`db/schema.sql` 是 DB schema 的唯一標準；不一致＝這個檔過期＝依其檔頭指令重產，不再要求以 `psql`／`databasechangelog` 複驗，也不再有「停下回報、不得重產」的判斷程序）
**前置任務:** t367（`db/schema.sql` 重產 ＋ `scripts/tests/schema-sql-drift-test.sh` ＋ `scripts/spec-check.sh` B10；本任務只改它留下的**定性與處置文字**，不改它建立的任何機制）
**Liquibase changeset:** 無（本任務不建表、不改任何 changeset。**嚴禁**碰 `backend/src/main/resources/db/changelog/` 底下任何檔案——連 `--comment` 註解都算進 Liquibase checksum，改動會導致 `ValidationFailed` → business-services crash loop）

> **編號說明**：動手前以修正過的掃描方式（`/bin/ls "$d"/spec/tasks/ | grep -E '^t36[7-9]_'`，**不可在迴圈裡直接用 glob**——zsh 的 `no matches found` 會中止整條指令）掃過全機 49 個含 `spec/tasks/` 的 worktree，Task 368 與 Requirement 104 均無人佔用。

## 背景

### 現在的錯誤行為

Task 367 已把 `db/schema.sql` 重產到與運行中 DB 逐位元一致（85 張表），並用 `scripts/tests/schema-sql-drift-test.sh` ＋ `scripts/spec-check.sh` 的 B10 建立機械查核。**機制沒問題，本任務一個字都不動它。**

問題出在 Task 367 對這個檔的**定性**：它寫成「可作為**離線查證依據**……但涉及『main 現況』的斷言**仍須**以 `psql` 加 `databasechangelog` 尾端複驗」，並在 B10 的 `DRIFT_HINT` 放了一整套判斷程序（查 `databasechangelog.filename` → 逐一 `git cat-file -e origin/main:<該檔>` → 排除版號避讓 → 確認確有 main 沒有的 schema 物件才**停下回報、不得重產**）。

三個具體問題：

1. **一份需要另一份來複驗的標準，不是標準。** 這重新製造了「該信哪一份」的模糊——本專案花了 Task 148→197→201 共 53 個 Task 才收斂，Task 367 又花三輪審查才把兩支 auditor 的矛盾口徑統一，結果自己留了一道後門。
2. **那套判斷程序今天就會踩假陽性。** 實測 `databasechangelog` 的 130 個 filename 有 8 筆在 `origin/main` 查無同名檔，逐一比對後**全部**是本專案已知的版號避讓：`v1.108.0-model-lineup-refresh`（main 為 `v1.109.0`）、`v1.109.0-dividend-ex-rights-date`（main 為 `v1.110.0`）、`v1.55.0-crawler-schedule`（→ `v1.58.0`）、`v1.55.0-trading-calendar-export-schedule`（→ `v1.56.0`）、`v1.58.0-realized-gain-export-schedule`（→ `v1.59.0`）、`v1.63.0-crawler-export-path`（→ `v1.64.0`）、`v1.63.0-etf-nav-history`（→ `v1.65.0`）、`v1.63.0-index-export-schedule`（→ `v1.66.0`）。要求每個被 BLOCK 的人當場跑完這套判斷，實務上只會被跳過。
3. **與既有基準線模型不一致。** 真正的基準線 `db/init/01_dump.sql` 本來就是從同一套共用 DB dump 出來的，同樣可能含尚未 merge 的表；`db/schema.sql` 是它的去資料鏡像。對鏡像額外加一道 dump 本身沒有的懷疑，沒有道理。

### 正確行為（使用者裁示，2026-08-23）

**`db/schema.sql` 就是 DB schema 的唯一標準。不一致＝這個檔過期＝依其檔頭指令重產它。**

已明示並接受的代價：運行中 DB 是全機 49–57 個 worktree 共用的可變狀態，重產可能把別人尚未 merge 的表一併寫進本檔；那些 changeset 其後都會 land，本檔的下一次重產也會自動收斂，故**不設攔阻**。**不得**因此在任何文件裡加回「所以要另尋來源複驗」的敘述。

### 邊界（哪些不是本任務要改的）

- `db/changelog/**` 仍然一律不得用於描述 schema 現況（那裡有永不執行的 changeset）——這條不變。
- `databasechangelog` 的正當用途**限縮**為「某個 migration 有沒有執行過／某個版號有沒有被佔用」。那不是 schema 形貌問題，不構成例外，也不需要刪除既有那類用法（例如 `spec/design.md` 的 `index_export_schedule` 段要確認 v1.66.0 parent 是否已套用）。
- **lagging 性質仍要據實揭露**，但只講事實、不得轉成「所以要另尋來源」。允許寫「B10 跑在實作**之前**，剛動過 `db/changelog/**` 的當下本檔可能尚未重產；此時在本檔查不到某張表，代表**本檔過期需要重產**」；**不得**寫「所以查不到時改用 `psql` 判斷」。

## 要做什麼

- [ ] **368.1 先記下基準命中數（改任何檔之前跑）。** 舊措辭有多種寫法，只用單一樣式的「零命中」grep 會靜默假通過：

  ```bash
  grep -ranE '仍須.*複驗|仍須以 `psql`|尚未 merge 的 changeset|不得以重產|停下回報|離線查證依據|離線參考|不等於它不存在|一律以 `psql` 複驗|排除「版號避讓」|git cat-file -e origin/main' \
    .claude/agents/ spec/steering/structure.md spec/tasks/README.md spec/design.md \
    scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql \
    | tee /tmp/t368-before.txt | wc -l
  ```

  把實跑數字與 `/tmp/t368-before.txt` 的內容抄進完成報告。**全部改完後同一條指令必須輸出 0。**（注意樣式刻意不含 `spec/requirements.md`——Requirement 103 的原文與本任務新增的 Requirement 104 都會提到這些字眼，那是歷史紀錄與規範敘述，不在清除範圍。）

- [ ] **368.2 統一的新口徑（下列各處一律採用同一段語意，措辭可依上下文調整長短）：**

  > **`db/schema.sql` 是 DB schema 的唯一標準。** 表存在與否、欄位、型別、位數、nullable、預設值、CHECK、索引一律以它為準。它與運行中 DB 的同步由 `scripts/spec-check.sh` 的 B10 機械查核（`scripts/tests/schema-sql-drift-test.sh` 逐位元全文比對）。**發現它與運行中 DB 不一致，就是這個檔過期——依它檔頭的指令重產並納入本次變更**，不要改用別的來源當基準。`db/changelog/**` 一律不得用於描述現況（那裡有永不執行的 changeset）。

- [ ] **368.3 `.claude/agents/spec-auditor.md`（兩處）。**
  - **第 24 行**「查證前務必知道的專案陷阱」第 2 點：現行文字是「DB 現況的**離線查證依據**是 `db/schema.sql`……但它反映的是運行中 DB……涉及『main 現況』的斷言**仍須**以 `docker exec asset-postgres psql …` 並比對 `databasechangelog` 尾端複驗。**B10 還是 lagging 檢查**……在裡面查不到某張表不等於它不存在。」→ 改為 368.2 的口徑；刪掉「仍須……複驗」整句；lagging 那句改寫成「查不到某張表代表**本檔過期需要重產**」。**保留**該點原有的最後一段（`db/changelog/**` 一律不得用於描述現況、Task 148→197→201 的教訓）。
  - **第 37 行**「審查維度」表的「資料來源正確性」列：把其中關於 `db/schema.sql` 的那一段改為 368.2 的口徑。**只改那一段**——同一格裡的「即時價只能從 Redis、收盤價只讀 `stock_price_history`、business-services 不直連外部行情 API」與「已改為 DB 可設定的排程時點不得在 spec 裡寫死成字面時間（Task 195）」必須原樣保留。
  - 另加一句（Requirement 104 AC6）：**若查證時發現本檔與運行中 DB 不符，處置是把「`db/schema.sql` 已漂移、需依檔頭指令重產」列為 finding**，不是改用 `db/changelog/**` 或運行中 DB 改寫斷言。

- [ ] **368.4 `.claude/agents/arch-auditor.md`（兩處）。**
  - **第 42 行**「查證前務必知道的專案陷阱」第 1 點：改為 368.2 的口徑；刪掉「涉及『main 現況』的斷言仍須以 `psql` 並比對 `databasechangelog` 尾端複驗」與「一律以 `psql` 複驗」。lagging 改寫成「你的時機在實作**之後**，剛動過 `db/changelog/**` 的視窗裡本檔可能尚未重產；此時在本檔查不到某張表，代表**本檔過期**——把『需依檔頭指令重產』列為 finding」。**保留**該點原有的「查不到（容器沒跑）就在報告寫『無法查證』，**不要用 changelog 推測**」。
  - **第 151 行**「不要做的事」清單那一條：同樣去掉「仍須以 `psql` 加 `databasechangelog` 尾端複驗」，改成指向「重產」。

- [ ] **368.5 `spec/steering/structure.md` 的 `db/` 樹狀說明（第 19 行起的 `schema.sql` 註解區塊）。** 改為 368.2 的口徑；刪掉「但它反映的是『運行中 DB』，可能含其他 worktree 尚未 merge 的 changeset；涉及『main 現況』的斷言仍須複驗：`docker exec …` 並比對 databasechangelog 尾端」整段。lagging 只留事實與「重產」處置。註解區塊要維持原有的樹狀對齊格式（`│` 與 `#` 的縮排）。

- [ ] **368.6 `spec/tasks/README.md`（兩處）。**
  - **任務檔模板 `## 驗證` 段**那條收尾約定（「任務若動到 `backend/src/main/resources/db/changelog/**`，驗證／收尾步驟**必須包含重產 `db/schema.sql`**」）：保留，但把後面「漏掉這一步不會當場被擋下——B10 是 lagging 檢查，跑在下一個任務的實作**之前**，所以漂移只會在下一次 `spec-check` 才被指出，屆時已難以歸屬是誰造成的」調整為與新口徑一致的措辭（重點是「本檔是唯一標準，你動了 schema 就有義務讓它保持正確」，而不是「屆時難以歸屬」）。
  - **自足性自查段**關於 `db/schema.sql` 的那幾行，含**「但運行中 DB 也不等於 main 的現況」整段**（現行要求「下斷言前先比對 `SELECT id FROM databasechangelog …` 與 main 的 `db.changelog-master.yaml` 尾端」）：改為 368.2 的口徑。該段原本存在的理由已被 Requirement 104 取代，**改寫而非保留**——這與 Task 367 的 366.9「必須原樣保留」相反，是刻意的推翻，實作時不要照舊任務檔辦。開頭「對 DB 現況的斷言**以運行中的 DB 為準**」那句也要改成以 `db/schema.sql` 為準。

- [ ] **368.7 `spec/design.md`（兩處）。**
  - 「Schema 基準線與 DB 層唯一鍵（重要澄清）」段（`✅ db/schema.sql 是可用的離線查證依據…` 那條、其下「B10 的兩項已知性質」與「⚠ 但『運行中 DB』不等於『main 的現況』」）：改為 368.2 的口徑。「B10 的兩項已知性質」保留**分流**那項與**lagging** 那項的事實描述，但處置一律改成「重產本檔」。刪掉整條「⚠ 但『運行中 DB』不等於『main 的現況』……仍須以 `psql` 加 `databasechangelog` 複驗」。同段更上方的 `📌 查證來源：運行中的 DB` 那行也要改——現況它與新口徑直接衝突。
  - `index_export_schedule` 段末句（Task 367 已改寫過一次，現行文字提到「不能據以判斷 parent 是否已套用——那仍須查 `databasechangelog` 與 `\d index_export_schedule`」）：**「migration 有沒有套用」屬於 `databasechangelog` 的正當用途，不是 schema 形貌問題，這半句保留**；但要拿掉任何把 `db/schema.sql` 講成次等來源的措辭，並明確「表／欄位形貌以本檔為準」。

- [ ] **368.8 `scripts/spec-check.sh`（三處）。**
  - **B8 的 CHECK 文字**：現行含「`db/schema.sql` 由 B10 機械查核與運行中 DB 的同步，可用來查欄位型別／位數／nullable／索引；**但運行中 DB 可能已套用其他 worktree 尚未 merge 的 changeset，涉及「main 現況」的斷言仍須複驗 `databasechangelog` 尾端**」→ 後半句刪除，改為 368.2 的一句話（唯一標準、不一致就重產）。B8 的主題、觸發條件與 CHECK 級別**不變**。
  - **B10 的 `DRIFT_HINT`**：整段（`先查 SELECT filename FROM databasechangelog …／排除「版號避讓」／確認確有 main 沒有的 schema 物件才停下回報，不得以重產把別人未 merge 的 schema 帶進 main`）**全部刪除**，改為單一句：`處置：依 db/schema.sql 檔頭「重新產生」段的指令重產本檔，並把它納入本次變更。`
  - **B10 上方的區塊註解**：現行「……別人跑過 `/run-stack` 就會把它尚未 merge 的 changeset 套進共用 DB，因此必須依『本次變更有沒有碰 schema』分流……」——分流的**行為不變**（見 368.9），但註解要改成新的理由：分流是為了不讓與 schema 無關的任務被頻繁中斷（本專案兩週 land 19 支 changeset）、以及避免多條分支同時重產 4600+ 行產物檔造成 merge 衝突；不再以「別人未 merge 的 schema 不該被帶進 main」為理由。

- [ ] **368.9 B10 的嚴重度分流維持不變。** 本次變更碰到 `db/schema.sql` 或 `backend/src/main/resources/db/changelog/` → BLOCK；否則 → CHECK。**只改兩種訊息的處置文字**（都改成「依檔頭指令重產並納入本次變更」）。不要因為「唯一標準」就改成一律 BLOCK——理由見 368.8 第三點與 Requirement 104 AC4。

- [ ] **368.10 `db/schema.sql` 檔頭的「防漂移閘門」段。** 現行末兩行是「運行中的 asset-postgres 是多個 worktree 共用的可變狀態，別人尚未 merge 的 changeset 也會出現在其中；涉及『main 現況』的斷言仍須複驗 databasechangelog 尾端。」→ 改為：本檔是 DB schema 的唯一標準；它反映運行中的共用 DB，可能短暫含尚未 merge 的表，那**不影響它的標準地位**，發現不一致一律重產本檔。lagging 那兩行保留事實、處置改成「重產」。
  **注意**：改檔頭會讓 `CREATE TABLE` 張數以外的內容變動，但檔頭不參與 `scripts/tests/schema-sql-drift-test.sh` 的本體比對（該腳本以「第一個整行等於 `-- PostgreSQL database dump` 的前一行」切分），故**不需要重產本體**；改完仍須跑一次該腳本確認回 0。

- [ ] **368.11 `scripts/tests/schema-sql-drift-test.sh` 的 FAIL 訊息。** 在 `print_regen` 之前或之中加一句定性：「`db/schema.sql` 是 DB schema 的唯一標準，不一致代表**本檔已過期**，依下列指令重產即可。」不要動任何比對邏輯、離開碼、切分規則或版本註解行的特別處理。

- [ ] **368.12 不得做的事（逐條都是硬性禁令）：**
  - **不改任何機制**：漂移測試的比對方式、檔頭切分規則、離開碼三態（0／1／2）、B10 的分流條件、B8 的觸發條件與級別，一律不動。本任務只改文字。
  - 不新增／修改／刪除任何 Liquibase changeset，不碰 `backend/src/main/resources/db/changelog/` 底下任何檔案。
  - 不動 `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、`docker-compose.yml`、`scripts/git-hooks/`。
  - **不修改 `spec/tasks/t367_schema_sql_drift_guard.md`**（含其完成報告）——那是 Task 367 派工與交付當下的事實紀錄，定性的改變由 Requirement 104 與本檔記錄。也不改其他歷史任務檔。
  - **不重產 `db/schema.sql` 的 pg_dump 本體**（本任務只改檔頭文字；若動工當下本體本來就漂移了，那是另一回事，照 B10 的指示重產並在完成報告說明）。
  - **不要裸跑 `git clean -fd`**——會刪掉未追蹤但重要的 `db/init/`。要復原只用 `git checkout -- <path>`。

## 驗證

全部在本 worktree 根目錄執行。

**（1）舊措辭清零**

```bash
grep -ranE '仍須.*複驗|仍須以 `psql`|尚未 merge 的 changeset|不得以重產|停下回報|離線查證依據|離線參考|不等於它不存在|一律以 `psql` 複驗|排除「版號避讓」|git cat-file -e origin/main' \
  .claude/agents/ spec/steering/structure.md spec/tasks/README.md spec/design.md \
  scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql | wc -l
```
必須為 **0**（368.1 記下的基準值 → 0）。

**（2）新口徑到位**

```bash
grep -rln '唯一標準' .claude/agents/spec-auditor.md .claude/agents/arch-auditor.md \
  spec/steering/structure.md spec/tasks/README.md spec/design.md \
  scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql
```
八個檔案必須全部命中。

**（3）保留條款沒有被誤刪**

```bash
grep -c '即時價只能從 Redis' .claude/agents/spec-auditor.md          # 必須 ≥1
grep -c 'Task 195' .claude/agents/spec-auditor.md                      # 必須 ≥1
grep -c '不要用 changelog 推測' .claude/agents/arch-auditor.md         # 必須 ≥1
grep -c '永不執行的 changeset' .claude/agents/spec-auditor.md          # 必須 ≥1
grep -c 'databasechangelog' spec/design.md                             # 必須 ≥1（index_export_schedule 段的 parent 判斷屬正當用途）
```

**（4）機制未被動到（368.12 第一條）**

```bash
git diff -- scripts/tests/schema-sql-drift-test.sh | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -vE '唯一標準|已過期|^[+-]#'
```
輸出應為空或僅剩訊息字串行——**不得**出現 `exit`、`cmp`、`diff`、`marker_line`、`body_start`、`grep -c '^CREATE TABLE'` 等邏輯行的增刪。

```bash
git diff -- scripts/spec-check.sh | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -E 'DRIFT_TEST=|drift_status|case |grep -qE|block |check '
```
`case` 結構、`drift_status=$?`、分流的 `grep -qE` 條件必須沒有被改動（只有 `block`／`check` 的訊息字串與 `DRIFT_HINT=` 那一行會變）。

**（5）腳本與閘門仍然正常**

```bash
bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"        # 預期 PASS / 0
SCHEMA_DRIFT_CONTAINER=asset-postgres-does-not-exist \
  bash scripts/tests/schema-sql-drift-test.sh; echo "exit=$?"      # 預期 SKIP / 2
```

注入一次確認 BLOCK 分支的新訊息（測完務必還原）：

```bash
cp db/schema.sql /tmp/t368-backup.sql
n=$(grep -c '^CREATE TABLE' db/schema.sql)
sed -i '' "s/產生當下表數：${n} 張/產生當下表數：$((n-1)) 張/" db/schema.sql
grep -q "產生當下表數：$((n-1)) 張" db/schema.sql || echo "!! 注入失敗，本項驗證無效"
bash scripts/spec-check.sh 2>&1 | grep -E 'BLOCK │|CHECK │'
cp /tmp/t368-backup.sql db/schema.sql && rm /tmp/t368-backup.sql
```
BLOCK 訊息必須只含「依 `db/schema.sql` 檔頭『重新產生』段的指令重產本檔，並把它納入本次變更」這一句處置，**不得**再出現 `databasechangelog`／`git cat-file`／`停下回報`。

**（6）最終 spec-check**

```bash
bash scripts/spec-check.sh; echo "exit=$?"
```
必須 `BLOCK: 0`、`exit=0`（B8 的 CHECK 會出現，屬預期）。B7（Requirements 計數）不得 BLOCK——`CLAUDE.md` 與 `spec/steering/structure.md` 已同步改為 103 個 Requirements、最新 104。

**（7）沒有誤觸實作檔**

```bash
git status --short
git diff --stat
```
不得出現 `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、`docker-compose.yml`、`scripts/git-hooks/` 底下任何檔案，也不得出現 `spec/tasks/t367_schema_sql_drift_guard.md`。

**（8）本任務不涉及可執行程式碼，不需 `/run-stack`。** 改動的檔案都不進任何 container image。

## 完成報告

（實作者做完後回填：實際改了哪些檔、上述八組驗證的實際輸出、與原計畫的偏差及原因。）
