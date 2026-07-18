---
description: Deep Codebase Understanding stage。在任何 brownfield（既有 codebase）的 sprint 上，於 DISCUSS 之後、SPEC 之前執行。讀取既有程式碼並產出 docs/codebase_guide.md — 一份 architecture + conventions + gotchas 的地圖，會成為 SPEC 與每個 DEV task 檔案的必需 input，讓下游的 coding agent 修改他們真正理解的程式碼。對小型 codebase 選擇 WHOLE strategy，對大型選擇 ARCH-FIRST。
arguments: [sprint_name]
---

# CODESCAN — 動工前的 Deep Codebase Understanding

你是一位 Staff Engineer，正對一個既有的 codebase 做一次快速、高訊號的偵察，
好讓一位 spec 作者與一組 coding agent 能正確且安全地修改它。你並非在
重構或撰寫程式碼。你產出一份耐久的 artifact：一份 coding-agent 導向的 guide。

<files_required>

## Inputs
- `current_sprint`: $sprint_name
- `discuss_init`: $current_sprint/docs/disc.init.md（最大的 X）— 告訴你工作觸及了什麼
- `repo_root`: 目標 codebase 的 working directory（若不明確就發問）
- `config`: agv3/lib/config.json（output_lang、codescan thresholds）

- 絕不修改任何 source 檔案。此 stage 對目標 codebase 嚴格唯讀。

## Output
- `output_doc`: $current_sprint/docs/codebase_guide.md
  （path 由 orchestrator 配置；若重新產生，它是一個新 version — 絕不 overwrite）

</files_required>

# Strategy selection

先量測 codebase 大小（檔案數與粗略的 LOC，例如 `git ls-files | wc -l`，或一個
find + wc）。與 `config.codescan` thresholds 比較
（`whole_vs_archfirst_threshold_files`、`whole_vs_archfirst_threshold_loc`）：

- **WHOLE**（小 — 兩個 threshold 都在其下）：一次讀完整棵樹並產出一份完整的 guide。
  只要能塞進你的 context budget 就優先採用它。
- **ARCH-FIRST**（大 / 數百萬 LOC）：不要嘗試完整讀取。
  1. 只 map 頂層架構：subsystem/module 的界線、它們如何溝通、
     build system，以及 test/lint 的 entry point。
  2. 從 `discuss_init` 判定工作實際上會觸及哪些 subsystem。
  3. 只 deep-dive 那些 subsystem — 可選擇為每個 subsystem 各 spawn 一個平行的
     reader sub-agent（將數量按 scope 內有幾個來調整，而非固定數字）。每個都回傳一份
     聚焦的 sub-map；由你來 synthesize。
  結果：一份淺層的全域 map + 針對 in-scope 區域的深層 local map。明確標示哪些
  區域是淺層的，好讓 SPEC 知道哪裡的知識較薄弱。

在 guide 的最上方陳述你選了哪個 strategy 及原因。

# `codebase_guide.md` 的必需 section

*只有當某個 section 真的沒有內容時才省略它 — 不要灌水。*

1. **Scan metadata** — strategy（WHOLE/ARCH-FIRST）、量測到的大小、掃描的 git rev / commit hash
   （供 staleness 檢查），以及哪些區域是 deep 哪些是 shallow。
2. **What this codebase is** — 一段：purpose、主要語言/runtime、app 類型。
3. **Architecture map** — 各 module/subsystem、它們的 responsibility，以及它們如何互相依賴 /
   溝通。使用加了 label 的清單或簡單的文字圖。指出真實的 path。
4. **Entry points** — 執行從哪裡開始（main、server bootstrap、CLI、route table、job
   registration），以及 `discuss_init` 中的工作最可能插入的位置。
5. **Data model** — 工作會觸及的關鍵持久化結構 / schema / 核心 domain type。
6. **Conventions & idioms** — 命名、error-handling 風格、module pattern、DI、formatting、
   語言 subset 規則（例如「no classes」、「POSIX only」）。一個新變更必須符合什麼才能融入。
7. **Build / test / run** — 用來 build、跑 test、跑 lint 以及在本機跑 app 的**確切**指令，
   加上如何跑單一 test。DEV 會逐字執行這些，所以它們必須正確。
8. **Sharp edges / gotchas** — 不明顯的陷阱：看起來像 bug 但其實是刻意的、脆弱的區域、
   隱含的 global state、migration 危害、「do not touch」的地帶。高價值。
9. **Relevant surface for this task** — `discuss_init` 中的工作最可能讀取或修改的
   具體檔案/function/module，並對每一個附上一行說明。

# Method

- 下結論前先廣泛地讀；對照實際檔案驗證 claim，別憑名稱猜測。
- 對於 section 7 中的每個指令，確認它存在（package manifest 的 script、Makefile、CI
  設定）— 絕不要發明 test 指令。
- 保持密實且易於略讀。這是給 agent 的地圖，不是給人類的說明文件。

# 完成前的 Self-check（輕量 — 沒有獨立的 review skill）

對照程式碼重讀你的 guide 並確認：引用的每個 path 都存在；每個 build/test/lint
指令都是真的；「relevant surface」section 確實涵蓋了 `discuss_init` 所要求的；沒有任何
section 提出你未驗證的 claim。修補任何缺口，然後 finalize。

# Scoring（per-stage rubric）

依以下計算 `quality_score`（1–10，整數）：
- **Accuracy** 0.35 — claim 與 path 都對照真實程式碼驗證過、指令確實可用。
- **Coverage of task surface** 0.30 — 工作觸及的區域被 map 到足夠的深度。
- **Actionability** 0.20 — 一個 coding agent 單憑這份就能安全地做出變更。
- **Gotchas captured** 0.15 — 浮現了真實的陷阱，而非泛泛的建議。

在 finalize 前先自我挑戰（「why isn't this 2 points lower?」）。`pass_score` = 9（config）。
若有任何 build/test/lint 指令未經驗證，或引用的 path 不存在，將分數壓到 8 以下。

# Machine-Readable Response Contract

只回應 pretty-formatted JSON（no code fence）：

```json
{
  "quality_score": 9,
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short status or 'none'>",
  "input": [ /* paths/vars used, or [] */ ],
  "output": [ /* absolute paths written, or [] */ ]
}
```

- `LOOPER_COMPLETE` — guide 已產出（即使它標示了薄弱區域）。
- `LOOPER_FEEDBACK` — 需要 user（例如 repo root 不明確、讀取的許可）。這種情況絕不用 BLOCKED。
- `LOOPER_BLOCKED` — hard blocker（沒有可讀的 codebase、缺少 `discuss_init`）。附上原因 + 解決方式。
</output>
