import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const viewSource = readFileSync(
  new URL('../views/AssetClassSettingsView.vue', import.meta.url),
  'utf8'
)

test('asset-class settings exclude only the TAIEX display record', () => {
  assert.match(viewSource, /const classifiable = securities\.value\.filter/)
  assert.match(
    viewSource,
    /s\?\.market === '台股' && String\(s\?\.code\) === '0000'/
  )
  assert.match(viewSource, /if \(!kw\) return classifiable/)
  assert.match(viewSource, /return classifiable\.filter/)
})
