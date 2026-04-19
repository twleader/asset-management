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

<el-table ref="tableRef" :data="alerts" v-loading="loading" border stripe row-key="id"
        @row-dblclick="onStockDblClick">
        <!-- 拖拽把手 -->
        <el-table-column width="36" align="center">
          <template #default>
            <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
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
        <el-table-column label="股票代號" width="100">
          <template #default="{ row }">
            <span style="font-weight:600">{{ row.stockCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="股票名稱" min-width="120">
          <template #default="{ row }">
            <span style="color:#475569">{{ row.stockName }}</span>
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

    <!-- 股價走勢分析 Dialog -->
    <el-dialog
      v-model="analysisVisible"
      :title="`${analysisStock?.stockCode} ${analysisStock?.stockName}　股價走勢分析`"
      width="900px"
      destroy-on-close
      draggable>
      <div v-if="analysisLoading" style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:60px 0;color:#64748b;font-size:14px">
        <el-icon class="is-loading" size="36"><Loading /></el-icon>
        <div>載入歷史股價中…</div>
      </div>
      <div v-else-if="!analysisHistory.length" style="text-align:center;padding:60px 0;color:#94a3b8;font-size:14px">
        無歷史資料，請先執行股價補齊
      </div>
      <template v-else>
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
          <el-tag size="small" type="info">雙擊任意股票可開啟分析</el-tag>
          <div style="display:flex;align-items:center;gap:6px">
            <span style="color:#64748b;font-size:12px">期間：</span>
            <el-button-group>
              <el-button
                v-for="opt in rangeOptions" :key="opt.label"
                size="small"
                :type="analysisMonths === opt.months ? 'primary' : 'default'"
                @click="analysisMonths = opt.months">
                {{ opt.label }}
              </el-button>
            </el-button-group>
            <span style="color:#64748b;font-size:12px;margin-left:8px">滾輪縮放 / 拖曳平移</span>
          </div>
        </div>
        <v-chart :option="analysisChartOption" style="height:580px" autoresize />
      </template>
    </el-dialog>

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
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Plus, Edit, Delete, Loading, Operation } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import dayjs from 'dayjs'
import api, { marketDataApi } from '@/api/index.js'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])

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

// ===== 股價走勢分析 =====
const analysisVisible = ref(false)
const analysisStock = ref(null)
const analysisLoading = ref(false)
const analysisHistory = ref([])
const analysisMonths = ref(12)

const rangeOptions = [
  { label: '1個月', months: 1 },
  { label: '3個月', months: 3 },
  { label: '1年',   months: 12 },
  { label: '2年',   months: 24 },
  { label: '3年',   months: 36 },
  { label: '5年',   months: 60 },
  { label: '10年',  months: 120 },
]

async function fetchAnalysisHistory() {
  if (!analysisStock.value) return
  analysisLoading.value = true
  analysisHistory.value = []
  try {
    const end = new Date().toISOString().split('T')[0]
    const startDate = new Date()
    startDate.setMonth(startDate.getMonth() - analysisMonths.value)
    const start = startDate.toISOString().split('T')[0]
    const data = await marketDataApi.getStockHistory(
      analysisStock.value.stockCode, analysisStock.value.market, start, end
    )
    analysisHistory.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.warn('無法取得歷史股價:', e)
  } finally {
    analysisLoading.value = false
  }
}

watch(analysisMonths, () => { if (analysisVisible.value) fetchAnalysisHistory() })

async function onStockDblClick(row) {
  analysisStock.value = { stockCode: row.stockCode, stockName: row.stockName, market: row.market }
  analysisMonths.value = 12
  analysisVisible.value = true
  await fetchAnalysisHistory()
}

function calcMA(prices, n) {
  return prices.map((_, i) => {
    if (i < n - 1) return null
    const avg = prices.slice(i - n + 1, i + 1).reduce((s, v) => s + v, 0) / n
    return parseFloat(avg.toFixed(2))
  })
}

