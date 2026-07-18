---
description: An explicit turn-by-turn, file-logged workflow — the user writes requests into devlog.md, quickdev answers/implements there instead of in chat. Covers any explicit quickdev session, not just coding: discussions and troubleshooting count too. When code is involved, it also applies a Node.js house style (require/pnpm/node:test, FP, snake_case), auto-commits after each logical step, and lazily maintains codewalk.md/usage.md/README.md. Trigger ONLY when the user explicitly names it — e.g. says "quickdev", "/quickdev", "start a quickdev session", "let's quickdev this". Do not infer this from generic coding requests that never mention quickdev.
---

## What this skill is

A standing turn-by-turn workflow: the user writes a batch of requests or questions, quickdev answers by writing directly into `devlog.md` — implementing things or just discussing them — instead of talking in the terminal, and the user reads and edits that file to kick off the next round.

It applies to any explicit quickdev session, not only coding — the same file-logging discipline covers pure discussions or OS troubleshooting or anything else. When a session does involve writing code or docs, it additionally fixes how the code is written, keeps the living docs current, and commits automatically without asking.

Because it changes normal defaults (auto-committing without asking, minimizing chat replies), it only activates when the user names it explicitly — never infer it from an ordinary "help me code this" request.

## Top rules — log first, every single invocation

Once invoked, before doing anything else, open `devlog.md` in the project root (create it with a `# Devlog` heading and initial template if it doesn't exist yet). This also applies to when there's no request text yet (bare invocation, no prior message, nothing already typed into devlog.md)


```initial round template
//--------------------------
// Ask

+
```

Append a new round to the bottom of the file in exactly this shape:

```later round template
//--------------------------
// Ask

+ <the user's request>

//--------------------------
// Ans / <YYYY-MM-DD HH:MM:SS>

# <short heading for this round>

<your full write-up of what you did, decided, or are asking back, basically redirecting your output here so they can be persisted>
```

Rules for filling this in:

- Copy the Ask verbatim. Don't paraphrase, trim, or clean it up — it's the user's own written record of what they asked, not your summary of it. Often the user will have already typed their request straight into `devlog.md` themselves before invoking you; if so, leave that line untouched and just append the Ans block under it.

- Only the Ans line gets a timestamp, in `Asia/Taipei` time. Get it with:

	```bash
	TZ='Asia/Taipei' date '+%Y-%m-%d %H:%M:%S'
	```

	Don't guess the time from context — always shell out for it.

- The Ans section is the real deliverable, not a footnote. Put everything you'd otherwise explain in chat into it: what you built or concluded, why, open questions, what you still need from the user. Once it's written, your terminal reply should be one short line confirming you're done (e.g. "Logged in devlog.md") — do not restate, summarize, or repeat the Ans content out loud afterward. The whole point of this file is that it replaces the back-and-forth chat with something both of you read and write directly.

- Log every meaningful round this way — a feature request, a bug report, a design question and its answer — but don't log routine tool-call noise inside a round; one round covers one coherent unit of conversation.

## Code convention — follow when the round involves writing js code

**Tooling**

- Latest Node.js. Tests via the built-in `node:test` module. Load modules with `require()` — never `import`/ESM syntax.

- Package management is pnpm only (`pnpm add`, `pnpm install`, `pnpm run ...`) — never npm or yarn.

- Favor CLI program over MCP server, which is more flexible for agents to write script for combined tasks or use it via Bash tool.

**Style**

- Functional programming only: plain functions composed together. No classes, no `this`, no OOP patterns of any kind.

- No TypeScript — plain JavaScript, always.

- `snake_case` for every identifier: variables, functions, file names where practical.

- Keep functions small and single-responsibility, and build features by composing them rather than writing one function that does everything.

- Actively resist over-engineering. Reach for the simplest structure that solves the problem actually in front of you — not one that anticipates requirements nobody has asked for yet. Three similar lines beat a premature abstraction.

- use standard file structure like `src/`, `lib/`, `bin/`, `tests/`, `docs/`...etc

**Commits**

- If cwd not a git repo, skip committing and don't mention it at all.

- Commit after each meaningful logical unit of work — a working feature slice, a fix, a passing test — with a clear, specific message. Do this automatically, without asking for confirmation first: that's a standing instruction for quickdev sessions specifically, overriding the usual "confirm before committing" caution. Keep unrelated changes in separate commits rather than batching them together.

## Documentation convention — follow when the round involves writing markdown

Applies to every `.md` file this skill touches (`devlog.md`, `codewalk.md`, `usage.md`, `README.md`, and any misc doc):

- Never use table syntax. If you need tabular information, express it as a nested list instead.

- Prefix each item with a legend:

	* `-` — Normal item (default)
	
	* `*` — highlight or emphasized items

	* `+` — TODO, question, or anything requiring attention. Use sparingly.

	* `→`, `←`, `↑`, `↓` — Indicates emphasis or a relationship to another item.

	* Other legends can be used as you see fit, but never emoticons.

- Leave one blank line after every line, including every list item. Never place two content lines directly adjacent to each other.

- No hard-wrapping using `\n` or `\r` in prose paragraphs, unless they are code blocks demonstrating exact content.

Correctly-spaced example:

```
+ first item

+ second item

# Heading

This paragraph is written as one continuous line with no manual breaks in the middle of it, and it just keeps going until it reaches its natural end, relying entirely on the editor to soft-wrap it for display.

```

## Rules — keep the living docs current, lazily

Don't scaffold empty stub files to look thorough — only create or update a doc once there's real content that belongs in it. All files live inside `docs/` (create it if doesn't exist)

- **devlog.md** — the exception: it starts immediately, from the very first quickdev invocation (Top rules, above). It's the single source of truth for the conversation; never restate or summarize its contents back in chat.

- **codewalk.md** — create or update it once there's an actual architecture, module layout, or non-obvious technique that a future coding agent would need explained in order to safely pick up the project. Write it for an agent, not a person: where things live, why they're structured that way, known gotchas, how to run the tests, and what not to change carelessly.

- **usage.md** — create or update it once there's a user-facing feature, command, or config option worth documenting. Keep a running "Feature list" at the very top, grouped by version or date so someone can tell what showed up when. Everything below that is the how-to: commands, configuration, examples.

- **README.md** — the default first-thing-you-see file. Leave it minimal or blank rather than inventing content for it; fill it in only once there's genuinely standard project info to put there (what the project is, how to install and run it).

- **misc docs** — if the user asks you to save a plan or spec to a specific named file (e.g. `v2-plan.md`), create that file and put the content there directly, instead of stuffing it into devlog.md (but must mention the creation of each file).

## Rules — language

Everything you author — devlog.md Ans sections, codewalk.md, usage.md, README.md, commit messages, code comments — is written in English, regardless of what language the user's request was in, unless the user explicitly asks for a different output language. The one exception is the verbatim Ask quote in devlog.md: that's a direct quote of the user, copied as-is in whatever language they used, not something you're authoring.
