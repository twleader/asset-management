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
          v-for="s in snapshots"
          :key="s.id"
          :label="s.snapshotDate"
          :value="s.id" />
      </el-select>
    </div>

    <div v-if="metadataError" class="metadata-error" role="alert">
      快照清單載入失敗，請重試。
      <el-button size="small" type="primary" plain @click="loadSnapshots">重試</el-button>
    </div>
    <div v-else-if="metadataLoading" role="status">快照清單載入中…</div>
    <div v-else-if="!snapshots.length" role="status">目前沒有資產快照</div>

    <!-- KPI Cards -->
    <DashboardPanelState panel="kpis" label="資產總覽" :state="panels.kpis" :height="96" @retry="retryPanel('kpis')">
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

    </DashboardPanelState>

    <!-- Charts Row -->
    <el-row :gutter="20" class="chart-row chart-row-main">
      <!-- Asset Distribution Pie Chart -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <div style="display:flex;align-items:center;justify-content:space-between">
              <div>
                <span class="card-title">資產配置分佈</span>
                <span class="card-sub">{{ pieDate }}</span>
              </div>
              <el-tabs v-model="allocationTab" class="chart-market-tabs" style="margin:0">
                <el-tab-pane label="資產類別" name="category" />
                <el-tab-pane label="台股個股" name="twStock" />
                <el-tab-pane label="美股個股" name="usStock" />
                <el-tab-pane label="現金/債券/股票" name="assetClass" />
              </el-tabs>
            </div>
          </template>
          <DashboardPanelState panel="allocation" label="資產配置" :state="panels.allocation" :height="400" @retry="retryPanel('allocation')">
          <v-chart v-if="allocationTab === 'category'" :option="pieOption" style="height: 400px" autoresize />
          <div v-else-if="allocationTab === 'twStock'">
            <div v-if="!twLookthroughHasData" style="height:400px;display:flex;align-items:center;justify-content:center;color:#94a3b8">
              此快照無台股部位
            </div>
            <v-chart v-else :option="twStockPieOption" style="height: 400px; cursor: pointer" autoresize
              @click="p => onLookthroughPieClick(p, '台股')" />
            <div v-if="twLookthroughHasData && twLookthroughDegraded.length > 0" class="lookthrough-degraded-note">
              {{ twLookthroughDegraded.length }} 檔 ETF 因故未展開成分股，已以整檔金額計入：{{ twLookthroughDegraded.map(d => d.code).join('、') }}
            </div>
          </div>
          <div v-else-if="allocationTab === 'usStock'">
            <div v-if="!usLookthroughHasData" style="height:400px;display:flex;align-items:center;justify-content:center;color:#94a3b8">
              此快照無美股部位
            </div>
            <v-chart v-else :option="usStockPieOption" style="height: 400px; cursor: pointer" autoresize
              @click="p => onLookthroughPieClick(p, '美股')" />
            <div v-if="usLookthroughHasData && usLookthroughEtfCount > 0" class="lookthrough-degraded-note">
              美股 ETF 僅揭露前 10 大成份股，其餘已計入『其它』
            </div>
          </div>
          <div v-else>
            <v-chart :option="assetClassPieOption" style="height: 400px" autoresize />
          </div>
          </DashboardPanelState>
        </el-card>
      </el-col>

      <!-- Asset Trend Line Chart -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <span class="card-title">資產歷史趨勢</span>
            <span class="card-sub">{{ pieDate }}</span>
          </template>
          <DashboardPanelState panel="trend" label="資產趨勢" :state="panels.trend" :height="400" @retry="retryPanel('trend')">
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
          <v-chart :option="trendOption" style="height: 318px; cursor: pointer" autoresize
            @updateAxisPointer="onTrendAxisPointer"
            @click="onTrendClick" />
          </DashboardPanelState>
        </el-card>
      </el-col>
    </el-row>

    <!-- Second Charts Row -->
    <el-row :gutter="20" class="chart-row">
      <!-- Deposit by Bank -->
      <el-col :span="10">
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
          <DashboardPanelState panel="deposits" label="銀行存款" :state="panels.deposits" :height="440" @retry="retryPanel('deposits')">
          <v-chart :option="bankOption" style="height: 440px" autoresize />
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
          </DashboardPanelState>
        </el-card>
      </el-col>

      <!-- Stock Portfolio -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display:flex;align-items:center;justify-content:space-between">
              <span class="card-title">持股明細 (現值)</span>
              <el-tabs v-model="chartMarketTab" class="chart-market-tabs" style="margin:0">
                <el-tab-pane label="台股" name="台股" />
                <el-tab-pane label="美股" name="美股" />
                <el-tab-pane label="英股" name="英股" />
              </el-tabs>
            </div>
          </template>
          <DashboardPanelState panel="stock-values" label="持股現值" :state="panels['stock-values']" :height="440" @retry="retryPanel('stock-values')">
          <v-chart :option="stockBarOption" style="height: 440px" autoresize
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
          </DashboardPanelState>
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
                <template v-if="marketStatusAvailable">
                  <span style="font-size:12px">
                    台股 <span :style="{ color: marketStatus.twMarketOpen ? '#16a34a' : '#94a3b8' }">●</span>
                    {{ marketStatus.twMarketOpen ? '開盤中' : '休市' }}
                  </span>
                  <span style="font-size:12px">
                    美股 <span :style="{ color: marketStatus.usMarketOpen ? '#16a34a' : '#94a3b8' }">●</span>
                    {{ marketStatus.usMarketOpen ? '開盤中' : '休市' }}
                  </span>
                  <span style="font-size:12px">
                    英股 <span :style="{ color: marketStatus.ukMarketOpen ? '#16a34a' : '#94a3b8' }">●</span>
                    {{ marketStatus.ukMarketOpen ? '開盤中' : '休市' }}
                  </span>
                </template>
                <span v-else style="font-size:12px;color:#94a3b8">市場狀態暫無資料</span>
                <el-button type="primary" size="small" :icon="ArrowRight"
                  @click="$router.push('/snapshots/' + latest?.id + '/edit')">
                  管理資產
                </el-button>
              </div>
            </div>
          </template>
          <DashboardPanelState panel="holdings" label="股票持股" :state="panels.holdings" :height="280" @retry="retryPanel('holdings')">
          <el-tabs v-model="stockMarketTab" class="stock-tabs">
            <el-tab-pane label="台股" name="台股" />
            <el-tab-pane label="美股" name="美股" />
            <el-tab-pane label="英股" name="英股" />
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
            <el-table-column label="股價/漲跌(%)" width="190" align="right">
              <template #default="{ row }">
                <span v-if="isRowClosePending(row)" style="color:#d97706;font-size:12px">收盤價待補</span>
                <span v-else-if="getPriceCell(row)">
                  <span :style="{ fontWeight: 600, color: priceNumberColor(row) }">{{ formatPrice(getPriceCell(row).price) }}</span>
                  <span v-if="getPriceCell(row).priceChange != null"
                    :style="{ color: changeColor(getPriceCell(row).priceChange), fontSize: '11px', marginLeft: '4px' }">
                    {{ changeArrow(getPriceCell(row).priceChange) }}${{ Math.abs(getPriceCell(row).priceChange).toFixed(2) }}
                    ({{ Math.abs(getPriceCell(row).changePercent ?? 0).toFixed(2) }}%)
                  </span>
                </span>
                <span v-else style="color:#94a3b8">-</span>
              </template>
            </el-table-column>
            <el-table-column label="股數" width="95" align="right">
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
            <el-table-column label="損益" align="right" width="175">
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
          </DashboardPanelState>
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
          <DashboardPanelState panel="funds" label="信託基金" :state="panels.funds" :height="280" @retry="retryPanel('funds')">
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
            <div class="csb-sep" />
            <div class="csb-item">
              <span class="csb-label">預估年配息</span>
              <span class="csb-val" style="color:#16a34a">{{ formatCurrency(fundSummary.totalDividend) }}</span>
            </div>
          </div>
          </DashboardPanelState>
        </el-card>
      </el-col>
    </el-row>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" :usd-rate="analysisUsdRate" />
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { ArrowRight, Operation } from '@element-plus/icons-vue'
import Sortable from 'sortablejs'
import { bffApi } from '@/api'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import DashboardPanelState from '@/components/DashboardPanelState.vue'
import { escapeHtml } from '@/utils/escapeHtml'
import { isAcceptedTodayQuote, isClosePending, marketToday, mergeSseQuote } from '@/utils/displayQuote'
import { createDashboardPanelLoader } from '@/utils/dashboardPanelLoader'

