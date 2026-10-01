import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const view = readFileSync(new URL('../views/BackupRestoreView.vue', import.meta.url), 'utf8')
const nginx = readFileSync(new URL('../../nginx-app.conf', import.meta.url), 'utf8')

test('backup browser deadlines stay beyond service workflows and below the exact nginx deadline', () => {
  assert.match(api, /create:\s*\(\) => api\.post\('\/bff\/backup-restore', null, \{ timeout: 960000 \}\)/)
  assert.match(api, /restore:[\s\S]*timeout: 1530000/)
  assert.match(api, /sync:\s*\(\) => api\.post\('\/bff\/backup-restore\/sync', null, \{ timeout: 1530000 \}\)/)
  assert.match(api, /updateSettings:\s*\(data\) => api\.put\('\/bff\/backup-restore\/settings', data, \{ timeout: 1530000 \}\)/)

  for (const path of ['', '/restore', '/sync', '/settings']) {
    assert.match(nginx, new RegExp(`location = /api/bff/backup-restore${path.replace('/', '\\/')} \\{[\\s\\S]*?proxy_read_timeout 1560s`))
  }
  assert.doesNotMatch(nginx, /location \^~ \/api\/bff\/backup-restore/)
})

test('backup action cannot be submitted again while its request is in flight', () => {
  assert.match(view, /:disabled="backing \|\| !settings\.backupEnabled"/)
  assert.match(view, /:loading="backing"/)
})
