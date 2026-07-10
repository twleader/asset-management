const fs = require('fs')
const path = require('path')

// create_sprint — scaffold a new AgentFlow v3 sprint directory.
//
// Layout (under <base_dir>/_sprints/<sprint_name>/):
//   docs/      elicited requirements, survey, spec, codebase_guide, spike reports
//   tasks/     self-contained task files + index.json
//   compact/   context-compaction handoff notes
//   reports/   *.coding.md, *.retro.md, *.review.md
//   logs/      ledger.json (telemetry), reentry.log (rollback trail)
//
// Seeds docs/disc.init.md from lib/template.md and logs/ledger.json with an empty
// structural-telemetry skeleton (stages/agents/rounds — no token accounting in v3.0).
exports.create_sprint = (base_dir, sprint_name) => {
	if (!base_dir) throw new Error('base_dir must not be empty')
	if (!sprint_name) throw new Error('sprint_name must not be empty')

	const sprints_root = path.resolve(base_dir, '_sprints')
	fs.mkdirSync(sprints_root, { recursive: true })

	const sprint_path = path.join(sprints_root, sprint_name)
	if (fs.existsSync(sprint_path)) throw new Error(`sprint already exists: ${sprint_path}`)

	for (const subdir of ['docs', 'tasks', 'compact', 'reports', 'logs']) {
		fs.mkdirSync(path.join(sprint_path, subdir), { recursive: true })
	}

	// seed the requirements template, substituting ${...} placeholders
	const template_path = path.join(__dirname, 'template.md')
	const disc_path = path.join(sprint_path, 'docs', 'disc.init.md')
	const filled = fs.readFileSync(template_path, 'utf8')
		.replaceAll('${sprint_name}', sprint_name)
		.replaceAll('${sprint_path}', sprint_path)
		.replaceAll('${timestamp}', new Date().toISOString())
	fs.writeFileSync(disc_path, filled)

	// seed the structural telemetry ledger
	const ledger = {
		sprint: sprint_name,
		track: null,
		created_at: new Date().toISOString(),
		stages: [],
		totals: { agents_spawned: 0, review_rounds: 0, reentries: 0 },
	}
	fs.writeFileSync(path.join(sprint_path, 'logs', 'ledger.json'), JSON.stringify(ledger, null, 2))
	fs.writeFileSync(path.join(sprint_path, 'logs', 'reentry.log'), '')

	console.log(JSON.stringify({ sprint_path, disc_doc: disc_path }, null, 2))
	return { sprint_path, disc_doc: disc_path }
}

// next_sprint_name — smallest unused SP_NNN under <base_dir>/_sprints.
exports.next_sprint_name = (base_dir) => {
	const sprints_root = path.resolve(base_dir, '_sprints')
	let max = 0
	try {
		for (const name of fs.readdirSync(sprints_root)) {
			const m = name.match(/^SP_(\d+)$/)
			if (m) max = Math.max(max, parseInt(m[1], 10))
		}
	} catch {
		// no _sprints yet
	}
	return `SP_${String(max + 1).padStart(3, '0')}`
}

// CLI: node init.js <base_dir> [sprint_name]
if (require.main === module) {
	const base = process.argv[2] || process.cwd()
	const name = process.argv[3] || exports.next_sprint_name(base)
	exports.create_sprint(base, name)
}