let orderSaveTimer = null
let stockSortable = null
const stockTableRef = ref(null)

use([CanvasRenderer, PieChart, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])

const selectedSnapshotId = ref(null)
const allocationTab = ref('category')
const snapshots = ref([])
const panelNames = ['kpis', 'allocation', 'trend', 'deposits', 'stock-values', 'holdings', 'funds']
const panelLoader = createDashboardPanelLoader({
  panelNames,
  stateFactory: reactive,
  isEmpty(panel, payload) {
    const data = payload?.data ?? {}
    if (panel === 'trend') return Array.isArray(data.history) && data.history.length === 0
    if (panel === 'deposits') return Array.isArray(data.snapshot?.deposits) && data.snapshot.deposits.length === 0
    if (panel === 'funds') return Array.isArray(data.snapshot?.funds) && data.snapshot.funds.length === 0
    if (panel === 'holdings' || panel === 'stock-values') {
      return Array.isArray(data.mergedStocks) && data.mergedStocks.length === 0
    }
    return false
  },
  request(panel, { snapshotId, context, signal }) {
    const id = snapshotId
    switch (panel) {
      case 'kpis': return bffApi.dashboard.kpis(id, { signal })
      case 'allocation': return bffApi.dashboard.allocation(id, context.tab ?? 'category', { signal })
      case 'trend': return bffApi.dashboard.trend({ signal })
      case 'deposits': return bffApi.dashboard.deposits(id, { signal })
      case 'stock-values': return bffApi.dashboard.stockValues(id, { signal })
      case 'holdings': return bffApi.dashboard.holdings(id, { signal })
      case 'funds': return bffApi.dashboard.funds(id, { signal })
      default: throw new Error(`未知 Dashboard panel: ${panel}`)
    }
  }
})
const panels = panelLoader.states
const panelData = panel => panels[panel]?.data?.data ?? null
const retryPanel = panel => panelLoader.retry(panel)

// 各 Panel 只維護自己的即時價副本；SSE 只是完成 payload 後的增量，不能成為首屏資料來源。
const livePrices = reactive({ kpis: {}, allocation: {}, 'stock-values': {}, holdings: {} })
function priceMap(prices) {
  return Object.fromEntries((prices ?? []).map(p => [`${p.market}_${p.stockCode}`, p]))
}
for (const panel of Object.keys(livePrices)) {
  watch(() => panelData(panel)?.stockPrices, prices => { livePrices[panel] = priceMap(prices) }, { immediate: true })
}
const stockPrices = computed(() => livePrices.holdings)
const holdingPayload = computed(() => panelData('holdings'))
const stockValuesPayload = computed(() => panelData('stock-values'))
const kpiPayload = computed(() => panelData('kpis'))
const allocationPayload = computed(() => panelData('allocation'))
const trendPayload = computed(() => panelData('trend'))
const depositsPayload = computed(() => panelData('deposits'))
const fundsPayload = computed(() => panelData('funds'))
const marketStatus = computed(() => holdingPayload.value?.marketStatus ?? {})
const marketStatusAvailable = computed(() =>
  ['twMarketOpen', 'usMarketOpen', 'ukMarketOpen'].every(key => marketStatus.value[key] != null)
)
const liveAssets = computed(() => holdingPayload.value?.liveAssets ?? null)
const latest = computed(() => snapshots.value.find(s => s.id === selectedSnapshotId.value) ?? null)
const detail = computed(() => holdingPayload.value?.snapshot ?? null)

let priceStream = null
let statusTimer = null
let reconnectTimer = null
let statusInFlight = false
let isMounted = false
let metadataController = null
let statusController = null

const metadataLoading = ref(false)
const metadataError = ref(null)

async function loadSnapshots() {
  metadataController?.abort()
  const controller = new AbortController()
  metadataController = controller
  metadataLoading.value = true
  metadataError.value = null
  try {
    const metadata = await bffApi.dashboard.snapshots({ signal: controller.signal })
    if (!isMounted || metadataController !== controller) return
    snapshots.value = metadata ?? []
    const id = snapshots.value[0]?.id
    if (id != null) {
      selectedSnapshotId.value = id
      // 每個 panel 在自己的 request 完成時立即提交，不等待其它 panel。
      panelLoader.select(id, { allocation: { tab: allocationTab.value } })
      if (!priceStream) openPriceStream()
      if (!statusTimer) statusTimer = setInterval(refreshMarketStatus, 60 * 1000)
    }
  } catch (error) {
    if (isMounted && metadataController === controller && !controller.signal.aborted) metadataError.value = error
  } finally {
    if (metadataController === controller) {
      metadataLoading.value = false
      metadataController = null
    }
  }
}

onMounted(() => {
  isMounted = true
  loadSnapshots()
})

