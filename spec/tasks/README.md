# 任務檔規範

Task 201 之後的新任務，一律建立**獨立的自足任務檔** `spec/tasks/tNNN_<slug>.md`，不再追加進 `spec/tasks.md`。

```
spec/
├── tasks.md              # 索引（Task 1–228、264–267、269–292、297–309、311–342、344–347、349）＋ 尚未歸檔的 201 起區段
└── tasks/
    ├── README.md         # 本檔
    ├── archive/          # Task 1–200 歷史，已凍結
    │   ├── tasks-001-050.md
    │   ├── tasks-051-100.md
    │   ├── tasks-101-150.md
    │   └── tasks-151-200.md
    └── tNNN_<slug>.md    # 新制自足任務檔
```

> **為什麼改成一檔一任務：** 舊的 `spec/tasks.md` 長到 5521 行、228 個區段。派給 coding agent 時，它要嘛讀整份（大半無關內容稀釋掉真正該注意的約束），要嘛只讀片段（漏掉散在別處的前置條件）。自足任務檔讓「該知道的全在這一支檔裡」。

---

## 鐵則

1. **一檔一任務。** 一支檔＝一個可獨立驗收的交付。做不完就拆成兩支，不要塞成一支。
2. **自足。** 判準是：**把這一支檔單獨交給一個沒有其他 context 的 coding agent，它能正確做完**——不必回頭翻 `requirements.md`／`design.md`／其他任務檔，也不必來問人。
3. **不要寫「見 X 文件第 Y 節」。** 需要的內容整段複寫進來。重複是刻意的：規格文件會演進，任務檔要凍結在派工當下的事實。唯一例外是**指向已凍結歸檔**的歷史脈絡連結。
4. **約束寫在「要做什麼」裡，不能只寫在「驗證」裡。** 實作者常常做完才看驗證段，約束擺在那裡等於沒寫。
5. **實作與其測試放同一支任務檔。** 拆開會變成先寫完程式再補測試。
6. **編號先查撞號。** 建檔前跑 `bash scripts/spec-check.sh`。本專案一年內有 12 次 commit 專門在做編號避讓——Task 編號、Requirement 編號、Liquibase changeset 版號會同時撞，因為多個 worktree 並行推進 main。

---

## 檔名

`tNNN_<slug>.md` — `NNN` 為三位數 Task 編號（補零），`slug` 為小寫英數與底線的短描述。

```
t221_etf_premium_radar.md
t222_fix_uk_open_price_null.md
```

編號與 `spec/tasks.md` 索引共用同一個編號空間；`spec-check.sh` 會把 `tasks.md`、`archive/*.md`、`tNNN_*.md` 三處一起算，任一處重號都會擋。

---

## 檔案結構

固定五段，順序不變，沒內容的段落寫「無」而不是刪掉。

```markdown
# [tNNN] <一句話標題>

**對應 Requirements:** Requirement N（<該 Requirement 的一句話摘要，不要只寫編號>）
**前置任務:** t### / 無
**Liquibase changeset:** vX.Y.Z-<slug>.sql / 無

## 背景

為什麼要做這件事。若是 bug fix，寫清楚**現在的錯誤行為**與**正確行為**，以及能重現的條件。
若這個決定推翻了先前的做法，寫明推翻的是什麼、為什麼。

## 要做什麼

逐項、具體、可執行。每一項都要能單獨判斷做完沒有。
所有約束（欄位型別、精度、命名、邊界條件、不得做的事）寫在這一段，
包含從 requirements/design 複寫過來的原文。

- [ ] NNN.1 ...
- [ ] NNN.2 ...

## 驗證

**可直接執行的指令**，不是「請確認功能正常」。至少涵蓋：建置、測試、以及本專案特有的
「跑起來真的有這個功能」——本專案沒有 dev server，改好的定義是 image rebuild + container
recreate（見 .claude/skills/run-stack）。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
curl -s http://localhost:8080/actuator/health
```

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
```

---

## 自足性自查

建好任務檔後，逐條問自己：

- 檔案裡有沒有出現 `requirements.md`／`design.md`／`spec.init` 之類的**文件名指引**？有就是沒複寫完。
- 有沒有出現「同上」「如前所述」「參考另一個任務」？收件者看不到那些。
- 欄位型別、精度、nullable、命名，是不是寫死在檔案裡而不是要對方去查？
  （對 DB 現況的斷言**以運行中的 DB 為準**：`docker exec asset-postgres psql -U assets -d assets -c '\d <table>'`。
  **不要引用 `db/changelog/**`** 描述現況——那裡面有永不執行的 changeset，照著改會把原本正確的改成錯的。
  `db/schema.sql` 只能當離線參考，**不是可信基準線**：它是被 `.gitignore` 排除的真基準線 `db/init/01_dump.sql`
  的去資料鏡像，靠人工重新產出，**實測已落後**——截至 Task 245 它仍沒有 `crawler_export_setting`（v1.64.0）
  與 `asset_transaction`（v1.72.0）。查不到某張表時，先確認是「真的沒有」還是「鏡像沒跟上」。
  **但運行中 DB 也不等於 main 的現況**：全機只有一套 `asset-*` 容器、多個 worktree 並行推進，DB 可能已套用
  其他分支尚未 merge 的 changeset。下斷言前先比對
  `SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5` 與 main 的
  `db.changelog-master.yaml` 尾端。）
- 驗證段的指令，是不是**貼上去就能跑**？
- 涉及 `@Scheduled` 的話，有沒有一項是「同步 `SchedulePublicBffController.JOBS`」？漏掉這項是排程列表頁漂移的固定成因。

---

## 與 SDD 流程的關係

任務檔是 SDD 第 3 步的產物，不取代前兩步：

```
1. spec/requirements.md   → User Story + Acceptance Criteria
2. spec/design.md         → 架構 / 資料模型 / API 設計
3. spec/tasks/tNNN_*.md   → 建立自足任務檔（新任務；既有任務見 tasks.md 索引）
4. spec 對抗式審查        → /spec-review（產出 findings，不打分數）
5. 實作程式碼
```
