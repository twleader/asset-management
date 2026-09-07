<template>
  <div>
    <!-- KPI Cards -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="cardSpan">
        <el-card class="kpi-card">
          <div class="kpi-label">排程總數</div>
          <div class="kpi-value">{{ jobs.length }}</div>
          <div class="kpi-sub">系統自動執行的排程</div>
        </el-card>
      </el-col>
      <el-col v-for="svc in distinctServices" :key="svc" :span="cardSpan">
        <el-card class="kpi-card">
          <div class="kpi-label">{{ svc }}</div>
          <div class="kpi-value" :style="{ color: serviceColor(svc) }">{{ serviceCount(svc) }}</div>
          <div class="kpi-sub">{{ serviceSub(svc) }}</div>
        </el-card>
      </el-col>
      <el-col :span="cardSpan">
        <el-card class="kpi-card">
          <div class="kpi-label">分類數</div>
          <div class="kpi-value">{{ categoryCount }}</div>
          <div class="kpi-sub">依用途分類</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="section-title">系統排程一覽</span>
          <div class="toolbar-right">
            <el-radio-group v-model="serviceFilter" size="small">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button v-for="svc in distinctServices" :key="svc" :label="svc">{{ svc }}</el-radio-button>
            </el-radio-group>
            <el-input
              v-model="keyword"
              size="small"
              placeholder="搜尋名稱 / 說明 / 分類"
              clearable
              style="width:220px"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="以下為系統各項自動排程；執行時間依各市場所在時區，「交易日」另判週末與國定／臨時休市。此頁為唯讀資訊。"
      />

      <el-table
        :data="filteredJobs"
        v-loading="loading"
        row-key="rowKey"
        :default-sort="{ prop: 'service', order: 'ascending' }"
        stripe
        size="small"
        style="width:100%"
      >
        <el-table-column label="服務" width="120" sortable prop="service">
          <template #default="{ row }">
            <el-tag :type="serviceTagType(row.service)" effect="light" size="small">
              {{ row.service }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分類" width="120" sortable prop="category">
          <template #default="{ row }">
            <el-tag type="info" effect="plain" size="small">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="名稱" width="200" prop="name">
          <template #default="{ row }"><span class="job-name">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column label="說明" prop="description" min-width="260" show-overflow-tooltip />
        <el-table-column label="執行時機" width="220" prop="schedule">
          <template #default="{ row }"><span class="schedule">{{ row.schedule }}</span></template>
        </el-table-column>
        <el-table-column label="cron" width="180" prop="cron">
          <template #default="{ row }"><code class="cron">{{ row.cron }}</code></template>
        </el-table-column>
        <el-table-column label="時區" width="90" prop="zone">
          <template #default="{ row }">{{ zoneLabel(row.zone) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { bffApi } from '@/api'

const jobs = ref([])
const loading = ref(false)
const keyword = ref('')
const serviceFilter = ref('all')

const ZONE_LABELS = {
  'Asia/Taipei': '台北',
  'America/New_York': '紐約',
  'Europe/London': '倫敦'
}
function zoneLabel(zone) {
  return ZONE_LABELS[zone] || '—'
}

// 已知服務的顯示樣式對照表（tag 顏色／KPI 卡顏色／技術服務名稱），對未知 service 一律有
// 合理 fallback；KPI 卡、篩選按鈕與 tag 顏色都依 distinctServices 動態產生，不得再用
// 「service === 某固定字串」的二元或三元判斷寫死分支——否則下一個新 service 出現時
// 又會重蹈 Task 421 修正的計數漂移（Requirement 143）。
const SERVICE_TAG_TYPES = { 業務服務: 'primary', 外部行情服務: 'warning', 'BFF 閘道觀測服務': 'success' }
const SERVICE_KPI_COLORS = { 業務服務: '#2563eb', 外部行情服務: '#d97706', 'BFF 閘道觀測服務': '#059669' }
const SERVICE_TECH_NAMES = { 業務服務: 'business-services', 外部行情服務: 'external-materials-service', 'BFF 閘道觀測服務': 'bff' }
function serviceTagType(service) { return SERVICE_TAG_TYPES[service] || 'info' }
function serviceColor(service) { return SERVICE_KPI_COLORS[service] || '#475569' }
function serviceSub(service) { return SERVICE_TECH_NAMES[service] || service }

// 依 jobs 資料依序取得的 distinct service 清單（保留第一次出現的順序）。
const distinctServices = computed(() => {
  const seen = []
  for (const j of jobs.value) {
    if (!seen.includes(j.service)) seen.push(j.service)
  }
  return seen
})
function serviceCount(service) {
  return jobs.value.filter(j => j.service === service).length
}
// KPI 卡數＝distinct service 數 + 排程總數／分類數兩張固定卡；span 依卡數動態算，
// 新增第 4 個 service 時版面自動讓出空間，不必再手動調整欄寬。
const cardSpan = computed(() => Math.max(4, Math.floor(24 / (distinctServices.value.length + 2))))
const categoryCount = computed(() => new Set(jobs.value.map(j => j.category)).size)

const filteredJobs = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return jobs.value.filter(j => {
    if (serviceFilter.value !== 'all' && j.service !== serviceFilter.value) return false
    if (!kw) return true
    return [j.name, j.description, j.category, j.schedule, j.cron]
      .some(v => (v || '').toLowerCase().includes(kw))
  })
})

async function fetchData() {
  loading.value = true
  try {
    const data = await bffApi.scheduleList.get()
    jobs.value = (data || []).map((j, i) => ({ ...j, rowKey: `${j.service}-${j.name}-${i}` }))
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.kpi-card { text-align: center; }
.kpi-label { color: #64748b; font-size: 13px; }
.kpi-value { font-size: 28px; font-weight: 700; color: #1e293b; margin: 6px 0 2px; }
.kpi-sub { color: #94a3b8; font-size: 12px; }

.section-title { font-size: 16px; font-weight: 600; color: #1e293b; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-right { display: flex; align-items: center; gap: 12px; }

.job-name { font-weight: 600; color: #1e293b; }
.schedule { color: #0f766e; }
.cron {
  font-family: 'SFMono-Regular', Menlo, Consolas, monospace;
  font-size: 12px; color: #475569;
  background: #f1f5f9; padding: 2px 6px; border-radius: 4px;
}
</style>
