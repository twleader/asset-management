<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">🔔 股票到價警示</span>
          <el-button type="primary" :icon="Plus" @click="openDialog()">新增警示</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom:16px">
        <template #title>
          系統每 5 分鐘隨股價更新自動檢查，條件符合時記錄觸發時間與股價（同一條件 24 小時內不重複觸發）。
        </template>
      </el-alert>

<el-table ref="tableRef" :data="alerts" v-loading="loading" border stripe row-key="id">
        <!-- 拖拽把手 -->
        <el-table-column width="36" align="center">
          <template #default>
            <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="股票" width="130">
          <template #default="{ row }">
            <span style="font-weight:600">{{ row.stockCode }}</span>
            <div style="font-size:12px;color:#64748b">{{ row.stockName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="市場" width="90">
          <template #default="{ row }">
            <span style="display:inline-flex;align-items:center;gap:5px;font-size:13px;font-weight:500">
              <!-- 民進黨黨旗 SVG -->
              <svg v-if="row.market === '台股'" xmlns="http://www.w3.org/2000/svg"
                   width="24" height="16" viewBox="0 0 60 40" style="border-radius:2px;flex-shrink:0">
                <!-- 綠底 -->
                <rect width="60" height="40" fill="#009900"/>
                <!-- 白色橫帶 -->
                <rect x="0" y="14" width="60" height="12" fill="white"/>
                <!-- 白色縱帶 -->
                <rect x="22" y="0" width="16" height="40" fill="white"/>
                <!-- 台灣島形（簡化輪廓） -->
                <polygon points="30,8 33,13 35,18 34,23 31,27 28,26 26,22 27,16 29,11" fill="#009900"/>
              </svg>
              <span v-else style="font-size:18px;line-height:16px;display:inline-block;width:24px;text-align:center;flex-shrink:0">🇺🇸</span>
              <span>{{ row.market }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="警示條件" min-width="180">
          <template #default="{ row }">
            <span>{{ row.conditionLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="建立時間" width="120">
          <template #default="{ row }">
            <span style="font-size:12px;color:#64748b">{{ fmtDt(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="狀態" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.active" @change="toggleActive(row)" />
          </template>
        </el-table-column>
        <el-table-column label="觸發時間 / 股價 / 均線 / KD" width="195">
          <template #default="{ row }">
            <template v-if="row.lastTriggeredAt">
              <div style="font-size:12px;color:#64748b">{{ fmtDt(row.lastTriggeredAt) }}</div>
              <div v-if="row.lastTriggeredPrice != null"
                   style="font-size:12px;color:#0f172a">
                股價 <strong>{{ Number(row.lastTriggeredPrice).toLocaleString() }}</strong>
              </div>
              <div v-if="row.lastTriggeredMaValue != null"
                   style="font-size:12px;color:#0f172a">
                {{ maLabel(row.alertType) }}
                <strong>{{ Number(row.lastTriggeredMaValue).toLocaleString() }}</strong>
              </div>
              <div v-if="row.lastTriggeredKdValue != null || row.lastTriggeredDValue != null"
                   style="font-size:12px;color:#2563eb">
                <span v-if="row.lastTriggeredKdValue != null">K {{ Number(row.lastTriggeredKdValue).toFixed(1) }}</span>
                <span v-if="row.lastTriggeredDValue != null" style="margin-left:6px">D {{ Number(row.lastTriggeredDValue).toFixed(1) }}</span>
              </div>
            </template>
            <span v-else style="font-size:12px;color:#94a3b8">尚未觸發</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" circle @click="openDialog(row)" />
            <el-button size="small" type="danger" :icon="Delete" circle @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/編輯 Dialog -->
    <el-dialog v-model="dialogVisible" :title="editId ? '編輯警示' : '新增警示'" width="520px">
      <el-form :model="form" label-width="110px" ref="formRef">
        <el-divider content-position="left">股票</el-divider>

        <el-form-item label="市場" required>
          <el-radio-group v-model="form.market">
            <el-radio value="台股">台股</el-radio>
            <el-radio value="美股">美股</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="股票代號" required>
          <el-input v-model="form.stockCode" placeholder="例：2330 或 AAPL"
            style="width:140px;margin-right:8px"
            @blur="fetchStockName" />
          <el-input v-model="form.stockName" placeholder="（離開欄位自動帶入）"
            style="width:200px"
            :suffix-icon="lookingUpName ? Loading : undefined" />
        </el-form-item>

        <el-divider content-position="left">警示條件</el-divider>

        <el-form-item label="條件類型" required>
          <el-select v-model="form.conditionGroup" style="width:100%" @change="onGroupChange">
            <el-option value="QUARTERLY_MA" label="季線偏離（60 日均線）" />
            <el-option value="ANNUAL_MA"    label="年線偏離（240 日均線）" />
            <el-option value="KD"          label="KD 值" />
          </el-select>
        </el-form-item>

        <!-- 季線 -->
        <template v-if="form.conditionGroup === 'QUARTERLY_MA'">
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於季線</el-radio>
              <el-radio value="BELOW">低於季線</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="幅度">
            <el-radio-group v-model="form.threshold">
              <el-radio :value="0">0%</el-radio>
              <el-radio :value="5">5%</el-radio>
              <el-radio :value="10">10%</el-radio>
              <el-radio :value="15">15%</el-radio>
              <el-radio :value="20">20%</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>

        <!-- 年線 -->
        <template v-if="form.conditionGroup === 'ANNUAL_MA'">
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於年線</el-radio>
              <el-radio value="BELOW">低於年線</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="幅度">
            <el-radio-group v-model="form.threshold">
              <el-radio :value="0">剛高於／低於（0%）</el-radio>
              <el-radio :value="5">5%</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>

        <!-- KD -->
        <template v-if="form.conditionGroup === 'KD'">
          <el-form-item label="指標">
            <el-radio-group v-model="form.kdIndicator">
              <el-radio value="K">K 值</el-radio>
              <el-radio value="D">D 值</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於</el-radio>
              <el-radio value="BELOW">低於</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="門檻值">
            <el-input-number v-model="form.threshold" :min="0" :max="100" :step="1" />
            <span style="margin-left:8px;color:#64748b">（例：高於 80 / 低於 20）</span>
          </el-form-item>
        </template>

        <el-form-item label="啟用">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Loading, Operation } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import dayjs from 'dayjs'
import api from '@/api/index.js'

// ===== State =====
const alerts = ref([])
const loading = ref(false)
const saving = ref(false)
const lookingUpName = ref(false)
const dialogVisible = ref(false)
const editId = ref(null)
const tableRef = ref(null)

const defaultForm = () => ({
  market: '台股',
  stockCode: '',
  stockName: '',
  conditionGroup: 'QUARTERLY_MA',
  direction: 'ABOVE',
  kdIndicator: 'K',   // K 或 D，僅 KD 條件使用
  threshold: 5,
  active: true,
})
const form = reactive(defaultForm())

// ===== Load =====
async function loadAlerts() {
  loading.value = true
  try {
    const res = await api.get('/stock-alerts')
    alerts.value = res
  } finally {
    loading.value = false
  }
}

// ===== Drag & Drop 排序 =====
function initSortable() {
  const tbody = tableRef.value?.$el?.querySelector('tbody')
  if (!tbody) return
  Sortable.create(tbody, {
    handle: '.drag-handle',
    animation: 150,
    onEnd({ oldIndex, newIndex }) {
      if (oldIndex === newIndex) return
      const moved = alerts.value.splice(oldIndex, 1)[0]
      alerts.value.splice(newIndex, 0, moved)
      // 儲存新順序
      api.put('/stock-alerts/reorder', alerts.value.map(a => a.id))
        .catch(() => ElMessage.error('排序儲存失敗'))
    }
  })
}

onMounted(async () => {
  await loadAlerts()
  nextTick(initSortable)
})


// ===== Dialog =====
function openDialog(row = null) {
  Object.assign(form, defaultForm())
  editId.value = null
  if (row) {
    editId.value = row.id
    form.market = row.market
    form.stockCode = row.stockCode
    form.stockName = row.stockName || ''
    form.active = row.active
    parseAlertType(row.alertType, row.threshold)
  }
  dialogVisible.value = true
}

function parseAlertType(alertType, threshold) {
  if (alertType.startsWith('QUARTERLY_MA')) {
    form.conditionGroup = 'QUARTERLY_MA'
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.threshold = Number(threshold)
  } else if (alertType.startsWith('ANNUAL_MA')) {
    form.conditionGroup = 'ANNUAL_MA'
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.threshold = Number(threshold)
  } else {
    form.conditionGroup = 'KD'
    // KD_D_ABOVE / KD_D_BELOW → D 值；KD_ABOVE / KD_BELOW → K 值
    form.kdIndicator = alertType.startsWith('KD_D') ? 'D' : 'K'
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.threshold = Number(threshold)
  }
}

function onGroupChange() {
  form.direction = 'ABOVE'
  form.kdIndicator = 'K'
  form.threshold = form.conditionGroup === 'KD' ? 80 : 5
}

function buildAlertType() {
  const dir = form.direction
  if (form.conditionGroup === 'QUARTERLY_MA') return `QUARTERLY_MA_${dir}_PCT`
  if (form.conditionGroup === 'ANNUAL_MA')    return `ANNUAL_MA_${dir}_PCT`
  // KD：K 值 → KD_ABOVE/BELOW；D 值 → KD_D_ABOVE/BELOW
  return form.kdIndicator === 'D' ? `KD_D_${dir}` : `KD_${dir}`
}

async function fetchStockName() {
  if (!form.stockCode || !form.market) return
  lookingUpName.value = true
  try {
    const res = await api.get('/stock-alerts/lookup-name', {
      params: { code: form.stockCode.trim().toUpperCase(), market: form.market }
    })
    if (res.stockName) {
      form.stockName = res.stockName
    } else {
      ElMessage.warning('找不到此股票名稱，請手動填寫')
    }
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e.message || ''))
  } finally {
    lookingUpName.value = false
  }
}

async function save() {
  if (!form.stockCode || !form.market) {
    ElMessage.warning('請填寫股票代號與市場')
    return
  }
  saving.value = true
  try {
    const payload = {
      market: form.market,
      stockCode: form.stockCode.toUpperCase(),
      stockName: form.stockName || null,
      alertType: buildAlertType(),
      threshold: form.threshold,
      active: form.active,
    }
    if (editId.value) {
      await api.put(`/stock-alerts/${editId.value}`, payload)
    } else {
      await api.post('/stock-alerts', payload)
    }
    ElMessage.success('儲存成功')
    dialogVisible.value = false
    loadAlerts()
  } catch (e) {
    ElMessage.error('儲存失敗')
  } finally {
    saving.value = false
  }
}

async function toggleActive(row) {
  try {
    const res = await api.patch(`/stock-alerts/${row.id}/active`)
    Object.assign(row, res.data)
  } catch {
    row.active = !row.active
    ElMessage.error('操作失敗')
  }
}

async function remove(row) {
  await ElMessageBox.confirm(
    `確定刪除 ${row.stockCode} 的「${row.conditionLabel}」警示？`,
    '刪除警示', { type: 'warning' }
  )
  await api.delete(`/stock-alerts/${row.id}`)
  ElMessage.success('已刪除')
  loadAlerts()
}

const fmtDt = (dt) => dayjs(dt).format('MM/DD HH:mm')

// 根據 alertType 回傳均線標籤
const maLabel = (alertType) => {
  if (!alertType) return ''
  if (alertType.startsWith('QUARTERLY_MA')) return '季線'
  if (alertType.startsWith('ANNUAL_MA'))    return '年線'
  return ''
}
</script>

<style scoped>
.page-container { padding: 4px; }
.section-title { font-size: 16px; font-weight: 600; }
</style>
