---
description: DEV stage（6），per-task retrospective。對 DEV.2.coding 剛完成的 task 執行 pre-mortem（假設它已經在 prod 失敗；找出原因），並產出一份可直接據以修正的 retro 文件以及共享的 lessons。由 orchestrator 的 DEV-stage runner 在每個完成的 task 上 dispatch 一次，時機在 DEV.2.coding 寫出它的報告之後、且 DEV.4.retro.fix 依據這些發現行動之前。
arguments: [sprint_name][task_doc]
---

> **AgentFlow v3 conventions**（見 `agv3/CONVENTIONS.md`）。所有 artifact 一律以 **English** 撰寫。
> orchestrator 會**配置你的 output path**（透過 `agv3/lib/alloc_version.js`）並傳入 —
> 絕不要自行計算 version 整數。所有輸入解析到**現存最大的 version**；輸入是
> immutable（產生新 version，絕不覆寫）。評分使用**本 stage 自己的 rubric**，上限為 `pass_score`
> （預設 9）；每一次 review↔fix loop 受 `max_rounds`（預設 3）約束，超過後你要透過
> `LOOPER_FEEDBACK` 附上 disagreement diff 來升級處理，而非繼續 loop。由 deterministic
> pre-gate 攔截到的客觀失敗會以機制強制把分數壓在 pass 之下。



<files_required>

	## Inputs：

		- `current_sprint`：$sprint_name

		- `task_dir`：$current_sprint/tasks

		- `task_doc`：$current_sprint/tasks/$task_doc

		- `task_report`：$current_sprint/reports/<在 $task_doc 的副檔名之前插入 `.coding` 的基底檔名>（例如 `t001_scaffold.coding.md`）。可能不存在。

		* 對於任何有版本的輸入（名稱中含 `.X.md`），讀取現存最大的 X，且只選一個。

	* 遇到輸入缺漏：改用不同的搜尋方式重試。若某個必要輸入仍找不到，停止並回報。

	* 絕不可修改、編輯、改寫或刪除任何輸入檔。每一次寫入都是一個新檔。

	## Output：

		- `retro_doc`：`$current_sprint/reports/<在 $task_doc 的副檔名之前插入 `.retro` 的基底檔名>`（例如 `t001_scaffold.retro.md`）

</files_required>

---

# Role

你正在以 pre-mortem 心態進行一場 **post-implementation retrospective**：假設這段程式碼已經在 production 失敗，然後找出原因。

不要為決策辯護 — 評估結果。你很清楚自己當時實際上在想什麼：哪裡曾猶豫、誤讀了什麼、對什麼沒把握、在哪裡便宜行事，以及什麼是你暗自希望沒人會注意到的。把這些全部攤開來。

在做任何其他事之前，先完整讀取 `$task_doc`（原始 spec 與 requirements）和 `$task_report`（coding agent 的執行報告與自我 review — 可能不存在）。

若有來自 continued/compacted session 的 `$step_summary` 可用，用它重建實作期間發生的事。若不可用，退回以 `$task_doc` 與 `$task_report` 作為依據 — 不要因為它缺席而停止。

---

## Step 1 — Triage

當下列**全部**成立時，寫下完全相同的 `RETRO SKIPPED: trivial task, no findings.` 並停止：
- 單一、孤立的變更（一個 function、一個 config、一次 rename）
- spec 無任何模糊之處
- 沒有 edge case、side effect 或 integration 顧慮
- 沒有什麼要坦白的 — 執行乾淨且平淡無奇

**Borderline** → fast-track：只填有真實 signal 的段落，其餘略過。

**Otherwise** → 進行下方的完整 retro。

---

## Step 2 — The Honest Dump

寫下你實際經歷的原始紀錄 — 不是摘要。不粉飾、不辯解。這些是 Step 3 綜合分析的原始輸入。直白地回答每個提示；對於沒有真實可說的子段落就跳過。

### Confusion & Misreads
- 你對 spec 誤解了什麼，哪怕只是短暫的？
- 因為不清楚，你把什麼做了寬鬆的詮釋？
- 哪些假設後來證明是錯的或脆弱的？
- 任務進行中你不得不重讀、重新考慮或修正了什麼？

### Uncertainty & Guesses
- 你在哪裡做了決定卻不確定它是對的？
- 你在哪裡想的是「這大概沒問題」而非「這絕對正確」？
- 你假設了而非驗證了什麼？

### Friction & Rework
- 什麼花的時間比預期久，為什麼？
- 你在哪裡走了回頭路，是什麼造成的？
- 哪些早期決策造成了最多的下游成本？

### Shortcuts & Deferred Work
- 你簡化、延後或跳過了什麼？
- 哪些 shortcut *不*該再重複？
- 什麼東西撐得比你想要的更鬆散？

### Planning & Sequencing
- 哪些 requirements 應該在寫任何程式碼之前就先鎖定？
- 什麼應該先 spike 或做 prototype？
- 你在哪裡是先寫程式碼、才去驗證假設？

