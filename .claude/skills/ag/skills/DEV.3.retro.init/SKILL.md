---
description: DEV stage (6), per-task retrospective. Runs a pre-mortem on the task DEV.2.coding just finished (assume it already failed in prod; find why) and produces a fix-ready retro doc plus shared lessons. Dispatched once per completed task by the orchestrator's DEV-stage runner, after DEV.2.coding writes its report and before DEV.4.retro.fix acts on the findings.
arguments: [sprint_name][task_doc]
---

> **AgentFlow v3 conventions** (see `agv3/CONVENTIONS.md`). Write all artifacts in **English**.
> The orchestrator **allocates your output path** (via `agv3/lib/alloc_version.js`) and passes it in —
> never compute a version integer yourself. Resolve inputs to the **largest existing version**; inputs are
> immutable (new version, never overwrite). Scoring uses **this stage's own rubric**, capped at `pass_score`
> (default 9); each review↔fix loop is bounded by `max_rounds` (default 3), after which you escalate via
> `LOOPER_FEEDBACK` with a disagreement diff instead of looping. Objective failures caught by a deterministic
> pre-gate mechanically cap the score below pass.



<files_required>

	## Inputs:

		- `current_sprint`: $sprint_name

		- `task_dir`: $current_sprint/tasks

		- `task_doc`: $current_sprint/tasks/$task_doc

		- `task_report`: $current_sprint/reports/<base name of $task_doc with `.coding` inserted before the extension> (e.g., `t001_scaffold.coding.md`). May not exist.

		* For any input that is versioned (`.X.md` in the name), read the LARGEST existing X, and choose only one.

	* On missing inputs: retry with different search approaches. If a required input is still not found, stop and report.

	* MUST NEVER modify, edit, revise, or delete any input file. Every write is a new file.

	## Output:

		- `retro_doc`: `$current_sprint/reports/<base name of $task_doc with `.retro` inserted before the extension>` (e.g., `t001_scaffold.retro.md`)

</files_required>

---

# Role

You are performing a **post-implementation retrospective** with a pre-mortem mindset: assume this code has already failed in production, and figure out why.

Do not justify decisions — evaluate outcomes. You know what you were actually thinking: where you hesitated, what you misread, what you were unsure about, what you cut corners on, and what you quietly hoped nobody would notice. Surface all of it.

Read `$task_doc` (the original spec and requirements) and `$task_report` (the coding agent's execution report and self-review — may not exist) in full before doing anything else.

If a `$step_summary` from a continued/compacted session is available, use it to reconstruct what happened during implementation. If it is not available, fall back to `$task_doc` and `$task_report` as your basis — do not stop for its absence.

---

## Step 1 — Triage

Write exactly `RETRO SKIPPED: trivial task, no findings.` and stop when **all** of these hold:
- Single, isolated change (one function, one config, one rename)
- No ambiguity in the spec
- No edge cases, side effects, or integration concerns
- Nothing to confess — execution was clean and unremarkable

**Borderline** → fast-track: populate only the sections with real signal, skip the rest.

**Otherwise** → full retro below.

---

## Step 2 — The Honest Dump

Write a raw account of your actual experience — not a summary. No softening, no justifying. These are raw inputs for the synthesis in Step 3. Answer each prompt bluntly; skip any sub-section that has nothing real to say.

### Confusion & Misreads
- What did you misunderstand about the spec, even briefly?
- What did you interpret loosely because it was unclear?
- What assumptions turned out false or fragile?
- What did you have to re-read, reconsider, or correct mid-task?

### Uncertainty & Guesses
- Where did you make a call without being sure it was right?
- Where did you think "this is probably fine" rather than "this is definitely correct"?
- What did you assume rather than verify?

### Friction & Rework
- What took longer than expected, and why?
- Where did you backtrack, and what caused it?
- Which early decisions created the most downstream cost?

### Shortcuts & Deferred Work
- What did you simplify, defer, or skip?
- What shortcuts should *not* be repeated?
- What's held together more loosely than you'd like?

### Planning & Sequencing
- Which requirements should have been locked down before writing any code?
- What should have been spiked or prototyped first?
- Where did you write code before validating assumptions?

### Code Quality
- Which abstractions helped? Which added complexity or coupling?
- What logic looks correct but is actually fragile?
- Where did you create avoidable technical debt?
- What would you deliberately *not* build if you started over?

### Quiet Concerns
- What did you complete but still feel uncertain about?
- What would make you nervous if a senior engineer looked closely?
- What did you leave out of the implementation report, and why?

---

## Step 3 — Synthesis

From the dump, produce:

- **Top risks** — what could actually break, and under what conditions
- **Action items** — concrete things to do before this ships or shortly after: `- [ ] Item — owner — urgency`
- **One-line verdict** — ship as-is, ship with caveats, or hold

---

## Output 1: `$retro_doc`

Write `$retro_doc` as a **fully self-contained brief for a fix agent** that has not read the spec, the coding report, or this retro. It must contain everything the fix agent needs — do not reference external documents.

The counts in the header MUST match the number of findings you list under each corresponding section.

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

Rules:
- Every finding is specific and actionable — no vague complaints, and enough context that the fix agent needs nothing else.
- Omit any severity tier that has nothing real in it (and set its count to 0).
- This doc is for fixing only — do not describe what was done correctly.

---

## Output 2: Lessons learned

<LESSONS_PROTOCOL>

<files_required>

	- `location`:
			1. If you are inside a worktree on a feature branch, use it.
			2. Otherwise use the root of the current working directory.

	- `lessons_doc`: `$location/lessons.md` (create if absent)

</files_required>

# Goals

Whenever you hit a mistake or discover an insight that could benefit the whole team, add a lessons-learned entry to `$lessons_doc` promptly — do not batch them at the end.

`$lessons_doc` is a living reference read by all future coding agents across all tasks, so write only what is **transferable**: high-level patterns, failure modes, and judgment calls that apply beyond this specific task.

Prepend each entry (newest-first), separated by `---`:

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

Rules:
- Only write entries that generalize — skip pure one-off trivia.
- Prefer one rich entry over three shallow ones on the same theme.
- No flattery, no "despite good intentions" softening — future agents need signal.
- The Step 2 Honest Dump is usually the best source: honest confusion and quiet concerns are where the real patterns live.

</LESSONS_PROTOCOL>

---

## Machine-Readable Response Contract

Respond with **pretty-formatted JSON only** — no code fences, no prose around it. Any non-JSON output is invalid.

### `code` must be exactly one of:

* `LOOPER_COMPLETE` — the workflow ran to completion. Use this even if the retro records serious findings: completing the retro counts as complete regardless of what it found.

* `LOOPER_FEEDBACK` — use when you need user permission, approval, confirmation, or clarification to continue. MUST NOT use `LOOPER_BLOCKED` for these.

* `LOOPER_BLOCKED` — use for a true hard blocker: missing required files/data, credentials, invalid inputs, or environmental constraints that prevent continuation. Include a detailed explanation and concrete resolution steps. MUST NOT use this code to ask for user approval or permission — use `LOOPER_FEEDBACK` instead.

Schema (append `quality_score` only if already calculated):

{
	"quality_score": <integer 1-10, optional>,

	"code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",

	"stop_reason": "<short human-readable status or 'none'>",

	"input": [ /* input variables or absolute file paths used this turn, or [] */ ],

	"output": [ /* absolute file paths written/updated this turn, or [] */ ]
}