onUnmounted(() => {
  isMounted = false
  panelLoader.dispose()
  metadataController?.abort()
  statusController?.abort()
  if (priceStream) { priceStream.close(); priceStream = null }
  if (statusTimer) { clearInterval(statusTimer); statusTimer = null }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (orderSaveTimer) { clearTimeout(orderSaveTimer); orderSaveTimer = null }
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
      const key = `${p.market}_${p.stockCode}`
      for (const panel of Object.keys(livePrices)) {
        const current = livePrices[panel][key]
        if (current) livePrices[panel] = { ...livePrices[panel], [key]: mergeSseQuote(current, p, marketToday(p.market)) }
      }
    } catch (e) {
      console.warn('SSE 解析失敗:', e)
    }
  })
  priceStream.onerror = () => {
    // EventSource 內建 reconnect；只在被永久關閉時重建
    if (isMounted && priceStream && priceStream.readyState === EventSource.CLOSED && !reconnectTimer) {
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        if (isMounted) openPriceStream()
      }, 5000)
    }
  }
}

async function refreshMarketStatus() {
  if (statusInFlight || !isMounted) return
  statusInFlight = true
  const controller = new AbortController()
  statusController = controller
  const selectedId = selectedSnapshotId.value
  const generations = Object.fromEntries(Object.keys(livePrices).map(panel => [panel, panels[panel].generation]))
  // 趨勢圖的即時估值也由自己的 BFF 完整更新；失敗保留上一份完整圖。
  if (panels.trend.data && !panels.trend.loading) panelLoader.run('trend', { preserve: true })
  try {
    const data = await bffApi.dashboard.realtime({ signal: controller.signal })
    if (!isMounted || controller.signal.aborted || selectedSnapshotId.value !== selectedId) return
    for (const panel of Object.keys(livePrices)) {
      panelLoader.update(panel, generations[panel], payload => {
        const next = { ...payload.data }
        // 資產分類/穿透 tab 不需要 stockPrices 與 liveAssets。
        if (Array.isArray(next.stockPrices) && data?.stockPrices?.length) {
          const prices = { ...livePrices[panel] }
          for (const quote of data.stockPrices) {
            const key = `${quote.market}_${quote.stockCode}`
            if (prices[key]) prices[key] = mergeSseQuote(prices[key], quote, marketToday(quote.market))
          }
          next.stockPrices = Object.values(prices)
        }
        const hasLive = next.liveAssets != null && data?.liveAssets?.snapshotDate === next.snapshot?.snapshotDate
        const hasStatus = next.marketStatus != null &&
          ['twMarketOpen', 'usMarketOpen', 'ukMarketOpen'].every(key => typeof data?.marketStatus?.[key] === 'boolean')
        if (hasLive) next.liveAssets = data.liveAssets
        if (hasStatus) next.marketStatus = data.marketStatus
        return { ...payload, data: next, warnings: (payload.warnings ?? []).filter(warning =>
          !(hasLive && warning === 'LIVE_ASSETS_UNAVAILABLE') && !(hasStatus && warning === 'MARKET_STATUS_UNAVAILABLE')) }
      })
    }
  } catch (e) {
    // 背景失敗不取代已完成的 panel 資料。
  } finally {
    statusInFlight = false
    if (statusController === controller) statusController = null
  }
}

function onSnapshotChange(id) {
  if (id == null) return
  statusController?.abort()
  if (orderSaveTimer) { clearTimeout(orderSaveTimer); orderSaveTimer = null }
  selectedSnapshotId.value = id
  panelLoader.select(id, { allocation: { tab: allocationTab.value } })
}

function isBaselineToday(market) {
  // 跨時區處理：基準日要與「該市場當地時區的今天」比，不是 host machine 時區。
  // 例：台灣已 4/30 凌晨，但美東仍是 4/29 下午盤中 → 對美股而言 basedate=4/29 仍視為今天。
  const d = latest.value?.snapshotDate
  if (!d) return false
  const tz = market === '美股' ? 'America/New_York' : market === '英股' ? 'Europe/London' : 'Asia/Taipei'
  const today = new Date().toLocaleDateString('en-CA', { timeZone: tz }) // YYYY-MM-DD
  return d === today
}

/** 該市場 row 是否該套 live 行情（基準日==市場當地今日；不再加「市場開盤」閘門）。
 *  收盤後 Redis cache 於 24h TTL 過期後才失效，但 liveAssets 後端已 fallback 至最近一筆收盤價，
 *  故此函式只判斷 basedate；live overlay 是否真的可用由 overlayLivePrice 內部判斷。 */
function shouldApplyLive(market) {
  return isBaselineToday(market)
}

/** 從 liveAssets.stocks 加總對應 row 的即時估值（台幣）。後端已乘 shares × livePrice ×（美股）匯率。
 *  注意：liveAssets.stocks 是「每 broker 每股票」一筆（同一檔在多家券商會有多筆），
 *  customTableData 是依 stockCode+market 合併過的（一檔一列），故必須 sum 全部 match 的 broker 列。
 *  若沒有任何一筆有 liveValue，回傳 null 讓上層 fallback。 */
function getLiveValueFromAssets(row, sourceLiveAssets = liveAssets.value) {
  const list = sourceLiveAssets?.stocks
  if (!Array.isArray(list)) return null
  let sum = 0
  let any = false
  for (const x of list) {
    if (x.market !== row.market || x.stockCode !== row.stockCode) continue
    if (x.liveValue == null) continue
    sum += Number(x.liveValue)
    any = true
  }
  return any ? sum : null
}

function getRealtimePrice(row, sourcePrices = stockPrices.value) {
  // 基準日 != 該市場當地今日時，股價欄一律凍結為快照儲存的收盤價（priceChange 顯示由模板 fallback 處理）。
  // 不加閘門會讓 SSE 推送的 live 價滲入歷史快照的「股價」欄，與其他欄位（現值/損益/預估配息）凍結值不一致。
  if (!shouldApplyLive(row.market)) return null
  const key = `${row.market}_${row.stockCode}`
  const p = sourcePrices[key]
  if (!p || p.price == null) return null
  if (!isAcceptedTodayQuote(p, marketToday(row.market))) return null
  return {
    price: Number(p.price),
    priceChange: p.priceChange != null ? Number(p.priceChange) : null,
    changePercent: p.changePercent != null ? Number(p.changePercent) : null
  }
}

function getQuoteForRow(row, sourcePrices = stockPrices.value) {
  return sourcePrices[`${row.market}_${row.stockCode}`]
}

function isRowClosePending(row, sourcePrices = stockPrices.value) {
  return isBaselineToday(row.market) && isClosePending(getQuoteForRow(row, sourcePrices))
}

