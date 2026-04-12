<template>
  <div class="dashboard">
    <!-- 頁頭：標題 + 快照選擇器 -->
    <div class="dashboard-header">
      <h2 class="dashboard-title">總覽儀表板</h2>
      <el-select
        v-model="selectedSnapshotId"
        size="small"
        style="width:180px"
        @change="onSnapshotChange"
        placeholder="選擇快照">
        <el-option
          v-for="s in store.snapshots"
          :key="s.id"
          :label="s.snapshotDate"
          :value="s.id" />
      </el-select>
    </div>

    <!-- KPI Cards -->
    <el-row :gutter="20" class="kpi-row">
      <el-col :span="6" v-for="kpi in kpiCards" :key="kpi.label">
        <el-card class="kpi-card">
          <div class="kpi-icon" :style="{ background: kpi.bg }">
            <el-icon size="22" :color="kpi.color"><component :is="kpi.icon" /></el-icon>
          </div>
          <div class="kpi-content">
            <div class="kpi-label">{{ kpi.label }}</div>
            <div class="kpi-value" :style="{ color: kpi.valueColor }">{{ kpi.value }}</div>
            <div class="kpi-sub" v-if="kpi.sub">{{ kpi.sub }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Charts Row -->
    <el-row :gutter="20" class="chart-row">
      <!-- Asset Distribution Pie Chart -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <span class="card-title">資產配置分佈</span>
            <span class="card-sub">{{ latest?.snapshotDate }}</span>
          </template>
          <v-chart :option="pieOption" style="height: 320px" autoresize />
        </el-card>
      </el-col>

      <!-- Asset Trend Line Chart -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <span class="card-title">資產歷史趨勢</span>
          </template>
          <v-chart :option="trendOption" style="height: 320px" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <!-- Second Charts Row -->
    <el-row :gutter="20" class="chart-row">
      <!-- Deposit by Bank -->
      <el-col :span="12">
        <el-card>
          <template #header><span class="card-title">各銀行存款分佈</span></template>
          <v-chart :option="bankOption" style="height: 280px" autoresize />
        </el-card>
      </el-col>

      <!-- Stock Portfolio -->
      <el-col :span="12">
        <el-card>
          <template #header><span class="card-title">持股明細 (現值)</span></template>
          <v-chart :option="stockBarOption" style="height: 280px" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <!-- Quick Stats Table -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div style="display:flex;align-items:center;justify-content:space-between">
              <span class="card-title">📊 股票持股</span>
              <div style="display:flex;align-items:center;gap:12px">
                <span style="font-size:12px">
                  台股 <span :style="{ color: marketStatus.twMarketOpen ? '#16a34a' : '#94a3b8' }">●</span>
                  {{ marketStatus.twMarketOpen ? '開盤中' : '休市' }}
                </span>
                <span style="font-size:12px">
                  美股 <span :style="{ color: marketStatus.usMarketOpen ? '#16a34a' : '#94a3b8' }">●</span>
                  {{ marketStatus.usMarketOpen ? '開盤中' : '休市' }}
                </span>
                <el-button type="primary" size="small" :icon="ArrowRight"
                  @click="$router.push('/snapshots/' + latest?.id + '/edit')">
                  管理資產
                </el-button>
              </div>
            </div>
          </template>
          <el-tabs v-model="stockMarketTab" class="stock-tabs">
            <el-tab-pane label="台股" name="台股" />
            <el-tab-pane label="美股" name="美股" />
          </el-tabs>
          <el-table :data="stockTableData" size="small" :max-height="500" stripe>
            <el-table-column prop="stockCode" label="代號" width="90" />
            <el-table-column prop="stockName" label="名稱" min-width="130" />
            <el-table-column label="股數" width="120" align="right">
              <template #default="{ row }">
                {{ formatShares(row.shares, row.market) }}
              </template>
            </el-table-column>
            <el-table-column label="股價" width="140" align="right">
              <template #default="{ row }">
                <span v-if="getRealtimePrice(row)">
                  {{ formatPrice(getRealtimePrice(row).price) }}
                  <span v-if="getRealtimePrice(row).changePercent != null"
                    :style="{ color: getRealtimePrice(row).changePercent >= 0 ? '#16a34a' : '#dc2626', fontSize: '11px' }">
                    {{ getRealtimePrice(row).changePercent >= 0 ? '▲' : '▼' }}{{ Math.abs(getRealtimePrice(row).changePercent).toFixed(2) }}%
                  </span>
                </span>
                <span v-else-if="row.stockPrice != null" style="color:#94a3b8">{{ formatPrice(row.stockPrice) }}</span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="investmentCost" label="投資成本" align="right"
              :formatter="(r,c,v) => formatCurrency(v)" />
            <el-table-column prop="currentValue" label="現值" align="right"
              :formatter="(r,c,v) => formatCurrency(v)" />
            <el-table-column label="損益" align="right">
              <template #default="{ row }">
                <span :class="row.profit >= 0 ? 'profit' : 'loss'">
                  {{ formatCurrency(row.profit) }}
                  ({{ formatPct(row.profitRate) }})
                </span>
              </template>
            </el-table-column>
            <el-table-column label="配息率" align="right" width="90">
              <template #default="{ row }">
                <span v-if="row.dividendRate" style="color:#7c3aed;font-weight:600">
                  {{ (Number(row.dividendRate) * 100).toFixed(2) }}%
                </span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
            <el-table-column label="預估配息" align="right" width="110">
              <template #default="{ row }">
                <span v-if="row.estimatedDividend">{{ formatCurrency(row.estimatedDividend) }}</span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowRight } from '@element-plus/icons-vue'
