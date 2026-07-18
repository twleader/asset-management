#!/usr/bin/env node
// apply_proposals.js — supervised applier for agv3:LEARN proposals.
//
// Applies ONLY human-approved (`[x]`) proposals from a LEARN proposals.md to their target
// SKILL.md files, and ONLY as a reviewable change — it never merges anything anywhere. Safe by
// construction: each proposal carries the EXACT current text as its `before` block, so an apply is
// an exact-match replace that fails loudly if the target has drifted (0 or >1 matches → skipped).
//
// Usage:
//   node agv3/lib/apply_proposals.js <proposals.md>            # DRY RUN: print unified diffs, write nothing
//   node agv3/lib/apply_proposals.js <proposals.md> --apply    # apply approved proposals to the working tree
//   node agv3/lib/apply_proposals.js <proposals.md> --root <dir>   # resolve target paths against <dir> (default: cwd)
//
// On --apply: backs up every touched file under agv3/_learn/apply-<ts>/backup/<path>, applies the
// changes in place, and writes a combined unified diff to agv3/_learn/apply-<ts>/changes.patch for
// review. It does NOT commit or merge — the human reviews the diff and versions the skills however
// they like (or reverts from the backup). If run inside a git repo, the changes simply land in the
// working tree for `git diff`.
//
// Proposal format consumed (see agv3/skills/LEARN/SKILL.md):
//   ### [x] Proposal N — <title>
//   - target: agv3/skills/<DIR>/SKILL.md
//   - mode: replace | append          (default: replace)
//   - evidence: SP_002, SP_004
//   ~~~before
//   <exact current text>              (omit/empty for mode: append)
//   ~~~
//   ~~~after
//   <exact new text>
//   ~~~
//
// Exit: 0 = ran cleanly (dry-run or apply); 1 = one or more approved proposals could not be applied
// (drift/ambiguity/missing target); 2 = bad args / unreadable proposals file.

const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

function die(code, msg) { process.stderr.write(msg + '\n'); process.exit(code) }

const args = process.argv.slice(2)
const proposalsPath = args.find((a) => !a.startsWith('--'))
const doApply = args.includes('--apply')
const rootIdx = args.indexOf('--root')
const root = rootIdx >= 0 ? path.resolve(args[rootIdx + 1]) : process.cwd()

if (!proposalsPath) die(2, 'usage: node apply_proposals.js <proposals.md> [--apply] [--root <dir>]')
let raw
try { raw = fs.readFileSync(proposalsPath, 'utf8') } catch (e) { die(2, `cannot read ${proposalsPath}: ${e.message}`) }

// --- parse proposals ---------------------------------------------------------
// Split into blocks at each "### [ ]" / "### [x]" heading.
const headerRe = /^###\s*\[([ xX])\]\s*Proposal\b(.*)$/
const lines = raw.split('\n')
const blocks = []
let cur = null
for (const line of lines) {
	const m = line.match(headerRe)
	if (m) {
		if (cur) blocks.push(cur)
		cur = { checked: m[1].toLowerCase() === 'x', title: m[2].trim(), body: '' }
	} else if (cur) {
		cur.body += line + '\n'
	}
}
if (cur) blocks.push(cur)

function field(body, name) {
	const m = body.match(new RegExp('^-\\s*' + name + ':\\s*(.+)$', 'm'))
	return m ? m[1].trim() : null
}
function fenced(body, tag) {
	// ~~~tag\n ... \n~~~   (tildes chosen so ``` inside skill text doesn't collide)
	const m = body.match(new RegExp('~~~' + tag + '\\n([\\s\\S]*?)\\n~~~'))
	return m ? m[1] : null
}

const approved = blocks.filter((b) => b.checked)
if (approved.length === 0) {
	process.stdout.write('No approved ([x]) proposals found — nothing to do.\n')
	process.stdout.write(`(${blocks.length} total proposal(s), all unchecked.)\n`)
	process.exit(0)
}

// --- resolve + validate each approved proposal -------------------------------
const planned = []   // { title, targetAbs, targetRel, mode, evidence, newContent }
const skipped = []   // { title, reason }

