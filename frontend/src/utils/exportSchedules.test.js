import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_EXPORT_SCHEDULES,
  buildExportSchedulePayload,
  createExportScheduleDraft,
  formatExportScheduleTime,
  formatExportRangeMonths,
  getExportRangeMonthOptions,
  requireExportScheduleList
} from './exportSchedules.js'

test('新增草稿預設停用，編輯草稿會獨立複製單筆設定', () => {
  const fresh = createExportScheduleDraft()
  assert.equal(fresh.enabled, false)
  assert.equal(fresh._time, '08:00')
  assert.equal(fresh.outputSubpath, 'input')

  const saved = { id: 7, name: '晨間', enabled: true, runHour: 9, runMinute: 5, outputSubpath: 'reports' }
  const draft = createExportScheduleDraft(saved)
  draft.name = '晚間'
  assert.equal(saved.name, '晨間')
  assert.equal(draft._time, '09:05')
  assert.equal(formatExportScheduleTime(undefined, undefined), '08:00')
})

test('payload 正規化名稱與時間，非管理者不送 Drive 設定', () => {
  assert.deepEqual(buildExportSchedulePayload({
    name: '  資產 1  ', enabled: false, _time: '23:59', outputSubpath: '  '
  }), {
    name: '資產 1', enabled: false, runHour: 23, runMinute: 59, outputSubpath: 'input',
    gdriveEnabled: null, gdriveSubpath: null
  })
})

test('payload 拒絕非法名稱、時間及已啟用但沒有資料夾的管理者 Drive', () => {
  assert.throws(() => buildExportSchedulePayload({ name: '報表/日', _time: '08:00' }), /名稱限/)
  assert.throws(() => buildExportSchedulePayload({ name: '合法', _time: '24:00' }), /執行時間/)
  assert.throws(() => buildExportSchedulePayload({ name: '', _time: '08:00', gdriveEnabled: true }, { isConfiguredAdmin: true }), /Drive 目標資料夾/)
})

test('rangeMonths 保留任意既存合法值，並讓自訂值在選單中可見', () => {
  const saved = { id: 9, name: null, enabled: false, runHour: 8, runMinute: 0, rangeMonths: 7 }
  const draft = createExportScheduleDraft(saved, { includeRangeMonths: true })
  assert.equal(draft.rangeMonths, 7)
  assert.ok(getExportRangeMonthOptions(draft.rangeMonths).some(option => option.value === 7))
  assert.equal(buildExportSchedulePayload({ ...draft, _time: '08:00' }).rangeMonths, 7)
  assert.equal(createExportScheduleDraft({ rangeMonths: null }, { includeRangeMonths: true }).rangeMonths, 120)
  assert.equal(formatExportRangeMonths(null), '全部十年')
  assert.equal(formatExportRangeMonths(7), '近 7 個月')
  assert.throws(() => buildExportSchedulePayload({ ...draft, _time: '08:00', rangeMonths: 121 }), /1 到 120/)
})

test('清單回應須完整且不可超過每頁上限', () => {
  const valid = { baseDir: '/home/user', gdriveRemote: 'Drive', schedules: [{ id: 1 }] }
  assert.equal(requireExportScheduleList(valid), valid)
  assert.throws(() => requireExportScheduleList({ schedules: null }), /有效的排程清單/)
  assert.throws(() => requireExportScheduleList({ schedules: Array.from({ length: MAX_EXPORT_SCHEDULES + 1 }, (_, id) => ({ id })) }), /超過/)
  assert.throws(() => requireExportScheduleList({ schedules: [{ id: null }] }), /缺少識別碼/)
  assert.throws(() => requireExportScheduleList({ schedules: [{ id: 2, rangeMonths: 0 }] }, { requireRangeMonths: true }), /匯出範圍無效/)
})
