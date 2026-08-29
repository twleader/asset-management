<template>
  <div>
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="section-title">富邦證 API 一覽</span>
          <div class="toolbar-right">
            <el-radio-group v-model="connectedFilter" size="small">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="connected">已串接</el-radio-button>
              <el-radio-button label="not-connected">未串接</el-radio-button>
            </el-radio-group>
            <el-select v-model="categoryFilter" size="small" style="width:160px">
              <el-option label="全部分類" value="all" />
              <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
            </el-select>
            <el-input
              v-model="keyword"
              size="small"
              placeholder="搜尋名稱 / 說明 / 分類 / SDK 方法"
              clearable
              style="width:240px"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>
        </div>
      </template>

      <el-alert
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom:12px"
        title="本頁列出富邦官方 SDK 已驗證存在的唯讀查詢能力，並標示是否已被本系統串接；系統不提供、也不會透過此頁面或任何其他路徑代為下單、改單、撤單或執行任何交易。「未串接」代表 SDK 具備此查詢能力但本系統尚未整合呼叫，僅供資訊盤點，不代表可透過本系統呼叫。如需下單，請自行至富邦官方平台或 App 操作。"
      />

      <el-table
        :data="filteredApis"
        v-loading="loading"
        :row-key="row => row.rowKey"
        stripe
        size="small"
        style="width:100%"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-panel">
              <div class="expand-item">
                <span class="expand-label">SDK 方法／頻道</span>
                <code class="sdk-ref">{{ row.sdkReference }}</code>
              </div>
              <div class="expand-item">
                <span class="expand-label">請求參數</span>
                <pre class="expand-pre">{{ row.requestSummary }}</pre>
              </div>
              <div class="expand-item">
                <span class="expand-label">回應內容</span>
                <pre class="expand-pre">{{ row.responseSummary }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="已串接" width="90">
          <template #default="{ row }">
            <el-tag :type="row.connected ? 'success' : 'info'" effect="light" size="small">
              {{ row.connected ? '已串接' : '未串接' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分類" width="150" prop="category">
          <template #default="{ row }">
            <el-tag type="info" effect="plain" size="small">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名稱" width="200" prop="name">
          <template #default="{ row }"><span class="api-name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="HTTP 端點" width="280">
          <template #default="{ row }">
            <code v-if="row.connected" class="http-endpoint">{{ row.httpEndpoint }}</code>
            <span v-else class="not-connected">－</span>
          </template>
        </el-table-column>
        <el-table-column label="唯讀用途說明" prop="description" min-width="280" show-overflow-tooltip />
        <el-table-column label="呼叫端／使用情境" prop="consumer" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { bffApi } from '@/api'

/** 七類固定分類，順序與 BFF DTO 說明一致。 */
const CATEGORIES = [
  '連線狀態查詢', '帳戶／庫存查詢', '委託與交易資訊查詢',
  '個股報價查詢', '歷史成交查詢', '行情查詢', '即時推播'
]

const apis = ref([])
const loading = ref(false)
const keyword = ref('')
const connectedFilter = ref('all')
const categoryFilter = ref('all')

const filteredApis = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return apis.value.filter(a => {
    if (connectedFilter.value === 'connected' && !a.connected) return false
    if (connectedFilter.value === 'not-connected' && a.connected) return false
    if (categoryFilter.value !== 'all' && a.category !== categoryFilter.value) return false
    if (!kw) return true
    return [a.name, a.description, a.category, a.sdkReference]
      .some(v => (v || '').toLowerCase().includes(kw))
  })
})

async function fetchData() {
  loading.value = true
  try {
    const data = await bffApi.fubonApi.get()
    apis.value = (data || []).map((a, i) => ({ ...a, rowKey: `${a.sdkReference}-${a.httpEndpoint}-${i}` }))
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.section-title { font-size: 16px; font-weight: 600; color: #1e293b; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-right { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }

.api-name { font-weight: 600; color: #1e293b; }
.http-endpoint {
  font-family: 'SFMono-Regular', Menlo, Consolas, monospace;
  font-size: 12px; color: #475569;
  background: #f1f5f9; padding: 2px 6px; border-radius: 4px;
}
.not-connected { color: #94a3b8; }

.expand-panel { padding: 8px 24px 16px; display: flex; flex-direction: column; gap: 10px; }
.expand-item { display: flex; flex-direction: column; gap: 4px; }
.expand-label { font-size: 12px; font-weight: 600; color: #64748b; }
.sdk-ref {
  font-family: 'SFMono-Regular', Menlo, Consolas, monospace;
  font-size: 12px; color: #0f766e;
  background: #f1f5f9; padding: 2px 6px; border-radius: 4px;
  width: fit-content;
}
.expand-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  color: #334155;
  background: #f8fafc;
  border-radius: 4px;
  padding: 8px 10px;
}
</style>