import { useAssetStore } from '@/stores/assetStore'
import { marketDataApi } from '@/api'

use([CanvasRenderer, PieChart, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const store = useAssetStore()
const stockPrices = ref({})
const marketStatus = ref({ twMarketOpen: false, usMarketOpen: false })
const selectedSnapshotId = ref(null)

let priceTimer = null

onMounted(async () => {
  await Promise.all([store.fetchSnapshots(), store.fetchHistory()])
  if (store.snapshots.length > 0) {
    selectedSnapshotId.value = store.snapshots[0].id
    await store.fetchSnapshotDetail(store.snapshots[0].id)
  }
  await fetchStockPrices()
  priceTimer = setInterval(fetchStockPrices, 5 * 60 * 1000)
})

onUnmounted(() => {
  if (priceTimer) { clearInterval(priceTimer); priceTimer = null }
})

async function onSnapshotChange(id) {
  await store.fetchSnapshotDetail(id)
}

async function fetchStockPrices() {
  try {
    const [prices, status] = await Promise.all([
      marketDataApi.getAllPrices(),
      marketDataApi.getMarketStatus()
    ])
    marketStatus.value = status
    const map = {}
    for (const p of prices) {
      map[`${p.market}_${p.stockCode}`] = p
    }
    stockPrices.value = map
  } catch (e) {
    console.warn('取得股價失敗:', e)
  }
}

const latest = computed(() =>
  store.snapshots.find(s => s.id === selectedSnapshotId.value) ?? store.latestSnapshot
)
const detail = computed(() => store.currentSnapshot)

function getRealtimePrice(row) {
  const key = `${row.market}_${row.stockCode}`
  const p = stockPrices.value[key]
  if (!p || p.price == null) return null
  return { price: Number(p.price), changePercent: p.changePercent != null ? Number(p.changePercent) : null }
}

const formatShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股') {
    return n.toLocaleString('en-US', { minimumFractionDigits: 5, maximumFractionDigits: 5 })
  }
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}
const formatPrice = (v) => {
  if (v == null) return '-'
  return Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatCurrency = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return n >= 0
    ? `$${n.toLocaleString('zh-TW', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`
    : `-$${Math.abs(n).toLocaleString('zh-TW', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`
}
const formatPct = (v) => v ? `${(Number(v) * 100).toFixed(1)}%` : '-'

