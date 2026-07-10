#!/usr/bin/env node
// alloc_version.js — orchestrator-side filename version allocator.
//
// Kills the numbering race that arises when parallel agents each compute "the next
// unused integer" for a versioned artifact. The orchestrator (which serializes
// dispatch) calls this to reserve the next integer, then hands the concrete path to
// the agent. Agents never compute their own version number.
//
// Given a directory and a filename pattern with a single `{N}` placeholder, it finds
// the largest existing N matching the pattern and prints the path for N+1 (or the
// requested `--current` largest existing N).
//
// Usage:
//   node alloc_version.js <dir> "<prefix>.{N}.<ext>"            -> next path (allocate)
//   node alloc_version.js <dir> "<prefix>.{N}.<ext>" --current  -> largest existing path (read)
//
// Examples:
//   node alloc_version.js _sprints/SP_001/docs "spec.init.{N}.md"
//   node alloc_version.js _sprints/SP_001/docs "spec.review.{N}.md" --current
//
// Output: single line — the absolute-ish path to use. Exit 0 on success, 2 on bad args.

const fs = require('fs')
const path = require('path')

const dir = process.argv[2]
const pattern = process.argv[3]
const wantCurrent = process.argv.includes('--current')

if (!dir || !pattern || !pattern.includes('{N}')) {
	process.stderr.write('usage: node alloc_version.js <dir> "<prefix>.{N}.<ext>" [--current]\n')
	process.exit(2)
}

const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace('\\{N\\}', '(\\d+)')
const re = new RegExp('^' + escaped + '$')

let maxN = -1
try {
	for (const name of fs.readdirSync(dir)) {
		const m = name.match(re)
		if (m) maxN = Math.max(maxN, parseInt(m[1], 10))
	}
} catch {
	// directory may not exist yet — treat as no existing versions
}

if (wantCurrent) {
	if (maxN < 0) { process.stderr.write('no existing version found\n'); process.exit(2) }
	process.stdout.write(path.join(dir, pattern.replace('{N}', String(maxN))) + '\n')
} else {
	const next = maxN + 1
	process.stdout.write(path.join(dir, pattern.replace('{N}', String(next))) + '\n')
}
