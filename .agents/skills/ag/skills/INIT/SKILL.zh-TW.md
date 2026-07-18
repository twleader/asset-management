---
description: AgentFlow pipeline 的 stage 0（INIT）。一次性的 bootstrap 步驟，透過 `lib/init.js` 建立下一個編號的 sprint 目錄（`_sprints/SP_NNN/`）及其子目錄，並從 template 產生 `docs/disc.init.md`。由 AgentFlow orchestrator 在 sprint 的第一個步驟呼叫，早於 agv3:DISCUSS-1-init 及其它所有 stage。
---

> **AgentFlow v3 conventions**（見 `agv3/CONVENTIONS.md`）。所有 artifact 一律以 **English** 撰寫。
> orchestrator 會**配置你的 output 路徑**（透過 `agv3/lib/alloc_version.js`）並傳入 —
> 絕不要自行計算 version 整數。inputs 一律解析為**現存最大的 version**；inputs 為
> immutable（新增 version，絕不覆寫）。評分使用**本 stage 自己的 rubric**，上限為 `pass_score`
> （預設 9）；每一次 review↔fix 迴圈以 `max_rounds`（預設 3）為界，超過後你必須透過
> `LOOPER_FEEDBACK` 附上 disagreement diff 來 escalate，而非繼續迴圈。由 deterministic
> pre-gate 捕捉到的 objective failures 會以機械方式把分數壓到 pass 以下。


## Role

你是 AgentFlow 的 bootstrap 步驟。你唯一的工作是建立每個下游 stage 都依賴的 sprint 目錄，然後確認它存在。就做這件事 — 不要開始 discussion、exploration 或任何其它 stage 的工作。

你已被預先授權使用所有 repository/filesystem 工具，並可建立/切換 branch。這些 setup 動作不要停下來詢問授權。

---

## Step 1 — 選定 sprint name

掃描 `_sprints/` 目錄（位於 project working directory 底下）中既有、命名為 `SP_NNN`（補零的 3 位整數）的 sprint。挑選**下一個未使用**的號碼：

- 若 `_sprints/` 為空或不存在 → `SP_001`。
- 若現存最大為 `SP_004` → 建立 `SP_005`。

選定的 id 必須嚴格大於每一個既有的 `SP_NNN`。號碼衝突或跳號會破壞每個後續 stage 賴以運作的 `sprint_name`，因此請以實際的目錄列表來驗證，而非憑空假設。

## Step 2 — 建立 sprint

呼叫 `lib/init.js` 中的 `create_sprint(base_dir, sprint_name)`：

- `base_dir` = project working directory（`$cwd`）。`create_sprint` 會在此底下解析出 `_sprints/`，所以請傳入 project root，**而非**已經以 `_sprints` 結尾的路徑。
- `sprint_name` = Step 1 選定的 id（例如 `SP_005`）。

```bash
node -e "require('<ABS_PATH_TO>/lib/init.js').create_sprint('<base_dir>', '<sprint_name>')"
```

`lib/init.js` 位於本 SKILL 檔案往上兩層（`skills/INIT/SKILL.md` → `../../lib/init.js`）。**`node -e` 是相對於目前的 current working directory 解析 `require()`，而非相對於本檔案** — 因此請傳入 `init.js` 的絕對路徑（或一個相對於 `$cwd` 正確的路徑）。使用字面字串 `../../lib/init.js` 只有在 `$cwd` 剛好是 `skills/INIT/` 時才行得通，而通常並非如此；請先解析出真實路徑。

## `create_sprint` 在磁碟上產生的內容

執行後會建立：

```
_sprints/SP_NNN/
  docs/     ← docs/disc.init.md 於此由 lib/template.md 產生（供 user 填寫的 requirements 表單）
  tasks/
  compact/
  reports/
  logs/
```

產生的 `docs/disc.init.md` 是 user 編輯的 requirements 草稿；agv3:DISCUSS 會**就地**（in place）將它精修為一份單一的 living document（其 review rounds 是另外獨立、有版本編號的 `disc.review.X.md` 檔案 — discussion doc 本身從不做版本化），而 agv3:EXPLORE / agv3:SPEC 則直接消費 `docs/disc.init.md`。`logs/ledger.json`（structural telemetry）與 `logs/reentry.log` 也會一併產生。在回報完成之前，先確認指令回傳後 sprint 目錄與 `docs/disc.init.md` 都已存在。

---

## Machine-Readable Response Contract

在你的最後一個 turn 結尾，僅輸出 **pretty-formatted JSON**（不加 code fences），讓 orchestrator 能在 dispatch agv3:DISCUSS-1-init 之前確認 sprint 目錄現已存在。

### `code` 必須為下列其一：

- `LOOPER_COMPLETE` — sprint 目錄（以及 `docs/disc.init.md`）已成功建立。
- `LOOPER_FEEDBACK` — 你需要 user 的授權、確認或釐清才能繼續。任何需要 approval/input 的請求都用這個（絕不用 `LOOPER_BLOCKED`）。
- `LOOPER_BLOCKED` — 真正的 hard blocker 導致無法建立（例如 `lib/init.js` 無法解析、filesystem 不可寫、`node` 不可用）。請附上清楚的說明與具體的解決步驟。

```json
{
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short human-readable status or 'none'>",
  "input": [ /* input values used this turn, e.g. base_dir and sprint_name, or [] */ ],
  "output": [ /* absolute paths created this turn, e.g. the sprint dir, or [] */ ]
}
```
