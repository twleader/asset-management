---
name: spec-review
description: 對 asset-management 的 spec/ 變更做獨立對抗式審查，找出實作前該修的問題。先跑 scripts/spec-check.sh 取機械證據，再派唯讀的 spec-auditor subagent 逐項查證並列出 findings（不打分數、不設通過門檻）。當使用者說「審 spec」「spec review」「spec 寫好了」「可以開始實作了嗎」「檢查規格」之類指令、或剛改完 spec/requirements.md／design.md／tasks 任務檔而尚未動 code 時使用。審查者一律不得修改任何檔案。
---

# Spec 對抗式審查（實作前閘門）

SDD 流程的第 4 步（實作）之前的閘門。目的**不是**檢查「需求寫得完不完整」——本專案稽核 64 處 spec 不一致時，**沒有一處是「規格寫了但沒實作」**（Task 197）。真正的缺陷全部是**可查證性**問題：規格宣稱了程式碼裡不存在的東西、文件自相矛盾、編號撞號、計數漂移。

> 觸發詞：審 spec、spec review、spec 寫好了、可以開始實作了嗎、檢查規格、規格審查。
>
> **也會被 hook 主動叫起**：寫入 `spec/` 之後，`.claude/hooks/notify-spec-changed.sh`
> 會提示跑本 skill；沒跑就想寫實作程式碼，`require-spec-review.sh` 會直接擋下。

**本檔只管流程（誰來審、餵什麼、結果怎麼處理）。審查判準（維度、架構鐵則檢查表、報告格式）住在 [`.claude/agents/spec-auditor.md`](../../agents/spec-auditor.md)，不要在這裡重複一份——兩份判準必然漂移。**

## 鐵則

1. **審查者不是作者。** 一律用 Agent tool 派 `subagent_type: spec-auditor`。主 agent 剛寫完 spec 就自己審＝沒有獨立性，這一關等於沒跑。
2. **唯讀由工具層保證。** `spec-auditor` 沒有 Edit / Write 工具。你（主 agent）不要把「順手幫我改掉」寫進它的 prompt。修正一律由你在報告產出後執行。
3. **機械證據壓過判斷。** `scripts/spec-check.sh` 報 BLOCK 就是已證實的缺陷，不得以主觀判斷繞過。
4. **不打分數、不設通過門檻。** 產出的是 findings 清單，由你判斷哪些該修、哪些是誤判。**critical 與 major 修完即可進入實作**，minor 可留待後續。重審最多 3 輪；第 3 輪仍在爭同一件事就停下來問使用者，附上「審查者反覆指出什麼 vs 修正者反覆做了什麼」的分歧摘要，不要無限迴圈。

---

## Step 0 — 機械前置檢查

```bash
bash scripts/spec-check.sh
```

先跑（預設比對 `origin/main`），**輸出整段**留著餵給審查者。它涵蓋**不需要判斷力**的部分：Task／Requirement 編號撞號與重號、Liquibase changeset 版號碰撞／非冪等／未註冊、spec 宣稱的測試類不存在、`@Scheduled` 變更未同步排程登錄表、文件計數漂移。

> `scripts/spec-check.sh` 與 commit-msg hook 是**兩個不同的閘門**。hook 只檢查「`spec/` 有沒有被碰」，改一個錯字就過關；而且它的觸發清單漏了 `service/`、`repository/`、`external-materials-service/**`——Task 195 那兩處排程漂移根本不會觸發 hook。這支 skill 補的正是 hook 看不到的那一面。

## Step 1 — 圈出審查範圍

```bash
git diff origin/main...HEAD --stat -- spec/
git diff origin/main...HEAD -- spec/
```

未 commit 的變更另跑 `git diff -- spec/` 與 `git diff --cached -- spec/`；**新增的自足任務檔尚未 `git add` 時不會出現在任何 diff**，要另外用 `git ls-files --others --exclude-standard -- spec/` 撈出來一起附上。

只審**這次變更的切片**，不是整份 10000 行的 spec。審查者要拿到完整 diff，不是摘要。

## Step 2 — 派 spec-auditor

用 Agent tool，`subagent_type: spec-auditor`。prompt 只需帶**這次的材料**（判準已在 agent 定義裡，不要重貼）：

- Step 0 的**完整**輸出（不是摘要）
- Step 1 的**完整** diff ＋ 未追蹤新檔的內容
- 這次變更想達成什麼（一兩句，讓它能判斷「對未來的正當描述」vs「對現況的錯誤斷言」）

要求它**逐維度**回報，每個維度都要有具體證據（檔案:行號、identifier、實際 grep 結果），不接受「大致沒問題」。

## Step 3 — 處理結果

- **有 critical／major**：依 findings 修 spec，**修完重跑 Step 0–2**——重派一支**新的** `spec-auditor`，不要叫同一支確認自己改過的東西。
- **只剩 minor**：可進入實作，minor 留待後續。
- **判定某條是誤判**：不用照修，但要在回覆裡說明為什麼是誤判（審查者的自我挑戰段落常已先撤下一部分，可交叉參考）。
- **第 3 輪仍未收斂**：停。回報使用者，附分歧摘要，不要繼續燒。

## Step 4 — 記錄審查通過（**不做這步就寫不了程式**）

critical／major 都處理完之後：

```bash
bash .claude/hooks/spec-review-pass.sh
```

這會記下**當下 `spec/` 的內容雜湊**。`.claude/hooks/require-spec-review.sh`（PreToolUse 閘門）會比對它——沒有這筆紀錄，對 `backend/**`、`bff/**`、`external-materials-service/**`、`frontend/src/views/*.vue`、`frontend/src/router/**`、`db/changelog/**` 的任何寫入都會被擋下。

**順序不能顛倒**：先修完再記錄。若記錄後又動了 `spec/`（例如補一句 AC），雜湊即失效、閘門會再次擋下——這是刻意的，代表那次修改沒被審過。重審完再跑一次即可。

查狀態：`bash .claude/hooks/spec-review-pass.sh --status`

## 完成判準

- `scripts/spec-check.sh` 零 BLOCK。
- 審查由 `spec-auditor` 產出，不是主 agent 自評。
- 報告含逐維度證據與自我挑戰段落，不是一句「看起來沒問題」。
- critical 與 major 已修完（或已判定為誤判並說明理由）。
- 審查過程零檔案修改。
- 已跑 Step 4 記錄通過（`--status` 顯示「已通過審查且 spec/ 未再變動」）。

## 不要做

- 不要自己審自己剛寫的 spec。
- 不要把判準複製回這份 skill——那正是 spec 漂移的成因，本專案已經示範過一次。
- 不要因為「這次改動很小」就跳過。本專案最貴的三次稽核（Task 183／197／201）修的全是當初看起來很小的改動。
- 不要在報告回來後直接進實作卻不修 critical——那等於白跑一輪。
