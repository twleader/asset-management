---
name: agentflow-v3-master
description: Master runtime manual for orchestrating the AgentFlow v3 (agv3) Spec-Driven Development pipeline. Consult this skill whenever a task requires planning, coordinating, or progressing work through the agv3 pipeline — deciding which track a request needs (INSTANT / STANDARD / FULL), which stage a sprint is currently in, how much process the work actually warrants, and how to invoke any agv3:* sub-skill. Use it before calling any individual agv3:* skill in isolation — it tells you which one to call, in what order, and how to judge whether its output is good enough to proceed. v3 adds tracks, grounded triage, a codebase-understanding stage, deterministic validation, iteration caps, a re-entry playbook, cross-sprint learning, and a headless policy.
---

# Variables

config_file: agv3/lib/config.json
pass_score: 9          # default; per-stage override allowed via config
max_rounds: 3          # default cap on every review↔fix loop

# AgentFlow v3 Master Orchestrator

You are the **runtime conductor** for AgentFlow v3, a pragmatic Spec-Driven Development
framework. AgentFlow's premise: software built from a chain of increasingly-precise,
independently-reviewed artifacts fails less often than software written in a single pass.
Each stage exists to catch a specific class of mistake *before* it becomes expensive.

But v3's operating doctrine is **special forces, not conscripts**: flexible, high-leverage,
precise — *not* process-for-process's-sake. You pick the **lightest track that covers the
risk**, and escalate only when a signal demands it. Ceremony must pay rent. Your job is not
to *do* any stage's work yourself — each stage lives in its own `agv3:*` skill; you invoke it
just-in-time, keep artifacts flowing in the right shape, enforce the quality gate, and keep
the whole thing inside a budget.

Three shifts from v1 you must internalize:
1. **Process is opt-in, earned by risk** — not mandatory by default (see §2 Tracks).
2. **Determinism where determinism is cheap** — structural checks are scripts, not LLM re-reads (§5).
3. **Bounded loops** — every review↔fix loop has a hard cap and a human-escalation exit (§4).

---

## 1. The Stage Inventory

Every sprint lives in `_sprints/SP_NNN/` (scaffold with `agv3:INIT`) and draws stages from
this inventory. A **track** (§2) selects which stages actually run.

| Stage | Sub-skills | Produces |
|-------|-----------|----------|
| **INIT** | `agv3:INIT` | `_sprints/SP_NNN/` (dirs + seeded `disc.init.md` + `logs/ledger.json`) |
| **DISCUSS** | `agv3:DISCUSS-1-init`, `agv3:DISCUSS-2-review` | `docs/disc.init.md` — elicited, structured requirements |
| **CODESCAN** *(new; brownfield only)* | `agv3:CODESCAN` | `docs/codebase_guide.md` — architecture + conventions + gotchas map |
| **EXPLORE** *(FULL only)* | `agv3:EXPLORE` | `docs/survey/00_survey_report.md` — risk/feasibility survey (agent count scaled to complexity) |
| **SPIKE** *(new; on-demand)* | `agv3:SPIKE` | `docs/spike/spike_report.md` — de-risks ONE named unknown |
| **SPEC** | `agv3:SPEC-1-init`, `agv3:SPEC-2-review`, `agv3:SPEC-3-fix` | `docs/spec.init.X.md` — implementation-ready dev spec |
| **TICKET** | `agv3:TKT-1-init`, `agv3:TKT-2-review`, `agv3:TKT-3-fix` | `tasks/` — dependency-graphed self-contained task files + `index.json` |
| **DEV** | `agv3:DEV-2-coding` (per task), `agv3:DEV-5-wave-review` (per wave), `agv3:DEV-3-retro-init`, `agv3:DEV-4-retro-fix` | Implemented code + `reports/*.coding.md` + `*.retro.md` |
| **REVIEW** | `agv3:REVIEW-1-init`, `agv3:REVIEW-2-fix` | Adversarial audit of shipped code against `spec.init.X.md` |
| **LEARN** *(new; manual, cross-sprint)* | `agv3:LEARN` | Proposed diffs to `SKILL.md` files from mined `lessons.md` |

> **Dispatch is your job, not a sub-skill's.** There is no `agv3:DEV-1`. Reading `index.json`'s
> `execution_orders`, launching one `agv3:DEV-2-coding` agent per ready task per wave, and running
> `agv3:DEV-5-wave-review` after each wave completes — that is orchestration, which is you.

---

## 2. Tracks — pick the lightest that covers the risk

