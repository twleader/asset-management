<template>
  <section class="page-shell">
    <h2>API logs 查詢</h2>
    <div class="filters">
      <el-radio-group v-model="source" @change="changeSource"><el-radio-button label="ALL">全部</el-radio-button><el-radio-button label="OPEN_API">開放 API</el-radio-button><el-radio-button label="FUBON_API">富邦證 API</el-radio-button></el-radio-group>
      <el-select v-model="sort" @change="load" aria-label="時間排序"><el-option label="由近而遠" value="NEWEST"/><el-option label="由遠而近" value="OLDEST"/></el-select>
      <el-select v-model="operationKey" clearable placeholder="單一 API" @change="load"><el-option v-for="item in operations" :key="item.operationKey" :label="item.apiName" :value="item.operationKey"/></el-select>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"/>
    <el-table :data="rows" v-loading="loading" row-key="id" @expand-change="expand">
      <el-table-column type="expand"><template #default="{ row }"><pre v-if="Object.hasOwn(details, row.id)" class="trace">{{ details[row.id] }}</pre><span v-else>載入詳細錯誤中…</span></template></el-table-column>
      <el-table-column prop="occurredAt" label="發生時間" min-width="190"/><el-table-column prop="source" label="來源" width="130"/><el-table-column prop="apiName" label="API 名稱" min-width="160"/><el-table-column prop="messageHeader" label="錯誤訊息標頭" min-width="360"/>
    </el-table>
    <el-empty v-if="!loading && !error && rows.length === 0" description="目前沒有錯誤日誌"/>
  </section>
</template>
<script setup>
import { bffApi, apiErrorMessage } from '@/api'
const source = ref('ALL'), sort = ref('NEWEST'), operationKey = ref(null), rows = ref([]), operations = ref([]), details = ref({}), loading = ref(false), error = ref('')
async function loadOperations () { operations.value = await bffApi.apiErrorLogs.operations(source.value) }
async function load () { loading.value = true; error.value = ''; try { rows.value = await bffApi.apiErrorLogs.list({ source: source.value, sort: sort.value, operationKey: operationKey.value }) } catch (e) { error.value = apiErrorMessage(e, '無法讀取 API logs') } finally { loading.value = false } }
async function changeSource () { operationKey.value = null; await loadOperations(); await load() }
async function expand (row, expandedRows) { if (!expandedRows.some(item => item.id === row.id) || Object.hasOwn(details.value, row.id)) return; try { const detail = await bffApi.apiErrorLogs.detail(row.id); details.value = { ...details.value, [row.id]: detail.stackTrace } } catch (e) { error.value = apiErrorMessage(e, '無法讀取詳細錯誤') } }
onMounted(async () => { await loadOperations(); await load() })
</script>
<style scoped>.page-shell{padding:24px}.filters{display:flex;gap:12px;align-items:center;margin:16px 0}.trace{white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto;margin:0}</style>