function calcKD(hist, period = 9) {
  const highs  = hist.map(d => Number(d.highPrice  || d.closePrice || 0))
  const lows   = hist.map(d => Number(d.lowPrice   || d.closePrice || 0))
  const closes = hist.map(d => Number(d.closePrice || 0))
  const K = [], D = []
  let prevK = 50, prevD = 50
  for (let i = 0; i < closes.length; i++) {
    if (i < period - 1) { K.push(null); D.push(null); continue }
    const hh = Math.max(...highs.slice(i - period + 1, i + 1))
    const ll  = Math.min(...lows.slice(i - period + 1, i + 1))
    const rsv = hh === ll ? 50 : (closes[i] - ll) / (hh - ll) * 100
    const k = prevK * 2 / 3 + rsv / 3
    const d = prevD * 2 / 3 + k  / 3
    K.push(parseFloat(k.toFixed(2)))
    D.push(parseFloat(d.toFixed(2)))
    prevK = k; prevD = d
  }
  return { K, D }
}

const analysisChartOption = computed(() => {
  const hist = analysisHistory.value
  if (!hist.length) return {}
  const dates  = hist.map(d => d.tradingDate)
  const prices = hist.map(d => parseFloat(Number(d.closePrice || 0).toFixed(2)))
  const ma20  = calcMA(prices, 20)
  const ma60  = calcMA(prices, 60)
  const ma240 = calcMA(prices, 240)
  const { K, D } = calcKD(hist)
  return {
    backgroundColor: '#fff',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }] },
      formatter: params => {
        let html = `<strong>${params[0].axisValue}</strong><br/>`
        params.forEach(p => { if (p.value != null) html += `${p.marker} ${p.seriesName}: <b>${p.value}</b><br/>` })
        return html
      }
    },
    legend: { data: ['收盤價', '月線MA20', '季線MA60', '年線MA240', 'K', 'D'], top: 8, textStyle: { fontSize: 12 } },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 64, right: 80, top: 48, bottom: 190 },
      { left: 64, right: 80, top: 'auto', height: 90, bottom: 60 }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 0, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], start: 0, end: 100, height: 20, bottom: 8 }
    ],
    xAxis: [
      { gridIndex: 0, type: 'category', data: dates, boundaryGap: false, axisLabel: { show: false }, axisLine: { onZero: false } },
      { gridIndex: 1, type: 'category', data: dates, boundaryGap: false, axisLabel: { rotate: 30, fontSize: 10, formatter: v => v.substring(0, 7) } }
    ],
    yAxis: [
      { gridIndex: 0, type: 'value', scale: true, axisLabel: { formatter: v => v.toFixed(0) }, splitLine: { lineStyle: { color: '#f0f0f0' } } },
      { gridIndex: 1, type: 'value', min: 0, max: 100, splitNumber: 2, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#f0f0f0' } } }
    ],
    series: [
      { name: '收盤價', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: prices, lineStyle: { width: 2, color: '#3b82f6' }, itemStyle: { color: '#3b82f6' }, showSymbol: false,
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(59,130,246,0.12)' }, { offset: 1, color: 'rgba(59,130,246,0)' }] } } },
      { name: '月線MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma20, lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false },
      { name: '季線MA60', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma60, lineStyle: { width: 1.5, color: '#8b5cf6' }, itemStyle: { color: '#8b5cf6' }, showSymbol: false },
      { name: '年線MA240', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma240, lineStyle: { width: 1.5, color: '#ef4444' }, itemStyle: { color: '#ef4444' }, showSymbol: false },
      { name: 'K', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: K, lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false,
        markLine: { silent: true, data: [{ yAxis: 80 }, { yAxis: 20 }], lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 }, label: { formatter: '{c}', fontSize: 10, color: '#94a3b8' } } },
      { name: 'D', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: D, lineStyle: { width: 1.5, color: '#3b82f6' }, itemStyle: { color: '#3b82f6' }, showSymbol: false }
    ]
  }
})
</script>

<style scoped>
.page-container { padding: 4px; }
.section-title { font-size: 16px; font-weight: 600; }
</style>