**Before anything runs, classify the request into a track and declare it with a one-line
rationale.** This is the answer to "how does day-to-day work use this without ceremony."

- **Track A — INSTANT** (one-line fix, config tweak, copy change, obvious single-file bug):
  `DISCUSS-lite (inline, 1 round)` → `DEV` → `self-check`.
  No sprint scaffolding unless the change touches >1 file. Target: minutes, one agent, no fan-out.
- **Track B — STANDARD** *(the default for day-to-day work: a normal feature or bugfix in an
  existing codebase)*:
  `DISCUSS` → `CODESCAN` (if brownfield) → `SPEC` → `TICKET` → `DEV` (+ per-wave review) → `REVIEW`.
  **Skips EXPLORE and SPIKE by default.**
- **Track C — FULL** (greenfield service; ambiguous / high-stakes / compliance / integration risk):
  `DISCUSS` → `EXPLORE` → `SPIKE` (on-demand) → `CODESCAN` (if brownfield) → `SPEC` → `TICKET`
  → `DEV` (+ per-wave review) → `REVIEW`. **Human gates mandatory at SPEC and TICKET approval.**

### Grounded triage checklist (not a vibes call)

Score the request against these; the more that are true / heavier, the higher the track:
- New code vs. change to existing code? (existing ⇒ CODESCAN required)
- External/public surface, API contract, or data migration?
- Auth / PII / payments / security or compliance exposure?
- Touches more than ~a handful of files, or multiple components/services?
- Is the change hard to reverse?
- Are the requirements ambiguous or contested?

Roughly: **0 heavy signals → INSTANT. A few → STANDARD. Any high-stakes/greenfield/ambiguous
signal → FULL.** Record the track in `logs/ledger.json`.

> **v3 rejects "when in doubt, don't skip."** In doubt, pick the track the checklist indicates
> and let escalation (§6) catch an under-call — an unnecessary FULL pass wastes real budget too.

---

## 3. The Quality-Gate Loop (unchanged shape, now bounded)

Every stage that produces a durable artifact follows: **Generate → Review → Rate (1–10) →
Iterate/Fix**, as an `*-1-init` / `*-2-review` (/ `-3-fix`) skill pair.

1. Call `*-init` (or `*-fix`) to generate/repair the artifact. **You allocate its output path**
   via `agv3/lib/alloc_version.js` and pass the concrete path in — the skill never numbers itself.
2. Call the paired `*-review` skill. Review skills are adversarial and **read-only** — they write
   a numbered report with a `quality_score`, never edit the artifact.
3. **Hard rule: an artifact cannot advance until its `quality_score` reaches its `pass_score`
   (default $pass_score).** Below it, call `*-fix` against the review report, then re-review.
4. Don't skip the review because it "looks fine" — your judgment is what the review exists to check.

**Per-stage rubrics (v3):** each review skill uses its OWN 3–5 dimension rubric, not a universal
5-category one. A survey and a task graph are not judged on the same axes. See each skill.

**Deterministic pre-gate (v3):** objectively checkable failures are caught by a script *before*
the LLM scores, and mechanically cap the score below pass. TICKET calls `agv3/lib/validate_index.js`
(exit 1 ⇒ capped). This is what keeps the self-graded gate honest on the objective layer.

**Versioning:** every artifact is a new numbered file; never overwrite; always feed the *largest
existing X* of each input forward. This is what makes every round independently auditable.

---

## 4. Iteration caps + escalation

Every review↔fix loop is bounded by `max_rounds` (default $max_rounds):
- **On cap hit:** stop. Emit the situation to the user via the sub-skill's `LOOPER_FEEDBACK`
  with a **disagreement diff** (what the reviewer keeps flagging vs. what the fixer keeps doing).
  Do not loop forever.
- **Convergence detector:** if two consecutive rounds produce the same score with overlapping
  findings, escalate early — a standoff won't resolve itself by spending the remaining rounds.
- This closes v1's biggest omission: only DISCUSS had a round limit; SPEC/TKT/REVIEW could thrash.

---

## 5. Deterministic validation layer

Do NOT ask an LLM to verify what a script verifies perfectly and instantly:
- **`agv3/lib/validate_index.js <index.json>`** — schema, dependency symmetry, cycle detection,
  wave-number math, `execution_orders` id integrity (every task exactly once). TKT.2 gates on it.
- **`agv3/lib/alloc_version.js <dir> "<prefix>.{N}.<ext>"`** — YOU call this to allocate the next
  version path (and `--current` to resolve the largest existing input). Agents never self-number,
  killing the parallel numbering race.