for (const b of approved) {
	const targetRel = field(b.body, 'target')
	const mode = (field(b.body, 'mode') || 'replace').toLowerCase()
	const evidence = field(b.body, 'evidence') || '(none cited)'
	const before = fenced(b.body, 'before')
	const after = fenced(b.body, 'after')
	if (!targetRel) { skipped.push({ title: b.title, reason: 'no `- target:` field' }); continue }
	if (after == null) { skipped.push({ title: b.title, reason: 'no ~~~after block' }); continue }
	const targetAbs = path.resolve(root, targetRel)
	let content
	try { content = fs.readFileSync(targetAbs, 'utf8') } catch { skipped.push({ title: b.title, reason: `target not found: ${targetRel}` }); continue }

	let newContent
	if (mode === 'append') {
		newContent = content.endsWith('\n') ? content + after + '\n' : content + '\n' + after + '\n'
	} else { // replace
		if (before == null || before === '') { skipped.push({ title: b.title, reason: 'mode replace needs a non-empty ~~~before block' }); continue }
		const occurrences = content.split(before).length - 1
		if (occurrences === 0) { skipped.push({ title: b.title, reason: 'before-text not found (target drifted or already applied)' }); continue }
		if (occurrences > 1) { skipped.push({ title: b.title, reason: `before-text matches ${occurrences} times (ambiguous) — make it more specific` }); continue }
		newContent = content.replace(before, after)
	}
	planned.push({ title: b.title, targetAbs, targetRel, mode, evidence, newContent })
}

// --- unified diff via `git diff --no-index` (works even outside a git repo) ---
function unifiedDiff(origAbs, newContent) {
	const tmp = path.join(require('os').tmpdir(), 'agv3_apply_' + Math.random().toString(36).slice(2) + '.md')
	fs.writeFileSync(tmp, newContent)
	try {
		execFileSync('git', ['diff', '--no-index', '--no-color', origAbs, tmp], { encoding: 'utf8' })
		return '(no changes)'
	} catch (e) {
		// git diff exits 1 when files differ — that's the normal case; stdout holds the diff
		return (e.stdout || '').split('\n').slice(2).join('\n') // drop the temp-path header lines
	} finally { fs.unlinkSync(tmp) }
}

// --- report ------------------------------------------------------------------
process.stdout.write(`Proposals file: ${proposalsPath}\n`)
process.stdout.write(`Root: ${root}\n`)
process.stdout.write(`Approved: ${approved.length}   Applicable: ${planned.length}   Skipped: ${skipped.length}\n\n`)

for (const p of planned) {
	process.stdout.write(`── ${p.title}\n   target: ${p.targetRel}  (mode: ${p.mode}, evidence: ${p.evidence})\n`)
	process.stdout.write(unifiedDiff(p.targetAbs, p.newContent) + '\n')
}
if (skipped.length) {
	process.stdout.write('SKIPPED:\n')
	for (const s of skipped) process.stdout.write(`  - ${s.title}: ${s.reason}\n`)
	process.stdout.write('\n')
}

if (!doApply) {
	process.stdout.write('DRY RUN — nothing written. Re-run with --apply to write these changes to the working tree.\n')
	process.exit(skipped.length ? 1 : 0)
}

// --- apply -------------------------------------------------------------------
const ts = new Date().toISOString().replace(/[:.]/g, '-')
const applyDir = path.resolve(root, 'agv3/_learn', `apply-${ts}`)
const backupDir = path.join(applyDir, 'backup')
fs.mkdirSync(backupDir, { recursive: true })
let patch = ''
for (const p of planned) {
	const rel = path.relative(root, p.targetAbs)
	const bak = path.join(backupDir, rel)
	fs.mkdirSync(path.dirname(bak), { recursive: true })
	fs.copyFileSync(p.targetAbs, bak)          // backup original
	patch += `# ${p.title} (${p.evidence})\n` + unifiedDiff(p.targetAbs, p.newContent) + '\n'
	fs.writeFileSync(p.targetAbs, p.newContent) // apply in place
}
fs.writeFileSync(path.join(applyDir, 'changes.patch'), patch)
process.stdout.write(`APPLIED ${planned.length} proposal(s) to the working tree.\n`)
process.stdout.write(`Backups: ${path.relative(root, backupDir)}/\n`)
process.stdout.write(`Combined diff: ${path.relative(root, path.join(applyDir, 'changes.patch'))}\n`)
process.stdout.write('Review the diff, then version the skills however you like. To revert: restore files from the backup dir.\n')
process.exit(skipped.length ? 1 : 0)