// 台股慣例配色：漲紅、跌綠、平盤灰（與股票走勢圖 markPoint「紅漲綠跌」一致）。
// 註：損益 / KPI 卡沿用另一套「綠獲利、紅虧損」慣例，語意不同、刻意不統一。
function changeColor(v) {
  if (v == null || v === 0) return '#94a3b8' // 平盤 / 無資料：灰
  return v > 0 ? '#dc2626' : '#16a34a'       // 漲：紅；跌：綠
}
function changeArrow(v) {
  if (v == null || v === 0) return ''
  return v > 0 ? '▲' : '▼'
}
// 「股價/漲跌(%)」欄顯示資料：basedate == 該市場當日 → 即時價（getRealtimePrice）；
// 否則 → 快照收盤價 + 該收盤日「vs 前一交易日」的當日漲跌（BFF 已算入 stockPrices，收盤/週末亦可顯示）。
function getPriceCell(row) {
  if (isRowClosePending(row)) return null
  const live = getRealtimePrice(row)
  if (live) {
    return { price: live.price, priceChange: live.priceChange, changePercent: live.changePercent }
  }
  if (row.stockPrice == null) return null
  const p = stockPrices.value[`${row.market}_${row.stockCode}`]
  // stockPrices map 只對應「目前正在檢視的是最新快照」的情境；選了歷史快照時其漲跌不對應該列收盤價 → 只顯示收盤價。
  const isLatestSnapshotView = selectedSnapshotId.value != null && selectedSnapshotId.value === snapshots.value[0]?.id
  const belongsToRow = p && isLatestSnapshotView
  return {
    price: Number(row.stockPrice),
    priceChange: belongsToRow && p.priceChange != null ? Number(p.priceChange) : null,
    changePercent: belongsToRow && p.changePercent != null ? Number(p.changePercent) : null
  }
}
// 股價數字本身顏色：live 或非基準日快照 → 深色；基準日今日但尚無 live → 灰。
function priceNumberColor(row) {
  if (getRealtimePrice(row)) return '#1e293b'
  return isBaselineToday(row.market) ? '#94a3b8' : '#1e293b'
}

const formatShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股' || market === '英股') {
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
  const history = kpiPayload.value?.history ?? []
  if (!baseline) return history
  return history.filter(r => r.snapshotDate <= baseline)
})
const categoryHistory = computed(() => allocationPayload.value?.history ?? [])

const kpiCards = computed(() => {
  // 整個 dashboard（含 KPI）由 selectedSnapshotId 驅動；liveLatest = 選中快照（含盤中 live overlay）。
  // 點趨勢圖某點＝onSnapshotChange 換 selectedSnapshotId，與右上下拉同一路徑、一次切換全部。
  const s = liveLatest.value
  if (!s) return []
  const total = Number(s.totalAssets || 0)
  // 在過濾後的 history 中找選中快照的前一筆
  const h = filteredHistory.value
  const idx = h.findIndex(r => r.snapshotDate === s.snapshotDate)
  const prev = idx > 0 ? h[idx - 1] : (idx === -1 && h.length > 0 ? h[h.length - 1] : null)
  const prevTotal = prev ? Number(prev.totalAssets || 0) : 0
  const change = prevTotal > 0 ? ((total - prevTotal) / prevTotal * 100).toFixed(1) : null

  // 「股票現值」「債券現值」兩卡皆用「現金/債券/股票」圓餅圖同邏輯（Requirement 25）：
  //   股票現值 = stockValue（股票型：一般股票/ETF ＋ 股票型基金，不含債券 ETF）
  //   債券現值 = bondValue（債券型：債券 ETF ＋ 債券型基金）
  // 與圓餅圖同源（history row）、同值：兩卡佔比 == 圓餅圖股票／債券；存款+股票現值+債券現值 == 資產總計。
  // 採快照凍結逐筆 currentValue、不套盤中 live（同 Req 25，與圓餅圖一致）。
  const classRow = filteredHistory.value.find(r => r.id === s.id)
  const stockClassified = Number(classRow?.stockValue || 0)
  const bondClassified = Number(classRow?.bondValue || 0)
  const pctOfTotal = v => total > 0 ? (v / total * 100).toFixed(1) : '0.0'

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
      value: formatCurrency(stockClassified), bg: '#fef3c7', color: '#d97706',
      sub: `佔比 ${pctOfTotal(stockClassified)}%`,
      valueColor: '#1e293b'
    },
    {
      label: '債券現值', emoji: '📜',
      value: formatCurrency(bondClassified), bg: '#f0fdfa', color: '#0d9488',
      sub: `佔比 ${pctOfTotal(bondClassified)}%`,
      valueColor: '#1e293b'
    },
    {
      label: '預估年配息', emoji: '💵',
      value: formatCurrency(s.estimatedAnnualDividend), bg: '#fdf4ff', color: '#9333ea',
      // 殖利率分母用「會生配息／利息的資產」（股票 + 基金現值 + 存款本金）。
      // 分子 estimatedAnnualDividend 已含存款預估年利息（Task 77），分母同步納入存款本金才口徑一致。
      sub: (() => {
        const yieldBase = Number(s.totalStockValue || 0) + Number(s.totalFundValue || 0) + Number(s.totalDeposit || 0)
        const rate = yieldBase > 0 ? (Number(s.estimatedAnnualDividend || 0) / yieldBase * 100) : 0
        return `殖利率 ${rate.toFixed(2)}%`
      })(),
      valueColor: '#1e293b'
    }
  ]
})

// 趨勢圖維持完整全圖、不收合；hover 只記住游標所在的點 index，點擊才正式選取（= 右上下拉，整個 dashboard 切換）。
const hoveredHistoryDate = ref(null)   // 保留供其它 computed 的 ?? fallback；已不由趨勢圖 hover 驅動
const trendHoverIdx = ref(null)
function onTrendAxisPointer(e) {
  const ai = e?.axesInfo?.[0]
  // value 在 category xAxis 是 dataIndex（number）
  trendHoverIdx.value = typeof ai?.value === 'number' ? ai.value : null
}
// 點趨勢圖某點 = 選那天：走 onSnapshotChange（與右上下拉同一支 BFF），整個 dashboard 一次切到該快照。
// 趨勢圖用完整 history（store.history）顯示、不以基準日收合，可連續點不同日期。
function onTrendClick(params) {
  // 點在線/點上：params.dataIndex；點在格線空白處：用 hover 追蹤的 index
  const idx = (params && typeof params.dataIndex === 'number') ? params.dataIndex : trendHoverIdx.value
  if (idx == null) return
  const row = trendPayload.value?.history?.[idx]
  if (row?.id != null && row.id !== selectedSnapshotId.value) {
    selectedSnapshotId.value = row.id     // 同步反映到下拉，避免閃動
    onSnapshotChange(row.id)              // 載入該快照明細，KPI/圓餅/銀行/持股一次切換
  }
}

const pieDate = computed(() => hoveredHistoryDate.value ?? latest.value?.snapshotDate ?? null)

// 「資產配置分佈」面板實際展示的 snapshot id：hover 時跟著 hover 的歷史節點變，
// 沒 hover 時回到下拉選的 selectedSnapshotId。台股個股 tab 的 lazy fetch 與 cache key 用此值。
const effectiveSnapshotId = computed(() => {
  if (hoveredHistoryDate.value) {
    const row = filteredHistory.value.find(x => x.snapshotDate === hoveredHistoryDate.value)
    if (row?.id != null) return row.id
  }
  return selectedSnapshotId.value
})

