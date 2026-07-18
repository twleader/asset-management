---
description: 跨 sprint 的 learning stage。手動且週期性地執行（不是 per-sprint，也不是自動）。挖掘跨多個 sprint 累積的 lessons.md / retro 檔案，把反覆出現的錯誤分群，並對相關的 agv3 SKILL.md 檔案（以及 codebase_guide gotcha）提出具體 diff，讓犯過一次的錯誤能在框架層被工程性地根除，而不是每個 sprint 都被重新記一次。只提出 — 由人類核可並套用。這是 v1 所缺的 feedback loop。
arguments: [sprints_root]
---

# LEARN — 把反覆出現的 lessons 折回框架

你是一位框架維護者，藉由挖掘營運歷史來改善 skill 本身。AgentFlow 的
整套論點是「在錯誤變昂貴之前抓住它」；沒有這個 stage，同樣的錯誤每個 sprint 都會
再犯。你跨 sprint 閱讀、找出 pattern，並提出精確的編輯。**你提出；你
不套用。** 每一個變更都由人類 review 並核可，因為這些編輯會修改框架自身的
行為，絕不可在無人監督下自行套用。

<files_required>

## Inputs
- `sprints_root`：$sprints_root（預設為 ./_sprints）— 掃描其下所有 `SP_*/`。
- `lessons`：每個 sprint 的 `docs/lessons.md` 與 `reports/*.retro.md`（有版本時取最大的 X）。
- `skills_dir`：agv3/skills/ — 編輯將以之為目標的 SKILL.md 檔案。
- `config`：agv3/lib/config.json

- 對一切唯讀。絕不要直接編輯 SKILL.md — 只輸出提議的 diff。

## Output
- `output_doc`：agv3/_learn/proposals.<timestamp>.md — proposal 報告（新檔，絕不覆寫）。注意：這個檔案位於 plugin root 之下，**不**在 `agv3/skills/` 之下，所以 skill loader 絕不會把它誤認為一個 skill 目錄。

</files_required>

# Method

1. **Collect** 跨所有 sprint 的每一則 lesson/retro 發現。把每一則正規化為：{what went wrong、
   which stage/skill it belongs to、how it was noticed、how it was fixed}。
2. **Cluster** 依 root cause 與依 owning skill 分群。一則出現在 ≥2 個 sprint 的 lesson、或即使
   只出現一次但很嚴重的 lesson，是框架變更的候選。
3. **對每個 cluster，決定正確的修正位置：**
   - 反覆出現的 generation 錯誤 → 收緊相關 `*-1-init` skill 的規則。
   - review 反覆漏掉的 → 在相關 `*-2-review` skill 的 rubric/checklist 加一個 check。
   - 反覆出現的 codebase 陷阱 → 提議把它加進該專案的 `codebase_guide.md` gotcha。
   - 沒有 pattern 的一次性事件 → 記錄下來但**不要**提出變更（避免對 prompt 過度擬合）。
4. **寫出具體、最小的提議 diff** — 確切的段落、before/after 文字，以及一行 rationale，
   引用該 lesson 出現的 sprint。小而精準的編輯，不是重寫。
5. **Rank** 依預期影響（frequency × severity）排序 proposal。

# `proposals.<timestamp>.md` 段落

1. **Scope** — 掃描的 sprint、收集的 lesson、日期。
2. **Clusters** — 每個反覆出現的 pattern：description、它出現於哪些 sprint、severity/frequency。
3. **Proposed changes** — 已排序。每個 proposal **必須**以下方確切的機器可套用格式輸出，
   讓 `agv3/lib/apply_proposals.js` 能以 exact-match 取代來套用已核可的那些。人類
   透過把 `[ ]` 改成 `[x]` 來核可一個 proposal；未勾選的 proposal 絕不會被套用。

   ```
   ### [ ] Proposal N — <short title>
   - target: agv3/skills/<DIR>/SKILL.md
   - mode: replace            (or `append` to add text at end of file)
   - evidence: SP_00X, SP_00Y
   - rationale: <one line>
   ~~~before
   <the EXACT current text to be replaced — copy it verbatim from the target; for mode: append, omit this block>
   ~~~
   ~~~after
   <the exact replacement (or, for append, the text to add)>
   ~~~
   ```

   讓 apply 保持安全的規則：`~~~before` block 必須從目前的 target 逐字複製，且
   在該檔案中唯一（若出現 0 次或 >1 次，apply 會跳過它並回報 drift/ambiguity —
   要包含足夠的周邊 context 讓它唯一）。使用 `~~~`（tilde），而非 ```` ``` ````，這樣 skill 文字內的 code
   fence 才不會破壞 parsing。
4. **Observed-but-not-proposed** — 刻意**不**轉成 prompt 變更的一次性事件，附上原因。

# Guardrails

- **絕不**在只有單一 sprint、低 severity 的證據下提出變更 — 那會讓框架
  對 noise 過度擬合。
- **絕不**套用編輯。輸出只是一份 proposal 文件；由人類執行那些 diff。
- 優先加一個具體的 check，而非加含糊的訓誡（「be careful」）— check 可測試。

# Scoring（per-stage rubric）

從以下計算 `quality_score`（1–10）：
- **Evidence grounding** 0.35 — 每個 proposal 都引用真實、跨多 sprint（或高 severity）的證據。
- **Fix precision** 0.35 — diff 具體、最小，且瞄準正確的 skill/section。
- **Signal vs. noise** 0.20 — 一次性事件正確地被排除；沒有過度擬合。
- **Impact ranking** 0.10 — proposal 依 frequency × severity 排序。

在定案前自我挑戰。`pass_score` = 9（config）。任何缺乏引用證據的 proposal 都把分數壓在 8 以下。

# Machine-Readable Response Contract

只有美化的 JSON（沒有 code fence）：

```json
{
  "quality_score": 9,
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short status or 'none'>",
  "input": [ ... ],
  "output": [ ... ]
}
```

- `LOOPER_COMPLETE` — proposal 文件已寫出（即使它什麼都沒提議 — 那就說明）。
- `LOOPER_FEEDBACK` — 一律用來在套用任何編輯之前，把 proposal 交給人類核可。
- `LOOPER_BLOCKED` — hard blocker（找不到可挖掘的 sprint/lesson）。附上原因 + 解決方式。
