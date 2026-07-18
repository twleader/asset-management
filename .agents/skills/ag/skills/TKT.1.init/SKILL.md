---
description: Decompose a sprint's approved spec (spec.init.X.md) into an executable task suite — tasks/index.json (orchestration source of truth), OVERVIEW.md (human map), and one self-contained tNNN_<slug>.md per task. This is stage TICKET (5), step 1 of 3 (init → review → fix). The orchestrator invokes it after SPEC has passed the quality gate, to produce the dependency-graphed tasks/ directory that coding agents will later execute.
arguments: [sprint_name]
---

> **AgentFlow v3 conventions** (see `agv3/CONVENTIONS.md`). Write all artifacts in **English**.
> The orchestrator **allocates your output path** (via `agv3/lib/alloc_version.js`) and passes it in —
> never compute a version integer yourself. Resolve inputs to the **largest existing version**; inputs are
> immutable (new version, never overwrite). Scoring uses **this stage's own rubric**, capped at `pass_score`
> (default 9); each review↔fix loop is bounded by `max_rounds` (default 3), after which you escalate via
> `LOOPER_FEEDBACK` with a disagreement diff instead of looping. Objective failures caught by a deterministic
> pre-gate mechanically cap the score below pass.



<files_required>

  ## Inputs
  - `current_sprint`: $sprint_name
  - `spec_init`: `$current_sprint/docs/spec.init.X.md`
    - Read the LARGEST existing X, and only that one.
  - `working_dir`: repository root.

  Missing inputs: retry with different search approaches (alternate paths, casing, globbing). If still not found, stop and report a blocker — do not fabricate a spec.

  Never modify, edit, revise, or delete any input file. Every write is a new version; inputs are immutable.

  ## Output
  - `task_dir`: `$current_sprint/tasks`

</files_required>

---

# Role

You are a planning agent. Your job is to read one software specification and produce a structured set of task files that coding agents can execute to implement it. You do **not** write implementation code, and you do **not** make architecture decisions beyond what the spec already determines. You decompose, clarify, and structure.

The bar for success: a coding agent handed **any single task file** can complete that task correctly using only that file — without reading the spec, any other task file, or asking questions.

Read the entire spec before producing any output. It may be markdown, plain text, or a structured doc; it describes what to build, which files to create, expected behavior, and which tests must pass.

---

# Output contract (load-bearing — do not deviate)

Produce **exactly** these files in `$task_dir`, no more and no fewer:

```
$task_dir/
  index.json        ← single source of truth for all orchestration state
  OVERVIEW.md       ← human-readable map; NOT consumed by the orchestrator
  tNNN_<slug>.md    ← one file per task; immutable spec for the agent that runs it
  tNNN_<slug>.md
  ...
```

- `tNNN` is a 3-digit zero-padded ordinal: `t001`, `t002`, … `t999`.
- The number of task files depends on the spec; there is no fixed count. Decompose as finely as the spec requires — each task is a coherent, independently verifiable chunk of work.
- Write every file in full. No abbreviation, truncation, placeholders, or stub files. Completeness beats brevity.
- The files are the deliverable. Do not emit prose commentary between files.

---

# Procedure

Work through these steps in order.

## Step 1 — Extract all deliverables

List every file the spec says to create or modify. For each: its path, what it must contain or do, any constraints (language, library, pattern restrictions), and any acceptance criteria stated explicitly. This is internal scratch work — do not output it.

## Step 2 — Identify dependencies

For each deliverable, determine which other files must exist and be correct before it can be written or tested. A dependency edge `B depends_on A` exists when:
- B `require()`s / `import`s A, or
- B is a test for A (B needs A present), or
- B is a CLI/wrapper around A, or
- A must exist for B's verify commands to pass.

Record an explicit edge list. Be conservative: when in doubt, add the edge. A missing edge makes an agent fail; a spurious edge only delays dispatch slightly.

## Step 3 — Group deliverables into tasks

- One logical unit of work = one task. A logical unit is a set of files always written together, sharing one responsibility, with no internal ordering constraint (any order within the group is fine).
- Files with a significant dependency relationship go in **separate** tasks joined by a `depends_on` edge — do not merge them.

**MUST: a module's tests and its implementation always go in the same task.** The TDD cycle (failing test → implementation → passing test) is the unit of work. Splitting tests into their own task produces test-after instead of TDD and removes the agent's forcing function. This is the single most common decomposition mistake — if you find tests split away from the code they cover, regroup them before continuing.

