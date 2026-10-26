export const MAX_EXPORT_SCHEDULES = 10

export const DEFAULT_EXPORT_RANGE_MONTHS = 120

const defaultRangeMonthOptions = [
  { label: '近 1 個月', value: 1 },
  { label: '近 3 個月', value: 3 },
  { label: '近 6 個月', value: 6 },
  { label: '近 1 年', value: 12 },
  { label: '近 3 年', value: 36 },
  { label: '近 5 年', value: 60 },
  { label: '全部十年', value: DEFAULT_EXPORT_RANGE_MONTHS }
]

const scheduleNamePattern = /^[\p{Script=Han}A-Za-z0-9_ -]+$/u
const scheduleTimePattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/

export function formatExportScheduleTime(runHour, runMinute) {
  return `${String(runHour ?? 8).padStart(2, '0')}:${String(runMinute ?? 0).padStart(2, '0')}`
}

export function createExportScheduleDraft(schedule = null, { includeRangeMonths = false } = {}) {
  const draft = {
    id: schedule?.id ?? null,
    name: schedule?.name ?? '',
    enabled: !!schedule?.enabled,
    _time: schedule ? formatExportScheduleTime(schedule.runHour, schedule.runMinute) : '08:00',
    outputSubpath: schedule?.outputSubpath ?? 'input',
    gdriveEnabled: !!schedule?.gdriveEnabled,
    gdriveSubpath: schedule?.gdriveSubpath ?? '',
    gdriveRemote: schedule?.gdriveRemote ?? '',
    lastRunAt: schedule?.lastRunAt ?? null,
    lastRunStatus: schedule?.lastRunStatus ?? null,
    gdriveLastRunAt: schedule?.gdriveLastRunAt ?? null,
    gdriveLastStatus: schedule?.gdriveLastStatus ?? null
  }
  if (includeRangeMonths) draft.rangeMonths = schedule?.rangeMonths ?? DEFAULT_EXPORT_RANGE_MONTHS
  return draft
}

export function getExportRangeMonthOptions(rangeMonths) {
  const options = [...defaultRangeMonthOptions]
  if (Number.isInteger(rangeMonths) && rangeMonths >= 1 && rangeMonths <= 120
    && !options.some(option => option.value === rangeMonths)) {
    options.push({ label: `自訂：近 ${rangeMonths} 個月`, value: rangeMonths })
  }
  return options
}

export function formatExportRangeMonths(rangeMonths) {
  return rangeMonths == null || rangeMonths === DEFAULT_EXPORT_RANGE_MONTHS
    ? '全部十年'
    : `近 ${rangeMonths} 個月`
}

export function buildExportSchedulePayload(draft, { isConfiguredAdmin = false } = {}) {
  const name = String(draft?.name ?? '').trim()
  if (name && (Array.from(name).length > 20 || !scheduleNamePattern.test(name))) {
    throw new Error('名稱限 1–20 個中英數字、空白、底線或連字號')
  }

  const time = String(draft?._time ?? '')
  if (!scheduleTimePattern.test(time)) throw new Error('請輸入有效的執行時間')
  const [runHour, runMinute] = time.split(':').map(Number)
  const outputSubpath = String(draft?.outputSubpath ?? '').trim() || 'input'
  const gdriveSubpath = String(draft?.gdriveSubpath ?? '').trim()

  if (isConfiguredAdmin && draft?.gdriveEnabled && !gdriveSubpath) {
    throw new Error('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
  }

  const payload = {
    name: name || null,
    enabled: !!draft?.enabled,
    runHour,
    runMinute,
    outputSubpath,
    // null means preserve existing settings on update; new rows default to disabled Drive.
    gdriveEnabled: isConfiguredAdmin ? !!draft?.gdriveEnabled : null,
    gdriveSubpath: isConfiguredAdmin ? (gdriveSubpath || null) : null
  }
  if (Object.hasOwn(draft ?? {}, 'rangeMonths')) {
    const rangeMonths = draft.rangeMonths
    if (rangeMonths !== null && (!Number.isInteger(rangeMonths) || rangeMonths < 1 || rangeMonths > 120)) {
      throw new Error('匯出範圍須為 1 到 120 個月，或全部十年')
    }
    payload.rangeMonths = rangeMonths
  }
  return payload
}

export function requireExportScheduleList(response, { requireRangeMonths = false } = {}) {
  if (!response || !Array.isArray(response.schedules)) {
    throw new Error('伺服器未回傳有效的排程清單')
  }
  if (response.schedules.length > MAX_EXPORT_SCHEDULES) {
    throw new Error(`伺服器回傳的排程超過每頁 ${MAX_EXPORT_SCHEDULES} 筆上限`)
  }
  if (response.schedules.some(schedule => schedule?.id == null)) {
    throw new Error('伺服器回傳的排程缺少識別碼')
  }
  if (requireRangeMonths && response.schedules.some(({ rangeMonths }) =>
    rangeMonths !== null && (!Number.isInteger(rangeMonths) || rangeMonths < 1 || rangeMonths > 120))) {
    throw new Error('伺服器回傳的匯出範圍無效')
  }
  return response
}

export function getExportScheduleError(error, fallback) {
  return error?.response?.data?.detail
    || error?.response?.data?.message
    || error?.message
    || fallback
}