// 趨勢圖下方自訂 legend：跟著 pieDate（hover 或選中快照）顯示金額與佔比
const trendLegendItems = computed(() => {
  const baseRow = (trendPayload.value?.history ?? []).find(x => x.snapshotDate === pieDate.value)
  if (!baseRow) {
    return [
      { name: '總資產', value: 0, pct: '-', color: '#8b5cf6' },
      { name: '存款',   value: 0, pct: '-', color: '#3b82f6' },
      { name: '投資',   value: 0, pct: '-', color: '#f59e0b' }
    ]
  }
  const r = baseRow
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
  const r = categoryHistory.value.find(x => x.snapshotDate === targetDate)
  if (!r) return {}
  const live = allocationLiveLatest.value
  const useLive = !hoveredHistoryDate.value && live && targetDate === live.snapshotDate
  const twStock = useLive ? Number(live.totalTwStockValue ?? r.totalTwStockValue ?? 0) : Number(r.totalTwStockValue || 0)
  const usStock = useLive ? Number(live.totalUsStockValue ?? r.totalUsStockValue ?? 0) : Number(r.totalUsStockValue || 0)
  const ukStock = useLive ? Number(live.totalUkStockValue ?? r.totalUkStockValue ?? 0) : Number(r.totalUkStockValue || 0)
  const segments = [
    { value: Math.round(Number(r.totalTwdDeposit || 0)),    name: '台幣存款',  color: '#3b82f6' },
    { value: Math.round(Number(r.totalUsdDeposit || 0)),    name: '美元存款',  color: '#60a5fa' },
    { value: Math.round(twStock),                           name: '台股',      color: '#f59e0b' },
    { value: Math.round(usStock),                           name: '美股',      color: '#ef4444' },
    { value: Math.round(ukStock),                           name: '英股',      color: '#0ea5e9' },
    { value: Math.round(Number(r.totalFundValue || 0)),     name: '信託基金',  color: '#10b981' }
  ]
  const data = segments.filter(d => d.value > 0)
  return {
    animation: false,
    tooltip: { trigger: 'item', formatter: p => `${p.name}: $${Number(p.value).toLocaleString()} (${p.percent}%)` },
    legend: { show: false },
    series: [{
      type: 'pie', radius: ['46%', '78%'],
      center: ['50%', '50%'],
      data: data.map(d => ({ value: d.value, name: d.name, itemStyle: { color: d.color } })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      labelLayout: { hideOverlap: true },
      itemStyle: { borderRadius: 6 }
    }]
  }
})

// 現金/債券/股票 三分類圓餅圖（Requirement 25）：與「資產類別」tab 同源，讀 history 的
// cashValue/bondValue/stockValue（business-services 算好），hover/換快照即時反應、不另抓。
// 雙層圓餅外圈 hover 用：載入該快照逐持股分類，群組成 { 成長型/收益型/短期/中期/長期: [{name,value}] }
const classifiedHoldings = computed(() => allocationPayload.value?.classifiedHoldings ?? [])
const holdingsByBucket = computed(() => {
  // 以代號合併同一標的的多筆 broker 持股，避免 tooltip 同名重複多列
  const buckets = { 成長型: {}, 收益型: {}, 短期: {}, 中期: {}, 長期: {} }
  for (const h of classifiedHoldings.value || []) {
    const v = Number(h.currentValue || 0)
    if (v <= 0) continue
    let b = null
    if (h.assetClass === 'STOCK') b = h.stockStyle === 'INCOME' ? '收益型' : '成長型'
    else if (h.assetClass === 'BOND') b = h.bondTerm === 'SHORT' ? '短期' : h.bondTerm === 'LONG' ? '長期' : '中期'
    if (!b) continue
    const key = h.code || h.name
    const cur = buckets[b][key] || { name: h.name || h.code, value: 0 }
    cur.value += v
    buckets[b][key] = cur
  }
  const out = {}
  for (const k in buckets) out[k] = Object.values(buckets[k]).sort((a, b) => b.value - a.value)
  return out
})

// 現金/債券/股票 雙層圓餅（Requirement 25/26/27）：內外層相接成同一個環、各佔一半。
// 內層 = 現金/債券/股票（總覽，家族底色）；外層把各類展開成同色系深淺不同的子分類：
//   現金→台幣/美元（藍系）、債券→短/中/長期（綠系）、股票→成長型/收益型（琥珀系）。
// 因 台幣+美元=現金、短+中+長=債券、成長+收益=股票，外層逐區與內層角度對齊。
// 資料同源（history 的 totalTwd/UsdDeposit + cash/bond/stock + bondShort/Mid/Long + growth/income）。
const assetClassPieOption = computed(() => {
  const targetDate = hoveredHistoryDate.value ?? latest.value?.snapshotDate
  const r = categoryHistory.value.find(x => x.snapshotDate === targetDate)
  if (!r) return {}
  const num = k => Math.round(Number(r[k] || 0))
  const twd = num('totalTwdDeposit'), usd = num('totalUsdDeposit')
  const cash = num('cashValue'), bond = num('bondValue'), stock = num('stockValue')
  const bShort = num('bondShortValue'), bMid = num('bondMidValue'), bLong = num('bondLongValue')
  const growth = num('growthValue'), income = num('incomeValue')
  const C = {
    現金: '#3b82f6', 台幣: '#2563eb', 美元: '#93c5fd',                 // 藍系
    債券: '#14b8a6', 短期: '#5eead4', 中期: '#2dd4bf', 長期: '#0f766e', // 綠/teal 系
    股票: '#f59e0b', 成長型: '#fcd34d', 收益型: '#d97706'              // 琥珀系
  }
  const seg = (name, value) => ({ value, name, itemStyle: { color: C[name] } })
  const inner = [seg('現金', cash), seg('債券', bond), seg('股票', stock)].filter(d => d.value > 0)
  const outer = [
    seg('台幣', twd), seg('美元', usd),
    seg('短期', bShort), seg('中期', bMid), seg('長期', bLong),
    seg('成長型', growth), seg('收益型', income)
  ].filter(d => d.value > 0)
  const base = { type: 'pie', center: ['50%', '50%'], itemStyle: { borderColor: '#fff', borderWidth: 2 } }
  // tooltip：外圈股票/債券子分類列出底下個別持股與金額（前 10 大 + 其餘彙總，股名過長省略）；其餘只顯示金額與佔比
  const money = v => `$${Number(v).toLocaleString(undefined, { maximumFractionDigits: 0 })}`
  const fmtRow = (name, val, color) =>
    `<div style="display:flex;justify-content:space-between;gap:16px;line-height:1.6;${color ? `color:${color};` : ''}">
       <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:188px">${escapeHtml(name)}</span>
       <span>${money(val)}</span>
     </div>`
  const fmtTooltip = p => {
    const head = `${escapeHtml(p.name)}：$${Number(p.value).toLocaleString()}（${p.percent}%）`
    const list = holdingsByBucket.value[p.name]
    if (p.seriesName === '細分' && Array.isArray(list) && list.length) {
      const TOPN = 10
      let rows = list.slice(0, TOPN).map(h => fmtRow(h.name, h.value)).join('')
      const rest = list.slice(TOPN)
      if (rest.length) {
        rows += fmtRow(`…其餘 ${rest.length} 檔`, rest.reduce((s, h) => s + h.value, 0), '#94a3b8')
      }
      return `<b>${head}</b><div style="margin-top:4px;border-top:1px solid #eee;padding-top:4px">${rows}</div>`
    }
    return head
  }
  return {
    animation: false,
    tooltip: { trigger: 'item', confine: true, extraCssText: 'max-width:300px;', formatter: fmtTooltip },
    legend: { show: false },
    series: [
      // 內圈標籤 host：半徑放大且與內環同角度 → 各標籤落到對應扇形方向、靠近內環，朝外引線指向該扇形。
      //   扇形必須透明：host data 去掉每筆的 itemStyle.color，才不會被當成第三個實心環。
      { type: 'pie', center: ['50%', '50%'], radius: ['0%', '30%'],
        silent: true, tooltip: { show: false }, emphasis: { disabled: true },
        avoidLabelOverlap: true, itemStyle: { color: 'transparent', borderColor: 'transparent', borderWidth: 0 },
        label: { show: true, position: 'outside', alignTo: 'none',
          formatter: '{b}\n{d}%', fontSize: 11, lineHeight: 15 },
        labelLine: { show: true, length: 6, length2: 10, lineStyle: { width: 1 } },
        data: inner.map(d => ({ name: d.name, value: d.value })) },
      // 內圈環（薄）：自身不出 label，標籤交給上面的 host
      { ...base, name: '總覽', radius: ['41%', '53%'],
        label: { show: false }, labelLine: { show: false }, data: inner },
      // 外圈環（薄）：label 往外、引線朝外
      { ...base, name: '細分', radius: ['53%', '65%'],
        label: { position: 'outside', formatter: '{b}\n{d}%', fontSize: 11 },
        labelLine: { length: 12, length2: 10 }, labelLayout: { hideOverlap: true }, data: outer }
    ]
  }
})

// 台股個股穿透前 10 大圓餅圖
const TW_PIE_COLORS = [
  '#2563eb', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6',
  '#06b6d4', '#f97316', '#84cc16', '#ec4899', '#0ea5e9',
  '#94a3b8'  // 「其它」灰色
]
const twLookthrough = computed(() => allocationPayload.value?.twLookthrough ?? null)
const twLookthroughHasData = computed(() => {
  const lt = twLookthrough.value
  return !!(lt && Array.isArray(lt.items) && (lt.items.length > 0 || Number(lt.others?.value || 0) > 0))
})
const twLookthroughDegraded = computed(() => twLookthrough.value?.degradedEtfs || [])
const twStockPieOption = computed(() => {
  const lt = twLookthrough.value
  if (!lt) return {}
  const segs = []
  ;(lt.items || []).forEach((it, i) => {
    // 圖例/標籤只顯示股名；代號帶在 data 上供 tooltip 顯示
    const label = it.stockName || it.stockCode || ''
    segs.push({
      value: Number(it.value || 0),
      name: label,
      code: it.stockCode || '',
      color: TW_PIE_COLORS[i % (TW_PIE_COLORS.length - 1)]
    })
  })
  const othersVal = Number(lt.others?.value || 0)
  if (othersVal > 0) {
    segs.push({ value: othersVal, name: '其它', code: '', color: TW_PIE_COLORS[TW_PIE_COLORS.length - 1] })
  }
  const data = segs.filter(d => d.value > 0)
  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      // hover 細節：有代號則顯示「代號 股名」，否則只顯示股名
      formatter: p => {
        const code = p.data && p.data.code ? `${escapeHtml(p.data.code)} ` : ''
        return `${code}${escapeHtml(p.name)}<br/>$${Number(p.value).toLocaleString(undefined, {maximumFractionDigits: 0})} (${p.percent}%)`
      }
    },
    legend: { show: false },
    series: [{
      type: 'pie',
      radius: ['46%', '78%'],
      center: ['50%', '50%'],
      data: data.map(d => ({ value: d.value, name: d.name, code: d.code, itemStyle: { color: d.color } })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      labelLayout: { hideOverlap: true },
      itemStyle: { borderRadius: 6 }
    }]
  }
})