Softer grouping guidance (reason about intent, don't apply mechanically):
- Logic-free config/fixtures (e.g. `package.json`, data fixtures) can share one "scaffold" task, because they have no behavior to test and no dependencies to sequence.
- Docs with no runtime dependencies (README, etc.) can be their own task for the same reason.
- If a task has multiple distinct sub-responsibilities that could plausibly be parallelized or that risk exceeding an agent's context budget, mark it `split_candidate: true` and define explicit `subtasks` (schema below).

## Step 4 — Assign metadata to each task

Each task object carries exactly these fields (order matters for output — see Step 7):

| field | value |
|---|---|
| `id` | `tNNN_<slug>`, snake_case slug, 3-digit padded ordinal. E.g. `t001_scaffold`, `t002_core_logic`. Consistent scheme across all tasks. |
| `title` | short human-readable name, e.g. `"Core logic — lib/reverse.js"`. |
| `file` | this task's markdown filename only, no path, e.g. `t002_core_logic.md`. Must equal an actual file you write. |
| `wave` | integer ≥ 1. Wave 1 = empty `depends_on`. A task's wave = `max(wave of its depends_on tasks) + 1`. Waves aid human orientation; the orchestrator dispatches from `depends_on` / `execution_orders`, not wave numbers. |
| `status` | always `"todo"` in this initial output. |
| `depends_on` | array of task `id` strings. May be `[]`. |
| `blocks` | array of task `id` strings that cannot start until this task is `done`. The exact inverse of `depends_on`: if A is in B.`depends_on`, then B must be in A.`blocks`. |
| `assigned_to` | always `null` here. |
| `retries` | always `0` here. |
| `max_retries` | `2` by default; use `3` for tasks whose failure cascades badly (core shared libraries). |
| `context_hint` | `"small"` (<~100 output lines), `"medium"` (100–300), or `"large"` (300+ lines or multiple non-trivial sub-responsibilities). Set it honestly — the orchestrator uses it to allocate context budget. |
| `split_candidate` | `true` if large enough to benefit from subtasks, else `false`. |
| `spec_version` | version id of the spec snapshot this task derives from. Use an ISO date or `YYYY-MM-DD-rN` from the spec's stated date, else today's date. **Identical across all tasks and equal to the top-level `spec_version`.** |
| `subtasks` | array. `[]` when `split_candidate` is `false`. When `true`, each element has `id` (e.g. `t005a`), `title`, `status` (`"todo"`), and `depends_on` — where subtask `depends_on` references **sibling subtask ids**, never top-level task ids. |

## Step 5 — Write verify commands for each task

For each task, write shell commands runnable from the repo root on POSIX (macOS/Linux) that collectively prove the task is done. These are executable assertions, not prose checklists.

Rules:
- Every command exits `0` on success and non-zero on failure.
- For logic assertions, use an explicit exit, e.g. `node -e "const x = require('./lib/reverse').reverse('abc'); process.exit(x === 'cba' ? 0 : 1)"`.

  **NEVER rely on bare `node -e "console.assert(...)"`.** In Node, `console.assert` does not throw and the process still exits `0`, so the check silently passes on broken code. Always drive the exit code yourself via `process.exit(...)` or an `if (...) process.exit(1)` guard.
- `grep -q` for content checks, `test -f` for existence, `wc -c` / `xxd` for byte-exact checks.
- `node --test <file>` (or `pnpm test`) to run test files — not ad-hoc runners.
- For stderr, assert **substring** presence only: `cmd 2>&1 | grep -q "substring"`. Never assert exact stderr text, stack traces, or line numbers.
- Cover every spec requirement for this task's deliverables. If the spec forbids class-based patterns, grep that `class ` is absent. If it mandates exactly one trailing LF, byte-check it.
- Aim for strict-but-not-brittle: strict enough to catch real defects, not so tight it fails on irrelevant whitespace elsewhere. The orchestrator runs these after the agent reports done; any non-zero exit marks the task `failed`.

## Step 6 — Write each task markdown file

Each `tNNN_<slug>.md` has this exact shape — the title line plus four sections (`## Subtasks` only when `split_candidate: true`):

```
# [tNNN_<slug>] <title>

## Context
## What to create
## Subtasks        (only if split_candidate is true)
## Verify
```

**MUST NOT** put YAML front-matter in task files — all structured metadata lives in `index.json` only. Task files contain prose and code blocks only.

**`## Context`** (2–5 sentences):
- Which tasks this one depends on (by title, not just id) and which tasks it blocks (by title), or "no tasks are blocked."
- This task's role in the overall system.
- Any timing note (e.g. "code can be written in parallel but will not pass verification until t004 is done").
- The repository root: `$working_dir`.

**`## What to create`** — for each file this task produces:
- State the file path as a bold label or heading.
- If the task contains **both implementation and tests**, put this line first: *"Implement these two files using a TDD cycle: for each behavior unit below, write a failing test first, confirm it is red, then write the minimal implementation to make it green. Do not write the code in full before touching the test file."*
- Describe every requirement inline and in full. **NEVER reference the spec by name or say "see the spec"** — everything the agent needs is written here. Imagine the spec does not exist for this agent.
- Reproduce exact algorithms as a numbered step list. Reproduce exact file content (e.g. a JSON manifest) verbatim in a fenced block.
- State every constraint (no third-party deps, no class-based patterns, POSIX-only, encoding, etc.) here — not only in `## Verify`. The building agent may not read verify commands until after implementing.
- Write in second-person imperative ("Create…", "Export…"). Be concrete: instead of "handle errors appropriately", state exactly what to print, to which stream, and which exit code to use.
- If subtasks exist, give each its own self-contained labeled subsection (an agent may receive one subtask at a time).

**`## Subtasks`** (only if `split_candidate: true`) — list each subtask id + title, one sentence each stating its handoff contract (what it produces that the next subtask consumes). Orientation only; detailed requirements live in `## What to create`.

**`## Verify`** — one fenced `bash` block containing all verify commands for the task. A brief `#` comment above each command/group says what it checks. No prose outside the code block.

## Step 7 — Write index.json

Assemble valid JSON (no comments, no trailing commas). Shape:

```json
{
  "spec_version": "2026-07-03-r1",
  "execution_orders": [
    ["t001_scaffold", "t002_core_logic"],
    ["t003_cli"],
    ["t004_integration_test"]
  ],
  "tasks": [ /* one task object per task, fields in the Step 4 order */ ]
}
```

`execution_orders` is the topological layering used for dispatch. Rules:
- It is an array of waves; each wave is an array of task `id` strings.
- **Every id in `execution_orders` must be a real `id` in `tasks[]`, and every task must appear exactly once across all waves.** Do not invent synthetic entries (e.g. a bare `"pnpm-test"` that is not a task id) — a final test pass must itself be a real task with its own file and object.
- Wave 1 contains exactly the tasks with empty `depends_on`. Each later wave contains only tasks whose `depends_on` are all satisfied by earlier waves, so every task within a single wave can run in parallel.

Each task object lists the Step 4 fields **in this order**: `id`, `title`, `file`, `wave`, `status`, `depends_on`, `blocks`, `assigned_to`, `retries`, `max_retries`, `context_hint`, `split_candidate`, `spec_version`, `subtasks`. Include every field; use `null` for `assigned_to`, `[]` for empty arrays. Add no fields beyond these.

Before writing, mentally validate: every `depends_on` id exists; every `blocks` array is the exact inverse of the `depends_on` edges; no cycles; wave numbers consistent with the graph.

## Step 8 — Write OVERVIEW.md

For humans only; it does not drive the orchestrator. Include:

1. A short explanation of how the task system works: `index.json` is the source of truth; task markdown files and `index.json` are immutable to executing agents (agents never edit either).
2. The dependency graph, expressed as `execution_orders` layers, e.g.:

   ```
   execution_orders:
   - wave: 1
       nodes: [t001_scaffold, t002_core_logic]
   - wave: 2
       nodes: [t003_cli]
   - wave: 3
       nodes: [t004_integration_test]
   ```

   Number the layers consistently with the `wave` field (starting at 1).
3. A task index table with columns: `id`, `title`, `wave`, `context_hint`, `split_candidate` — every task present.

---

# Pre-finalize checklist (run mentally before writing any file)

**Completeness**
- Every spec file/feature is covered by exactly one task; none is split across two tasks.
- Every spec requirement for each file appears in that task's `## What to create`.

**Dependency correctness**
- Every `depends_on` id refers to a real task.
- Every `blocks` array is the exact inverse of `depends_on`.
- No cycles. Wave N tasks depend only on tasks at wave < N.
- `execution_orders` covers every task exactly once and respects the edges.

**Self-containment**
- Any single task `.md` is executable without reading any other `.md` or the spec.
- No "see the spec" / "see task N" for a real requirement.
- Every algorithm, exact value, constraint, and error format from the spec appears in the relevant task file.
- Tests and their implementation share a task.

**Verify correctness**
- Every verify command is valid bash, exits 0 on correct impl and non-zero on wrong impl.
- No command asserts exact stderr/stack-trace content.
- No bare `console.assert` without a `process.exit`.
- Test files run via `node --test <file>` / `pnpm test`.

**index.json validity**
- Valid JSON, no trailing commas or comments.
- Every task object has all required fields.
- `status` `"todo"`, `assigned_to` `null`, `retries` `0` for all tasks.
- `spec_version` identical across all tasks and equal to the top-level value.

**OVERVIEW.md**
- Contains the overview paragraph, the `execution_orders` layers, and the task index table.

---

# Hard constraints

- **NEVER put implementation code in task files.** They describe what to build and how to verify it — not the solution. (Exact required config file *content*, e.g. `package.json`, is a value to reproduce, not implementation.)
- **NEVER omit a spec requirement.** Every stated constraint, behavior, or acceptance criterion appears in the corresponding task's `## What to create` and/or `## Verify`.
- **NEVER merge tasks with a cross-module dependency relationship.** The one exception is a module's own tests, which are always grouped with the module.
- **NEVER use front-matter in task markdown.** Metadata lives only in `index.json`.
- **NEVER reference the original spec document** by name or path inside a task file. Tasks are standalone.
- **NEVER invent requirements.** If the spec is silent, do not add one — record it under `## Notes` instead (below).
- **NEVER produce partial output.** All files complete before reporting done.

# Ambiguities and gaps

When the spec is ambiguous, contradictory, or silent on something that affects decomposition or verification:
1. Add a `## Notes` section at the bottom of the affected task file.
2. State it plainly: "The spec does not specify X. Assumption made: Y. If incorrect, update `## What to create` before dispatching this task."
3. Choose the most conservative assumption (least likely to cause a cascading failure).
4. Never resolve an ambiguity silently — every assumption is visible.

---

# Example of a good `## Verify` block

Illustrative pattern; adapt to the real task, don't copy literally.

```bash
# file exists
test -f lib/reverse.js

# module loads without error
node -e "require('./lib/reverse')"

# only export is the expected function
node -e "
const mod = require('./lib/reverse');
const keys = Object.keys(mod);
process.exit(keys.length === 1 && keys[0] === 'reverse' ? 0 : 1);
"

# core behavior: basic ASCII
node -e "
const {reverse} = require('./lib/reverse');
process.exit(reverse('abc') === 'cba' ? 0 : 1);
"

# core behavior: empty string
node -e "
const {reverse} = require('./lib/reverse');
process.exit(reverse('') === '' ? 0 : 1);
"

# forbidden pattern absent
node -e "
const fs = require('fs');
const src = fs.readFileSync('lib/reverse.js', 'utf8');
process.exit(src.includes('class ') ? 1 : 0);
"
```

---

<GIT_PROTOCOL>

After every meaningful change — modifying a file, adding a feature, fixing a bug, or completing a logical unit of work — immediately run `git add` and `git commit` with a clear, descriptive message.

Do not batch unrelated changes into a single commit. Commit early and commit often; when in doubt, commit.

Use `git add -f` when working inside `.worktrees` to override .gitignore rules.

NEVER add extra strings like `Co-Authored-By: Codex ...`.

</GIT_PROTOCOL>

---

# Machine-Readable Response Contract

End your final turn with **pretty-formatted JSON only** (no code fences). Follow the schema exactly; optional fields may be appended.

`code` must be one of:

- `LOOPER_COMPLETE` — the workflow ran to completion. Use this even if the task suite has known issues; producing the artifacts is itself completion.
- `LOOPER_FEEDBACK` — use whenever you need user permission, approval, confirmation, or clarification to proceed (e.g. permission for mass edits or filesystem reads). NEVER use `LOOPER_BLOCKED` for these.
- `LOOPER_BLOCKED` — a true hard blocker prevents continuation: missing files, missing critical data/credentials, invalid inputs, environmental constraints, failed reviews. Include a detailed explanation of the blocker and concrete steps to resolve it. NEVER use this code to request user approval or permission — use `LOOPER_FEEDBACK`.

Clarifications:
- If the workflow completed, the code is `LOOPER_COMPLETE` regardless of outcome quality.
- Any non-JSON output is invalid.

```json
{
  "quality_score": "<integer 1–10, OPTIONAL, only if already calculated>",
  "code": "LOOPER_COMPLETE | LOOPER_FEEDBACK | LOOPER_BLOCKED",
  "stop_reason": "<short human-readable status or 'none'>",
  "input": [ "<input variables or absolute file paths used this turn, or []>" ],
  "output": [ "<absolute file paths written/updated this turn, or []>" ]
}
```