const kpiCards = computed(() => {
  const s = latest.value
  if (!s) return []
  const total = Number(s.totalAssets || 0)
  // 找選中快照在 history 中的前一筆
  const h = store.history
  const idx = h.findIndex(r => r.snapshotDate === s.snapshotDate)
  const prev = idx > 0 ? h[idx - 1] : null
  const prevTotal = prev ? Number(prev.totalAssets || 0) : 0
  const change = prevTotal > 0 ? ((total - prevTotal) / prevTotal * 100).toFixed(1) : null

  return [
    {
      label: '資產總計', icon: 'Wallet',
      value: formatCurrency(total), bg: '#eff6ff', color: '#2563eb',
      sub: change != null ? `較上次 ${change >= 0 ? '+' : ''}${change}%` : null,
      valueColor: '#1e293b'
    },
    {
      label: '存款總計', icon: 'Bank',
      value: formatCurrency(s.totalDeposit), bg: '#f0fdf4', color: '#16a34a',
      sub: `佔比 ${total > 0 ? (Number(s.totalDeposit) / total * 100).toFixed(1) : 0}%`,
      valueColor: '#1e293b'
    },
    {
      label: '股票現值', icon: 'TrendCharts',
      value: formatCurrency(s.totalStockValue), bg: '#fef3c7', color: '#d97706',
      sub: `損益 ${formatCurrency(s.stockProfit)}`,
      valueColor: Number(s.stockProfit) >= 0 ? '#16a34a' : '#dc2626'
    },
    {
      label: '預估年配息', icon: 'Money',
      value: formatCurrency(s.estimatedAnnualDividend), bg: '#fdf4ff', color: '#9333ea',
      sub: `殖利率 ${total > 0 ? (Number(s.estimatedAnnualDividend || 0) / total * 100).toFixed(2) : 0}%`,
      valueColor: '#1e293b'
    }
  ]
})

const pieOption = computed(() => {
  const s = latest.value
  if (!s) return {}
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 12 } },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      center: ['50%', '45%'],
      data: [
        { value: Math.round(Number(s.totalDeposit || 0)), name: '存款' },
        { value: Math.round(Number(s.totalFundValue || 0)), name: '信託基金' },
        { value: Math.round(Number(s.totalStockValue || 0)), name: '股票' }
      ],
      label: { formatter: '{b}\n{d}%' },
      itemStyle: { borderRadius: 6 }
    }],
    color: ['#3b82f6', '#10b981', '#f59e0b']
  }
})

const trendOption = computed(() => {
  const h = store.history
  if (!h.length) return {}
  return {
    tooltip: { trigger: 'axis', formatter: (params) =>
      params[0].axisValue + '<br>' +
      params.map(p => `${p.seriesName}: $${Number(p.value).toLocaleString()}`).join('<br>')
    },
    legend: { top: 0, data: ['存款', '基金', '台股', '美股', '總資產'] },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: h.map(r => r.snapshotDate), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `${(v / 1e4).toFixed(0)}萬` } },
    series: [
      { name: '存款', type: 'line', smooth: true, data: h.map(r => Number(r.totalDeposit || 0)), itemStyle: { color: '#3b82f6' } },
      { name: '基金', type: 'line', smooth: true, data: h.map(r => Number(r.totalFundValue || 0)), itemStyle: { color: '#10b981' } },
      { name: '台股', type: 'line', smooth: true, data: h.map(r => Number(r.totalTwStockValue || 0)), itemStyle: { color: '#f59e0b' } },
      { name: '美股', type: 'line', smooth: true, data: h.map(r => Number(r.totalUsStockValue || 0)), itemStyle: { color: '#ef4444' } },
      { name: '總資產', type: 'line', smooth: true, lineStyle: { width: 3 }, data: h.map(r => Number(r.totalAssets || 0)), itemStyle: { color: '#8b5cf6' } }
    ]
  }
})

