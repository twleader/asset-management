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
    <div class="kpi-row kpi-flex">
      <el-card v-for="kpi in kpiCards" :key="kpi.label" class="kpi-card kpi-flex-item">
        <div class="kpi-icon" :style="{ background: kpi.bg }">
          <img v-if="kpi.img" :src="kpi.img" style="width:30px;height:30px;object-fit:contain" />
          <span v-else-if="kpi.emoji" style="font-size:26px;line-height:1">{{ kpi.emoji }}</span>
          <el-icon v-else size="22" :color="kpi.color"><component :is="kpi.icon" /></el-icon>
        </div>
        <div class="kpi-content">
          <div class="kpi-label">{{ kpi.label }}</div>
          <div class="kpi-value" :style="{ color: kpi.valueColor }">{{ kpi.value }}</div>
          <div class="kpi-sub" v-if="kpi.sub" :style="kpi.subColor ? { color: kpi.subColor } : null">{{ kpi.sub }}</div>
        </div>
      </el-card>
    </div>

    <!-- Charts Row -->
    <el-row :gutter="20" class="chart-row">
      <!-- Asset Distribution Pie Chart -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <span class="card-title">資產配置分佈</span>
            <span class="card-sub">{{ pieDate }}</span>
          </template>
          <v-chart :option="pieOption" style="height: 320px" autoresize />
        </el-card>
      </el-col>

      <!-- Asset Trend Line Chart -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <span class="card-title">資產歷史趨勢</span>
            <span class="card-sub">{{ pieDate }}</span>
          </template>
          <div class="trend-legend">
            <div v-for="item in trendLegendItems" :key="item.name" class="trend-legend-item">
              <span class="tl-dot" :style="{ background: item.color }"></span>
              <div class="tl-text">
                <div class="tl-name">{{ item.name }}</div>
                <div class="tl-amount" :style="{ color: item.color }">{{ formatCurrency(item.value) }}</div>
                <div class="tl-pct" :style="{ color: item.color }">{{ item.pct }}</div>
              </div>
            </div>
          </div>
          <v-chart :option="trendOption" style="height: 260px" autoresize
            @updateAxisPointer="onTrendAxisPointer"
            @globalout="onTrendLeave" />
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
                  :style="{ fontWeight: 600, color: isBaselineToday(row.market) ? '#94a3b8' : '#1e293b' }">
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
                <span style="color:#475569">{{ row.avgCostOriginal != null ? formatPrice(row.avgCostOriginal) : '-' }}</span>
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
          <div class="chart-summary-bar">
            <div class="csb-item">
              <span class="csb-label">目前總值</span>
              <span class="csb-val">{{ formatCurrency(stockTableSummary.value) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">投資成本</span>
              <span class="csb-val">{{ formatCurrency(stockTableSummary.cost) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">損益</span>
              <span class="csb-val" :class="stockTableSummary.profit >= 0 ? 'profit' : 'loss'">
                {{ formatCurrency(stockTableSummary.profit) }}
                <small style="font-weight:400"> ({{ formatPct(stockTableSummary.profitRate) }})</small>
              </span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">預估配息</span>
              <span class="csb-val" style="color:#0369a1">{{ formatCurrency(stockTableSummary.dividend) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">持股數</span>
              <span class="csb-val">{{ stockTableSummary.count }} 檔</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 信託基金圖表（放在股票持股下方，重要性次於股票） -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="24">
        <el-card>
          <template #header>
            <span class="card-title">信託基金（現值）</span>
            <span class="card-sub">{{ latest?.snapshotDate }}</span>
          </template>
          <div v-if="!fundFilteredHoldings.length"
               style="height:280px;display:flex;align-items:center;justify-content:center;color:#94a3b8">
            尚無基金資料
          </div>
          <v-chart v-else :option="fundBarOption" style="height: 280px" autoresize />
          <div class="chart-summary-bar">
            <div class="csb-item">
              <span class="csb-label">總值</span>
              <span class="csb-val">{{ formatCurrency(fundSummary.totalValue) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">成本</span>
              <span class="csb-val">{{ formatCurrency(fundSummary.totalCost) }}</span>
            </div>
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">損益</span>
              <span class="csb-val" :class="fundSummary.profit >= 0 ? 'profit' : 'loss'">
                {{ formatCurrency(fundSummary.profit) }}
                <small style="font-weight:400"> ({{ formatPct(fundSummary.profitRate) }})</small>
              </span>
            </div>
          </div>
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
import { bffApi } from '@/api'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

let orderSaveTimer = null
let stockSortable = null
const stockTableRef = ref(null)

use([CanvasRenderer, PieChart, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])

const store = useAssetStore()
const stockPrices = ref({})
const mergedStocksFromBff = ref([]) // 由 BFF 預先彙總（含 stockPrice、profit、profitRate 等）
const marketStatus = ref({ twMarketOpen: false, usMarketOpen: false })
const selectedSnapshotId = ref(null)

let priceStream = null
let statusTimer = null

onMounted(async () => {
  // Single BFF call aggregates: snapshots + history + latestSnapshotDetail + prices + marketStatus
  await loadDashboardSummary()

  // 即時股價：透過 SSE 訂閱 external-materials-service 推送（取代原本 2 分鐘 polling）
  openPriceStream()

  // marketStatus 仍用低頻 polling（每分鐘）— 純時區判斷，不需要即時推
  statusTimer = setInterval(refreshMarketStatus, 60 * 1000)

  // 背景補齊所有快照缺漏的配息率（不阻塞頁面載入）
  bffApi.dashboard.enrichDividendRates().then(() => {
    return loadDashboardSummary()
  }).catch(() => {})
})

onUnmounted(() => {
  if (priceStream) { priceStream.close(); priceStream = null }
  if (statusTimer) { clearInterval(statusTimer); statusTimer = null }
  if (stockSortable) { stockSortable.destroy(); stockSortable = null }
})

function openPriceStream() {
  if (priceStream) priceStream.close()
  priceStream = new EventSource('/api/market-data/prices/stream')
  priceStream.addEventListener('price-update', (ev) => {
    try {
      const p = JSON.parse(ev.data)
      // 後端 SSE payload 用 changePct，前端內部用 changePercent，順手 mirror 一下
      if (p.changePct != null && p.changePercent == null) p.changePercent = p.changePct
      stockPrices.value = { ...stockPrices.value, [`${p.market}_${p.stockCode}`]: p }
    } catch (e) {
      console.warn('SSE 解析失敗:', e)
    }
  })
  priceStream.onerror = () => {
    // EventSource 內建 reconnect；只在被永久關閉時重建
    if (priceStream && priceStream.readyState === EventSource.CLOSED) {
      setTimeout(openPriceStream, 5000)
    }
  }
}

async function refreshMarketStatus() {
  try {
    const data = await bffApi.dashboard.realtime()
    if (data?.marketStatus) marketStatus.value = data.marketStatus
  } catch (e) {
    /* silent */
  }
}

async function loadDashboardSummary() {
  try {
    const summary = await bffApi.dashboard.summary()
    store.snapshots = summary.snapshots ?? []
    store.history = summary.history ?? []
    // 僅在使用者尚未選擇任何快照、或目前選的就是最新時才更新；保留使用者選擇避免被輪詢覆蓋
    const latestId = summary.latestSnapshotDetail?.id
    if (latestId != null && (selectedSnapshotId.value == null || selectedSnapshotId.value === latestId)) {
      store.currentSnapshot = summary.latestSnapshotDetail
      selectedSnapshotId.value = latestId
      mergedStocksFromBff.value = summary.mergedStocks ?? []
    }
    applyPricesAndStatus(summary.stockPrices ?? [], summary.marketStatus ?? {})
  } catch (e) {
    console.warn('載入儀表板摘要失敗:', e)
  }
}


async function onSnapshotChange(id) {
  try {
    const detail = await bffApi.dashboard.snapshot(id)
    store.currentSnapshot = detail
    selectedSnapshotId.value = id
    mergedStocksFromBff.value = detail.mergedStocks ?? []
  } catch (e) {
    console.warn('載入快照失敗:', e)
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

function isBaselineToday(market) {
  // 跨時區處理：基準日要與「該市場當地時區的今天」比，不是 host machine 時區。
  // 例：台灣已 4/30 凌晨，但美東仍是 4/29 下午盤中 → 對美股而言 basedate=4/29 仍視為今天。
  const d = latest.value?.snapshotDate
  if (!d) return false
  const tz = market === '美股' ? 'America/New_York' : 'Asia/Taipei'
  const today = new Date().toLocaleDateString('en-CA', { timeZone: tz }) // YYYY-MM-DD
  return d === today
}

/** 該市場 row 是否該套 live 行情（基準日==市場當地今日，且市場開盤）。 */
function shouldApplyLive(market) {
  if (!isBaselineToday(market)) return false
  return market === '美股' ? !!marketStatus.value.usMarketOpen : !!marketStatus.value.twMarketOpen
}

function getRealtimePrice(row) {
  const key = `${row.market}_${row.stockCode}`
  const p = stockPrices.value[key]
  if (!p || p.price == null) return null
  if (p.priceChange == null) return null
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
  const s = liveLatest.value
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
      label: '信託基金', emoji: '📊',
      value: formatCurrency(s.totalFundValue), bg: '#ecfdf5', color: '#10b981',
      sub: (() => {
        const cost = Number(s.totalFundCost || 0)
        const profit = Number(s.totalFundValue || 0) - cost
        return `損益 ${formatCurrency(profit)}`
      })(),
      subColor: (Number(s.totalFundValue || 0) - Number(s.totalFundCost || 0)) >= 0 ? '#16a34a' : '#dc2626',
      valueColor: '#1e293b'
    },
    {
      label: '預估年配息', emoji: '💵',
      value: formatCurrency(s.estimatedAnnualDividend), bg: '#fdf4ff', color: '#9333ea',
      sub: `殖利率 ${total > 0 ? (Number(s.estimatedAnnualDividend || 0) / total * 100).toFixed(2) : 0}%`,
      valueColor: '#1e293b'
    }
  ]
})

// 趨勢圖 hover 狀態：指向 history row 的 snapshotDate；null 表示沒 hover → 顯示最新
const hoveredHistoryDate = ref(null)
function onTrendAxisPointer(e) {
  const ai = e?.axesInfo?.[0]
  // value 在 category xAxis 是 dataIndex（number），label 是 snapshotDate string
  const idx = typeof ai?.value === 'number' ? ai.value : null
  if (idx == null) return
  const row = filteredHistory.value[idx]
  hoveredHistoryDate.value = row?.snapshotDate ?? null
}
function onTrendLeave() { hoveredHistoryDate.value = null }

const pieDate = computed(() => hoveredHistoryDate.value ?? latest.value?.snapshotDate ?? null)

// 趨勢圖下方自訂 legend：跟著 pieDate（hover 或選中快照）顯示金額與佔比
const trendLegendItems = computed(() => {
  const r = filteredHistory.value.find(x => x.snapshotDate === pieDate.value)
  if (!r) {
    return [
      { name: '總資產', value: 0, pct: '-', color: '#8b5cf6' },
      { name: '存款',   value: 0, pct: '-', color: '#3b82f6' },
      { name: '投資',   value: 0, pct: '-', color: '#f59e0b' }
    ]
  }
  const total   = Number(r.totalAssets || 0)
  const deposit = Number(r.totalDeposit || 0)
  const invest  = Number(r.totalFundValue || 0) + Number(r.totalStockValue || 0)
  const pct = v => total > 0 ? `${(v / total * 100).toFixed(1)}%` : '-'
  return [
    { name: '總資產', value: total,   pct: '100.0%',     color: '#8b5cf6' },
    { name: '存款',   value: deposit, pct: pct(deposit), color: '#3b82f6' },
    { name: '投資',   value: invest,  pct: pct(invest),  color: '#f59e0b' }
  ]
})

const pieOption = computed(() => {
  // 一律從 filteredHistory（已含 totalTwdDeposit / totalUsdDeposit / totalTw|UsStockValue / totalFundValue）
  // 取資料：hover 時用對應日期那筆；否則用選中／最新快照那筆。
  // 這樣不論選的是哪個快照，5 區都能正確顯示。
  const targetDate = hoveredHistoryDate.value ?? latest.value?.snapshotDate
  const r = filteredHistory.value.find(x => x.snapshotDate === targetDate)
  if (!r) return {}
  // 若顯示的是「最新／選中快照 + 該快照=最新」且盤中即時跳動：用 liveLatest 的台股/美股值覆蓋（hover 時不覆蓋）
  const useLive = !hoveredHistoryDate.value && liveLatest.value
        && targetDate === liveLatest.value.snapshotDate
  const twStock = useLive ? Number(liveLatest.value.totalTwStockValue ?? r.totalTwStockValue ?? 0)
                          : Number(r.totalTwStockValue || 0)
  const usStock = useLive ? Number(liveLatest.value.totalUsStockValue ?? r.totalUsStockValue ?? 0)
                          : Number(r.totalUsStockValue || 0)
  const segments = [
    { value: Math.round(Number(r.totalTwdDeposit || 0)),    name: '台幣存款',  color: '#3b82f6' },
    { value: Math.round(Number(r.totalUsdDeposit || 0)),    name: '美元存款',  color: '#60a5fa' },
    { value: Math.round(twStock),                           name: '台股',      color: '#f59e0b' },
    { value: Math.round(usStock),                           name: '美股',      color: '#ef4444' },
    { value: Math.round(Number(r.totalFundValue || 0)),     name: '信託基金',  color: '#10b981' }
  ]
  const data = segments.filter(d => d.value > 0)
  return {
    tooltip: { trigger: 'item', formatter: p => `${p.name}: $${Number(p.value).toLocaleString()} (${p.percent}%)` },
    legend: { bottom: 0, textStyle: { fontSize: 12 } },
    series: [{
      type: 'pie', radius: ['40%', '70%'],
      center: ['50%', '45%'],
      data: data.map(d => ({ value: d.value, name: d.name, itemStyle: { color: d.color } })),
      label: { formatter: '{b}\n{d}%' },
      itemStyle: { borderRadius: 6 }
    }]
  }
})

const trendOption = computed(() => {
  const baseH = filteredHistory.value
  if (!baseH.length) return {}
  // 若選中快照=該市場當地今日且市場開盤，把該日 history row 的對應欄位用 liveLatest 覆蓋，
  // 讓趨勢線最後一點隨輪詢同步跳動。
  const live = liveLatest.value
  const h = (live && latest.value) ? baseH.map(r =>
    r.snapshotDate === latest.value.snapshotDate
      ? { ...r,
          totalStockValue: live.totalStockValue,
          totalTwStockValue: live.totalTwStockValue ?? r.totalTwStockValue,
          totalUsStockValue: live.totalUsStockValue ?? r.totalUsStockValue,
          totalAssets: live.totalAssets }
      : r
  ) : baseH
  return {
    tooltip: { trigger: 'axis', formatter: (params) =>
      params[0].axisValue + '<br>' +
      params.map(p => `${p.seriesName}: $${Number(p.value).toLocaleString()}`).join('<br>')
    },
    legend: { show: false },
    grid: { left: 60, right: 20, top: 10, bottom: 40 },
    xAxis: { type: 'category', data: h.map(r => r.snapshotDate), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    series: [
      { name: '總資產', type: 'line', smooth: true, lineStyle: { width: 3 }, data: h.map(r => Number(r.totalAssets || 0)), itemStyle: { color: '#8b5cf6' } },
      { name: '存款', type: 'line', smooth: true, data: h.map(r => Number(r.totalDeposit || 0)), itemStyle: { color: '#3b82f6' } },
      // 投資 = 信託基金 + 股票（與資產配置圓餅圖中「投資類」一致）
      { name: '投資', type: 'line', smooth: true, data: h.map(r => Number(r.totalFundValue || 0) + Number(r.totalStockValue || 0)), itemStyle: { color: '#f59e0b' } }
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
    } else if (cur === 'TRANSIT_TWD') {
      demand += amt   // 台幣在途歸入台幣活存淨額
    } else if (cur === 'TRANSIT_USD') {
      usdDemand += amt   // 美元在途（amount 已是台幣值）歸入美元活存淨額
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

// 由 BFF 預先彙總、排序好的持股清單；前端只負責 render
const mergedStocks = computed(() =>
  (mergedStocksFromBff.value ?? []).map(g => ({
    ...g,
    shares: Number(g.shares || 0),
    investmentCost: Number(g.investmentCost || 0),
    investmentCostOriginal: Number(g.investmentCostOriginal || 0),
    currentValue: Number(g.currentValue || 0),
    estimatedDividend: Number(g.estimatedDividend || 0),
    profit: Number(g.profit || 0),
    profitRate: Number(g.profitRate || 0),
    stockPrice: g.stockPrice != null ? Number(g.stockPrice) : null,
    avgCostOriginal: g.avgCostOriginal != null ? Number(g.avgCostOriginal) : null,
    dividendRate: g.dividendRate != null ? Number(g.dividendRate) : null
  }))
)

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

// 基準日 == 該市場當地今日，且該市場開盤 → 用 live price 重算 currentValue / profit / estimatedDividend，
// 讓 2 分鐘輪詢能即時反映最新行情；否則直接回傳 BFF 預先算好的快照值（凍結在基準日）。
function overlayLivePrice(row) {
  if (!shouldApplyLive(row.market)) return row
  const live = getRealtimePrice(row)
  const shares = Number(row.shares ?? 0)
  if (!live || shares <= 0) return row
  const fx = Number(detail.value?.usdExchangeRate ?? 0)
  const livePriceTwd = row.market === '美股' ? Number(live.price) * fx : Number(live.price)
  const cv = shares * livePriceTwd
  const cost = Number(row.investmentCost ?? 0)
  const profit = cv - cost
  const profitRate = cost > 0 ? profit / cost : 0
  const dr = Number(row.dividendRate ?? 0)
  const estimatedDividend = dr > 0 ? cv * dr : Number(row.estimatedDividend ?? 0)
  return { ...row, currentValue: cv, profit, profitRate, estimatedDividend }
}

/**
 * 將 latest snapshot 的總額（totalStockValue / stockProfit / estimatedAnnualDividend / totalAssets）
 * 用 live 行情重算。僅限「basedate==該市場當地今日 & 該市場開盤」的市場才套 live；
 * 另一個市場仍維持快照凍結值。讓 KPI 卡 / 配置 donut / 趨勢線最後一點隨 2 分鐘輪詢更新。
 */
const liveLatest = computed(() => {
  const s = latest.value
  if (!s) return null
  const twLive = shouldApplyLive('台股')
  const usLive = shouldApplyLive('美股')
  if (!twLive && !usLive) return s

  const tw = customTableData['台股'] ?? []
  const us = customTableData['美股'] ?? []
  const sumOf = (rows, applyLive) => rows.reduce((a, row) => {
    const r = applyLive ? overlayLivePrice(row) : row
    a.value    += Number(r.currentValue || 0)
    a.cost     += Number(r.investmentCost || 0)
    a.dividend += Number(r.estimatedDividend || 0)
    return a
  }, { value: 0, cost: 0, dividend: 0 })

  const t = sumOf(tw, twLive)
  const u = sumOf(us, usLive)
  const totalStockValue = t.value + u.value
  const totalStockCost = t.cost + u.cost
  const stockProfit = totalStockValue - totalStockCost
  const estimatedAnnualDividend = t.dividend + u.dividend
  const totalDeposit = Number(s.totalDeposit || 0)
  const totalFundValue = Number(s.totalFundValue || 0)
  const totalAssets = totalDeposit + totalFundValue + totalStockValue
  const totalTwStockValue = t.value
  const totalUsStockValue = u.value

  return {
    ...s,
    totalStockValue, totalStockCost, stockProfit, estimatedAnnualDividend,
    totalAssets, totalTwStockValue, totalUsStockValue
  }
})

const stockTableData = computed(() =>
  (customTableData[stockMarketTab.value] ?? []).map(overlayLivePrice)
)

const stockTableSummary = computed(() => {
  const stocks = stockTableData.value
  const value    = stocks.reduce((s, x) => s + Number(x.currentValue || 0), 0)
  const cost     = stocks.reduce((s, x) => s + Number(x.investmentCost || 0), 0)
  const dividend = stocks.reduce((s, x) => s + Number(x.estimatedDividend || 0), 0)
  const profit     = value - cost
  const profitRate = cost > 0 ? profit / cost : 0
  return { value, cost, profit, profitRate, dividend, count: stocks.length }
})

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
    bffApi.dashboard.updateStockOrder(snapshotId, orders).catch(() => {})
  }, 400)
}

// ── 信託基金圖（資料來自 detail.funds，不參與盤中輪詢） ─────────────
const fundFilteredHoldings = computed(() => detail.value?.funds ?? [])

const fundSummary = computed(() => {
  const funds = fundFilteredHoldings.value
  const totalValue = funds.reduce((s, f) => s + Number(f.currentValue || 0), 0)
  const totalCost  = funds.reduce((s, f) => s + Number(f.investmentAmount || 0), 0)
  const profit     = totalValue - totalCost
  const profitRate = totalCost > 0 ? profit / totalCost : 0
  return { totalValue, totalCost, profit, profitRate }
})

const fundBarOption = computed(() => {
  const funds = fundFilteredHoldings.value
  if (!funds.length) return {}
  const sorted = [...funds].sort(
    (a, b) => Number(a.currentValue || 0) - Number(b.currentValue || 0)
  )
  const profitColor = '#16a34a'
  const lossColor   = '#dc2626'
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (p) => {
        const f = p[0]?.data?.fund
        if (!f) return ''
        const value = Number(f.currentValue || 0)
        const cost  = Number(f.investmentAmount || 0)
        const profit = value - cost
        const rate = cost > 0 ? (profit / cost * 100).toFixed(2) : '0.00'
        const color = profit >= 0 ? profitColor : lossColor
        const fmt = n => `$${Math.round(n).toLocaleString()}`
        return `<b>${f.fundName || f.fundCode || ''}</b><br/>`
          + `現值：${fmt(value)}<br/>`
          + `成本：${fmt(cost)}<br/>`
          + `損益：<span style="color:${color}">${fmt(profit)} (${rate}%)</span>`
      }
    },
    grid: { left: 160, right: 140, top: 10, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    yAxis: { type: 'category', data: sorted.map(f => f.fundName || f.fundCode || '—') },
    series: [{
      type: 'bar',
      data: sorted.map(f => {
        const profit = Number(f.currentValue || 0) - Number(f.investmentAmount || 0)
        return {
          value: Math.round(Number(f.currentValue || 0)),
          fund: f,
          itemStyle: { color: profit >= 0 ? profitColor : lossColor, borderRadius: [0,4,4,0] }
        }
      }),
      label: {
        show: true,
        position: 'right',
        formatter: p => `$${Number(p.value).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
      }
    }]
  }
})

const chartMarketTab = ref('台股')

const chartFilteredStocks = computed(() =>
  mergedStocks.value.filter(s => s.market === chartMarketTab.value).map(overlayLivePrice)
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
  const profitColor = '#16a34a'  // 賺錢：綠色
  const lossColor   = '#dc2626'  // 賠錢：紅色
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
        const title = s.stockName ? `${s.stockCode} ${s.stockName}` : s.stockCode
        return `<b>${title}</b><br/>`
          + `現值：${fmt(value)}<br/>`
          + `成本：${fmt(cost)}<br/>`
          + `損益：<span style="color:${color}">${fmt(profit)} (${rate}%)</span>`
      }
    },
    grid: { left: 100, right: 140, top: 10, bottom: 30 },
    xAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    // 美股股名太長（如 Vanguard S&P 500 ETF），y 軸用代號顯示；台股名稱短，沿用名稱
    yAxis: { type: 'category', data: sorted.map(s => isTw ? (s.stockName || s.stockCode) : s.stockCode) },
    series: [{
      type: 'bar',
      data: sorted.map(s => {
        const profit = Number(s.currentValue || 0) - Number(s.investmentCost || 0)
        return {
          value: Math.round(Number(s.currentValue)),
          stock: s,
          itemStyle: { color: profit >= 0 ? profitColor : lossColor, borderRadius: [0,4,4,0] }
        }
      }),
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
.kpi-flex { display: flex; gap: 20px; flex-wrap: nowrap; }
.kpi-flex-item { flex: 1 1 0; min-width: 0; }

/* 資產歷史趨勢自訂 legend：3 個項目集中置上方中央，項目間留固定間距避免數字相疊 */
.trend-legend { display: flex; justify-content: center; padding: 6px 16px 10px; gap: 36px; flex-wrap: wrap; }
.trend-legend-item { display: flex; align-items: center; gap: 8px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.tl-text { display: flex; flex-direction: column; line-height: 1.25; }
.tl-name   { font-size: 12px; color: #64748b; }
.tl-amount { font-size: 15px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tl-pct    { font-size: 11px; font-weight: 600; }

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