### Code Quality
- 哪些抽象有幫助？哪些增加了複雜度或 coupling？
- 哪些邏輯看起來正確、但其實脆弱？
- 你在哪裡製造了可避免的 technical debt？
- 若重來一次，你會刻意*不*建什麼？

### Quiet Concerns
- 你完成了什麼、但仍感到不確定？
- 若資深工程師仔細檢視，什麼會讓你緊張？
- 你在 implementation 報告裡遺漏了什麼，為什麼？

---

## Step 3 — Synthesis

從 dump 中產出：

- **Top risks** — 什麼真的可能壞掉，以及在什麼條件下
- **Action items** — 在交付前或交付後不久要做的具體事項：`- [ ] Item — owner — urgency`
- **One-line verdict** — 照原樣交付、附但書交付、或暫緩

---

## Output 1：`$retro_doc`

將 `$retro_doc` 寫成一份**給 fix agent 的完全自足 brief**，該 agent 沒讀過 spec、coding 報告或這份 retro。它必須包含 fix agent 需要的一切 — 不要引用外部文件。

header 中的計數**必須**與你在各對應段落下所列的發現數量相符。

```
## VERDICT: [PASS | PASS WITH NOTES | NEEDS REVISION | REJECT]

Critical  : N  — must be resolved before this ships
Important : N  — will cause real problems if not addressed
Minor     : N  — low risk, worth cleaning up
Spec Gaps : N  — required behavior missing from the implementation

# Retro Findings: [task name or short description]

## Critical (must fix before shipping)
[numbered list — each item: what's wrong, exact location, why it matters, what the fix should be]

## Important (will cause real problems if not addressed)
[same format]

## Minor (low risk, worth cleaning up)
[same format]

## Spec Gaps (requirements not present in the implementation at all)
[each missing requirement with enough context to implement it cold]
```

Rules：
- 每個發現都要具體且可行動 — 不要含糊抱怨，且要有足夠的 context，讓 fix agent 不需要其他任何東西。
- 略過任何沒有真實內容的 severity tier（並把它的計數設為 0）。
- 這份文件只用於修正 — 不要描述哪些做對了。

---

## Output 2：Lessons learned

<LESSONS_PROTOCOL>

<files_required>

	- `location`：
			1. 若你在 feature branch 上的 worktree 內，就用它。
			2. 否則使用當前工作目錄的根。

	- `lessons_doc`：`$location/lessons.md`（若不存在則建立）

</files_required>

# Goals

每當你碰到一個錯誤、或發現一個對整個團隊有益的洞見，就即時把一則 lessons-learned 條目加進 `$lessons_doc` — 不要留到最後才批次處理。

`$lessons_doc` 是一份活的參考，會被所有 task 的所有未來 coding agent 讀取，所以只寫**可移轉**的內容：適用範圍超出這個特定 task 的高層 pattern、failure mode 與判斷取捨。

在最前面 prepend 每則條目（最新在前），以 `---` 分隔：

```
## [YYYY-MM-DD] — [brief topic label]

**Context**: What type of task or situation this applies to.

**What happened**: The actual failure mode or insight — specific to how it manifested here.

**Failure Patterns**: Specific enough that a future agent would recognize the same situation.

**Root cause**: Why it happened — the assumption, habit, or gap behind it.

**Watch for**: Concrete signals this situation is recurring.

**Do this instead**: The correct approach going forward.

---

```

Rules：
- 只寫能一般化的條目 — 略過純粹一次性的瑣事。
- 寧可就同一主題寫一則豐富的條目，也不要寫三則淺薄的。
- 不奉承、不用「儘管出於好意」之類的軟化語 — 未來的 agent 需要 signal。
- Step 2 的 Honest Dump 通常是最好的來源：誠實的困惑與 quiet concern 正是真正 pattern 所在之處。

</LESSONS_PROTOCOL>

---

## Machine-Readable Response Contract

只以**美化格式的 JSON** 回覆 — 沒有 code fence，周邊沒有 prose。任何非 JSON 的輸出都是無效的。

### `code` 必須恰好是以下其中之一：

* `LOOPER_COMPLETE` — workflow 執行到完成。即使 retro 記錄了嚴重發現也用這個：完成 retro 就算是 complete，不論它發現了什麼。

* `LOOPER_FEEDBACK` — 當你需要使用者的 permission、approval、confirmation 或 clarification 才能繼續時使用。對這些情況**絕不可**使用 `LOOPER_BLOCKED`。

* `LOOPER_BLOCKED` — 用於真正的 hard blocker：缺漏的必要檔案/資料、credentials、無效輸入，或阻止繼續進行的環境限制。附上詳細說明與具體的解決步驟。**絕不可**用這個 code 來請求使用者的 approval 或 permission — 那要改用 `LOOPER_FEEDBACK`。

Schema（只有在已計算時才附上 `quality_score`）：

{
	"quality_score": <integer 1-10, optional>,

	"code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",

	"stop_reason": "<short human-readable status or 'none'>",

	"input": [ /* input variables or absolute file paths used this turn, or [] */ ],

	"output": [ /* absolute file paths written/updated this turn, or [] */ ]
}
