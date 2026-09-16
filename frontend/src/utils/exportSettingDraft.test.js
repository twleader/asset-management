import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { cloneExportSetting, persistExportSettingDraft, replaceExportSetting } from './exportSettingDraft.js'

test('草稿時間列與 canonical 完全隔離，取消不會發出寫入', () => {
  const canonical = reactive({ enabled: true, times: [{ id: 7, runHour: 9, runMinute: 15, enabled: true }] })
  const draft = cloneExportSetting(canonical)
  draft.times[0].runMinute = 30
  draft.times.push({ runHour: 13, runMinute: 0, enabled: false })

  assert.deepEqual(canonical.times, [{ id: 7, runHour: 9, runMinute: 15, enabled: true }])
})

test('fake API 成功只接受一次保存並以 server canonical response 覆寫摘要', async () => {
  const busy = { value: false }
  const canonical = { enabled: false, times: [{ id: 1, runHour: 8, runMinute: 0, enabled: true }] }
  let requests = 0
  let release
  const pending = new Promise(resolve => { release = resolve })
  const apply = response => replaceExportSetting(canonical, response)
  const first = persistExportSettingDraft({
    busy,
    payload: () => ({ enabled: true, times: [{ runHour: 9, runMinute: 5, enabled: true }] }),
    save: async body => { requests++; await pending; return { ...body, times: [{ id: 99, runHour: 9, runMinute: 5, enabled: true }] } },
    apply
  })
  const second = await persistExportSettingDraft({ busy, payload: () => ({}), save: async () => { requests++ }, apply })
  assert.deepEqual(second, { skipped: true })
  assert.equal(requests, 1)
  release()
  await first
  assert.deepEqual(canonical, { enabled: true, times: [{ id: 99, runHour: 9, runMinute: 5, enabled: true }] })
})

test('fake API 失敗時草稿保留，下一次可重試', async () => {
  const busy = { value: false }
  const draft = { outputSubpath: 'reports', times: [{ runHour: 8, runMinute: 0, enabled: true }] }
  const canonical = { outputSubpath: 'old', times: [] }
  await assert.rejects(() => persistExportSettingDraft({
    busy,
    payload: () => draft,
    save: async () => { throw new Error('fixture failure') },
    apply: response => replaceExportSetting(canonical, response)
  }), /fixture failure/)
  assert.equal(busy.value, false)
  assert.deepEqual(draft, { outputSubpath: 'reports', times: [{ runHour: 8, runMinute: 0, enabled: true }] })
  assert.deepEqual(canonical, { outputSubpath: 'old', times: [] })
})
