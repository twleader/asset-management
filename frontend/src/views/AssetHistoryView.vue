<template>
  <div>
    <!-- Summary Table -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">📅 歷年資產管理</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="exporting" @click="handleExport">匯出 Excel</el-button>
            <el-button size="small" :icon="Refresh" :loading="recalculating" @click="handleRecalcDividends">重算配息</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="$router.push('/snapshots/new')">新增快照</el-button>
          </div>
        </div>
      </template>
      <el-table :data="history" size="small" stripe>
        <el-table-column prop="snapshotDate" label="日期" width="110" />
        <el-table-column label="台幣存款" align="right" :formatter="(r) => fmt(r.totalTwdDeposit)" />
        <el-table-column label="美元存款" align="right" :formatter="(r) => fmt(r.totalUsdDeposit)" />
        <el-table-column label="信託基金" align="right" :formatter="(r) => fmt(r.totalFundValue)" />
        <el-table-column label="台股" align="right" min-width="100" :formatter="(r) => fmt(r.totalTwStockValue)" />
        <el-table-column label="美股" align="right" :formatter="(r) => fmt(r.totalUsStockValue)" />
        <el-table-column label="英股" align="right" :formatter="(r) => fmt(r.totalUkStockValue)" />
        <el-table-column label="資產總計" align="right" min-width="120">
          <template #default="{ row }">
            <strong>{{ fmt(row.totalAssets) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="增加金額" align="right">
          <template #default="{ row }">
            <span v-if="row.increase != null" :class="row.increase >= 0 ? 'profit' : 'loss'">
              {{ fmt(row.increase) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="增幅" align="right" width="80">
          <template #default="{ row }">
            <span v-if="row.increaseRate != null" :class="row.increaseRate >= 0 ? 'profit' : 'loss'">
              {{ pct(row.increaseRate) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="投資比例" align="right" width="90"
          :formatter="(r) => r.investmentRate ? pct(r.investmentRate) : '-'" />
        <el-table-column label="預估配息" align="right"
          :formatter="(r) => r.estimatedAnnualDividend ? fmt(r.estimatedAnnualDividend) : '-'" />
        <el-table-column label="已實現損益" align="right">
          <template #default="{ row }">
            {{ isLastOfYear(row) && row.realizedGain ? fmt(row.realizedGain) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary"
              @click="$router.push(`/snapshots/${row.id}/edit`)">管理</el-button>
            <el-popconfirm title="確定刪除此快照？" @confirm="deleteSnapshot(row.id)">
              <template #reference>
                <el-button size="small" :icon="Delete" link type="danger">刪除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 排程自動匯出設定（Requirement 34 / Task 171） -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="runningNow" @click="handleRunNow">立即匯出到目錄</el-button>
            <el-button size="small" type="primary" :loading="savingSchedule" @click="saveSchedule">儲存設定</el-button>
          </div>
        </div>
      </template>
      <el-form :inline="true" label-width="100px" class="schedule-form">
        <el-form-item label="啟用每日排程">
          <el-switch v-model="schedule.enabled" />
        </el-form-item>
        <el-form-item label="每日執行時間">
          <el-time-picker v-model="scheduleTime" format="HH:mm" value-format="HH:mm"
            placeholder="時:分" style="width:130px" />
        </el-form-item>
        <el-form-item label="輸出子資料夾">
          <el-input v-model="schedule.outputSubpath" placeholder="input" style="width:180px" />
        </el-form-item>
      </el-form>
      <div class="schedule-hint">
        檔案寫入容器基底目錄 <code>{{ schedule.baseDir || '/data/export-output' }}</code> 下的子資料夾（對映主機
        <code>/Users/steven/Project/SRPP/data</code>）。例如子資料夾填 <code>input</code> →
        主機 <code>/Users/steven/Project/SRPP/data/input</code>；每日產生 <code>資產總覽_{使用者ID}_YYYYMMDD.xlsx</code>。
      </div>
      <div v-if="schedule.lastRunAt || schedule.lastRunStatus" class="schedule-status">
        上次執行：{{ schedule.lastRunAt || '—' }}　{{ schedule.lastRunStatus || '' }}
      </div>
    </el-card>

    <!-- Charts -->
    <el-row :gutter="20">
      <el-col :span="24">
        <el-card style="margin-bottom:20px">
          <template #header>
            <span class="section-title">總資產趨勢</span>
            <span class="card-sub">{{ trendDate }}</span>
          </template>
          <div class="trend-legend">
            <div v-for="item in trendLegendItems" :key="item.name" class="trend-legend-item">
              <span class="tl-dot" :style="{ background: item.color }"></span>
              <div class="tl-text">
                <div class="tl-name">{{ item.name }}</div>
                <div class="tl-amount" :style="{ color: item.color }">{{ fmt(item.value) }}</div>
                <div class="tl-pct" :style="{ color: item.color }">{{ item.pct }}</div>
              </div>
            </div>
          </div>
          <v-chart :option="totalTrendOption" style="height:300px" autoresize
            @updateAxisPointer="onTrendAxisPointer"
            @globalout="onTrendLeave" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">資產組成堆疊圖</span></template>
          <v-chart :option="stackedOption" style="height:300px" autoresize />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">歷次增幅</span></template>
          <v-chart :option="increaseOption" style="height:300px" autoresize />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Plus, Download, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent])

const history = ref([])
const exporting = ref(false)
const recalculating = ref(false)

// 排程自動匯出設定（Requirement 34 / Task 171）
const schedule = reactive({ enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input', lastRunAt: null, lastRunStatus: null, baseDir: '' })
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
const runningNow = ref(false)

const reload = async () => { history.value = await bffApi.assetHistory.getHistory() }

async function loadSchedule() {
  const s = await bffApi.assetHistory.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.runHour = s.runHour ?? 8
  schedule.runMinute = s.runMinute ?? 0
  schedule.outputSubpath = s.outputSubpath ?? 'input'
  schedule.lastRunAt = s.lastRunAt ?? null
  schedule.lastRunStatus = s.lastRunStatus ?? null
  schedule.baseDir = s.baseDir ?? ''
  scheduleTime.value = `${String(schedule.runHour).padStart(2, '0')}:${String(schedule.runMinute).padStart(2, '0')}`
}

onMounted(() => { reload(); loadSchedule().catch(() => {}) })

const handleRecalcDividends = async () => {
  recalculating.value = true
  try {
    const result = await bffApi.assetHistory.recalcDividends()
    ElMessage.success(`配息重算完成：${result.updated} 個快照已更新`)
    await reload()
  } catch (e) {
    ElMessage.error('重算失敗，請稍後再試')
  } finally {
    recalculating.value = false
  }
}

// BFF 已預先標註 isLastOfYear（每年最後一筆，用於「已實現損益」欄位顯示判斷）
const isLastOfYear = (row) => !!row?.isLastOfYear

const deleteSnapshot = async (id) => {
  await bffApi.assetHistory.deleteSnapshot(id)
  await reload()
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.assetHistory.exportExcel()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `資產管理_${dayjs().format('YYYYMMDD')}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

async function saveSchedule() {
  savingSchedule.value = true
  try {
    const [h, m] = (scheduleTime.value || '08:00').split(':').map(Number)
    const s = await bffApi.assetHistory.updateExportSchedule({
      enabled: schedule.enabled,
      runHour: h,
      runMinute: m,
      outputSubpath: (schedule.outputSubpath || 'input').trim()
    })
    schedule.runHour = s.runHour ?? h
    schedule.runMinute = s.runMinute ?? m
    schedule.outputSubpath = s.outputSubpath ?? schedule.outputSubpath
    schedule.baseDir = s.baseDir ?? schedule.baseDir
    ElMessage.success('排程設定已儲存')
  } catch (e) {
    ElMessage.error('儲存失敗，請稍後再試')
  } finally {
    savingSchedule.value = false
  }
}

async function handleRunNow() {
  runningNow.value = true
  try {
    const r = await bffApi.assetHistory.runExportNow()
    ElMessage.success(`已匯出到：${r.path}`)
  } catch (e) {
    ElMessage.error('立即匯出失敗，請確認目錄與權限')
  } finally {
    runningNow.value = false
  }
  loadSchedule().catch(() => {}) // 刷新上次執行資訊，失敗不影響匯出結果
}

const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const pct = (v) => v ? `${(Number(v) * 100).toFixed(1)}%` : '-'

const dates = computed(() => history.value.map(h => h.snapshotDate))

// hover 連動 legend：未 hover 時顯示最新一筆
const hoveredDate = ref(null)
function onTrendAxisPointer(e) {
  const idx = e?.axesInfo?.[0]?.value
  if (typeof idx !== 'number') return
  hoveredDate.value = history.value[idx]?.snapshotDate ?? null
}
function onTrendLeave() { hoveredDate.value = null }
const trendDate = computed(() =>
  hoveredDate.value ?? history.value[history.value.length - 1]?.snapshotDate ?? '')

const trendLegendItems = computed(() => {
  const r = history.value.find(h => h.snapshotDate === trendDate.value)
        ?? history.value[history.value.length - 1]
  if (!r) return []
  const total = Number(r.totalAssets || 0)
  const pctOf = v => total > 0 ? `${(v / total * 100).toFixed(1)}%` : '-'
  return [
    { name: '總資產',   value: Number(r.totalAssets || 0),       pct: '100.0%',                                color: '#8b5cf6' },
    { name: '台幣存款', value: Number(r.totalTwdDeposit || 0),   pct: pctOf(Number(r.totalTwdDeposit || 0)),   color: '#3b82f6' },
    { name: '美元存款', value: Number(r.totalUsdDeposit || 0),   pct: pctOf(Number(r.totalUsdDeposit || 0)),   color: '#60a5fa' },
    { name: '台股',     value: Number(r.totalTwStockValue || 0), pct: pctOf(Number(r.totalTwStockValue || 0)), color: '#f59e0b' },
    { name: '美股',     value: Number(r.totalUsStockValue || 0), pct: pctOf(Number(r.totalUsStockValue || 0)), color: '#ef4444' },
    { name: '英股',     value: Number(r.totalUkStockValue || 0), pct: pctOf(Number(r.totalUkStockValue || 0)), color: '#0ea5e9' },
    { name: '基金',     value: Number(r.totalFundValue || 0),    pct: pctOf(Number(r.totalFundValue || 0)),    color: '#10b981' }
  ]
})

const totalTrendOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: params => {
      let s = `<strong>${params[0].axisValue}</strong><br/>`
      params.forEach(p => {
        if (p.value != null) s += `${p.marker} ${p.seriesName}: $${Number(p.value).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}<br/>`
      })
      return s
    }
  },
  legend: { show: false },
  grid: { left: 70, right: 30, top: 20, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v/1e4).toFixed(0)}萬` } },
  series: [
    {
      name: '總資產', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalAssets || 0)),
      itemStyle: { color: '#8b5cf6' },
      lineStyle: { width: 3 },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(139,92,246,0.15)' }, { offset: 1, color: 'rgba(139,92,246,0)' }] } }
    },
    {
      name: '台幣存款', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalTwdDeposit || 0)),
      itemStyle: { color: '#3b82f6' },
      lineStyle: { width: 2 }
    },
    {
      name: '美元存款', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUsdDeposit || 0)),
      itemStyle: { color: '#60a5fa' },
      lineStyle: { width: 2 }
    },
    {
      name: '基金', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalFundValue || 0)),
      itemStyle: { color: '#10b981' },
      lineStyle: { width: 2 }
    },
    {
      name: '台股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalTwStockValue || 0)),
      itemStyle: { color: '#f59e0b' },
      lineStyle: { width: 2 }
    },
    {
      name: '美股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUsStockValue || 0)),
      itemStyle: { color: '#ef4444' },
      lineStyle: { width: 2 }
    },
    {
      name: '英股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUkStockValue || 0)),
      itemStyle: { color: '#0ea5e9' },
      lineStyle: { width: 2 }
    }
  ]
}))

const stackedOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['台幣存款', '美元存款', '基金', '台股', '美股', '英股'] },
  grid: { left: 70, right: 20, top: 40, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30, fontSize: 11 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v/1e4).toFixed(0)}萬` } },
  series: [
    { name: '台幣存款', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalTwdDeposit||0)), itemStyle: { color: '#3b82f6' } },
    { name: '美元存款', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalUsdDeposit||0)), itemStyle: { color: '#60a5fa' } },
    { name: '基金', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalFundValue||0)), itemStyle: { color: '#10b981' } },
    { name: '台股', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalTwStockValue||0)), itemStyle: { color: '#f59e0b' } },
    { name: '美股', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalUsStockValue||0)), itemStyle: { color: '#ef4444' } },
    {
      name: '英股', type: 'bar', stack: 'total',
      data: history.value.map(h => Number(h.totalUkStockValue||0)),
      itemStyle: { color: '#0ea5e9' },
      label: {
        show: true,
        position: 'top',
        formatter: p => {
          const h = history.value[p.dataIndex]
          if (!h) return ''
          const total = Number(h.totalAssets || 0)
          return `$${(total / 1e4).toFixed(0)}萬`
        },
        fontSize: 11,
        color: '#374151',
        fontWeight: '600'
      }
    }
  ]
}))

const increaseOption = computed(() => {
  const data = history.value.map(h => ({ value: h.increaseRate ? Number(h.increaseRate)*100 : null, date: h.snapshotDate }))
    .filter(d => d.value != null)
  return {
    tooltip: { trigger: 'axis', formatter: p => `${p[0].name}: ${p[0].value?.toFixed(1)}%` },
    grid: { left: 60, right: 20, top: 20, bottom: 50 },
    xAxis: { type: 'category', data: data.map(d => d.date), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `${v}%` } },
    series: [{
      type: 'bar',
      data: data.map(d => ({
        value: d.value,
        itemStyle: { color: d.value >= 0 ? '#16a34a' : '#dc2626', borderRadius: [4,4,0,0] }
      })),
      label: {
        show: true,
        position: 'top',
        formatter: p => `${Number(p.value).toFixed(1)}%`,
        fontSize: 11,
        fontWeight: '600',
        color: '#374151'
      }
    }]
  }
})
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.card-sub { font-size: 12px; color: #94a3b8; margin-left: 8px; font-weight: 400; }
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
:deep(.el-table .el-table__cell) { font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace; }
:deep(.el-table .el-table__cell:first-child) { font-family: inherit; }

/* 趨勢圖自訂 legend：6 項集中置中、間距 32px、下方寫金額（同色） */
.trend-legend { display: flex; justify-content: center; padding: 6px 16px 12px; gap: 32px; flex-wrap: wrap; }
.trend-legend-item { display: flex; align-items: center; gap: 8px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.tl-text { display: flex; flex-direction: column; line-height: 1.25; }
.tl-name   { font-size: 12px; color: #64748b; }
.tl-amount { font-size: 14px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tl-pct    { font-size: 11px; font-weight: 600; }

/* 排程自動匯出設定 */
.schedule-form { margin-bottom: 4px; }
.schedule-hint { font-size: 12px; color: #94a3b8; line-height: 1.6; }
.schedule-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.schedule-status { margin-top: 8px; font-size: 12px; color: #64748b; }
</style>
