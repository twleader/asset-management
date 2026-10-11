import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/ApiErrorLogsView.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')

test('API error-log view keeps the three selects, source contract and lazy text-only stacktrace detail', () => {
  for (const token of ['aria-label="來源"', 'label="全部" value="ALL"', 'label="開放 API" value="OPEN_API"', 'label="富邦證 API" value="FUBON_API"', 'value="NEWEST"', 'value="OLDEST"', 'type="expand"']) assert.ok(view.includes(token))
  assert.equal(view.includes('el-radio-group'), false)
  assert.equal(view.includes('el-radio-button'), false)
  assert.ok(view.includes('grid-template-columns:160px minmax(0, 1fr) minmax(0, 2fr)'))
  assert.ok(view.includes('gap:12px'))
  assert.ok(view.includes('.filters>.el-select{width:100%}'))
  assert.ok(view.includes('const operationLabel = item => `${item.apiName} — ${item.apiUrl}`'))
  assert.ok(view.includes(':label="operationLabel(item)"'))
  const changeSource = view.slice(view.indexOf('async function changeSource () {'), view.indexOf('\nfunction changeSort'))
  assert.ok(changeSource.indexOf('invalidateListRequests()') >= 0)
  assert.ok(changeSource.indexOf('invalidateListRequests()') < changeSource.indexOf('loadOperations(requestedSource)'))
  assert.ok(changeSource.includes('await loadOperations(requestedSource)'))
  assert.ok(changeSource.includes('if (requestId !== listRequestId || requestedSource !== source.value) return'))
  assert.ok(changeSource.indexOf('await loadOperations(requestedSource)') < changeSource.indexOf('await load()'))
  assert.ok(view.includes('requestedSource === source.value'))
  assert.ok(view.includes('bffApi.apiErrorLogs.detail(row.id)'))
  assert.ok(view.includes('Object.hasOwn(details.value, row.id)'))
  assert.equal(view.includes('v-html'), false)
})

test('API-name select displays explicit all while encoding it as an absent request filter', () => {
  const apiNameSelect = view.match(/<el-select v-model="operationKey"[\s\S]*?<\/el-select>/)?.[0]
  const allOption = '<el-option label="全部" :value="ALL_OPERATION_FILTER"/>'

  assert.ok(apiNameSelect)
  assert.ok(apiNameSelect.includes('aria-label="API 名稱"'))
  assert.ok(apiNameSelect.includes(':value-on-clear="null"'))
  assert.ok(apiNameSelect.includes('clearable'))
  assert.ok(apiNameSelect.includes('placeholder="單一 API"'))
  assert.ok(apiNameSelect.includes('@change="changeOperation"'))
  assert.ok(apiNameSelect.includes('@clear="restoreAllOperationFilter"'))
  assert.equal((view.match(/<el-option label="全部" :value="ALL_OPERATION_FILTER"\/>/g) ?? []).length, 1)
  assert.ok(apiNameSelect.indexOf(allOption) >= 0)
  assert.ok(apiNameSelect.indexOf(allOption) < apiNameSelect.indexOf('v-for="item in operations"'))
  assert.ok(view.includes("const ALL_OPERATION_FILTER = 'ALL'"))
  assert.ok(view.includes('operationKey = ref(ALL_OPERATION_FILTER)'))
  assert.ok(view.includes('const selectedOperationKey = () => operationKey.value === ALL_OPERATION_FILTER ? null : operationKey.value'))
  assert.ok(view.includes('function restoreAllOperationFilter () { operationKey.value = ALL_OPERATION_FILTER; currentPage.value = 1; load() }'))
  assert.ok(view.includes('operationKey: selectedOperationKey()'))
})

test('API error-log view adds an HTTP status column between source and apiName with a dash fallback for null (Task 421／Requirement 143)', () => {
  assert.ok(view.includes('prop="httpStatus" label="HTTP 狀態碼" width="120"'))
  assert.ok(view.includes("{{ row.httpStatus ?? '—' }}"))
  const sourceIndex = view.indexOf('prop="source"')
  const httpStatusIndex = view.indexOf('prop="httpStatus"')
  const apiNameIndex = view.indexOf('prop="apiName"')
  assert.ok(sourceIndex >= 0)
  assert.ok(httpStatusIndex > sourceIndex)
  assert.ok(apiNameIndex > httpStatusIndex)
})

test('API error-log listing requests one zero-based server page of 100 and renders response metadata', () => {
  assert.ok(api.includes("list: ({ source = 'ALL', sort = 'NEWEST', operationKey = null, page = 0 } = {}) => api.get('/bff/api-error-logs', { params: { source, sort, page, ...(operationKey && { operationKey }) } })"))
  assert.ok(view.includes('const PAGE_SIZE = 100'))
  assert.ok(view.includes(':page-size="PAGE_SIZE"'))
  assert.ok(view.includes('layout="total, prev, pager, next"'))
  assert.ok(view.includes('@current-change="changePage"'))
  assert.ok(view.includes('page: currentPage.value - 1'))
  assert.ok(view.includes('rows.value = result.items'))
  assert.ok(view.includes('totalElements.value = result.totalElements'))
  const filtersEnd = view.indexOf('</div>', view.indexOf('<div class="filters">'))
  const pagination = view.indexOf('<div class="pagination"')
  const table = view.indexOf('<el-table')
  assert.ok(filtersEnd < pagination)
  assert.ok(pagination < table)
  assert.equal(view.includes('.slice('), false)
})

test('list responses are last-request-wins and each filter resets to page one', () => {
  assert.ok(view.includes('let listRequestId = 0'))
  assert.ok(view.includes('const requestId = ++listRequestId'))
  assert.ok(view.includes('if (requestId !== listRequestId) return'))
  assert.ok(view.includes('if (requestId === listRequestId) loading.value = false'))
  assert.ok(view.includes('function changeSort () { currentPage.value = 1; load() }'))
  assert.ok(view.includes('function changeOperation () { if (operationKey.value == null) operationKey.value = ALL_OPERATION_FILTER; currentPage.value = 1; load() }'))
  assert.ok(view.includes('function restoreAllOperationFilter () { operationKey.value = ALL_OPERATION_FILTER; currentPage.value = 1; load() }'))
  assert.ok(view.includes('function changePage (page) { currentPage.value = page; load() }'))
  assert.ok(view.includes('let catalogRequestId = 0'))
  assert.ok(view.includes('requestId === catalogRequestId && requestedSource === source.value'))
})
