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
            <img v-if="kpi.img" :src="kpi.img" style="width:30px;height:30px;object-fit:contain" />
            <span v-else-if="kpi.emoji" style="font-size:26px;line-height:1">{{ kpi.emoji }}</span>
            <el-icon v-else size="22" :color="kpi.color"><component :is="kpi.icon" /></el-icon>
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
          <template #header>
            <div style="display:flex;align-items:center;justify-content:space-between">
              <span class="card-title">各銀行存款分佈</span>
              <el-tabs v-model="bankCurrencyTab" class="chart-market-tabs" style="margin:0">
                <el-tab-pane label="台幣" name="TWD" />
                <el-tab-pane label="美元" name="USD" />
              </el-tabs>
            </div>
          </template>
          <v-chart :option="bankOption" style="height: 280px" autoresize />
          <div class="chart-summary-bar">
            <template v-if="bankCurrencyTab === 'TWD'">
              <div class="csb-item">
                <span class="csb-label">定存</span>
                <span class="csb-val" style="color:#16a34a">{{ formatCurrency(bankSummary.fixed) }}</span>
              </div>
              <div class="csb-sep" />
              <div class="csb-item">
                <span class="csb-label">活存／其他</span>
                <span class="csb-val" style="color:#2563eb">{{ formatCurrency(bankSummary.demand) }}</span>
              </div>
              <div class="csb-sep" />
              <div class="csb-item">
                <span class="csb-label">台幣合計</span>
                <span class="csb-val">{{ formatCurrency(bankSummary.fixed + bankSummary.demand) }}</span>
              </div>
            </template>
            <template v-else>
              <div class="csb-item">
                <span class="csb-label">美元定存（台幣值）</span>
                <span class="csb-val" style="color:#16a34a">{{ formatCurrency(bankSummary.usdFixed) }}</span>
              </div>
              <div class="csb-sep" />
              <div class="csb-item">
                <span class="csb-label">美元活存（台幣值）</span>
                <span class="csb-val" style="color:#2563eb">{{ formatCurrency(bankSummary.usdDemand) }}</span>
              </div>
              <div class="csb-sep" />
              <div class="csb-item">
                <span class="csb-label">美元合計（台幣值）</span>
                <span class="csb-val">{{ formatCurrency(bankSummary.usd) }}</span>
              </div>
            </template>
          </div>
        </el-card>
      </el-col>

      <!-- Stock Portfolio -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <div style="display:flex;align-items:center;justify-content:space-between">
              <span class="card-title">持股明細 (現值)</span>
              <el-tabs v-model="chartMarketTab" class="chart-market-tabs" style="margin:0">
                <el-tab-pane label="台股" name="台股" />
                <el-tab-pane label="美股" name="美股" />
              </el-tabs>
            </div>
          </template>
          <v-chart :option="stockBarOption" style="height: 280px" autoresize
            @dblclick="onBarDblClick" />
          <div class="chart-summary-bar">
            <div class="csb-item">
              <span class="csb-label">總值</span>
              <span class="csb-val">{{ formatCurrency(chartSummary.totalValue) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">成本</span>
              <span class="csb-val">{{ formatCurrency(chartSummary.totalCost) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">損益</span>
              <span class="csb-val" :class="chartSummary.profit >= 0 ? 'profit' : 'loss'">
                {{ formatCurrency(chartSummary.profit) }}
                <small style="font-weight:400"> ({{ formatPct(chartSummary.profitRate) }})</small>
              </span>
            </div>
          </div>
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
          <el-table
            ref="stockTableRef"
            :data="stockTableData"
            :max-height="500"
            border
            stripe
            row-key="stockCode"
            style="cursor:pointer"
            @row-dblclick="onStockDblClick">
            <el-table-column width="36" align="center">
              <template #default>
                <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="股票代號" width="110">
              <template #default="{ row }">
                <span style="font-weight:600">{{ row.stockCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="股票名稱" min-width="130">
              <template #default="{ row }">
                <span style="color:#475569">{{ row.stockName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="股價" width="140" align="right">
              <template #default="{ row }">
                <span v-if="getRealtimePrice(row)">
                  <span style="font-weight:600">{{ formatPrice(getRealtimePrice(row).price) }}</span>
                  <span v-if="getRealtimePrice(row).changePercent != null"
                    :style="{ color: getRealtimePrice(row).changePercent >= 0 ? '#16a34a' : '#dc2626', fontSize: '11px' }">
                    {{ getRealtimePrice(row).changePercent >= 0 ? '▲' : '▼' }}{{ Math.abs(getRealtimePrice(row).changePercent).toFixed(2) }}%
                  </span>
                </span>
                <span v-else-if="row.stockPrice != null"
                  :style="{ fontWeight: 600, color: isBaselineToday() ? '#94a3b8' : '#1e293b' }">
                  {{ formatPrice(row.stockPrice) }}
                </span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
            <el-table-column label="股數" width="110" align="right">
              <template #default="{ row }">
                {{ formatShares(row.shares, row.market) }}
              </template>
            </el-table-column>
            <el-table-column label="買入均價" width="110" align="right">
              <template #default="{ row }">
                <span style="color:#475569">{{ row.shares > 0 ? formatPrice(row.investmentCostOriginal / row.shares) : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="投資成本" align="right" width="120">
              <template #default="{ row }">
                <span style="color:#475569">{{ formatCurrency(row.investmentCost) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="現值" align="right" width="120">
              <template #default="{ row }">
                <span style="font-weight:600">{{ formatCurrency(row.currentValue) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="損益" align="right" width="160">
              <template #default="{ row }">
                <span :class="row.profit >= 0 ? 'profit' : 'loss'" style="font-weight:600">
                  {{ formatCurrency(row.profit) }}
                  <span style="font-size:12px">({{ formatPct(row.profitRate) }})</span>
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
                <span v-if="row.estimatedDividend" style="color:#0369a1;font-weight:600">{{ formatCurrency(row.estimatedDividend) }}</span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" :usd-rate="detail?.usdExchangeRate" />
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowRight, Loading, Operation } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { useAssetStore } from '@/stores/assetStore'
import { bffApi, snapshotApi, marketDataApi } from '@/api'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

let orderSaveTimer = null
let stockSortable = null
const stockTableRef = ref(null)

use([CanvasRenderer, PieChart, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])

const store = useAssetStore()
const stockPrices = ref({})
const snapshotClosePrices = ref({}) // 快照基準日（或之前最近）的收盤價，原幣別（美股 USD、台股 TWD）
const marketStatus = ref({ twMarketOpen: false, usMarketOpen: false })
const selectedSnapshotId = ref(null)

let priceTimer = null

onMounted(async () => {
  // Single BFF call aggregates: snapshots + history + latestSnapshotDetail + prices + marketStatus
  await loadDashboardSummary()
  priceTimer = setInterval(refreshPricesAndStatus, 5 * 60 * 1000)

  // 背景補齊所有快照缺漏的配息率（不阻塞頁面載入）
  snapshotApi.enrichAllDividendRates().then(() => {
    return loadDashboardSummary()
  }).catch(() => {})
})

onUnmounted(() => {
  if (priceTimer) { clearInterval(priceTimer); priceTimer = null }
  if (stockSortable) { stockSortable.destroy(); stockSortable = null }
})

async function loadDashboardSummary() {
  try {
    const summary = await bffApi.getDashboardSummary()
    store.snapshots = summary.snapshots ?? []
    store.history = summary.history ?? []
    // 僅在使用者尚未選擇任何快照時才預設為最新；之後保留使用者的選擇，避免被輪詢覆蓋
    if (selectedSnapshotId.value == null && summary.latestSnapshotDetail?.id) {
      store.currentSnapshot = summary.latestSnapshotDetail
      selectedSnapshotId.value = summary.latestSnapshotDetail.id
    }
    applyPricesAndStatus(summary.stockPrices ?? [], summary.marketStatus ?? {})
    await loadSnapshotClosePrices()
  } catch (e) {
    console.warn('載入儀表板摘要失敗:', e)
  }
}

async function refreshPricesAndStatus() {
  // 5 分鐘輪詢只刷新即時股價與市場狀態，避免覆蓋使用者選擇的基準日
  try {
    const [prices, status] = await Promise.all([
      marketDataApi.getAllPrices(),
      marketDataApi.getMarketStatus()
    ])
    applyPricesAndStatus(prices ?? [], status ?? {})
  } catch (e) {
    console.warn('刷新股價/市場狀態失敗:', e)
  }
}

async function onSnapshotChange(id) {
  await store.fetchSnapshotDetail(id)
  selectedSnapshotId.value = id
  await loadSnapshotClosePrices()
}

async function loadSnapshotClosePrices() {
  const d = store.currentSnapshot
  if (!d?.snapshotDate || !Array.isArray(d.stocks) || d.stocks.length === 0) {
    snapshotClosePrices.value = {}
    return
  }
  const seen = new Set()
  const stocks = []
  for (const s of d.stocks) {
    const key = `${s.market}_${s.stockCode}`
    if (seen.has(key)) continue
    seen.add(key)
    stocks.push({ code: s.stockCode, market: s.market })
  }
  try {
    const prices = await marketDataApi.getPricesOnDate(d.snapshotDate, stocks)
    const map = {}
    for (const p of prices ?? []) {
      map[`${p.market}_${p.stockCode}`] = Number(p.price)
    }
    snapshotClosePrices.value = map
  } catch (e) {
    console.warn('載入快照收盤價失敗:', e)
  }
}

function applyPricesAndStatus(prices, status) {
  marketStatus.value = status
  const map = {}
  for (const p of prices) {
    map[`${p.market}_${p.stockCode}`] = p
  }
  stockPrices.value = map
}

const latest = computed(() =>
  store.snapshots.find(s => s.id === selectedSnapshotId.value) ?? store.latestSnapshot
)
const detail = computed(() => store.currentSnapshot)

function isBaselineToday() {
  const d = latest.value?.snapshotDate
  if (!d) return false
  const today = new Date()
  const yyyy = today.getFullYear()
  const mm = String(today.getMonth() + 1).padStart(2, '0')
  const dd = String(today.getDate()).padStart(2, '0')
  return d === `${yyyy}-${mm}-${dd}`
}

function getRealtimePrice(row) {
  if (!isBaselineToday()) return null
  const isOpen = row.market === '美股' ? marketStatus.value.usMarketOpen : marketStatus.value.twMarketOpen
  if (!isOpen) return null
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
  return `$${Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const formatCurrency = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return n >= 0
    ? `$${n.toLocaleString('zh-TW', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`
    : `-$${Math.abs(n).toLocaleString('zh-TW', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`
}
const formatPct = (v) => v ? `${(Number(v) * 100).toFixed(1)}%` : '-'

const filteredHistory = computed(() => {
  const baseline = latest.value?.snapshotDate
  if (!baseline) return store.history
  return store.history.filter(r => r.snapshotDate <= baseline)
})

const kpiCards = computed(() => {
  const s = latest.value
  if (!s) return []
  const total = Number(s.totalAssets || 0)
  // 在過濾後的 history 中找選中快照的前一筆
  const h = filteredHistory.value
  const idx = h.findIndex(r => r.snapshotDate === s.snapshotDate)
  const prev = idx > 0 ? h[idx - 1] : (idx === -1 && h.length > 0 ? h[h.length - 1] : null)
  const prevTotal = prev ? Number(prev.totalAssets || 0) : 0
  const change = prevTotal > 0 ? ((total - prevTotal) / prevTotal * 100).toFixed(1) : null

  return [
    {
      label: '資產總計', img: '/icons/gold-coins-v2.svg',
      value: formatCurrency(total), bg: '#eff6ff', color: '#2563eb',
      sub: change != null ? `較上次 ${change >= 0 ? '+' : ''}${change}%` : null,
      valueColor: '#1e293b'
    },
    {
      label: '存款總計', emoji: '📒',
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
      label: '預估年配息', emoji: '💵',
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
    tooltip: { trigger: 'item', formatter: p => `${p.name}: $${Number(p.value).toLocaleString()} (${p.percent}%)` },
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
  const h = filteredHistory.value
  if (!h.length) return {}
  return {
    tooltip: { trigger: 'axis', formatter: (params) =>
      params[0].axisValue + '<br>' +
      params.map(p => `${p.seriesName}: $${Number(p.value).toLocaleString()}`).join('<br>')
    },
    legend: { top: 0, data: ['存款', '基金', '台股', '美股', '總資產'] },
    grid: { left: 60, right: 20, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: h.map(r => r.snapshotDate), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    series: [
      { name: '存款', type: 'line', smooth: true, data: h.map(r => Number(r.totalDeposit || 0)), itemStyle: { color: '#3b82f6' } },
      { name: '基金', type: 'line', smooth: true, data: h.map(r => Number(r.totalFundValue || 0)), itemStyle: { color: '#10b981' } },
      { name: '台股', type: 'line', smooth: true, data: h.map(r => Number(r.totalTwStockValue || 0)), itemStyle: { color: '#f59e0b' } },
      { name: '美股', type: 'line', smooth: true, data: h.map(r => Number(r.totalUsStockValue || 0)), itemStyle: { color: '#ef4444' } },
      { name: '總資產', type: 'line', smooth: true, lineStyle: { width: 3 }, data: h.map(r => Number(r.totalAssets || 0)), itemStyle: { color: '#8b5cf6' } }
    ]
  }
})

const bankCurrencyTab = ref('TWD')

const bankSummary = computed(() => {
  const deposits = detail.value?.deposits || []
  let fixed = 0, demand = 0, usdFixed = 0, usdDemand = 0
  deposits.forEach(d => {
    const amt = Number(d.amount || 0)
    const cur = d.currency || 'TWD'
    const type = d.depositType || ''
    if (cur === 'USD') {
      if (type.includes('定存')) usdFixed += amt
      else usdDemand += amt
    } else if (cur === 'TRANSIT_TWD' || cur === 'TRANSIT_USD') {
      demand += amt   // 在途（負值）歸入活存淨額
    } else {
      if (type.includes('定存')) fixed += amt
      else demand += amt
    }
  })
  const usd = usdFixed + usdDemand
  return { fixed, demand, usdFixed, usdDemand, usd, total: fixed + demand + usd }
})

const bankOption = computed(() => {
  const deposits = detail.value?.deposits || []
  if (!deposits.length) return {}

  // 依銀行分組，區分定存 vs 活存；依頁籤決定幣別
  // TRANSIT 在途款項不計入 bar
  const isTwdTab = bankCurrencyTab.value === 'TWD'
  const bankMap = {} // { bankName: { fixed: 0, demand: 0 } }
  deposits.forEach(d => {
    const cur = d.currency || 'TWD'
    if (cur === 'TRANSIT_TWD' || cur === 'TRANSIT_USD') return
    // 篩選幣別
    if (isTwdTab && cur === 'USD') return
    if (!isTwdTab && cur !== 'USD') return
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
  const sorted = Object.entries(bankMap).sort((a, b) => (a[1].fixed + a[1].demand) - (b[1].fixed + b[1].demand))
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
    legend: { data: ['定存', '活存'], top: 0, right: 0, textStyle: { fontSize: 12 },
      textStyle: { fontSize: 12 },
      formatter: name => isTwdTab ? name : (name === '定存' ? '美元定存' : '美元活存') },
    grid: { left: 100, right: 130, top: 30, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
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
        data: sorted.map(e => Math.max(0, e[1].demand)),
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
        displayOrder: null,
        shares: 0,
        investmentCost: 0,
        investmentCostOriginal: 0,
        currentValue: 0,
        estimatedDividend: 0
      })
    }
    const g = map.get(key)
    g.shares += Number(s.shares || 0)
    g.investmentCost += Number(s.investmentCostTwd ?? s.investmentCost ?? 0)
    // 買入均價：BFF 已將美股 legacy TWD 記錄換算為 USD，前端直接 sum 即可
    g.investmentCostOriginal += Number(s.investmentCostOriginal ?? s.investmentCost ?? 0)
    g.currentValue += Number(s.currentValue || 0)
    g.estimatedDividend += Number(s.estimatedDividend || 0)
    if (s.dividendRate && !g.dividendRate) g.dividendRate = Number(s.dividendRate)
    // 取任一有效的 displayOrder
    if (s.displayOrder != null && g.displayOrder == null) g.displayOrder = s.displayOrder
  }
  // 股價：直接使用快照基準日的歷史收盤價（美股 USD、台股 TWD）
  const closeMap = snapshotClosePrices.value
  return [...map.values()]
    .map(g => ({
      ...g,
      stockPrice: closeMap[`${g.market}_${g.stockCode}`] ?? null,
      profit: g.currentValue - g.investmentCost,
      profitRate: g.investmentCost > 0 ? (g.currentValue - g.investmentCost) / g.investmentCost : 0
    }))
    .sort((a, b) => {
      // 已設定順序的依 displayOrder 排；未設定的（新增持股）排在最後，依現值降序
      if (a.displayOrder != null && b.displayOrder != null) return a.displayOrder - b.displayOrder
      if (a.displayOrder != null) return -1
      if (b.displayOrder != null) return 1
      return b.currentValue - a.currentValue
    })
})

const stockMarketTab = ref('台股')

// 保留使用者自訂排序；若股票清單相同只更新數值，若清單變動才重置順序
const customTableData = reactive({ '台股': [], '美股': [] })

watch(stockMarketTab, () => nextTick(initStockSortable))

// 每次資料更新（首次載入 or 價格刷新）都確保 Sortable 已綁定
watch(() => customTableData[stockMarketTab.value], () => nextTick(initStockSortable), { deep: false })

watch(mergedStocks, (stocks) => {
  for (const market of ['台股', '美股']) {
    const incoming = stocks.filter(s => s.market === market)
    const existing = customTableData[market]
    const incomingKeys = incoming.map(s => s.stockCode).sort().join(',')
    const existingKeys = existing.map(s => s.stockCode).sort().join(',')
    if (incomingKeys === existingKeys && existing.length > 0) {
      // 股票清單不變，只刷新數值、保留使用者排序
      customTableData[market] = existing.map(s =>
        incoming.find(n => n.stockCode === s.stockCode) ?? s
      )
    } else {
      // 股票清單有異動，使用後端儲存的 displayOrder 決定順序
      customTableData[market] = incoming
    }
  }
}, { immediate: true })

const stockTableData = computed(() => customTableData[stockMarketTab.value] ?? [])

function initStockSortable() {
  if (stockSortable) { stockSortable.destroy(); stockSortable = null }
  const el = stockTableRef.value?.$el
  if (!el) return
  const tbody = el.querySelector('.el-table__body tbody') ?? el.querySelector('tbody')
  if (!tbody || tbody.children.length === 0) return
  stockSortable = Sortable.create(tbody, {
    handle: '.drag-handle',
    animation: 150,
    onEnd({ oldIndex, newIndex, item, from }) {
      if (oldIndex === newIndex) return
      // Sortable 已實際移動 DOM；先還原以避免與 el-table 的虛擬渲染衝突，
      // 再交由資料 splice 觸發 Vue 重新渲染至正確位置。
      from.removeChild(item)
      if (oldIndex >= from.children.length) {
        from.appendChild(item)
      } else {
        from.insertBefore(item, from.children[oldIndex])
      }
      const list = customTableData[stockMarketTab.value]
      const moved = list.splice(oldIndex, 1)[0]
      list.splice(newIndex, 0, moved)
      scheduleSaveOrder()
    }
  })
}

function scheduleSaveOrder() {
  if (orderSaveTimer) clearTimeout(orderSaveTimer)
  orderSaveTimer = setTimeout(() => {
    const snapshotId = selectedSnapshotId.value
    if (!snapshotId) return
    const market = stockMarketTab.value
    const orders = customTableData[market].map((stock, idx) => ({
      stockCode: stock.stockCode,
      market: stock.market,
      displayOrder: idx
    }))
    snapshotApi.updateStockOrder(snapshotId, orders).catch(() => {})
  }, 400)
}

const chartMarketTab = ref('台股')

const chartFilteredStocks = computed(() =>
  mergedStocks.value.filter(s => s.market === chartMarketTab.value)
)

const chartSummary = computed(() => {
  const stocks = chartFilteredStocks.value
  const totalValue  = stocks.reduce((s, x) => s + Number(x.currentValue || 0), 0)
  const totalCost   = stocks.reduce((s, x) => s + Number(x.investmentCost || 0), 0)
  const profit      = totalValue - totalCost
  const profitRate  = totalCost > 0 ? profit / totalCost : 0
  return { totalValue, totalCost, profit, profitRate }
})

const stockBarOption = computed(() => {
  const filtered = chartFilteredStocks.value
  if (!filtered.length) return {}
  const sorted = [...filtered].sort((a, b) => a.currentValue - b.currentValue)
  const isTw = chartMarketTab.value === '台股'
  const barColor = isTw ? '#3b82f6' : '#f59e0b'
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (p) => {
        const s = p[0]?.data?.stock
        if (!s) return ''
        const value = Number(s.currentValue || 0)
        const cost = Number(s.investmentCost || 0)
        const profit = value - cost
        const rate = cost > 0 ? (profit / cost * 100).toFixed(2) : '0.00'
        const color = profit >= 0 ? '#16a34a' : '#dc2626'
        const fmt = n => `$${Math.round(n).toLocaleString()}`
        return `<b>${p[0].name}</b><br/>`
          + `現值：${fmt(value)}<br/>`
          + `成本：${fmt(cost)}<br/>`
          + `損益：<span style="color:${color}">${fmt(profit)} (${rate}%)</span>`
      }
    },
    grid: { left: 100, right: 140, top: 10, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    yAxis: { type: 'category', data: sorted.map(s => s.stockName || s.stockCode) },
    series: [{
      type: 'bar',
      data: sorted.map(s => ({
        value: Math.round(Number(s.currentValue)),
        stock: s,
        itemStyle: { color: barColor, borderRadius: [0,4,4,0] }
      })),
      label: {
        show: true,
        position: 'right',
        formatter: p => `$${Number(p.value).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
      }
    }]
  }
})

// ── Stock Analysis Dialog ─────────────────────────────────────
const analysisVisible = ref(false)
const analysisStock = ref(null)

function onStockDblClick(row) {
  analysisStock.value = row
  analysisVisible.value = true
}

function onBarDblClick(params) {
  const s = params?.data?.stock
  if (!s) return
  analysisStock.value = s
  analysisVisible.value = true
}

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
.chart-market-tabs :deep(.el-tabs__header) { margin-bottom: 0; }
.chart-market-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }

.chart-summary-bar {
  display: flex; align-items: center; gap: 0;
  background: #f8fafc; border-radius: 8px;
  padding: 8px 16px; margin-top: 10px;
  border: 1px solid #e2e8f0;
}
.csb-item { display: flex; flex-direction: column; align-items: center; flex: 1; }
.csb-label { font-size: 12px; color: #64748b; margin-bottom: 2px; }
.csb-val { font-size: 14px; font-weight: 600; color: #1e293b; }
.csb-sep { width: 1px; height: 32px; background: #e2e8f0; margin: 0 8px; }
:deep(.el-table__row--striped .el-table__cell) { background: #f7f8fa !important; }


.analysis-loading {
  display: flex; flex-direction: column; align-items: center;
  gap: 12px; padding: 60px 0; color: #64748b; font-size: 14px;
}
.analysis-empty {
  text-align: center; padding: 60px 0; color: #94a3b8; font-size: 14px;
}
.analysis-meta {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
</style>
