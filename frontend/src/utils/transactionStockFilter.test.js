import test from 'node:test'
import assert from 'node:assert/strict'
import {
  filterByYearAndMarket,
  buildStockOptions,
  filterByStock,
  pruneInvalidStockFilter
} from './transactionStockFilter.js'

const stockA = { assetType: '股票', assetCode: 'A', assetName: 'A股', market: '台股' }
const stockB = { assetType: '股票', assetCode: 'B', assetName: 'B股', market: '台股' }
const fundRow = { assetType: '基金', assetCode: null, assetName: '某基金', market: null }

test('filterByYearAndMarket：selectedYear 為 null 時展開所有年度的 records', () => {
  const summaries = [
    { year: 2024, records: [stockA] },
    { year: 2025, records: [stockB] }
  ]
  const result = filterByYearAndMarket(summaries, null, '')
  assert.deepEqual(result, [stockA, stockB])
})

test('filterByYearAndMarket：指定 selectedYear 只取該年度的 records', () => {
  const summaries = [
    { year: 2024, records: [stockA] },
    { year: 2025, records: [stockB] }
  ]
  const result = filterByYearAndMarket(summaries, 2025, '')
  assert.deepEqual(result, [stockB])
})

test('filterByYearAndMarket：selectedYear 不存在時回空陣列', () => {
  const summaries = [{ year: 2024, records: [stockA] }]
  const result = filterByYearAndMarket(summaries, 1999, '')
  assert.deepEqual(result, [])
})

test('filterByYearAndMarket：marketFilter 非空時疊加市場過濾', () => {
  const usStock = { assetType: '股票', assetCode: 'C', assetName: 'C股', market: '美股' }
  const summaries = [{ year: 2024, records: [stockA, usStock] }]
  const result = filterByYearAndMarket(summaries, 2024, '美股')
  assert.deepEqual(result, [usStock])
})

test('filterByYearAndMarket：marketFilter 為空字串時不過濾市場', () => {
  const usStock = { assetType: '股票', assetCode: 'C', assetName: 'C股', market: '美股' }
  const summaries = [{ year: 2024, records: [stockA, usStock] }]
  const result = filterByYearAndMarket(summaries, 2024, '')
  assert.deepEqual(result, [stockA, usStock])
})

test('buildStockOptions：只收集股票類型且有代號的列，依中文排序，同 key 去重', () => {
  const options = buildStockOptions([stockB, stockA, fundRow, stockA])
  assert.deepEqual(options, [
    { value: 'A__台股', label: 'A股（A）' },
    { value: 'B__台股', label: 'B股（B）' }
  ])
})

test('buildStockOptions：空陣列回傳空陣列', () => {
  assert.deepEqual(buildStockOptions([]), [])
})

test('buildStockOptions：沒有任何股票列（只有基金）時回傳空陣列', () => {
  assert.deepEqual(buildStockOptions([fundRow]), [])
})

test('filterByStock：預設 stockFilter=[] 時回傳與輸入完全相同的陣列內容（股票＋基金列皆在）', () => {
  const records = [stockA, stockB, fundRow]
  const result = filterByStock(records, [])
  assert.deepEqual(result, records)
})

test('filterByStock：帶單一 key 時，只剩該股票列＋基金列', () => {
  const records = [stockA, stockB, fundRow]
  const result = filterByStock(records, ['A__台股'])
  assert.deepEqual(result, [stockA, fundRow])
})

test('filterByStock：帶兩個 key 時為聯集（兩檔股票列＋基金列皆在）', () => {
  const stockC = { assetType: '股票', assetCode: 'C', assetName: 'C股', market: '台股' }
  const records = [stockA, stockB, stockC, fundRow]
  const result = filterByStock(records, ['A__台股', 'B__台股'])
  assert.deepEqual(result, [stockA, stockB, fundRow])
})

test('pruneInvalidStockFilter：移除失效項、保留有效項', () => {
  const result = pruneInvalidStockFilter(['A__台股', 'B__台股'], [{ value: 'A__台股', label: 'A股（A）' }])
  assert.deepEqual(result, ['A__台股'])
})

test('pruneInvalidStockFilter：全部仍有效時回傳原陣列參照（供呼叫端判斷是否需要寫回）', () => {
  const input = ['A__台股']
  const result = pruneInvalidStockFilter(input, [{ value: 'A__台股', label: 'A股（A）' }])
  assert.equal(result, input)
})

test('pruneInvalidStockFilter：全部失效時回空陣列', () => {
  const result = pruneInvalidStockFilter(['A__台股'], [])
  assert.deepEqual(result, [])
})
