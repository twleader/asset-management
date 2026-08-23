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
  grep -ranE '仍須.*複驗|仍須以 `psql`|仍須查|尚未 merge 的 changeset|不得以重產|停下回報|離線查證依據|離線查證用|可用於離線查證|離線參考|不等於它不存在|一律以 `psql` 複驗|排除「版號避讓」|git cat-file -e origin/main|以運行中 DB 為準|以運行中的 DB 為準|查證來源：運行中的 DB|查證位數/nullable 一律以|以 `db/init/01_dump\.sql` 為準|一律以 dump 為準|難以歸屬|並比對 databasechangelog 尾端|psql -U assets -d assets -c' \
    .claude/agents/ spec/steering/structure.md spec/tasks/README.md spec/design.md \
    scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql CLAUDE.md \
    | tee /tmp/t368-before.txt | wc -l
  ```

  把實跑數字與 `/tmp/t368-before.txt` 的內容抄進完成報告。**全部改完後同一條指令必須輸出 0。**

  **光看 `wc -l` 歸零不算數**——Task 367 連續兩輪就是被「樣式漏一種寫法、跑出 0 卻沒真的清乾淨」騙過去的。必須把 `/tmp/t368-before.txt` 的命中**逐條對回 368.3–368.10 指名的位置**，確認每一個位置都至少有一筆命中；**有哪個位置一筆都沒命中，就是樣式還漏了寫法，先補樣式再動手**。

  **零命中的位置有兩個，都不算樣式漏了**：
  - `CLAUDE.md`（368.13 是**新增**一段口徑，不是改寫舊文字；該檔對「schema 現況查哪裡」本來就零敘述，`運行中 DB` 只在工具表第 368 列出現一次、屬機制描述）；
  - `scripts/tests/schema-sql-drift-test.sh`（368.11 是**加一句**定性，不是改寫舊文字；該檔內四處「純離線檢查／此為離線檢查」指的是「不需 docker」，語意無關且 368.11 明令不得動）。

  這兩個檔在掃描路徑裡，只是為了確保**新寫進去的文字不含任何被禁的措辭**。

  改動前實測基準命中數為 **26 行**，分布在 `spec-auditor.md`(2)／`arch-auditor.md`(2)／`structure.md`(6)／`tasks/README.md`(4)／`design.md`(6)／`spec-check.sh`(4)／`db/schema.sql`(2)——對應 368.3–368.10 指名的全部位置，一個不缺。

  > 樣式最後兩個 alternative（`並比對 databasechangelog 尾端`、`psql -U assets -d assets -c`）是為了 `spec/steering/structure.md`：它是樹狀註解，**違規祈使句與關鍵詞被換行拆到不同行**（第 25–26 行「`docker exec asset-postgres psql … -c '\d <table>'` ／ 並比對 databasechangelog 尾端」），只刪 23／24／28 而漏掉 25／26，驗證(1) 會回 0 但檔案仍在叫人去查運行中 DB。其餘七個位置的違規句都與關鍵詞同行，沒有這個問題。（`psql -U assets -d assets -c` 不會誤傷 `db/schema.sql` 的重產指令——那條是 `pg_dump -U assets -d assets`，沒有 `-c`。）

  （樣式刻意不含 `spec/requirements.md`、`spec/tasks.md` 與 `spec/tasks/t367_*.md`——Requirement 103 的原文、本任務的 Requirement 104 敘述、索引列與 Task 367 的完成報告都必然提到這些字眼，那是歷史紀錄與規範敘述，強制清零等於竄改歷史，違反 AC10。`scripts/tests/schema-sql-drift-test.sh` 內既有四處「純離線檢查／此為離線檢查」指的是「不需 docker」，語意無關，樣式刻意不涵蓋，**也不得順手改掉**。）

- [ ] **368.2 統一的新口徑（下列各處一律採用同一段語意，措辭可依上下文調整長短）：**

  > **`db/schema.sql` 是 DB schema 的唯一標準。** 表存在與否、欄位、型別、位數、nullable、預設值、CHECK、索引**一律以 `db/schema.sql` 為準**。它與運行中 DB 的同步由 `scripts/spec-check.sh` 的 B10 機械查核（`scripts/tests/schema-sql-drift-test.sh` 逐位元全文比對）。**發現它與運行中 DB 不一致，就是這個檔過期——依它檔頭的指令重產並納入本次變更**，不要改用別的來源當基準。`db/changelog/**` 一律不得用於描述現況（那裡有永不執行的 changeset）。

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
  - **自足性自查段**關於 `db/schema.sql` 的那幾行，含**「但運行中 DB 也不等於 main 的現況」整段**（現行要求「下斷言前先比對 `SELECT id FROM databasechangelog …` 與 main 的 `db.changelog-master.yaml` 尾端」）：改為 368.2 的口徑。該段原本存在的理由已被 Requirement 104 取代，**改寫而非保留**——這與 Task 367 的 **367.9**（見 `spec/tasks/t367_schema_sql_drift_guard.md:216`，該條下的 `:218` 子項寫「第 103–106 行『但運行中 DB 也不等於 main 的現況』整段必須原樣保留」）相反，是刻意的推翻，實作時不要照舊任務檔辦。（注意不要看成 `366.x`——`Task 366` 是另一支在途 worktree `export-to-blog-0f382e` 的「交易雷達匯出到 blog」，與本任務無關。）開頭「對 DB 現況的斷言**以運行中的 DB 為準**」那句也要改成以 `db/schema.sql` 為準；同段「它是被 `.gitignore` 排除的**真基準線** `db/init/01_dump.sql` 的去資料鏡像」的從屬敘述也要一併拿掉（理由同 368.10(a)）。

- [ ] **368.7 `spec/design.md`（兩處，第一處是整個 blockquote 不是單行）。**
  - **「Schema 基準線與 DB 層唯一鍵（重要澄清）」整個 blockquote（約 845–859 行，實作前用 `grep -n 'Schema 基準線與 DB 層唯一鍵' spec/design.md` 定位起點）。** 段內目前有**三種互相競爭的標準**，全部要收斂到 368.2 的口徑：
    - `📌 **查證來源：運行中的 DB。** … 欄位型別／位數／nullable **一律以它為準**` → 改為以 `db/schema.sql` 為準。
    - `✅ db/schema.sql 是可用的離線查證依據…` 那條、其下「B10 的兩項已知性質」→ 改為「唯一標準」；**分流**與 **lagging** 兩項的事實描述保留，處置一律改成「重產本檔」。
    - `⚠ 但「運行中 DB」不等於「main 的現況」…仍須以 psql 加 databasechangelog 複驗` 整條 → 刪除。**但這一條的句尾是 `spec/design.md` 全檔唯一一處 `db/changelog` 禁令**（`grep -c '不要引用 \`db/changelog' spec/design.md` = 1），AC2 明訂該禁令不變——**刪句不刪那條禁令**，把「同理不要引用 `db/changelog/**` 描述現況——那裡有永不執行的 changeset」搬進新寫的段落。
    - `stock_price_history` 那條的 **「以 `db/init/01_dump.sql` 為準」**、以及其下 `⚠ v1.0.0-initial-schema.sql …` 那行的 **「查證位數/nullable 一律以 dump 為準」** → 一併改為以 `db/schema.sql` 為準。`db/init/*.sql` 被 `.gitignore` 排除（見 `spec/steering/structure.md` 的 `db/init/` 註解），指向它比指向運行中 DB 更糟——多數讀者根本拿不到；而同段下一行的 ✅（Task 201）本來就已經拿 `db/schema.sql` 當依據，不改就是段內自相矛盾。
    - blockquote 開頭「本專案的資料表基準線由 `db/init/01_dump.sql`……提供」**保留**，但語意限定為「**DB 初始化**的基準線」，與「查證標準」明確切開。
    - `✅ db/schema.sql …` 那條裡的「**它是 `db/init/01_dump.sql`（…）「去除全部資料」後的可版控鏡像**」→ 改為「本檔由 `pg_dump --schema-only` 直接自運行中 `asset-postgres` 產生」。理由同 368.10(a)：把唯一標準的權威掛在一個 gitignored、無機械保證、本 worktree 根本不存在的檔上，等於沒切斷從屬鏈。
    - 保留「B10 的兩項已知性質」時，把其中寫死的「**全機 56 個 worktree**」改成不會漂移的說法（如「數十個 worktree」）——實測目前是 55 個（含 `spec/tasks/` 的 49 個），與 AC4 對 changeset 數的處理同一原則。
    - `exchange_rate_history` 那條已經是引 `db/schema.sql`，不動。
  - `index_export_schedule` 段末句（Task 367 已改寫過一次）：**「migration 有沒有套用」屬於 `databasechangelog` 的正當用途，`databasechangelog` 那半句保留**；但同句的 **`與 \d index_export_schedule`** 是運行中 DB 的**形貌**查詢（parent 的 legacy 欄位還在不在），依 AC2 不在豁免範圍，**刪掉並改指 `db/schema.sql`**。同時拿掉任何把 `db/schema.sql` 講成次等來源的措辭。

- [ ] **368.8 `scripts/spec-check.sh`（三處）。**
  - **B8 的 CHECK 文字（整句都要看，不只後半）**：現行訊息有**兩處**與新口徑衝突——
    (i) 開頭「**現況一律以運行中 DB 為準：`docker exec asset-postgres psql -U assets -d assets -c '\d <table>'`**」（這句 368 的初稿漏了，它是每次 `spec-check` 都會印出來的那一條）；
    (ii) 句尾「但運行中 DB 可能已套用其他 worktree 尚未 merge 的 changeset，涉及「main 現況」的斷言**仍須複驗 `databasechangelog` 尾端**」。
    兩處**一併**改為 368.2 的一句話（唯一標準、不一致就重產）。B8 的**主題、觸發條件與 CHECK 級別不變**，「若本次引用只是『新 changeset 放哪／現有最大版號』則屬正當用途，可忽略本項」的豁免句**保留**。
  - **B10 的 `DRIFT_HINT`**：整段（`先查 SELECT filename FROM databasechangelog …／排除「版號避讓」／確認確有 main 沒有的 schema 物件才停下回報，不得以重產把別人未 merge 的 schema 帶進 main`）**全部刪除**，改為單一句：`處置：依 db/schema.sql 檔頭「重新產生」段的指令重產本檔，並把它納入本次變更。`
  - **B10 上方的區塊註解**：現行「……別人跑過 `/run-stack` 就會把它尚未 merge 的 changeset 套進共用 DB，因此必須依『本次變更有沒有碰 schema』分流……」——分流的**行為不變**（見 368.9），但註解要改成新的理由：分流是為了不讓與 schema 無關的任務被頻繁中斷（本專案兩週就 land 二十餘支 changeset）、以及避免多條分支同時重產 4600+ 行產物檔造成 merge 衝突；不再以「別人未 merge 的 schema 不該被帶進 main」為理由。

- [ ] **368.9 B10 的嚴重度分流維持不變，但兩種訊息「整句重寫」。** 分流條件與級別不動：本次變更碰到 `db/schema.sql` 或 `backend/src/main/resources/db/changelog/` → BLOCK；否則 → CHECK。
  訊息則是**理由與處置一起重寫**（不是只換處置那半句）——現行 CHECK 訊息的理由句含「漂移可能來自其他 worktree **尚未 merge 的 changeset**」，那正是 368.1 清零樣式要抓的措辭，只改處置會讓驗證(1) 過不了。新的 CHECK 理由改為「本次變更未碰 schema／changelog，但 `db/schema.sql` 未通過漂移檢查（成因見上方完整訊息）」——**「成因見上方完整訊息」必須保留、不要改寫成「本檔已過期」**：離開碼 1 也可能來自檔頭表數不符或檔頭結構破壞，此時本體與 DB 完全同步，斷言「過期」會誤導（Requirement 103 AC7 對此有明文，該條未被 Requirement 104 作廢）。處置句兩者共用「依 `db/schema.sql` 檔頭『重新產生』段的指令重產本檔，並把它納入本次變更」。
  **不要因為「唯一標準」就改成一律 BLOCK**——理由見 368.8 第三點與 Requirement 104 AC4。

- [ ] **368.10 `db/schema.sql` 的整個檔頭（不只「防漂移閘門」段）。**
  **（a）「用途」段第 6–9 行的從屬敘述**：現行寫「真正的基準線 `db/init/01_dump.sql` 因含真實個人財務資料而被 `.gitignore` 排除……本檔即為該基準線『去除資料』後的可版控鏡像」。這句把本檔的權威性掛在另一個檔上，與「唯一標準」直接衝突，而且**已不符實際產生方式**——檔頭第 17–31 行的重產流程是直接對 `asset-postgres` 跑 `pg_dump --schema-only`，跟 `01_dump.sql` 無關；`01_dump.sql` 靠人工跑 `scripts/db-export.sh` 更新、沒有任何機械閘門保證新鮮度（且本 worktree 根本沒有 `db/init/` 目錄）。改為：**本檔是 DB schema 的唯一標準，由 `pg_dump --schema-only` 直接自運行中的 `asset-postgres` 產生**。「全新環境初始化／還原一律仍用 `db/init/01_dump.sql`」那一行（實測在第 14 行，實作前以 `grep -n` 複驗）**保留**——那是初始化來源，不是查證標準。
  **（b）「防漂移閘門」段。** 現行末兩行是「運行中的 asset-postgres 是多個 worktree 共用的可變狀態，別人尚未 merge 的 changeset 也會出現在其中；涉及『main 現況』的斷言仍須複驗 databasechangelog 尾端。」→ 改為：本檔是 DB schema 的唯一標準；它反映運行中的共用 DB，可能短暫含尚未 merge 的表，那**不影響它的標準地位**，發現不一致一律重產本檔。lagging 那兩行保留事實、處置改成「重產」。
  **注意**：改檔頭會讓 `CREATE TABLE` 張數以外的內容變動，但檔頭不參與 `scripts/tests/schema-sql-drift-test.sh` 的本體比對（該腳本以「第一個整行等於 `-- PostgreSQL database dump` 的前一行」切分），故**不需要重產本體**；改完仍須跑一次該腳本確認回 0。

- [ ] **368.11 `scripts/tests/schema-sql-drift-test.sh` 的 FAIL 訊息。** **只加在「本體漂移」那一次 `print_regen` 呼叫的前面**（現況在該檔第 187 行附近、`FAIL: … 稽核基準線已漂移` 那一段的結尾）。**不得把這句放進 `print_regen()` 函式本體**——該函式另被五條與運行中 DB 無關的離線失敗路徑呼叫（檔頭結構破壞 ×3、檔頭表數宣告 ×2），其中兩條的既有輸出還明寫「此為離線檢查，與運行中 DB 無關」「本項失敗並不代表與運行中 DB 不一致」，緊接著印「本檔已過期」會自我打臉，也違反 368.9 同一段的論證與 Requirement 103 AC13(e)。要加的句子是：「`db/schema.sql` 是 DB schema 的唯一標準，不一致代表**本檔已過期**，依下列指令重產即可。」不要動任何比對邏輯、離開碼、切分規則或版本註解行的特別處理。**也不得動檔內既有四處「純離線檢查／此為離線檢查」的措辭**——那指的是「不需 docker」，與被清除的「離線查證依據」語意無關，清零樣式刻意不涵蓋它們。

- [ ] **368.12 不得做的事（逐條都是硬性禁令）：**
  - **不改任何機制**：漂移測試的比對方式、檔頭切分規則、離開碼三態（0／1／2）、B10 的分流條件、B8 的觸發條件與級別，一律不動。本任務只改文字。
  - 不新增／修改／刪除任何 Liquibase changeset，不碰 `backend/src/main/resources/db/changelog/` 底下任何檔案。
  - 不動 `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、`docker-compose.yml`、`scripts/git-hooks/`。
  - **不修改 `spec/tasks/t367_schema_sql_drift_guard.md`**（含其完成報告）——那是 Task 367 派工與交付當下的事實紀錄，定性的改變由 Requirement 104 與本檔記錄。也不改其他歷史任務檔。
  - **不重產 `db/schema.sql` 的 pg_dump 本體**（本任務只改檔頭文字；若動工當下本體本來就漂移了，那是另一回事，照 B10 的指示重產並在完成報告說明）。
  - **不要裸跑 `git clean -fd`**。（本 worktree 目前沒有 `db/init/` 目錄——這正是 368.10(a) 的論據之一——但主 clone 與部分 worktree 有，且該目錄未追蹤，一律會被清掉。）要復原改動只用 `git checkout -- <path>`。

- [ ] **368.13 `CLAUDE.md` 補上這個定性。** 現行全檔對 `db/schema.sql` 只有「工具」表的一行機制描述（`scripts/tests/schema-sql-drift-test.sh` 那列），對「schema 現況要查哪裡」零敘述（`grep -n 'DB 現況' CLAUDE.md` 零命中；`運行中 DB` 只在工具表第 368 列出現一次，且屬機制描述、非查證指引）。**臨時派出的 subagent 不繼承 `spec-auditor`／`arch-auditor` 的 prompt，只讀得到 `CLAUDE.md`**——不寫進去，等於這個標準對它們不存在。請在「架構規範」的〈資料庫完整正規化〉小節之後、或〈Spec 文件位置〉的表格附近，加一小段 368.2 的口徑（含「不一致就重產本檔」與「`db/changelog/**` 不得用於描述現況」）。不要動 `CLAUDE.md` 的其他章節。

## 驗證

全部在本 worktree 根目錄執行。

**（1）舊措辭清零**

```bash
grep -ranE '仍須.*複驗|仍須以 `psql`|仍須查|尚未 merge 的 changeset|不得以重產|停下回報|離線查證依據|離線查證用|可用於離線查證|離線參考|不等於它不存在|一律以 `psql` 複驗|排除「版號避讓」|git cat-file -e origin/main|以運行中 DB 為準|以運行中的 DB 為準|查證來源：運行中的 DB|查證位數/nullable 一律以|以 `db/init/01_dump\.sql` 為準|一律以 dump 為準|難以歸屬|並比對 databasechangelog 尾端|psql -U assets -d assets -c' \
  .claude/agents/ spec/steering/structure.md spec/tasks/README.md spec/design.md \
  scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql CLAUDE.md | wc -l
```
必須為 **0**（368.1 記下的基準值 → 0），且完成報告要附上 368.1 的「命中逐條對回位置」對照表。

**（2）新口徑到位**

```bash
grep -rln '唯一標準' .claude/agents/spec-auditor.md .claude/agents/arch-auditor.md \
  spec/steering/structure.md spec/tasks/README.md spec/design.md \
  scripts/spec-check.sh scripts/tests/schema-sql-drift-test.sh db/schema.sql CLAUDE.md
```
**九個檔案必須全部命中**（少一個就是 368.3–368.11 或 368.13 有一項沒做）。

**（3）保留條款沒有被誤刪**

下列每一條都是「被改寫的那一段裡順帶帶著、但仍然有效」的規範，改寫時最容易被整段覆蓋掉：

```bash
grep -c '即時價只能從 Redis'                 .claude/agents/spec-auditor.md   # 必須 ≥1
grep -c 'Task 195'                            .claude/agents/spec-auditor.md   # 必須 ≥1
grep -c 'Task 148→197→201'                   .claude/agents/spec-auditor.md   # 必須 =1（368.2 口徑不含此字串，才不是空檢查）
grep -c '不要用 changelog 推測'               .claude/agents/arch-auditor.md   # 必須 ≥1（容器沒跑就寫「無法查證」那條）
grep -c 'Liquibase 只做增量'                  .claude/agents/arch-auditor.md   # 必須 =1（同上，368.2 口徑不含）
grep -c '不要用 `db/changelog/\*\*` 推測'     .claude/agents/arch-auditor.md   # 必須 =1（「不要做的事」清單那條）
grep -c '不要引用 `db/changelog'              spec/design.md                   # 必須 =1（唯一一處 db/changelog 禁令，見 368.7）
sed -n "$(grep -n 'index_export_schedule_time_market` 刻意一列一指數' spec/design.md | cut -d: -f1)p" spec/design.md | grep -c databasechangelog
                                                                               # 必須 =1（parent 是否已套用，屬正當用途）
grep -c '必須包含重產'                         spec/tasks/README.md             # 必須 =1（368.6 的收尾約定沒被連帶刪掉）
awk '/^## 自足性自查/,0' spec/tasks/README.md | grep -c 'db/changelog'          # 必須 ≥1（自足性自查段的禁令不得連帶消失）
#   ↑ 不可寫成 `grep -c 'db/changelog' spec/tasks/README.md ≥1`——那是空檢查：368.6 明令保留的收尾約定
#     （第 84 行「任務若動到 backend/src/main/resources/db/changelog/**…」）本身就命中，禁令刪光也照樣 ≥1。
```

> 上一版這裡寫的是 `grep -c 'databasechangelog' spec/design.md # 必須 ≥1`——那是**空檢查**：該檔現有 10 筆 `databasechangelog`，就算 `index_export_schedule` 段那半句被整句刪光也照樣通過。改成只看那一行。

**（4）機制未被動到（368.12 第一條）**

> 兩條都用 `git merge-base origin/main HEAD` 當基準，**刻意不用三點 diff**：三點 diff 只看 HEAD，實作者在 commit 之前跑會得到空輸出＝假通過，而「還沒 commit 就先驗證」正是最常見的順序。

```bash
git diff "$(git merge-base origin/main HEAD)" -- scripts/tests/schema-sql-drift-test.sh | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -vE '唯一標準|已過期|^[+-]#'
```
輸出應為空或僅剩訊息字串行——**不得**出現 `exit`、`cmp`、`diff`、`marker_line`、`body_start`、`grep -c '^CREATE TABLE'` 等邏輯行的增刪。

```bash
git diff "$(git merge-base origin/main HEAD)" -- scripts/spec-check.sh | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | grep -E 'DRIFT_TEST=|drift_status|case |grep -qE|block |check '
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
git diff origin/main...HEAD --stat
git diff --stat
```
（兩個 `diff` 都要跑：三點 diff 抓已 commit 的，`git diff` 抓還沒 commit 的；只跑其中一個，在「已先 commit」或「還沒 commit」的情境下都會得到空輸出的假通過。）
不得出現 `backend/`、`bff/`、`external-materials-service/`、`frontend/`、`api-gateway/`、`docker-compose.yml`、`scripts/git-hooks/` 底下任何檔案，也不得出現 `spec/tasks/t367_schema_sql_drift_guard.md`。

**（8）本任務不涉及可執行程式碼，不需 `/run-stack`。** 改動的檔案都不進任何 container image。

## 完成報告

（實作者做完後回填：實際改了哪些檔、上述八組驗證的實際輸出、與原計畫的偏差及原因。）
