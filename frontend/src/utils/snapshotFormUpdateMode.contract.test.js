import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../views/SnapshotFormView.vue', import.meta.url), 'utf8')

test('兩個在途款項表皆顯示唯讀更新方式，AUTO 列不可編輯或刪除', () => {
  assert.equal((source.match(/label="更新方式"/g) ?? []).length, 2)
  assert.equal((source.match(/:model-value="row\.updateMode"/g) ?? []).length, 2)
  assert.match(source, /const isAutoTransit = \(row\) => row\?\.updateMode === 'AUTO'/)
  assert.equal((source.match(/:disabled="isAutoTransit\(row\)"/g) ?? []).length >= 8, true)
  assert.equal((source.match(/v-if="!isAutoTransit\(row\)"/g) ?? []).length >= 4, true)
})

test('新增在途列預設手動，updateMode 只讀回且不進 submit payload', () => {
  assert.match(source, /currency, amount: 0, amountStr: '0',[\s\S]*?updateMode: 'MANUAL'/)
  assert.match(source, /updateMode: d\.updateMode === 'AUTO' \? 'AUTO' : 'MANUAL'/)
  const submit = source.slice(source.indexOf('const submit = async () =>'))
  assert.doesNotMatch(submit, /updateMode/)
})
