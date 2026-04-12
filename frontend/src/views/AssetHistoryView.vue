<template>
  <div>
    <!-- Summary Table -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">📅 歷年資產管理</span>
          <div style="display:flex;gap:8px">
            <el-upload :show-file-list="false" :before-upload="handleImport" accept=".xlsx" style="display:inline-block">
              <el-button size="small" :icon="Upload" :loading="importing">匯入 Excel</el-button>
            </el-upload>
            <el-button size="small" :icon="Refresh" :loading="recalculating" @click="handleRecalcDividends">重算配息</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="$router.push('/snapshots/new')">新增快照</el-button>
          </div>
        </div>
      </template>
      <el-table :data="store.history" size="small" stripe>
        <el-table-column prop="snapshotDate" label="日期" width="110" />
        <el-table-column label="存款" align="right" :formatter="(r) => fmt(r.totalDeposit)" />
        <el-table-column label="信託基金" align="right" :formatter="(r) => fmt(r.totalFundValue)" />
        <el-table-column label="台股" align="right" :formatter="(r) => fmt(r.totalTwStockValue)" />
        <el-table-column label="美股" align="right" :formatter="(r) => fmt(r.totalUsStockValue)" />
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
        <el-table-column label="增幅" align="right" width="90">
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
        <el-table-column label="已實現損益" align="right"
          :formatter="(r) => r.realizedGain ? fmt(r.realizedGain) : '-'" />
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

    <!-- Charts -->
    <el-row :gutter="20">
      <el-col :span="24">
        <el-card style="margin-bottom:20px">
          <template #header><span class="section-title">總資產趨勢</span></template>
          <v-chart :option="totalTrendOption" style="height:350px" autoresize />
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
import { Plus, Upload, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAssetStore } from '@/stores/assetStore'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent])

const store = useAssetStore()
const importing = ref(false)
const recalculating = ref(false)

onMounted(() => store.fetchHistory())

const handleRecalcDividends = async () => {
  recalculating.value = true
  try {
    const result = await store.recalcDividends()
    ElMessage.success(`配息重算完成：${result.updated} 個快照已更新`)
  } catch (e) {
    ElMessage.error('重算失敗，請稍後再試')
  } finally {
    recalculating.value = false
  }
}

const deleteSnapshot = async (id) => {
  await store.deleteSnapshot(id)
  await store.fetchHistory()
}

const handleImport = async (file) => {
  importing.value = true
  try {
    const result = await store.importExcel(file)
    ElMessage.success(`匯入完成：${result.snapshotsImported} 個快照，${result.gainsImported} 筆損益`)
    if (result.errors?.length) {
      result.errors.forEach(e => ElMessage.warning(e))
    }
    await store.fetchHistory()
  } finally {
    importing.value = false
  }
  return false
}

const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}
const pct = (v) => v ? `${(Number(v) * 100).toFixed(1)}%` : '-'

const dates = computed(() => store.history.map(h => h.snapshotDate))

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
  legend: { data: ['總資產', '存款', '基金', '台股', '美股'] },
  grid: { left: 70, right: 30, top: 50, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `${(v/1e4).toFixed(0)}萬` } },
  series: [
    {
      name: '總資產', type: 'line', smooth: true,
      data: store.history.map(h => Number(h.totalAssets || 0)),
      itemStyle: { color: '#8b5cf6' },
      lineStyle: { width: 3 },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(139,92,246,0.15)' }, { offset: 1, color: 'rgba(139,92,246,0)' }] } }
    },
    {
      name: '存款', type: 'line', smooth: true,
      data: store.history.map(h => Number(h.totalDeposit || 0)),
      itemStyle: { color: '#3b82f6' },
      lineStyle: { width: 2 }
    },
    {
      name: '基金', type: 'line', smooth: true,
      data: store.history.map(h => Number(h.totalFundValue || 0)),
      itemStyle: { color: '#10b981' },
      lineStyle: { width: 2 }
    },
    {
      name: '台股', type: 'line', smooth: true,
      data: store.history.map(h => Number(h.totalTwStockValue || 0)),
      itemStyle: { color: '#f59e0b' },
      lineStyle: { width: 2 }
    },
    {
      name: '美股', type: 'line', smooth: true,
      data: store.history.map(h => Number(h.totalUsStockValue || 0)),
      itemStyle: { color: '#ef4444' },
      lineStyle: { width: 2 }
    }
  ]
}))

const stackedOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['存款', '基金', '台股', '美股'] },
  grid: { left: 70, right: 20, top: 40, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30, fontSize: 11 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `${(v/1e4).toFixed(0)}萬` } },
  series: [
    { name: '存款', type: 'bar', stack: 'total', data: store.history.map(h => Number(h.totalDeposit||0)), itemStyle: { color: '#3b82f6' } },
    { name: '基金', type: 'bar', stack: 'total', data: store.history.map(h => Number(h.totalFundValue||0)), itemStyle: { color: '#10b981' } },
    { name: '台股', type: 'bar', stack: 'total', data: store.history.map(h => Number(h.totalTwStockValue||0)), itemStyle: { color: '#f59e0b' } },
    {
      name: '美股', type: 'bar', stack: 'total',
      data: store.history.map(h => Number(h.totalUsStockValue||0)),
      itemStyle: { color: '#ef4444' },
      label: {
        show: true,
        position: 'top',
        formatter: p => {
          const h = store.history[p.dataIndex]
          if (!h) return ''
          const total = Number(h.totalAssets || 0)
          return `${(total / 1e4).toFixed(0)}萬`
        },
        fontSize: 11,
        color: '#374151',
        fontWeight: '600'
      }
    }
  ]
}))

const increaseOption = computed(() => {
  const data = store.history.map(h => ({ value: h.increaseRate ? Number(h.increaseRate)*100 : null, date: h.snapshotDate }))
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
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
:deep(.el-table .el-table__cell) { font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace; }
:deep(.el-table .el-table__cell:first-child) { font-family: inherit; }
</style>
