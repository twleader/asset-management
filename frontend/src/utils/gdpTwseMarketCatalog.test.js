import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/GdpTwseView.vue', import.meta.url), 'utf8')

test('GDP 大盤頁 market catalog 固定為集中、櫃買及八個海外市場', () => {
  const expected = [
    ["TWSE", "台股集中市場"], ["TPEX", "台股櫃買市場"],
    ["DJI", "道瓊工業"], ["SPX", "標普 500"], ["IXIC", "那斯達克綜合"],
    ["SOX", "費城半導體"], ["FTSE", "英國富時 100"], ["DAX", "德國 DAX"],
    ["KOSPI", "韓國 KOSPI"], ["N225", "日經 225"]
  ]
  const actual = [...view.matchAll(/\{ value: '([^']+)',\s+label: '([^']+)' \}/g)]
    .map(([, value, label]) => [value, label])
  assert.deepEqual(actual, expected)
})

test('主圖單選與排程匯出多選皆以 market value 綁定 TPEX', () => {
  const selectOptions = [...view.matchAll(/<el-option v-for="m in MARKETS"[\s\S]*?:value="m\.value"/g)]
  assert.equal(selectOptions.length, 2)
  assert.match(view, /MARKETS\.find\(m => m\.value === market\.value\)/)
})
