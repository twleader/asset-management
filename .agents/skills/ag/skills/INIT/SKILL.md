---
description: AgentFlow pipeline stage 0 (INIT). One-shot bootstrap that creates the next-numbered sprint directory (`_sprints/SP_NNN/`) and its subdirectories via `lib/init.js`, seeding `docs/disc.init.md` from the template. Invoked by the AgentFlow orchestrator as the very first step of a sprint, before agv3:DISCUSS-1-init and every other stage.
---

> **AgentFlow v3 conventions** (see `agv3/CONVENTIONS.md`). Write all artifacts in **English**.
> The orchestrator **allocates your output path** (via `agv3/lib/alloc_version.js`) and passes it in —
> never compute a version integer yourself. Resolve inputs to the **largest existing version**; inputs are
> immutable (new version, never overwrite). Scoring uses **this stage's own rubric**, capped at `pass_score`
> (default 9); each review↔fix loop is bounded by `max_rounds` (default 3), after which you escalate via
> `LOOPER_FEEDBACK` with a disagreement diff instead of looping. Objective failures caught by a deterministic
> pre-gate mechanically cap the score below pass.


## Role

You are the AgentFlow bootstrap step. Your only job is to create the sprint directory that every downstream stage depends on, then confirm it exists. Do exactly this — do not start discussion, exploration, or any other stage work.

You are pre-authorized to use all repository/filesystem tools and to create/switch branches. Do not pause to ask for permission for these setup actions.

---

## Step 1 — Choose the sprint name

Scan the `_sprints/` directory (under the project working directory) for existing sprints named `SP_NNN` (zero-padded 3-digit integer). Pick the **next unused** number:

- If `_sprints/` is empty or absent → `SP_001`.
- If the largest existing is `SP_004` → create `SP_005`.

The chosen id must be strictly greater than every existing `SP_NNN`. A colliding or out-of-sequence number would corrupt the `sprint_name` every later stage keys off of, so verify against the actual directory listing rather than assuming.

## Step 2 — Create the sprint

Call `create_sprint(base_dir, sprint_name)` from `lib/init.js`:

- `base_dir` = the project working directory (`$cwd`). `create_sprint` resolves `_sprints/` under this, so pass the project root, **not** a path that already ends in `_sprints`.
- `sprint_name` = the id chosen in Step 1 (e.g. `SP_005`).

```bash
node -e "require('<ABS_PATH_TO>/lib/init.js').create_sprint('<base_dir>', '<sprint_name>')"
```

`lib/init.js` lives two levels up from this SKILL file (`skills/INIT/SKILL.md` → `../../lib/init.js`). **`node -e` resolves `require()` relative to the current working directory, not to this file** — so pass an absolute path to `init.js` (or a path correct relative to `$cwd`). Using the literal string `../../lib/init.js` only works when `$cwd` happens to be `skills/INIT/`, which is generally not the case; resolve the real path first.

## What `create_sprint` produces on disk

Running it creates:

```
_sprints/SP_NNN/
  docs/     ← docs/disc.init.md is seeded here from lib/template.md (the requirements form for the user to fill in)
  tasks/
  compact/
  reports/
  logs/
```

The seeded `docs/disc.init.md` is the requirements draft the user edits; agv3:DISCUSS refines it **in place** as a single living document (its review rounds are the separate versioned `disc.review.X.md` files — the discussion doc itself is never versioned), and agv3:EXPLORE / agv3:SPEC consume `docs/disc.init.md` directly. `logs/ledger.json` (structural telemetry) and `logs/reentry.log` are also seeded. Confirm the sprint directory and `docs/disc.init.md` exist after the command returns before reporting completion.

---

## Machine-Readable Response Contract

End your final turn with **pretty-formatted JSON only** (no code fences), so the orchestrator can confirm the sprint directory now exists before dispatching agv3:DISCUSS-1-init.

### `code` must be one of:

- `LOOPER_COMPLETE` — the sprint directory (and `docs/disc.init.md`) was created successfully.
- `LOOPER_FEEDBACK` — you need user permission, confirmation, or clarification to proceed. Use this (never `LOOPER_BLOCKED`) for any approval/input request.
- `LOOPER_BLOCKED` — a genuine hard blocker prevents creation (e.g. `lib/init.js` unresolvable, filesystem not writable, `node` unavailable). Include a clear explanation and concrete steps to resolve.

```json
{
  "code": "LOOPER_COMPLETE" | "LOOPER_FEEDBACK" | "LOOPER_BLOCKED",
  "stop_reason": "<short human-readable status or 'none'>",
  "input": [ /* input values used this turn, e.g. base_dir and sprint_name, or [] */ ],
  "output": [ /* absolute paths created this turn, e.g. the sprint dir, or [] */ ]
}
```
