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
  assert.doesNotMatch(submit, /source/)
})

test('處理日以 server 原值鎖定 AUTO，並在跨快照複製時移除舊列 id', () => {
  assert.equal((source.match(/label="款項處理日"/g) ?? []).length, 2)
  assert.equal((source.match(/label="款項處理日"[\s\S]{0,500}?value-format="YYYY-MM-DD"/g) ?? []).length, 2)
  assert.match(source, /const isProcessingDateReadonly = \(row\) => isAutoTransit\(row\) && row\.serverProcessingDate != null/)
  assert.match(source, /id: d\.id \?\? null,[\s\S]*?processingDate: d\.processingDate \?\? null,[\s\S]*?serverProcessingDate: d\.processingDate \?\? null/)
  assert.match(source, /form\.deposits = detail\.deposits\.map\(d => \{[\s\S]*?row\.id = null/)

  const submit = source.slice(source.indexOf('const submit = async () =>'))
  assert.match(submit, /id: d\.id \?\? null/)
  assert.match(submit, /processingDate: isTransit \? d\.processingDate \|\| null : null/)
})

test('更新後讀回 server canonical 快照，避免到期列與 MANUAL id 留在舊草稿', () => {
  const submit = source.slice(source.indexOf('const submit = async () =>'))
  assert.match(submit, /await bffApi\.snapshotForm\.update\(route\.params\.id, payload\)[\s\S]*?await Promise\.allSettled\([\s\S]*?loadFormData\(\), store\.fetchSnapshots\(\)/)
  assert.doesNotMatch(source, /transit(?:Twd|Usd)?Deposits\.filter\([^\n]*processingDate/)
})
