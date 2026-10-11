<template>
  <section class="page-shell">
    <h2>API logs 查詢</h2>
    <div class="filters">
      <el-select v-model="source" @change="changeSource" aria-label="來源"><el-option label="全部" value="ALL"/><el-option label="開放 API" value="OPEN_API"/><el-option label="富邦證 API" value="FUBON_API"/></el-select>
      <el-select v-model="sort" @change="changeSort" aria-label="時間排序"><el-option label="由近而遠" value="NEWEST"/><el-option label="由遠而近" value="OLDEST"/></el-select>
      <el-select v-model="operationKey" clearable placeholder="單一 API" @change="changeOperation" @clear="restoreAllOperationFilter" aria-label="API 名稱" :value-on-clear="null"><el-option label="全部" :value="ALL_OPERATION_FILTER"/><el-option v-for="item in operations" :key="item.operationKey" :label="operationLabel(item)" :value="item.operationKey"/></el-select>
    </div>
    <el-alert v-if="operationsError" :title="operationsError" type="error" show-icon :closable="false"/>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"/>
    <el-alert v-if="detailError" :title="detailError" type="error" show-icon :closable="false"/>
    <div class="pagination" aria-label="API logs 分頁">
      <el-pagination :current-page="currentPage" :page-size="PAGE_SIZE" :total="totalElements" layout="total, prev, pager, next" @current-change="changePage"/>
    </div>
    <el-table :data="rows" v-loading="loading" row-key="id" @expand-change="expand">
      <el-table-column type="expand"><template #default="{ row }"><pre v-if="Object.hasOwn(details, row.id)" class="trace">{{ details[row.id] }}</pre><span v-else>載入詳細錯誤中…</span></template></el-table-column>
      <el-table-column prop="occurredAt" label="發生時間" min-width="190"/><el-table-column prop="source" label="來源" width="130"/><el-table-column prop="httpStatus" label="HTTP 狀態碼" width="120"><template #default="{ row }">{{ row.httpStatus ?? '—' }}</template></el-table-column><el-table-column prop="apiName" label="API 名稱" min-width="160"/><el-table-column prop="messageHeader" label="錯誤訊息標頭" min-width="360"/>
    </el-table>
    <el-empty v-if="!loading && !error && rows.length === 0" description="目前沒有錯誤日誌"/>
  </section>
</template>
<script setup>
import { bffApi, apiErrorMessage } from '@/api'
const ALL_OPERATION_FILTER = 'ALL'
const PAGE_SIZE = 100
const source = ref('ALL'), sort = ref('NEWEST'), operationKey = ref(ALL_OPERATION_FILTER), currentPage = ref(1), totalElements = ref(0), rows = ref([]), operations = ref([]), details = ref({}), loading = ref(false), error = ref(''), operationsError = ref(''), detailError = ref('')
let listRequestId = 0
let catalogRequestId = 0
const operationLabel = item => `${item.apiName} — ${item.apiUrl}`
const selectedOperationKey = () => operationKey.value === ALL_OPERATION_FILTER ? null : operationKey.value
function invalidateListRequests () { listRequestId += 1; rows.value = []; totalElements.value = 0 }
async function loadOperations (requestedSource = source.value) {
  const requestId = ++catalogRequestId
  operationsError.value = ''
  try {
    const catalog = await bffApi.apiErrorLogs.operations(requestedSource)
    if (requestId === catalogRequestId && requestedSource === source.value) operations.value = catalog
  } catch (e) {
    if (requestId === catalogRequestId && requestedSource === source.value) operationsError.value = apiErrorMessage(e, '無法讀取 API 名稱')
  }
}
async function load () {
  const requestId = ++listRequestId
  loading.value = true
  error.value = ''
  try {
    const result = await bffApi.apiErrorLogs.list({ source: source.value, sort: sort.value, operationKey: selectedOperationKey(), page: currentPage.value - 1 })
    if (requestId !== listRequestId) return
    rows.value = result.items
    totalElements.value = result.totalElements
  } catch (e) {
    if (requestId !== listRequestId) return
    rows.value = []
    totalElements.value = 0
    error.value = apiErrorMessage(e, '無法讀取 API logs')
  } finally {
    if (requestId === listRequestId) loading.value = false
  }
}
async function changeSource () {
  const requestedSource = source.value
  invalidateListRequests()
  const requestId = listRequestId
  loading.value = true
  currentPage.value = 1
  operationKey.value = ALL_OPERATION_FILTER
  operations.value = []
  operationsError.value = ''
  await loadOperations(requestedSource)
  if (requestId !== listRequestId || requestedSource !== source.value) return
  await load()
}
function changeSort () { currentPage.value = 1; load() }
function changeOperation () { if (operationKey.value == null) operationKey.value = ALL_OPERATION_FILTER; currentPage.value = 1; load() }
function restoreAllOperationFilter () { operationKey.value = ALL_OPERATION_FILTER; currentPage.value = 1; load() }
function changePage (page) { currentPage.value = page; load() }
async function expand (row, expandedRows) { if (!expandedRows.some(item => item.id === row.id) || Object.hasOwn(details.value, row.id)) return; try { const detail = await bffApi.apiErrorLogs.detail(row.id); details.value = { ...details.value, [row.id]: detail.stackTrace }; detailError.value = '' } catch (e) { detailError.value = apiErrorMessage(e, '無法讀取詳細錯誤') } }
onMounted(() => { loadOperations(source.value); load() })
</script>
<style scoped>.page-shell{padding:24px}.filters{display:grid;grid-template-columns:160px minmax(0, 1fr) minmax(0, 2fr);gap:12px;align-items:center;margin:16px 0}.filters>.el-select{width:100%}.pagination{display:flex;justify-content:flex-end;margin:0 0 12px}.trace{white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto;margin:0}@media (max-width:720px){.filters{grid-template-columns:1fr}.pagination{justify-content:center}}</style>