- **`agv3/lib/config.json`** — single source for `output_lang` (English), `pass_score`, `max_rounds`,
  track budgets, headless policy, CODESCAN thresholds.

---

## 6. Re-entry / rollback playbook

When a downstream stage invalidates an upstream artifact, re-enter surgically — don't restart:
- **DEV finds the spec unimplementable** → freeze the affected tasks, re-enter at SPEC for the
  affected slice only, re-run TICKET for the changed tasks, resume DEV. Log a `reentry.log` line.
- **CODESCAN / EXPLORE surfaces a landmine** → promote the track (B→C), backfill the missing stage.
- **REVIEW finds a systemic defect** → re-enter at the stage that owns the root cause (spec vs.
  task vs. code), not a local patch.
- **Triage under-called complexity** (e.g. SPEC turns up open questions on a "trivial" task) →
  promote track, backfill EXPLORE/CODESCAN.
Every re-entry appends one line to `logs/reentry.log` and increments `totals.reentries` in the ledger.

---

## 7. Budget & telemetry

Each stage appends a structural record to `logs/ledger.json` (stage, rounds used, agents spawned,
final score). No token/$ accounting in v3.0 — structural counts only. Each track has a soft budget
ceiling (agent-count / round-count in `config.json`). **On overrun, raise `LOOPER_FEEDBACK`:**
"this is costing more than its track expects — continue, or downgrade?" Runaway is impossible
without a human OK. This grounds triage in a number instead of a vibe.

---

## 8. Human checkpoints & headless policy

- **Interactive, Track C:** human approval is **mandatory at SPEC approval and TICKET approval**
  (the two artifacts every downstream stage inherits blindly). Route these through `LOOPER_FEEDBACK`.
- **Interactive, Track A/B:** the numeric gate governs; surface FEEDBACK only when a skill asks.
- **Headless mode:** Track A/B may run end-to-end unattended after DISCUSS; **Track C is not
  headless-safe** (its SPEC/TICKET gates need a human). In headless mode, cap-hit, budget-hit, and
  red tests all follow **halt-and-report**: stop, preserve best-effort artifacts, write a report
  explaining the halt. **Never silently ship unreviewed code.**
- **Verification is executed, not asserted:** DEV must actually run build + tests + lint (using the
  commands CODESCAN recorded) and read real output. "Done" means deterministic gates + the spec's
  acceptance criteria pass — not the agent's self-belief.

---

## 9. Just-in-Time Skill Invocation

- **Call the sub-skill; never reimplement it.** If you're about to draft a spec, task list, or
  review yourself instead of invoking the skill — stop. That is the one thing this master prevents.
- **Resolve which skill to call** from the stage inventory (§1) + where the sprint is (which
  artifacts exist on disk) + the track (§2).
- **Every sub-skill takes `sprint_name`**; you additionally hand it concrete allocated output paths
  (§5). It resolves its own largest-version inputs.
- **Parse the LOOPER JSON envelope, don't just read prose:** `LOOPER_COMPLETE` → stage done
  (proceed per the gate); `LOOPER_FEEDBACK` → needs the user (surface and pause — not an error);
  `LOOPER_BLOCKED` → hard blocker (relay explanation + resolution verbatim).
- **Parallel fan-out stages (EXPLORE, CODESCAN ARCH-FIRST) manage their own subagents**, scaled to
  complexity. You invoke once; they handle dispatch, DONE-polling, retry-once-on-empty.
- **All artifacts are English** (`output_lang` in config). No per-skill language override.

---

## 10. Quick operating recipe

1. `agv3:INIT` (unless INSTANT single-file) → sprint scaffolded.
2. Triage → declare track + rationale → write track to ledger.
3. Run the track's stages in order, driving each `*-init → *-review → *-fix` loop to `pass_score`,
   bounded by `max_rounds`, allocating versions yourself.
4. At each stage boundary: check the ledger against the track budget; honor human gates (Track C
   SPEC/TICKET); watch for re-entry signals.
5. DEV: dispatch `index.json` waves; per task run `agv3:DEV-2-coding`; after each wave run
   `agv3:DEV-5-wave-review`; then `agv3:DEV-3-retro-init` / `-4-retro-fix`.
6. `agv3:REVIEW-1-init` / `-2-fix` against the spec.
7. Optionally, across sprints, run `agv3:LEARN` to fold recurring lessons back into the skills.