const bankOption = computed(() => {
  const deposits = detail.value?.deposits || []
  if (!deposits.length) return {}

  // 依銀行分組，區分定存 vs 活存
  const bankMap = {} // { bankName: { fixed: 0, demand: 0 } }
  deposits.forEach(d => {
    const bank = d.bankDisplayName || d.bankName
    if (!bankMap[bank]) bankMap[bank] = { fixed: 0, demand: 0 }
    const amt = Number(d.amount || 0)
    const type = d.depositType || ''
    if (type.includes('定存')) {
      bankMap[bank].fixed += amt
    } else {
      bankMap[bank].demand += amt
    }
  })
  const sorted = Object.entries(bankMap).sort((a, b) => (b[1].fixed + b[1].demand) - (a[1].fixed + a[1].demand))
  const banks = sorted.map(e => e[0])
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        let s = `<strong>${params[0].name}</strong><br/>`
        let total = 0
        params.forEach(p => {
          if (p.value > 0) {
            s += `${p.marker} ${p.seriesName}: $${Number(p.value).toLocaleString()}<br/>`
            total += p.value
          }
        })
        s += `合計: $${total.toLocaleString()}`
        return s
      }
    },
    legend: { data: ['定存', '活存'], top: 0, right: 0, textStyle: { fontSize: 12 } },
    grid: { left: 100, right: 130, top: 30, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `${(v / 1e4).toFixed(0)}萬` } },
    yAxis: { type: 'category', data: banks },
    series: [
      {
        name: '定存',
        type: 'bar',
        stack: 'total',
        data: sorted.map(e => e[1].fixed),
        itemStyle: { color: '#22c55e', borderRadius: [0,0,0,0] },
      },
      {
        name: '活存',
        type: 'bar',
        stack: 'total',
        data: sorted.map(e => e[1].demand),
        itemStyle: { color: '#3b82f6', borderRadius: [0,0,0,0] },
      }
    ].map((s, i, arr) => ({
      ...s,
      // 最後一層顯示總額 label
      label: i === arr.length - 1 ? {
        show: true,
        position: 'right',
        formatter: p => {
          const bank = banks[p.dataIndex]
          const total = bankMap[bank].fixed + bankMap[bank].demand
          return `$${total.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
        }
      } : { show: false }
    }))
  }
})

const mergedStocks = computed(() => {
  const stocks = detail.value?.stocks || []
  const map = new Map()
  for (const s of stocks) {
    const key = `${s.market}_${s.stockCode}`
    if (!map.has(key)) {
      map.set(key, {
        stockCode: s.stockCode,
        stockName: s.stockName,
        market: s.market,
        dividendRate: null,
        shares: 0,
        investmentCost: 0,
        currentValue: 0,
        estimatedDividend: 0
      })
    }
    const g = map.get(key)
    g.shares += Number(s.shares || 0)
    g.investmentCost += Number(s.investmentCost || 0)
    g.currentValue += Number(s.currentValue || 0)
    g.estimatedDividend += Number(s.estimatedDividend || 0)
    // 取任一有值的 dividendRate
    if (s.dividendRate && !g.dividendRate) g.dividendRate = Number(s.dividendRate)
  }
  return [...map.values()]
    .map(g => ({
      ...g,
      stockPrice: g.shares > 0 ? g.currentValue / g.shares : null,
      profit: g.currentValue - g.investmentCost,
      profitRate: g.investmentCost > 0 ? (g.currentValue - g.investmentCost) / g.investmentCost : 0
    }))
    .sort((a, b) => b.currentValue - a.currentValue)
})

const stockMarketTab = ref('台股')
const stockTableData = computed(() =>
  mergedStocks.value.filter(s => s.market === stockMarketTab.value)
)

const stockBarOption = computed(() => {
  const stocks = mergedStocks.value
  if (!stocks.length) return {}
  const sorted = [...stocks].slice(0, 12)
  return {
    tooltip: { trigger: 'axis', formatter: (p) => `${p[0].name}: $${Number(p[0].value).toLocaleString()}` },
    grid: { left: 90, right: 130, top: 10, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `${(v / 1e4).toFixed(0)}萬` } },
    yAxis: { type: 'category', data: sorted.map(s => s.stockName || s.stockCode) },
    series: [{
      type: 'bar',
      data: sorted.map(s => ({
        value: Math.round(Number(s.currentValue)),
        itemStyle: { color: s.market === '台股' ? '#3b82f6' : '#f59e0b', borderRadius: [0,4,4,0] }
      })),
      label: {
        show: true,
        position: 'right',
        formatter: p => `$${Number(p.value).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
      }
    }]
  }
})
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 20px; }
.dashboard-header {
  display: flex; align-items: center; justify-content: space-between;
  padding-bottom: 4px;
}
.dashboard-title { font-size: 18px; font-weight: 700; color: #1e293b; margin: 0; }
.kpi-row, .chart-row { margin: 0 !important; }

.kpi-card :deep(.el-card__body) {
  display: flex; align-items: center; gap: 16px; padding: 20px;
}
.kpi-icon {
  width: 52px; height: 52px; border-radius: 14px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.kpi-label { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.kpi-value { font-size: 22px; font-weight: 700; }
.kpi-sub { font-size: 12px; color: #94a3b8; margin-top: 2px; }

.card-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.card-sub { font-size: 12px; color: #94a3b8; margin-left: 8px; }

.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
.stock-tabs { margin-bottom: 4px; }
.stock-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
:deep(.el-table__row--striped .el-table__cell) { background: #f7f8fa !important; }
</style>
