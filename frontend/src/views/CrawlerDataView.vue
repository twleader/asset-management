<template>
  <div>
    <!-- 查詢區 -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div class="toolbar">
          <span class="section-title">爬蟲資訊查詢</span>
          <span class="hint">查指定日期公開資訊爬蟲（NewsPoller）爬回的 news_headline 資料</span>
        </div>
      </template>

      <div class="query-bar">
        <div class="field">
          <span class="field-label">日期</span>
          <el-date-picker
            v-model="date"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            placeholder="選擇日期"
            style="width:160px"
          />
        </div>
        <div class="field">
          <span class="field-label">日期依據</span>
          <el-radio-group v-model="dateField" size="small">
            <el-radio-button label="fetched">爬取時間</el-radio-button>
            <el-radio-button label="published">資料日期</el-radio-button>
          </el-radio-group>
        </div>
        <div class="field">
          <span class="field-label">類別</span>
          <el-select v-model="category" size="small" clearable placeholder="全部" style="width:150px">
            <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </div>
        <el-button type="primary" :loading="loading" @click="fetchData">
          <el-icon style="margin-right:4px"><Search /></el-icon>查詢
        </el-button>
        <span class="count" v-if="!loading">共 {{ rows.length }} 筆</span>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin:12px 0"
        title="「爬取時間」＝那天爬蟲實際抓回入庫（fetched_at）；「資料日期」＝新聞發布日／交易日（published_at）。區間以台北時間該日 00:00–翌日 00:00 計。"
      />

      <el-table :data="rows" v-loading="loading" stripe size="small" style="width:100%"
                empty-text="該日期查無爬回資料">
        <el-table-column label="資料日期" width="150" prop="publishedAt">
          <template #default="{ row }">{{ fmt(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="爬取時間" width="150" prop="fetchedAt">
          <template #default="{ row }">{{ fmt(row.fetchedAt) }}</template>
        </el-table-column>
        <el-table-column label="類別" width="120" prop="category">
          <template #default="{ row }">
            <el-tag :type="catType(row.category)" effect="light" size="small">{{ catLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="來源" width="100" prop="source" />
        <el-table-column label="地區" width="70" prop="region">
          <template #default="{ row }">{{ row.region || '—' }}</template>
        </el-table-column>
        <el-table-column label="標題" min-width="280" prop="title">
          <template #default="{ row }">
            <a v-if="row.url" :href="row.url" target="_blank" rel="noopener" class="title-link">{{ row.title }}</a>
            <span v-else>{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240" prop="summary" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 爬蟲執行時間設定 -->
    <el-card>
      <template #header>
        <div class="toolbar">
          <span class="section-title">爬蟲執行時間設定</span>
          <span class="hint">公開資訊新聞爬蟲（NewsPoller）每天執行的時間點，可設定多個</span>
        </div>
      </template>

      <el-alert
        v-if="!auth.isAdmin"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="僅管理者可修改爬蟲執行時間，以下為唯讀顯示。"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="設定後即時生效（免重啟），下一分鐘起依新時間執行。清空全部時間點＝該爬蟲不再自動執行。"
      />

      <div v-loading="scheduleLoading">
        <div v-for="(item, idx) in schedule" :key="idx" class="time-row">
          <el-time-picker
            v-model="item.time"
            value-format="HH:mm"
            format="HH:mm"
            placeholder="時間"
            :clearable="false"
            :disabled="!auth.isAdmin"
            style="width:130px"
          />
          <el-switch
            v-model="item.enabled"
            active-text="啟用"
            inactive-text="停用"
            :disabled="!auth.isAdmin"
            style="margin:0 12px"
          />
          <el-button
            v-if="auth.isAdmin"
            type="danger"
            plain
            size="small"
            @click="removeRow(idx)"
          >移除</el-button>
        </div>

        <div v-if="!schedule.length" class="empty-schedule">目前未設定任何執行時間點。</div>

        <div v-if="auth.isAdmin" class="schedule-actions">
          <el-button plain @click="addRow">
            <el-icon style="margin-right:4px"><Plus /></el-icon>新增時間點
          </el-button>
          <el-button type="primary" :loading="saving" @click="saveSchedule">儲存設定</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()

const CATEGORIES = [
  { value: 'news', label: '新聞' },
  { value: 'twse-institutional', label: '三大法人' },
  { value: 'twse-turnover', label: '大盤成交' },
  { value: 'fx', label: '台幣兌美元' },
  { value: 'us-market', label: '美股指數' },
  { value: 'kr-market', label: '韓股指數' }
]
const CAT_LABELS = Object.fromEntries(CATEGORIES.map(c => [c.value, c.label]))
const CAT_TYPES = {
  news: 'primary',
  'twse-institutional': 'success',
  'twse-turnover': 'warning',
  fx: 'danger',
  'us-market': 'info',
  'kr-market': 'info'
}
function catLabel(c) { return CAT_LABELS[c] || c || '—' }
function catType(c) { return CAT_TYPES[c] || 'info' }
function fmt(iso) { return iso ? dayjs(iso).format('YYYY-MM-DD HH:mm') : '—' }

// --- 查詢 ---
const date = ref(dayjs().format('YYYY-MM-DD'))
const dateField = ref('fetched')
const category = ref('')
const rows = ref([])
const loading = ref(false)

async function fetchData() {
  loading.value = true
  try {
    rows.value = (await bffApi.crawlerData.query(date.value, dateField.value, category.value || undefined)) || []
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e?.response?.data?.detail || e.message))
    rows.value = []
  } finally {
    loading.value = false
  }
}

// --- 排程設定 ---
const schedule = ref([])
const scheduleLoading = ref(false)
const saving = ref(false)

function pad(n) { return String(n).padStart(2, '0') }

async function fetchSchedule() {
  scheduleLoading.value = true
  try {
    const data = (await bffApi.crawlerData.getSchedule()) || []
    schedule.value = data.map(s => ({ time: `${pad(s.hour)}:${pad(s.minute)}`, enabled: s.enabled }))
  } finally {
    scheduleLoading.value = false
  }
}

function addRow() {
  schedule.value.push({ time: '09:00', enabled: true })
}
function removeRow(idx) {
  schedule.value.splice(idx, 1)
}

async function saveSchedule() {
  for (const item of schedule.value) {
    if (!item.time) { ElMessage.warning('請填寫每個時間點'); return }
  }
  const payload = schedule.value.map(item => {
    const [h, m] = item.time.split(':')
    return { hour: Number(h), minute: Number(m), enabled: item.enabled }
  })
  saving.value = true
  try {
    const data = (await bffApi.crawlerData.saveSchedule(payload)) || []
    schedule.value = data.map(s => ({ time: `${pad(s.hour)}:${pad(s.minute)}`, enabled: s.enabled }))
    ElMessage.success('已儲存爬蟲執行時間，下一分鐘起生效')
  } catch (e) {
    ElMessage.error('儲存失敗：' + (e?.response?.data?.detail || e.message))
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  fetchData()
  fetchSchedule()
})
</script>

<style scoped>
.section-title { font-size: 16px; font-weight: 600; color: #1e293b; }
.toolbar { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.hint { color: #94a3b8; font-size: 12px; }

.query-bar { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; }
.field { display: flex; align-items: center; gap: 8px; }
.field-label { color: #475569; font-size: 13px; }
.count { color: #64748b; font-size: 13px; }

.title-link { color: #2563eb; text-decoration: none; }
.title-link:hover { text-decoration: underline; }

.time-row { display: flex; align-items: center; margin-bottom: 10px; }
.empty-schedule { color: #94a3b8; font-size: 13px; padding: 8px 0; }
.schedule-actions { margin-top: 12px; display: flex; gap: 12px; }
</style>