// 美股個股穿透（鏡像台股；穿透演算法差異在 BFF /us-stock-lookthrough：依真實權重、未揭露歸其它、代號聚合）
const usLookthrough = computed(() => allocationPayload.value?.usLookthrough ?? null)
const usLookthroughHasData = computed(() => {
  const lt = usLookthrough.value
  return !!(lt && Array.isArray(lt.items) && (lt.items.length > 0 || Number(lt.others?.value || 0) > 0))
})
const usLookthroughEtfCount = computed(() => Number(usLookthrough.value?.lookthroughEtfCount || 0))
const usStockPieOption = computed(() => {
  const lt = usLookthrough.value
  if (!lt) return {}
  const segs = []
  ;(lt.items || []).forEach((it, i) => {
    // 美股圖例/標籤顯示代號（Yahoo 成分股英文全名過長）；全名帶在 data 上供 tooltip
    const label = it.stockCode || it.stockName || ''
    segs.push({
      value: Number(it.value || 0),
      name: label,
      fullName: it.stockName || '',
      color: TW_PIE_COLORS[i % (TW_PIE_COLORS.length - 1)]
    })
  })
  const othersVal = Number(lt.others?.value || 0)
  if (othersVal > 0) {
    segs.push({ value: othersVal, name: '其它', fullName: '', color: TW_PIE_COLORS[TW_PIE_COLORS.length - 1] })
  }
  const data = segs.filter(d => d.value > 0)
  return {
    animation: false,
    tooltip: {
      trigger: 'item',
      // hover 細節：顯示「代號 英文全名」+ 金額（台幣）+ 佔比
      formatter: p => {
        const fn = p.data && p.data.fullName ? ` ${escapeHtml(p.data.fullName)}` : ''
        return `${escapeHtml(p.name)}${fn}<br/>$${Number(p.value).toLocaleString(undefined, {maximumFractionDigits: 0})} (${p.percent}%)`
      }
    },
    legend: { show: false },
    series: [{
      type: 'pie',
      radius: ['46%', '78%'],
      center: ['50%', '50%'],
      data: data.map(d => ({ value: d.value, name: d.name, fullName: d.fullName, itemStyle: { color: d.color } })),
      label: { formatter: '{b}\n{d}%', fontSize: 11 },
      labelLayout: { hideOverlap: true },
      itemStyle: { borderRadius: 6 }
    }]
  }
})

watch(allocationTab, tab => {
  if (selectedSnapshotId.value != null) panelLoader.run('allocation', { context: { tab }, preserve: false })
})

