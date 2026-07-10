#!/usr/bin/env node
// validate_index.js — deterministic hard pre-gate for a TICKET stage index.json.
//
// Checks the objectively-verifiable structural properties of a task graph so the
// LLM review never has to (and never gets them wrong): schema shape, dependency
// symmetry, cycle detection, wave-number math, and id integrity of execution_orders.
//
// Usage:   node validate_index.js <path/to/index.json>
// Output:  pretty JSON report on stdout.
// Exit:    0 = PASS (no errors), 1 = FAIL (>=1 error), 2 = could not run (bad args/IO/JSON).
//
// A FAIL from this script MUST mechanically cap the TICKET quality_score below pass_score.

const fs = require('fs')

function fail(code, errors) {
	process.stdout.write(JSON.stringify({ ok: false, errors, warnings: [] }, null, 2) + '\n')
	process.exit(code)
}

const file = process.argv[2]
if (!file) fail(2, ['usage: node validate_index.js <index.json>'])

let raw
try {
	raw = fs.readFileSync(file, 'utf8')
} catch (e) {
	fail(2, [`cannot read ${file}: ${e.message}`])
}

let doc
try {
	doc = JSON.parse(raw)
} catch (e) {
	fail(2, [`invalid JSON (no comments/trailing commas allowed): ${e.message}`])
}

const errors = []
const warnings = []

// --- schema shape -----------------------------------------------------------
if (typeof doc !== 'object' || doc === null) fail(1, ['top-level value is not an object'])
if (!Array.isArray(doc.tasks)) errors.push('`tasks` must be an array')
if (!Array.isArray(doc.execution_orders)) errors.push('`execution_orders` must be an array')

if (errors.length) fail(1, errors)

const tasks = doc.tasks
const ids = []
const idSet = new Set()

for (let i = 0; i < tasks.length; i++) {
	const t = tasks[i]
	const where = `tasks[${i}]`
	if (typeof t !== 'object' || t === null) { errors.push(`${where} is not an object`); continue }
	if (typeof t.id !== 'string' || !t.id) { errors.push(`${where}.id missing or not a string`); continue }
	if (idSet.has(t.id)) errors.push(`duplicate task id "${t.id}"`)
	idSet.add(t.id)
	ids.push(t.id)
	if (!Array.isArray(t.depends_on)) errors.push(`${where} ("${t.id}").depends_on must be an array`)
}

// --- depends_on references + symmetry (every edge points at a real task) -----
for (const t of tasks) {
	if (!t || !Array.isArray(t.depends_on)) continue
	for (const dep of t.depends_on) {
		if (!idSet.has(dep)) errors.push(`task "${t.id}" depends_on unknown task "${dep}"`)
		if (dep === t.id) errors.push(`task "${t.id}" depends on itself`)
	}
}

// --- cycle detection (Kahn topological sort) --------------------------------
if (!errors.some((e) => e.includes('depends_on'))) {
	const indeg = new Map(ids.map((id) => [id, 0]))
	const adj = new Map(ids.map((id) => [id, []]))
	for (const t of tasks) {
		for (const dep of t.depends_on) {
			adj.get(dep).push(t.id)
			indeg.set(t.id, indeg.get(t.id) + 1)
		}
	}
	const queue = ids.filter((id) => indeg.get(id) === 0)
	let visited = 0
	// computed wave number per task = longest dependency depth
	const wave = new Map(ids.map((id) => [id, 0]))
	const q = [...queue]
	while (q.length) {
		const id = q.shift()
		visited++
		for (const nxt of adj.get(id)) {
			wave.set(nxt, Math.max(wave.get(nxt), wave.get(id) + 1))
			indeg.set(nxt, indeg.get(nxt) - 1)
			if (indeg.get(nxt) === 0) q.push(nxt)
		}
	}
	if (visited !== ids.length) {
		errors.push('dependency cycle detected (task graph is not a DAG)')
	} else {
		// --- execution_orders integrity + wave math -------------------------
		const eo = doc.execution_orders
		const seen = new Map()
		let waveErr = false
		for (let w = 0; w < eo.length; w++) {
			const layer = eo[w]
			if (!Array.isArray(layer)) { errors.push(`execution_orders[${w}] must be an array of task ids`); waveErr = true; continue }
			for (const id of layer) {
				if (!idSet.has(id)) errors.push(`execution_orders[${w}] references unknown task "${id}"`)
				if (seen.has(id)) errors.push(`task "${id}" appears in execution_orders more than once (waves ${seen.get(id)} and ${w})`)
				seen.set(id, w)
				if (idSet.has(id) && wave.get(id) !== w) {
					errors.push(`task "${id}" is in wave ${w} but its dependency depth is ${wave.get(id)}`)
					waveErr = true
				}
			}
		}
		// every task must appear exactly once across execution_orders
		for (const id of ids) {
			if (!seen.has(id)) errors.push(`task "${id}" is missing from execution_orders`)
		}
		if (!waveErr && eo.length && Array.isArray(eo[0])) {
			for (const id of eo[0]) {
				const t = tasks.find((x) => x.id === id)
				if (t && t.depends_on.length !== 0) errors.push(`wave-1 task "${id}" must have empty depends_on`)
			}
		}
	}
}

const ok = errors.length === 0
process.stdout.write(JSON.stringify({ ok, task_count: ids.length, wave_count: doc.execution_orders.length, errors, warnings }, null, 2) + '\n')
process.exit(ok ? 0 : 1)
