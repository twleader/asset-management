import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { dualExportNotice } from './dualExportMessage.js'
import {
  calendarDayClass,
  calendarEntry,
  calendarExportYears,
  hasLocalExport,
  marketTradingFlag,
  moveCalendarMonth,
  taipeiDateParts,
  unavailableMarketKeys
} from './tradingCalendarYearWindow.js'

const view = readFileSync(new URL('../views/TradingCalendarView.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const nginx = readFileSync(new URL('../../nginx.conf', import.meta.url), 'utf8')
const nginxApp = readFileSync(new URL('../../nginx-app.conf', import.meta.url), 'utf8')

const available = {
  holidays: { tw: { '2026-01-01': '元旦' }, us: {}, uk: {} },
  availability: { tw: 'AVAILABLE', us: 'AVAILABLE', uk: 'AVAILABLE' }
}
const unavailable = {
  holidays: { tw: {}, us: {}, uk: {} },
  availability: { tw: 'UNAVAILABLE', us: 'AVAILABLE', uk: 'UNAVAILABLE' }
}

test('逐年 cache 來回切換時 availability 不互相污染', () => {
  const cache = { 2026: available, 2027: unavailable }
  assert.equal(calendarEntry(cache, 2026), available)
  assert.deepEqual(unavailableMarketKeys(calendarEntry(cache, 2026)), [])
  assert.equal(calendarEntry(cache, 2027), unavailable)
  assert.deepEqual(unavailableMarketKeys(calendarEntry(cache, 2027)), ['tw', 'uk'])
  assert.equal(calendarEntry(cache, 2026), available)
  assert.equal(marketTradingFlag(calendarEntry(cache, 2027), 'tw', false, '2027-03-01'), null)
  assert.equal(marketTradingFlag(calendarEntry(cache, 2026), 'tw', false, '2026-01-01'), false)
})

test('unknown 市場不會被畫成三市同交易或全市場休市', () => {
  assert.equal(calendarDayClass({ day: 1, isWeekend: false, tw: null, us: null, uk: null }), '')
  assert.equal(calendarDayClass({ day: 1, isWeekend: false, tw: true, us: null, uk: false }), '')
  assert.equal(calendarDayClass({ day: 1, isWeekend: true, tw: null, us: null, uk: null }), '')
  assert.equal(calendarDayClass({ day: 1, isWeekend: true, tw: false, us: null, uk: false }), '')
  assert.equal(calendarDayClass({ day: 1, isWeekend: false, tw: true, us: true, uk: true }), 'both')
  assert.equal(calendarDayClass({ day: 1, isWeekend: false, tw: false, us: false, uk: false }), 'holiday')
})

test('月份移動在三年硬邊界內，程式呼叫也不能越界', () => {
  assert.deepEqual(moveCalendarMonth(2025, 1, -1, 2025, 2027), { year: 2025, month: 1 })
  assert.deepEqual(moveCalendarMonth(2025, 12, 1, 2025, 2027), { year: 2026, month: 1 })
  assert.deepEqual(moveCalendarMonth(2027, 12, 1, 2025, 2027), { year: 2027, month: 12 })
  assert.deepEqual(moveCalendarMonth(2027, 1, -1, 2025, 2027), { year: 2026, month: 12 })
})

test('今天與匯出年度使用台北時間及 BFF 年份窗口', () => {
  assert.deepEqual(taipeiDateParts(new Date('2026-12-31T16:30:00Z')), {
    year: 2027, month: 1, day: 1, date: '2027-01-01'
  })
  assert.deepEqual(calendarExportYears([2025, 2026, 2027], 2026), [2026, 2027])
  assert.deepEqual(calendarExportYears([], 2026), [])
})

test('authority 失敗使用後端原因且不誤報成功或磁碟權限', () => {
  const notice = dualExportNotice({
    prefix: '2027 年', localStatus: 'xlsx 略過：2027 年 TWSE authority unavailable／json 略過：同原因'
  })
  assert.equal(notice.type, 'warning')
  assert.match(notice.message, /2027 年.*authority unavailable/)
  assert.doesNotMatch(notice.message, /已匯出|磁碟|權限/)
  assert.equal(hasLocalExport({ path: null, jsonPath: null }), false)
  assert.equal(hasLocalExport({ path: '/tmp/a.xlsx', jsonPath: null }), true)
})

test('雙年度匯出與逾時預算固定在頁面專屬鏈路', () => {
  assert.match(api, /exportToDir:\s+\(subpath\).*timeout: 250000/)
  assert.match(nginx, /include \/etc\/nginx\/includes\/frontend-app\.conf;/)
  assert.match(nginxApp, /location = \/api\/bff\/trading-calendar\/export[\s\S]*?proxy_read_timeout 240s/)
  assert.match(view, /:disabled="atMinMonth"/)
  assert.match(view, /:disabled="atMaxMonth"/)
  assert.match(view, /v-for="year in availableYears"/)
  assert.doesNotMatch(view, /exportDialog\.year/)
})