const trendOption = computed(() => {
  // 趨勢圖一律顯示完整歷史（不以基準日收合），點選某日只切換 dashboard、不改變此圖範圍
  const baseH = trendPayload.value?.history ?? []
  if (!baseH.length) return {}
  // trend response 已由 BFF 套用 LiveAssetsOverlay；不可依賴 KPI panel 的資料。
  const h = baseH
  return {
    animation: false,
    tooltip: { trigger: 'axis', formatter: (params) =>
      params[0].axisValue + '<br>' +
      params.map(p => `${p.seriesName}: $${Number(p.value).toLocaleString()}`).join('<br>')
    },
    legend: { show: false },
    grid: { left: 60, right: 20, top: 10, bottom: 40 },
    xAxis: { type: 'category', data: h.map(r => r.snapshotDate), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v / 1e4).toFixed(0)}萬` } },
    series: [
      { name: '總資產', type: 'line', smooth: true, lineStyle: { width: 3 }, data: h.map(r => Number(r.totalAssets || 0)), itemStyle: { color: '#8b5cf6' },
        // 在目前選中快照的日期畫一條虛線，標示整個 dashboard 目前顯示的時間點
        markLine: {
          silent: true, symbol: 'none',
          lineStyle: { color: '#a78bfa', type: 'dashed', width: 1.5 },
          label: { show: false },
          data: latest.value?.snapshotDate ? [{ xAxis: latest.value.snapshotDate }] : []
        }
      },
      { name: '存款', type: 'line', smooth: true, data: h.map(r => Number(r.totalDeposit || 0)), itemStyle: { color: '#3b82f6' } },
      // 投資 = 信託基金 + 股票（與資產配置圓餅圖中「投資類」一致）
      { name: '投資', type: 'line', smooth: true, data: h.map(r => Number(r.totalFundValue || 0) + Number(r.totalStockValue || 0)), itemStyle: { color: '#f59e0b' } }
    ]
  }
})

const bankCurrencyTab = ref('TWD')

const bankSummary = computed(() => {
  const deposits = depositsPayload.value?.snapshot?.deposits || []
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
  const deposits = depositsPayload.value?.snapshot?.deposits || []
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
    animation: false,
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        let s = `<strong>${escapeHtml(params[0].name)}</strong><br/>`
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
function normalizeMergedStocks(stocks) {
  return (stocks ?? []).map(g => ({
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
}
const mergedStocks = computed(() => normalizeMergedStocks(holdingPayload.value?.mergedStocks))
const stockValueMergedStocks = computed(() => normalizeMergedStocks(stockValuesPayload.value?.mergedStocks))
const kpiMergedStocks = computed(() => normalizeMergedStocks(kpiPayload.value?.mergedStocks))

const stockMarketTab = ref('台股')

// 保留使用者自訂排序；若股票清單相同只更新數值，若清單變動才重置順序
const customTableData = reactive({ '台股': [], '美股': [], '英股': [] })

watch(stockMarketTab, () => nextTick(initStockSortable))

// 每次資料更新（首次載入 or 價格刷新）都確保 Sortable 已綁定
watch(() => customTableData[stockMarketTab.value], () => nextTick(initStockSortable), { deep: false })

watch(mergedStocks, (stocks) => {
  for (const market of ['台股', '美股', '英股']) {
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

// 基準日 == 該市場當地今日 → 用 live 估值重算 currentValue / profit / estimatedDividend，
// 讓 SSE 推播的即時價立即反映最新行情；否則直接回傳 BFF 預先算好的快照值（凍結在基準日）。
// 數值來源優先序：
//  1. liveAssets.stocks[].liveValue（與「歷年資產管理」共用同一支 API，收盤後 fallback 至最近收盤價）
//  2. stockPrices map 即時價（SSE 推播；收盤後 Redis cache 24h TTL 過後此來源才會失效）
//  3. row 原值（BFF 凍結在基準日的快照值）
function overlayLivePrice(row, snapshot = holdingPayload.value?.snapshot, sourceLiveAssets = liveAssets.value, sourcePrices = stockPrices.value) {
  if (!shouldApplyLive(row.market)) return row
  const shares = Number(row.shares ?? 0)
  if (shares <= 0) return row

  // 即時原幣股價（與表格「股價」欄同源 getRealtimePrice），供 tooltip 顯示，避免顯示快照舊收盤價
  const live = getRealtimePrice(row, sourcePrices)
  let cv = getLiveValueFromAssets(row, sourceLiveAssets)
  if (cv == null) {
    if (!live) return row
    const fx = Number(snapshot?.usdExchangeRate ?? 0)
    // 美股 / 英股 UCITS（USD 計價）：live price 為原幣 USD，乘 fx 換算台幣
    const livePriceTwd = (row.market === '美股' || row.market === '英股') ? Number(live.price) * fx : Number(live.price)
    cv = shares * livePriceTwd
  }
  const cost = Number(row.investmentCost ?? 0)
  const profit = cv - cost
  const profitRate = cost > 0 ? profit / cost : 0
  const dr = Number(row.dividendRate ?? 0)
  const estimatedDividend = dr > 0 ? cv * dr : Number(row.estimatedDividend ?? 0)
  return {
    ...row,
    currentValue: cv,
    profit,
    profitRate,
    estimatedDividend,
    // 即時價可用時覆寫股價欄，與現值 / 表格股價一致；不可用則保留快照收盤價
    ...(live ? { stockPrice: Number(live.price) } : {})
  }
}

/**
 * 將 latest snapshot 的總額（totalStockValue / stockProfit / totalAssets）依 per-market 基準日閘門重算。
 * basedate==該市場當地今日的市場才套 live（customTableData × overlayLivePrice，隨 SSE 推播（及每分鐘 realtime 輪詢）更新）；
 * 非今日市場（含昨日快照、今日尚未建檔、跨午夜）維持快照凍結收盤值（= 該基準日收盤）。
 *
 * 「資產總計」僅在三市場皆為今日時採 liveAssets.liveTotalAssets（與「歷年資產管理」最新列同值），
 * 否則一律 per-market 加總，避免把較新交易日收盤洩漏進一個過去基準日的估值。
 * 「預估配息」一律讀 snapshot 凍結值 s.estimatedAnnualDividend（含基金），不前端重算 — 與歷年資產管理同源。
 */
function withPanelLiveOverlay(s, stocks, live, prices) {
  if (!s) return null
  // per-market 基準日閘門：basedate == 該市場當地今日才套 live；非今日市場（含昨日快照、今日尚未
  // 建檔、週末）一律維持快照凍結收盤值（= 該基準日收盤）。三市場皆非今日 → 走下方 sumOf 全 frozen 分支，
  // 顯示基準日收盤，與「歷年資產管理」per-market overlay 同源同值。
  const twLive = shouldApplyLive('台股')
  const usLive = shouldApplyLive('美股')
  const ukLive = shouldApplyLive('英股')

  const tw = stocks.filter(row => row.market === '台股')
  const us = stocks.filter(row => row.market === '美股')
  const uk = stocks.filter(row => row.market === '英股')
  const sumOf = (rows, applyLive) => rows.reduce((a, row) => {
    const r = applyLive ? overlayLivePrice(row, s, live, prices) : row
    a.value += Number(r.currentValue || 0)
    a.cost  += Number(r.investmentCost || 0)
    return a
  }, { value: 0, cost: 0 })

  const t = sumOf(tw, twLive)
  const u = sumOf(us, usLive)
  const k = sumOf(uk, ukLive)
  const totalStockValue = t.value + u.value + k.value
  const totalStockCost = t.cost + u.cost + k.cost
  const stockProfit = totalStockValue - totalStockCost
  const totalDeposit = Number(s.totalDeposit || 0)
  const totalFundValue = Number(s.totalFundValue || 0)
  // 「資產總計」：唯有三市場皆為「基準日==當地今日」時，liveAssets.liveTotalAssets（全 live 口徑）才與
  // per-market 加總相等，可採用以與「歷年資產管理」最新列同值；只要有任一市場非今日（昨日快照、今日尚未
  // 建檔、跨午夜），一律用 per-market 加總（非今日市場為基準日凍結收盤），避免把較新收盤洩漏進基準日估值。
  const allLive = twLive && usLive && ukLive
  const liveMatchesLatest = live && live.snapshotDate === s.snapshotDate
  const totalAssets = allLive && liveMatchesLatest && live.liveTotalAssets != null
        ? Number(live.liveTotalAssets)
        : totalDeposit + totalFundValue + totalStockValue
  const totalTwStockValue = t.value
  const totalUsStockValue = u.value
  const totalUkStockValue = k.value

  return {
    ...s,
    totalStockValue, totalStockCost, stockProfit,
    totalAssets, totalTwStockValue, totalUsStockValue, totalUkStockValue
  }
}

const liveLatest = computed(() => withPanelLiveOverlay(
  kpiPayload.value?.snapshot,
  kpiMergedStocks.value,
  kpiPayload.value?.liveAssets,
  livePrices.kpis
))
const allocationLiveLatest = computed(() => withPanelLiveOverlay(
  allocationPayload.value?.snapshot,
  normalizeMergedStocks(allocationPayload.value?.mergedStocks),
  allocationPayload.value?.liveAssets,
  livePrices.allocation
))

const stockTableData = computed(() =>
  (customTableData[stockMarketTab.value] ?? []).map(row => overlayLivePrice(row))
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
const fundFilteredHoldings = computed(() => fundsPayload.value?.snapshot?.funds ?? [])

const fundSummary = computed(() => {
  const funds = fundFilteredHoldings.value
  const totalValue = funds.reduce((s, f) => s + Number(f.currentValue || 0), 0)
  const totalCost  = funds.reduce((s, f) => s + Number(f.investmentAmount || 0), 0)
  const totalDividend = funds.reduce((s, f) => s + Number(f.estimatedDividend || 0), 0)
  const profit     = totalValue - totalCost
  const profitRate = totalCost > 0 ? profit / totalCost : 0
  return { totalValue, totalCost, totalDividend, profit, profitRate }
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
    animation: false,
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
        return `<b>${escapeHtml(f.fundName || f.fundCode || '')}</b><br/>`
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
  stockValueMergedStocks.value.filter(s => s.market === chartMarketTab.value)
    .map(row => overlayLivePrice(row, stockValuesPayload.value?.snapshot, stockValuesPayload.value?.liveAssets, livePrices['stock-values']))
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
    animation: false,
    tooltip: {
      trigger: 'axis',
      confine: true,
      formatter: (p) => {
        const s = p[0]?.data?.stock
        if (!s) return ''
        const value = Number(s.currentValue || 0)
        const cost = Number(s.investmentCost || 0)
        const shares = Number(s.shares || 0)
        const price = Number(s.stockPrice || 0)
        const avgCost = Number(s.avgCostOriginal || 0)
        const profit = value - cost
        const rate = cost > 0 ? (profit / cost * 100).toFixed(2) : '0.00'
        const color = profit >= 0 ? '#16a34a' : '#dc2626'
        const fmt = n => `$${Math.round(n).toLocaleString()}`
        const fmtShares = n => n.toLocaleString('zh-TW', { maximumFractionDigits: 4 })
        const fmtPrice = n => `$${n.toLocaleString('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
        const title = s.stockName ? `${escapeHtml(s.stockCode)} ${escapeHtml(s.stockName)}` : escapeHtml(s.stockCode)
        return `<b>${title}</b><br/>`
          + `持股：${fmtShares(shares)}<br/>`
          + `股價：${fmtPrice(price)}<br/>`
          + `均價：${fmtPrice(avgCost)}<br/>`
          + `現值：${fmt(value)}<br/>`
          + `成本：${fmt(cost)}<br/>`
          + `損益：<span style="color:${color}">${fmt(profit)} (${rate}%)</span>`
      }
    },
    grid: { left: 140, right: 140, top: 10, bottom: 30 },
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
const analysisUsdRate = ref(null)

function onStockDblClick(row) {
  analysisStock.value = row
  analysisUsdRate.value = holdingPayload.value?.snapshot?.usdExchangeRate ?? null
  analysisVisible.value = true
}

function onBarDblClick(params) {
  const s = params?.data?.stock
  if (!s) return
  analysisStock.value = s
  analysisUsdRate.value = stockValuesPayload.value?.snapshot?.usdExchangeRate ?? null
  analysisVisible.value = true
}

// 個股穿透圓餅圖點擊 → 開股票分析 dialog
// 台股段 data: { name=股名, code=代號 }；美股段 data: { name=代號, fullName=股名 }
function onLookthroughPieClick(params, market) {
  const d = params?.data
  if (!d) return
  const code = market === '美股' ? d.name : d.code
  const stockName = market === '美股' ? d.fullName : d.name
  if (!code) return // 「其它」聚合段無代號，不開
  // 若為直接持有的個股，沿用完整列（含成本，可畫成本均價線）；否則只帶代號/股名/市場
  const full = mergedStocks.value.find(s => s.stockCode === code && s.market === market)
  analysisStock.value = full || { stockCode: code, stockName, market }
  analysisUsdRate.value = allocationPayload.value?.snapshot?.usdExchangeRate ?? null
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
.metadata-error { padding: 12px; background: #fef2f2; color: #b42318; border-radius: 6px; }
.kpi-row, .chart-row { margin: 0 !important; }
/* 左右兩個 panel 等高：整列 flex 拉伸，兩張卡片同高 */
.chart-row-main { display: flex; align-items: stretch; }
.chart-row-main > .el-col { display: flex; flex-direction: column; }
.chart-row-main :deep(.el-card) { height: 100%; }
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
.lookthrough-degraded-note {
  margin-top: 6px; font-size: 11px; color: #94a3b8; text-align: center;
}

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
