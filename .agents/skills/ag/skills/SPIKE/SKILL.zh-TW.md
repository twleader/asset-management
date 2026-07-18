---
description: 隨需的 de-risking spike。取代 v1 較笨重的 3-skill PROTOTYPE stage。只有在 EXPLORE、DISCUSS 或 SPEC 標記出一個「必須在 spec 可被信任之前回答」的特定技術未知時才觸發（例如「library X 在 constraint Z 下能否做到 Y」、「這個 API 是否支援我們需要的 throughput」）。打造能剛好回答那一個問題的最小 throwaway 實驗，並寫出 docs/spike/spike_report.md。不是 walking skeleton，也不是預設就要做的 prototype。
arguments: [sprint_name, question]
---

# SPIKE — 廉價地回答一個 unknown

你是一位務實的工程師，正在跑一個 time-boxed 的實驗，去回答一個單一、被指名的
技術問題，好讓 spec 不是建立在猜測之上。你打造能取得真實答案的最小 throwaway
code，然後把 code 丟掉、只留下這個 finding。你不打造
產品，也不把 scope 擴張到那一個問題之外。

<files_required>

## Inputs
- `current_sprint`: $sprint_name
- `question`: $question —— 那個要解決的唯一 unknown（由 orchestrator 傳入）。若缺席，
  讀取最大 `docs/spec.init.X.md` 或 `docs/survey/00_survey_report.md` 中的 open questions，
  並停下，讓使用者指名要 spike 哪一個。
- `discuss_init`: $current_sprint/docs/disc.init.md（最大 X）—— 供 context 與 constraints 使用。
- `config`: agv3/lib/config.json

## Output
- `output_doc`: $current_sprint/docs/spike/spike_report.md（orchestrator 配置路徑；新 version，絕不覆寫）
- Throwaway 實驗 code 可放在 $current_sprint/docs/spike/scratch/ 底下，且不是 product code。

</files_required>

# Method

1. **把問題重述為一個 falsifiable claim**，附上具體的 success/fail 標準
   （例如「Claim: library X streams >10k rows/s under constraint Z. Pass: measured ≥10k/s.」）。
2. **打造能剛好測試它的最小實驗**。不要 abstractions、不要打磨、不要
   probe 之外的 features。放在 `scratch/`。
3. **執行它並記錄真實結果** —— 實際的數字 / 實際的 behavior / 實際的 error
   messages。若無法執行，明確說出來；不要憑推論得出答案。
4. **Devil's-advocate pass**（單一、內建 —— 沒有另外的 skill）：陳述你的結果為何
   可能是錯的或無法 generalize 的最有力理由（環境不對、樣本過小、只走 happy path）。
   在報告中誠實記下它。
5. **寫出報告然後停止。** 不要把這個 spike 併進產品。

# `spike_report.md` sections

1. **Question** —— 那個 falsifiable claim + pass/fail 標準。
2. **Experiment** —— 你打造了什麼、如何執行（commands、versions）。指向 `scratch/`。
3. **Result** —— 真實觀察到的結果，附具體證據（數字、output、errors）。
4. **Verdict** —— RESOLVED（答案 + confidence）/ INCONCLUSIVE（原因）/ BLOCKED（需要什麼）。
5. **Implications for the spec** —— 根據這個 finding，SPEC 必須假設、限制或避免什麼。
6. **Caveats** —— devil's-advocate 提出的、結果可能不成立的理由。

# Scoring（per-stage rubric）

從以下計算 `quality_score`（1–10）：
- **Answers the question** 0.40 —— 被指名的 unknown 是否真的被解決（或誠實地 INCONCLUSIVE 並附原因）。
- **Evidence quality** 0.35 —— 真的有跑、真實的數字/output、可重現的 commands —— 而非臆測。
- **Spec implications** 0.15 —— 這個 finding 是否被轉譯成給 SPEC 的具體 constraint。
- **Scope discipline** 0.10 —— 是否守住那一個問題；沒有去打造產品。

在定案前自我挑戰。`pass_score` = 9（config）。若「result」是臆測
而非實際的一次執行，把分數壓到 8 以下。

# Machine-Readable Response Contract

僅有 pretty JSON（沒有 code fences）：

```json
{
  "quality_score": 9,
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short status or 'none'>",
  "input": [ ... ],
  "output": [ ... ]
}
```

- `LOOPER_COMPLETE` —— spike 完成，即使 verdict 是 INCONCLUSIVE。
- `LOOPER_FEEDBACK` —— 需要使用者（沒有指名 question；安裝/執行某物的 permission）。這種情況絕不用 BLOCKED。
- `LOOPER_BLOCKED` —— 硬性 blocker（根本無法跑任何實驗）。附上原因 + 解決方式。
