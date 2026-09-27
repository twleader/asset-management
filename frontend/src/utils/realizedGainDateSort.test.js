import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveRealizedGainDateSortOrder, sortRealizedGainRecords } from './realizedGainDateSort.js'
import {
  buildRealizedGainStockOptions,
  filterRealizedGainByStock,
  pruneInvalidRealizedGainStockFilter
} from './realizedGainStockFilter.js'

const records = [
  { id: 'same-day-first', tradeDate: '2025-01-05' },
  { id: 'oldest', tradeDate: '2024-12-31' },
  { id: 'newest', tradeDate: '2026-01-01' },
  { id: 'same-day-second', tradeDate: '2025-01-05' }
]

test('sortRealizedGainRecords：以完整 ISO 日期跨年升冪排序，且同日維持原順序', () => {
  const result = sortRealizedGainRecords(records, 'ascending')

  assert.deepEqual(result.map(record => record.id), [
    'oldest', 'same-day-first', 'same-day-second', 'newest'
  ])
})

test('sortRealizedGainRecords：降冪只反轉日期比較方向，同日仍維持原順序', () => {
  const result = sortRealizedGainRecords(records, 'descending')

  assert.deepEqual(result.map(record => record.id), [
    'newest', 'same-day-first', 'same-day-second', 'oldest'
  ])
})

test('sortRealizedGainRecords：排序時不修改輸入，未排序狀態也回傳新陣列', () => {
  const input = structuredClone(records)
  const sorted = sortRealizedGainRecords(input, 'descending')
  const unsorted = sortRealizedGainRecords(input, null)

  assert.deepEqual(input, records)
  assert.notEqual(sorted, input)
  assert.notEqual(unsorted, input)
  assert.deepEqual(unsorted, records)
})

test('resolveRealizedGainDateSortOrder：active caret 回報 null 時保留方向，兩個 caret 都可切換', () => {
  assert.equal(resolveRealizedGainDateSortOrder('ascending', null), 'ascending')
  assert.equal(resolveRealizedGainDateSortOrder('descending', null), 'descending')
  assert.equal(resolveRealizedGainDateSortOrder('descending', 'ascending'), 'ascending')
  assert.equal(resolveRealizedGainDateSortOrder('ascending', 'descending'), 'descending')
})

const twAlpha = { id: 'tw-alpha', assetCode: 'AAA', assetName: '甲股', market: '台股' }
const usAlpha = { id: 'us-alpha', assetCode: 'AAA', assetName: '美甲股', market: '美股' }
const twBeta = { id: 'tw-beta', assetCode: 'BBB', assetName: '乙股', market: '台股' }
const noCode = { id: 'no-code', assetCode: '   ', assetName: '舊資料', market: '台股' }

test('已實現損益股票選項：依代號與市場去重、繁中排序，跨市場同代號保留為不同項目', () => {
  const options = buildRealizedGainStockOptions([twBeta, usAlpha, twAlpha, twAlpha, noCode])

  assert.deepEqual(options, [
    { value: 'BBB__台股', label: '乙股（BBB）' },
    { value: 'AAA__台股', label: '甲股（AAA）' },
    { value: 'AAA__美股', label: '美甲股（AAA）' }
  ])
})

test('已實現損益股票篩選：空選取顯示全部，多選為聯集，空白代號列恆常保留', () => {
  const rangeRecords = [twAlpha, usAlpha, twBeta, noCode]

  assert.deepEqual(filterRealizedGainByStock(rangeRecords, []), rangeRecords)
  assert.deepEqual(
    filterRealizedGainByStock(rangeRecords, ['AAA__台股', 'BBB__台股']),
    [twAlpha, twBeta, noCode]
  )
})

test('已實現損益股票篩選：範圍資料變更時只移除失效 key，保留有效選取', () => {
  const selected = ['AAA__台股', 'AAA__美股']
  const options = [{ value: 'AAA__台股', label: '甲股（AAA）' }]

  assert.deepEqual(pruneInvalidRealizedGainStockFilter(selected, options), ['AAA__台股'])
})

test('已實現損益頁將交易日期欄接線到自訂雙向排序', () => {
  const view = readFileSync(new URL('../views/RealizedGainView.vue', import.meta.url), 'utf8')

  assert.ok(view.includes("import { resolveRealizedGainDateSortOrder, sortRealizedGainRecords } from '@/utils/realizedGainDateSort'"))
  assert.ok(view.includes('ref="realizedGainTable"'))
  assert.ok(view.includes('@sort-change="handleDateSortChange"'))
  assert.ok(view.includes('sortable="custom"'))
  assert.ok(view.includes(":sort-orders=\"['ascending', 'descending']\""))
  assert.ok(view.includes("from '@/utils/realizedGainStockFilter'"))
  assert.ok(view.includes('v-if="stockOptions.length"'))
  assert.ok(view.includes('v-model="stockFilter"'))
  assert.ok(view.includes('const stockOptions = computed(() => buildRealizedGainStockOptions(marketYearRecords.value))'))
  assert.ok(view.includes('const stockFiltered = filterRealizedGainByStock(marketYearRecords.value, stockFilter.value)'))
  assert.ok(view.includes('return sortRealizedGainRecords(stockFiltered, dateSortOrder.value)'))
  assert.ok(view.includes('const records = filteredRecords.value'))
  assert.ok(view.includes('const resolvedOrder = resolveRealizedGainDateSortOrder(dateSortOrder.value, order)'))
  assert.ok(view.includes("nextTick(() => realizedGainTable.value?.sort('tradeDate', resolvedOrder))"))
})
