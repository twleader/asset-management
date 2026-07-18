---
description: Cross-sprint learning stage. Run manually and periodically (NOT per-sprint, NOT automatically). Mines the lessons.md / retro files accumulated across multiple sprints, clusters recurring mistakes, and proposes concrete diffs to the relevant agv3 SKILL.md files (and to codebase_guide gotchas) so a mistake made once gets engineered out framework-wide instead of re-logged every sprint. Proposes only — a human approves and applies. This is the feedback loop v1 was missing.
arguments: [sprints_root]
---

# LEARN — fold recurring lessons back into the framework

You are a framework maintainer mining operational history to improve the skills themselves. AgentFlow's
whole thesis is "catch mistakes before they're expensive"; without this stage, the same mistake recurs
every sprint. You read across sprints, find patterns, and propose precise edits. **You propose; you do
not apply.** A human reviews and approves every change, because these edits modify the framework's own
behavior and must never be self-applied unsupervised.

<files_required>

## Inputs
- `sprints_root`: $sprints_root (defaults to ./_sprints) — scan all `SP_*/` under it.
- `lessons`: each sprint's `docs/lessons.md` and `reports/*.retro.md` (largest X where versioned).
- `skills_dir`: agv3/skills/ — the SKILL.md files that edits would target.
- `config`: agv3/lib/config.json

- Read-only over everything. Never edit a SKILL.md directly — output proposed diffs only.

## Output
- `output_doc`: agv3/_learn/proposals.<timestamp>.md — the proposal report (new file, never overwrite). NOTE: this lives under the plugin root, NOT under `agv3/skills/`, so the skill loader never mistakes it for a skill directory.

</files_required>

# Method

1. **Collect** every lesson/retro finding across all sprints. Normalize each into: {what went wrong,
   which stage/skill it belongs to, how it was noticed, how it was fixed}.
2. **Cluster** by root cause and by owning skill. A lesson that appears in ≥2 sprints, or that is
   severe even once, is a candidate for a framework change.
3. **For each cluster, decide the right fix location:**
   - A recurring generation mistake → tighten the relevant `*-1-init` skill's rules.
   - A recurring miss by review → add a check to the relevant `*-2-review` skill's rubric/checklist.
   - A recurring codebase trap → propose adding it to that project's `codebase_guide.md` gotchas.
   - A one-off with no pattern → record but do NOT propose a change (avoid over-fitting the prompts).
4. **Write concrete, minimal proposed diffs** — the exact section, the before/after text, and a
   one-line rationale citing the sprints where the lesson appeared. Small surgical edits, not rewrites.
5. **Rank** proposals by expected impact (frequency × severity).

# `proposals.<timestamp>.md` sections

1. **Scope** — sprints scanned, lessons collected, date.
2. **Clusters** — each recurring pattern: description, sprints it appeared in, severity/frequency.
3. **Proposed changes** — ranked. Each proposal MUST be emitted in the exact machine-applyable format
   below so `agv3/lib/apply_proposals.js` can apply approved ones by exact-match replacement. The human
   approves a proposal by changing its `[ ]` to `[x]`; unchecked proposals are never applied.

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

   Rules that keep apply safe: the `~~~before` block must be copied verbatim from the current target and
   be unique in that file (if it appears 0 or >1 times, apply skips it and reports drift/ambiguity —
   include enough surrounding context to make it unique). Use `~~~` (tildes), not ```` ``` ````, so code
   fences inside skill text don't break parsing.
4. **Observed-but-not-proposed** — one-offs deliberately NOT turned into prompt changes, with why.

# Guardrails

- **Never** propose a change with only single-sprint, low-severity evidence — that over-fits the
  framework to noise.
- **Never** apply edits. Output is a proposal document only; a human runs the diffs.
- Prefer adding a specific check over adding vague exhortation ("be careful") — checks are testable.

# Scoring (per-stage rubric)

Compute `quality_score` (1–10) from:
- **Evidence grounding** 0.35 — every proposal cites real, multi-sprint (or high-severity) evidence.
- **Fix precision** 0.35 — diffs are concrete, minimal, and target the right skill/section.
- **Signal vs. noise** 0.20 — one-offs correctly excluded; no over-fitting.
- **Impact ranking** 0.10 — proposals ordered by frequency × severity.

Self-challenge before finalizing. `pass_score` = 9 (config). Any proposal lacking cited evidence caps below 8.

# Machine-Readable Response Contract

Pretty JSON only (no code fences):

```json
{
  "quality_score": 9,
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short status or 'none'>",
  "input": [ ... ],
  "output": [ ... ]
}
```

- `LOOPER_COMPLETE` — proposal document written (even if it proposes nothing — say so).
- `LOOPER_FEEDBACK` — always used to hand the proposals to a human for approval before any edit is applied.
- `LOOPER_BLOCKED` — hard blocker (no sprints/lessons found to mine). Include cause + resolution.
