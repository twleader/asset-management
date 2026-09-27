import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const view = readFileSync(new URL('../views/GdpTwseView.vue', import.meta.url), 'utf8')

test('排程 CRUD 與 run-now 使用本頁 BFF route 和 row id', () => {
  assert.match(api, /getExportSchedules:\s*\(\) => api\.get\('\/bff\/gdp-twse\/export\/schedules'/)
  assert.match(api, /createExportSchedule:\s*\(data\) => api\.post\('\/bff\/gdp-twse\/export\/schedules', data/)
  assert.match(api, /updateExportSchedule:\s*\(id, data\) => api\.put\(`\/bff\/gdp-twse\/export\/schedules\/\$\{id\}`/)
  assert.match(api, /deleteExportSchedule:\s*\(id\) => api\.delete\(`\/bff\/gdp-twse\/export\/schedules\/\$\{id\}`/)
  assert.match(api, /runExportNow:\s*\(id\) => api\.post\(`\/bff\/gdp-twse\/export\/schedules\/\$\{id\}\/run-now`/)
  assert.match(api, /skipErrorToast: true/)
  assert.match(view, /await bffApi\.gdpTwse\.createExportSchedule\(payload\)/)
  assert.match(view, /await bffApi\.gdpTwse\.updateExportSchedule\(editingScheduleId\.value, payload\)/)
  assert.match(view, /await bffApi\.gdpTwse\.deleteExportSchedule\(row\.id\)/)
  assert.match(view, /await bffApi\.gdpTwse\.runExportNow\(row\.id\)/)
  assert.match(view, /await loadSchedule\(\)/)
})

test('指數選項與排程標籤採用 list response 的 marketOptions', () => {
  assert.match(view, /marketOptions\.value = Array\.isArray\(s\.marketOptions\) \? s\.marketOptions : \[\]/)
  assert.match(view, /<el-option v-for="option in marketOptions"[\s\S]*:label="option\.label"[\s\S]*:value="option\.value"/)
  assert.match(view, /new Map\(marketOptions\.value\.map\(option => \[option\.value, option\.label\]\)\)/)
})

test('空 markets placeholder 停用立即匯出並提示先選指數', () => {
  assert.match(view, /:disabled="!hasScheduleMarkets\(row\)"/)
  assert.match(view, /此排程尚無指數，請先選至少一個指數/)
  assert.match(view, /此排程尚未選擇指數，請先編輯並選擇至少一個指數/)
  assert.match(view, /if \(!hasScheduleMarkets\(row\)\)\s*\{\s*ElMessage\.warning/)
})

test('逐指數結果傳入共用雙格式提示，手動下載仍走單一 market route', () => {
  assert.match(view, /for \(const file of result\.results \|\| \[\]\)\s*\{\s*showDualExportResult\(\{[\s\S]*jsonPath: file\.jsonPath, xlsxPath: file\.path, gdriveStatus: file\.gdriveStatus[\s\S]*prefix: file\.marketLabel \|\| file\.market/)
  assert.match(api, /exportExcel: \(market = 'TWSE', start, end\) =>\s*api\.get\('\/bff\/gdp-twse\/export',\s*\{\s*params: \{ market/)
  assert.match(view, /bffApi\.gdpTwse\.exportExcel\(market\.value/)
})
