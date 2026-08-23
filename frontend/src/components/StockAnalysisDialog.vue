<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    class="stock-analysis-dialog"
    width="min(1100px, calc(100vw - 32px))"
    top="calc(15vh - 25px)"
    destroy-on-close
    draggable
    @open="onOpen">
    <template #header>
      <span style="font-size:18px;font-weight:600;color:#1e293b">
        {{ stock?.stockCode }} {{ stock?.stockName || '' }}　股票分析
      </span>
    </template>
    <div class="tabs-wrap">
      <span v-if="activeTab === 'chart' && latestTradingDate" class="tabs-trailing">
        資料截止：{{ latestTradingDate }}
      </span>
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 走勢圖 -->
      <el-tab-pane label="走勢圖" name="chart">
        <div v-if="loading" class="analysis-loading">
          <el-icon class="is-loading" size="36"><Loading /></el-icon>
          <div>{{ backfilling ? '首次載入，補齊 10 年歷史中…（約需數秒）' : '載入歷史股價中…' }}</div>
        </div>
        <div v-else-if="!chartDates.length" class="analysis-empty">
          無歷史資料，請先執行股價補齊
        </div>
        <template v-else>
          <div class="analysis-meta">
            <div class="chart-controls">
              <div class="chart-control-row">
                <div class="chart-control-group">
                  <span class="chart-control-label">圖型：</span>
                  <el-button-group>
                    <el-button size="small" :type="chartMode === 'line' ? 'primary' : 'default'" @click="setChartMode('line')">走勢線</el-button>
                    <el-button size="small" :type="chartMode === 'daily-candle' ? 'primary' : 'default'" @click="setChartMode('daily-candle')">日 K</el-button>
                    <el-button size="small" :type="chartMode === 'weekly-candle' ? 'primary' : 'default'" @click="setChartMode('weekly-candle')">週 K</el-button>
                  </el-button-group>
                </div>
                <div class="chart-control-group">
                  <span class="chart-control-label">指標：</span>
                  <el-select v-model="selectedIndicator" size="small" class="indicator-select">
                    <el-option v-for="o in INDICATOR_OPTIONS" :key="o" :label="o" :value="o" />
                  </el-select>
                </div>
                <span v-if="chartMode === 'weekly-candle'" class="weekly-candle-hint">週 K；技術指標為日線值的週末取樣</span>
                <div class="chart-control-group">
                  <span class="chart-control-label">期間：</span>
                  <el-button-group class="period-control-group">
                    <el-button
                      v-for="opt in visibleRangeOptions" :key="opt.label"
                      size="small"
                      :type="months === opt.months ? 'primary' : 'default'"
                      @click="selectRange(opt.months)">
                      {{ opt.label }}
                    </el-button>
                  </el-button-group>
                </div>
                <span class="chart-zoom-hint">滾輪縮放 / 拖曳平移</span>
              </div>
            </div>
          </div>
          <div v-if="isIntraday && intradayQuote" class="intraday-quote">
            <span class="iq-label">昨收</span>
            <span class="iq-val">{{ fmtQuote(intradayQuote.previousClose) }}</span>
            <template v-if="intradayQuote.change != null">
              <span class="iq-label">今日漲跌</span>
              <span class="iq-val" :style="{ color: quoteColor(intradayQuote.change) }">
                {{ intradayQuote.change > 0 ? '▲' : intradayQuote.change < 0 ? '▼' : '' }}{{ fmtQuote(Math.abs(intradayQuote.change)) }}
                （{{ intradayQuote.changePct > 0 ? '+' : intradayQuote.changePct < 0 ? '-' : '' }}{{ Math.abs(intradayQuote.changePct).toFixed(2) }}%）
              </span>
            </template>
          </div>
          <div v-if="isIntraday && intradayLoading" class="analysis-loading" style="height:500px">
            <el-icon class="is-loading" size="36"><Loading /></el-icon>
            <div>載入當日分時資料中…</div>
          </div>
          <div v-else-if="isIntraday && !intradayTicks.length" class="analysis-empty" style="height:500px">
            無當日分時資料
          </div>
          <div v-else-if="isCandle && !activeFrame.dates?.length" class="analysis-empty" style="height:500px">無完整 OHLC 資料</div>
          <!-- notMerge 必要：切換指標時子圖 series 數量會變（KD,J 五個 vs 威廉指標一個），
               vue-echarts 預設 merge 不會移除多餘的舊 series，舊指標的線會殘留在畫面上。
               dataZoom 的 start/end 本就由 option 明確指定（ez），故 notMerge 不會丟失縮放狀態。 -->
          <v-chart v-else ref="chartRef" :option="chartOption" :update-options="{ notMerge: true }"
                   style="height:500px" autoresize @datazoom="onZoom" />
        </template>
      </el-tab-pane>

      <el-tab-pane v-if="stock?.market === '台股' && stock?.stockCode !== '0000'" label="行情五檔" name="quote-detail">
        <div class="quote-detail-head"><span>Yahoo 股市 · {{ quoteSourceTime }}</span><el-tag size="small" :type="quoteStatusType">{{ quoteStatusText }}</el-tag><el-button size="small" :loading="quoteDetailLoading" @click="refreshQuoteDetail">重新整理</el-button></div>
        <div v-if="quoteDetailLoading" class="analysis-loading"><el-icon class="is-loading" size="36"><Loading /></el-icon><div>載入行情五檔中…</div></div>
        <div v-else-if="!quoteDetail?.available" class="analysis-empty">{{ quoteDetail?.message || '暫時無法取得行情五檔' }}<br><el-button size="small" style="margin-top:12px" @click="refreshQuoteDetail">重新整理</el-button></div>
        <template v-else>
          <div class="quote-summary">
            <div v-for="item in quoteSummary" :key="item.label" class="quote-item"><span>{{ item.label }}</span><b :style="{ color: item.color }">{{ item.value }}</b></div>
          </div>
          <div v-if="quoteDetail.innerPercent == null && quoteDetail.outerPercent == null" class="quote-flow-empty">無分類資料</div>
          <div v-else class="quote-flow"><span class="inner">內盤 {{ fmtLots(quoteDetail.innerVolumeLots) }}（{{ fmtPct(quoteDetail.innerPercent) }}）</span><span class="outer">外盤 {{ fmtLots(quoteDetail.outerVolumeLots) }}（{{ fmtPct(quoteDetail.outerPercent) }}）</span><div class="flow-bar"><i :style="{ width: `${quoteDetail.innerPercent ?? 0}%` }"></i><em :style="{ width: `${quoteDetail.outerPercent ?? 0}%` }"></em></div></div>
          <div class="orderbook"><div class="order-head"><span>量</span><span>委買價</span><span>委賣價</span><span>量</span></div><div v-for="row in quoteLevels" :key="row.level" class="order-row"><span class="volume"><i :style="{width: bidWidth(row)}"></i><span>{{ fmtLots(row.bidVolumeLots) }}</span></span><span>{{ fmtQuote(row.bidPrice) }}</span><span>{{ fmtQuote(row.askPrice) }}</span><span class="volume"><i :style="{width: askWidth(row)}"></i><span>{{ fmtLots(row.askVolumeLots) }}</span></span></div><div class="order-total"><span>委買小計 {{ fmtLots(quoteDetail.bidTotalLots) }}</span><span>委賣小計 {{ fmtLots(quoteDetail.askTotalLots) }}</span></div></div>
        </template>
      </el-tab-pane>

      <!-- 持股明細（ETF only） -->
      <el-tab-pane v-if="isEtf" label="持股明細" name="holdings">
        <div v-if="holdingsLoading" class="analysis-loading">
          <el-icon class="is-loading" size="36"><Loading /></el-icon>
          <div>載入持股明細中…</div>
        </div>
        <template v-else-if="holdingsData?.holdings?.length">
          <div style="margin-bottom:8px;color:#64748b;font-size:12px">
            資料來源：{{ holdingsData.source }}．{{ holdingsData.asOfDate || '時間不明' }}
          </div>
          <v-chart :option="holdingsPieOption" style="height:400px" autoresize />
        </template>
        <div v-else style="padding:24px 8px">
          <div style="color:#475569;font-size:14px;line-height:1.8;margin-bottom:16px">
            ETF 成分股資料目前免費資料源都有限制（TWSE 無此 API、FinMind 需付費方案、發行商官網為 SPA）。
            <br>點擊以下外部連結可查看最新完整成分股：
          </div>
          <div style="display:flex;flex-direction:column;gap:10px">
            <el-link
              v-for="link in etfExternalLinks" :key="link.url"
              :href="link.url" target="_blank" type="primary"
              style="font-size:14px">
              🔗 {{ link.label }}
            </el-link>
          </div>
        </div>
      </el-tab-pane>

      <!-- 股利歷史 -->
      <el-tab-pane label="股利歷史（10 年）" name="dividends">
        <div v-if="dividendsLoading" class="analysis-loading">
          <el-icon class="is-loading" size="36"><Loading /></el-icon>
          <div>載入股利資料中…</div>
        </div>
        <div v-else-if="!dividendHistory.rows?.length" class="analysis-empty">
          {{ dividendHistory.message || '查無股利資料' }}
        </div>
        <template v-else>
          <div style="margin-bottom:8px;color:#64748b;font-size:12px">
            資料來源：{{ dividendHistory.source }}
          </div>
          <el-table :data="dividendDisplayRows" size="small" border max-height="500" style="width:100%"
            :row-class-name="dividendRowClass">
            <el-table-column label="年度" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="font-weight:700">{{ row.year }}</span>
                <span v-else>{{ row.year }}</span>
              </template>
            </el-table-column>
            <el-table-column label="現金股利" width="100" align="right">
              <template #default="{ row }">${{ Number(row.cashDividend || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column label="股票股利" width="100" align="right">
              <template #default="{ row }">{{ Number(row.stockDividend || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column label="現金殖利率" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.yieldPct != null">{{ row.yieldPct.toFixed(2) }}%</span>
                <span v-else style="color:#94a3b8">—</span>
              </template>
            </el-table-column>
            <el-table-column label="除息日昨收價" width="120" align="right">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else-if="row.previousClose != null">${{ Number(row.previousClose).toFixed(2) }}</span>
                <span v-else style="color:#94a3b8">—</span>
              </template>
            </el-table-column>
            <el-table-column label="除息日" width="120" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.exDividendDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="除權日" width="120" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.exRightsDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="現金股利發放日" width="140" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.cashPaymentDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="股票股利發放日" width="140" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.stockPaymentDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="填息天數" width="100" align="right">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else-if="row.fillDays === 0" style="color:#16a34a">當日</span>
                <span v-else-if="row.fillDays != null">{{ row.fillDays }} 天</span>
                <span v-else style="color:#94a3b8">尚未填息</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>
    </div>
  </el-dialog>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, CandlestickChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Loading } from '@element-plus/icons-vue'
import { bffApi } from '@/api/index.js'
import { escapeHtml } from '@/utils/escapeHtml'

// 注意：tree-shaking 版 echarts 必須顯式註冊元件才生效。MarkPointComponent 漏註冊時，
// 收盤線的 markPoint（最高/最低標記）會被 ECharts 靜默忽略、完全不畫（markLine 有註冊故 KD 80/20 正常）。
// Task 359：持股明細圓餅圖同一類陷阱——PieChart 未在此 use() 清單註冊會靜默不畫、無錯誤訊息，
// 這是獨立於 DashboardView.vue 的 <script setup>，不會繼承後者的註冊（比照該檔 392、407 行寫法）。
use([CanvasRenderer, LineChart, BarChart, CandlestickChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent])

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  stock: { type: Object, default: null },
  usdRate: { type: [Number, String], default: null }
})
defineEmits(['update:modelValue'])

const activeTab = ref('chart')
const loading = ref(false)
const backfilling = ref(false)  // Task 136：首次無歷史 → 即時補齊 10 年中
// 走勢圖資料：BFF chart-series 已把股價與技術指標以 tradingDate 聯集對齊，前端零計算、零 join。
// 形狀：{ dates[], prices[], ma5[], ma20[], ma60[], ma240[], k[], d[], j9[], k3d2[], rsv[], latest{} }
const series = ref(null)
const chartDates  = computed(() => series.value?.dates  ?? [])
const chartPrices = computed(() => series.value?.prices ?? [])
// legend／「當日」水平線用的最新值：BFF 已挑好「指標序列本身的最後一筆」，
// 不是對齊後陣列的最後一筆——0000 大盤盤中兩者不同（股價側不併 live、指標側併）
const latestIndicators = computed(() => series.value?.latest ?? null)
const months = ref(12)
const chartMode = ref('line')
const isCandle = computed(() => chartMode.value !== 'line')
const activeFrame = computed(() => chartMode.value === 'daily-candle' ? (series.value?.daily ?? { dates: [] }) : chartMode.value === 'weekly-candle' ? (series.value?.weekly ?? { dates: [] }) : (series.value ?? { dates: [] }))
// K 線的收盤與漲跌基準由 BFF 的 frame-local 欄位定義；不可自行從 closes 回推。
const activeClose = computed(() => isCandle.value ? activeFrame.value.currentClose : lastNonNull(chartPrices.value))
const intradayTicks = ref([])
const intradayLoading = ref(false)
const dividendHistory = ref({ rows: [] })
const dividendsLoading = ref(false)
const chartRef = ref(null)
// 使用者手動拖曳縮放後的 [start,end]%；null＝跟隨期間按鈕預設（最高/最低標記只在可視區間內計算）
const zoomPct = ref(null)

const isIntraday = computed(() => months.value === 0)
const visibleRangeOptions = computed(() => isCandle.value ? rangeOptions.filter(x => x.months !== 0) : rangeOptions)

// 期間按鈕的預設縮放窗（日線：以 months 換算 ~21 個交易日/月；當日：整段全顯示）
const defaultZoomRange = computed(() => {
  if (isIntraday.value) return { start: 0, end: 100 }
  const total = isCandle.value ? (activeFrame.value.dates?.length ?? 0) : chartDates.value.length
  const want = chartMode.value === 'weekly-candle' ? Math.max(4, Math.ceil(months.value * 52 / 12)) : Math.max(20, Math.round(months.value * 21))
  const start = total > 0 ? Math.max(0, 100 * (total - want) / total) : 0
  return { start, end: 100 }
})
// 目前實際可視窗（手動拖曳優先，否則跟隨期間按鈕；當日恆整段）— 最高/最低標記依此計算
const effectiveZoom = computed(() =>
  isIntraday.value ? { start: 0, end: 100 } : (zoomPct.value ?? defaultZoomRange.value))

// 使用者拖曳 dataZoom 後，讀回圖表目前 start/end%，讓最高/最低標記跟著可視區間更新（去抖避免迴圈）
function onZoom() {
  const opt = chartRef.value?.getOption?.()
  const dz = opt?.dataZoom?.[0]
  if (!dz || dz.start == null || dz.end == null) return
  const cur = zoomPct.value
  if (cur && Math.abs(cur.start - dz.start) < 1e-6 && Math.abs(cur.end - dz.end) < 1e-6) return
  zoomPct.value = { start: dz.start, end: dz.end }
}

const latestTradingDate = computed(() => {
  if (isIntraday.value && intradayTicks.value.length) {
    const t = intradayTicks.value[intradayTicks.value.length - 1]?.time
    return t ? String(t).substring(0, 10) : null
  }
  const d = isCandle.value ? (activeFrame.value.dates ?? []) : chartDates.value
  return d.length ? d[d.length - 1] : null
})

// 「當日」報價摘要：昨收、現價、今日漲跌（僅當日模式且已有分時 tick 時回值）。
// 昨收＝該分時交易日「前一交易日」的日線收盤，取自已載入的 history（＝stock_price_history 收盤，
// 與 Dashboard／管理資產「當日漲跌」同一 business API、同一「vs 前一交易日原始收盤」口徑；
// 刻意不採 Redis LivePrice.previousClose，因 TWSE `y` 於除息日為除息參考價、與全站慣例不一致）。
// 現價＝最後一筆非 null 分時成交（＝ legend「股價」的 lastNonNull）。今日漲跌由畫面現價自算，
// 確保「股價 − 昨收 = 今日漲跌」三值一致（不另抓 live，免與現價對不上）。
const intradayQuote = computed(() => {
  if (!isIntraday.value) return null
  const ticks = intradayTicks.value
  if (!ticks.length) return null
  let price = null
  for (let i = ticks.length - 1; i >= 0; i--) {
    if (ticks[i]?.price != null) { price = Number(ticks[i].price); break }
  }
  if (price == null) return null
  const sessionDate = String(ticks[0]?.time || '').substring(0, 10)  // 分時序列所屬交易日（YYYY-MM-DD）
  // 昨收：日線序列（升冪）中 tradingDate 嚴格早於當日交易日的最後一筆收盤。
  // 聯集對齊後尾格可能是「有指標、無股價」的 null（0000 大盤盤中必然如此），故須跳過 null。
  let previousClose = null
  const dates = chartDates.value
  const prices = chartPrices.value
  for (let i = dates.length - 1; i >= 0; i--) {
    if (dates[i] && dates[i] < sessionDate && prices[i] != null) {
      previousClose = Number(prices[i]); break
    }
  }
  if (!(previousClose > 0)) return { price, previousClose: null, change: null, changePct: null }
  const change = price - previousClose
  return { price, previousClose, change, changePct: (change / previousClose) * 100 }
})
// 漲跌配色（台股慣例：漲紅、跌綠、平灰），與 markPoint／Dashboard 同義同色
const quoteColor = v => (v == null ? '#94a3b8' : v > 0 ? '#dc2626' : v < 0 ? '#16a34a' : '#94a3b8')
// 千分位 2 位小數（與 legend fmt 同口徑）；null → 「—」
const fmtQuote = v => (v == null ? '—' : Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

// Task 262：子圖指標選單。切換不重抓資料——chart-series 一次回傳全部指標欄位。
const INDICATOR_OPTIONS = ['KD,J', 'MACD', 'RSI', '乖離率', '威廉指標']
const selectedIndicator = ref('KD,J')

const rangeOptions = [
  { label: '當日',   months: 0 },
  { label: '1個月', months: 1 },
  { label: '3個月', months: 3 },
  { label: '1年',   months: 12 },
  { label: '2年',   months: 24 },
  { label: '3年',   months: 36 },
  { label: '5年',   months: 60 },
  { label: '10年',  months: 120 },
]

function setChartMode(mode) {
  chartMode.value = mode
  if (mode !== 'line' && months.value === 0) months.value = 12
  zoomPct.value = null
}

function selectRange(value) {
  // 當日沒有完整 OHLC，從 K 線切回當日時明確回到既有分時走勢線。
  if (value === 0) chartMode.value = 'line'
  months.value = value
  zoomPct.value = null
}

async function fetchHistory() {
  if (!props.stock) return
  loading.value = true
  backfilling.value = false
  series.value = null
  try {
    // 一次抓 10 年（DB 查詢 < 100ms），之後切期間只調 dataZoom，不再 roundtrip
    const end = new Date().toISOString().split('T')[0]
    const startDate = new Date()
    startDate.setMonth(startDate.getMonth() - 120)
    const start = startDate.toISOString().split('T')[0]
    const code = props.stock.stockCode
    const market = props.stock.market
    let data = await bffApi.stockAnalysis.getChartSeries(code, market, start, end)
    // Task 136：無歷史（多為 ETF 透視成份股尚未被 startup / 每日 cron 補到）→ 即時觸發單檔 10 年回補後重載一次。
    // 只寫 stock_price_history、不入主檔（backfill-stock 端點本就不碰主檔）；今日列獨佔給 ClosePersister。
    // 台股大盤 0000 不觸發（歷史走 twse_index_daily_history）。
    const isTaiex = code === '0000' && market === '台股'
    if (!data?.dates?.length && !isTaiex) {
      backfilling.value = true
      try {
        await bffApi.stockAnalysis.backfillStock(code, market)
        data = await bffApi.stockAnalysis.getChartSeries(code, market, start, end)
      } catch (e) {
        console.warn('歷史回補失敗:', e)
      } finally {
        backfilling.value = false
      }
    }
    series.value = data?.dates?.length ? data : null
  } catch (e) {
    console.warn('無法取得歷史股價:', e)
  } finally {
    loading.value = false
  }
}

async function fetchIntraday() {
  if (!props.stock) return
  intradayLoading.value = true
  intradayTicks.value = []
  try {
    const data = await bffApi.stockAnalysis.getIntradayTicks(props.stock.stockCode, props.stock.market)
    intradayTicks.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.warn('無法取得當日分時資料:', e)
  } finally {
    intradayLoading.value = false
  }
}

watch(months, (m) => {
  // 切期間：清掉手動縮放，最高/最低標記回到該區間預設窗
  zoomPct.value = null
  if (m === 0 && !intradayTicks.value.length && !intradayLoading.value) {
    fetchIntraday()
  }
})

function onOpen() {
  activeTab.value = 'chart'
  months.value = 12
  chartMode.value = 'line'
  intradayTicks.value = []
  zoomPct.value = null
  dividendHistory.value = { rows: [] }
  quoteDetail.value = null
  quoteRequestToken.value++
  quoteDetailLoading.value = false
  quoteDetailAttempted.value = false
  holdingsData.value = null
  holdingsLoading.value = false
  holdingsAttempted.value = false
  fetchHistory()
}

async function fetchDividendHistory() {
  if (!props.stock) return
  dividendsLoading.value = true
  try {
    dividendHistory.value = await bffApi.stockAnalysis.getDividendHistory(props.stock.stockCode, props.stock.market, 10)
  } catch (e) {
    dividendHistory.value = { rows: [], message: '查詢失敗' }
  } finally {
    dividendsLoading.value = false
  }
}

async function onTabChange(name) {
  if (name === 'dividends' && !dividendHistory.value.rows?.length && !dividendsLoading.value) {
    await fetchDividendHistory()
  }
  if (name === 'quote-detail' && !quoteDetailAttempted.value && !quoteDetailLoading.value) {
    quoteDetailAttempted.value = true
    await fetchQuoteDetail()
  }
  if (name === 'holdings' && !holdingsAttempted.value && !holdingsLoading.value) {
    holdingsAttempted.value = true
    await fetchHoldings()
  }
}

const quoteDetail = ref(null)
const quoteDetailLoading = ref(false)
const quoteDetailAttempted = ref(false)
const quoteRequestToken = ref(0)
function refreshQuoteDetail() {
  quoteDetailAttempted.value = true
  return fetchQuoteDetail()
}
async function fetchQuoteDetail() {
  if (!props.stock || quoteDetailLoading.value) return
  const stockCode = props.stock.stockCode
  const market = props.stock.market
  const token = ++quoteRequestToken.value
  quoteDetailLoading.value = true
  try {
    const result = await bffApi.stockAnalysis.getQuoteDetail(stockCode, market)
    if (token === quoteRequestToken.value && props.stock?.stockCode === stockCode && props.stock?.market === market) quoteDetail.value = result
  } catch (e) {
    if (token === quoteRequestToken.value && props.stock?.stockCode === stockCode && props.stock?.market === market) quoteDetail.value = { available: false, message: '暫時無法取得行情五檔' }
  } finally {
    if (token === quoteRequestToken.value) quoteDetailLoading.value = false
  }
}
const quoteStatusText = computed(() => quoteDetail.value?.marketStatus === 'OPEN' ? '盤中' : quoteDetail.value?.marketStatus === 'CLOSED' ? '收盤' : '狀態未知')
const quoteStatusType = computed(() => quoteDetail.value?.marketStatus === 'OPEN' ? 'danger' : quoteDetail.value?.marketStatus === 'CLOSED' ? 'info' : 'warning')
const quoteSourceTime = computed(() => { const v = quoteDetail.value?.sourceTime; const date = v ? new Date(v) : null; return date && !Number.isNaN(date.getTime()) ? new Intl.DateTimeFormat('zh-TW',{ timeZone:'Asia/Taipei',dateStyle:'short',timeStyle:'medium' }).format(date) : '時間不明' })
const fmtLots = v => v == null ? '—' : Number(v).toLocaleString('zh-TW')
const fmtPct = v => v == null ? '—' : `${Number(v).toFixed(2)}%`
const quoteValueColor = v => quoteDetail.value?.previousClose == null || v == null ? '#1e293b' : Number(v) > Number(quoteDetail.value.previousClose) ? '#dc2626' : Number(v) < Number(quoteDetail.value.previousClose) ? '#16a34a' : '#475569'
const signed = v => v == null ? '—' : `${Number(v)>0?'▲':Number(v)<0?'▼':''}${fmtQuote(Math.abs(Number(v)))}`
const quoteSummary = computed(() => { const q=quoteDetail.value||{}; return [
  ['成交',fmtQuote(q.price),quoteValueColor(q.price)],['昨收',fmtQuote(q.previousClose),'#1e293b'],['開盤',fmtQuote(q.openPrice),quoteValueColor(q.openPrice)],['漲跌幅',q.changePercent==null?'—':`${Number(q.changePercent)>0?'▲':Number(q.changePercent)<0?'▼':''}${Math.abs(Number(q.changePercent)).toFixed(2)}%`,quoteColor(q.changePercent)],['最高',fmtQuote(q.highPrice),quoteValueColor(q.highPrice)],['漲跌',signed(q.change),quoteColor(q.change)],['最低',fmtQuote(q.lowPrice),quoteValueColor(q.lowPrice)],['總量',fmtLots(q.volumeLots),'#1e293b'],['均價',fmtQuote(q.averagePrice),'#1e293b'],['昨量',fmtLots(q.previousVolumeLots),'#1e293b'],['成交金額(億)',fmtQuote(q.turnoverYi),'#1e293b'],['振幅',fmtPct(q.amplitudePercent),'#1e293b']
].map(([label,value,color])=>({label,value,color})) })
const quoteLevels = computed(() => Array.from({length:5},(_,i)=>quoteDetail.value?.levels?.[i] ?? {level:i+1,bidPrice:null,bidVolumeLots:null,askPrice:null,askVolumeLots:null}))
const bookWidth = (key,row) => { const m=Math.max(0,...quoteLevels.value.map(x=>Number(x[key]??0))); return m ? `${100*Number(row[key]??0)/m}%` : '0%' }
const bidWidth = row => bookWidth('bidVolumeLots',row); const askWidth = row => bookWidth('askVolumeLots',row)

// 股利歷史：將原始事件依年度分組，年度小計列插在每年事件之上
const dividendDisplayRows = computed(() => {
  const rows = dividendHistory.value.rows || []
  if (!rows.length) return []
  // 為每筆事件附上 yieldPct（以除息日昨收價為分母）
  const enriched = rows.map(r => {
    const cash = Number(r.cashDividend || 0)
    const prev = r.previousClose != null ? Number(r.previousClose) : null
    return {
      ...r,
      isYearSummary: false,
      yieldPct: prev && prev > 0 ? (cash / prev) * 100 : null
    }
  })
  // 依年度（除息日年）分組；年度新→舊
  const byYear = new Map()
  for (const r of enriched) {
    const y = r.year
    if (y == null) continue
    if (!byYear.has(y)) byYear.set(y, [])
    byYear.get(y).push(r)
  }
  const years = [...byYear.keys()].sort((a, b) => b - a)
  const out = []
  for (const y of years) {
    const items = byYear.get(y)
    const totalCash = items.reduce((s, r) => s + Number(r.cashDividend || 0), 0)
    const totalStock = items.reduce((s, r) => s + Number(r.stockDividend || 0), 0)
    // 年度殖利率：以該年最近一次除息事件的昨收價為分母（與 Yahoo 顯示口徑一致）
    const firstWithPrev = items.find(r => r.previousClose != null)
    const yearYield = firstWithPrev && Number(firstWithPrev.previousClose) > 0
      ? (totalCash / Number(firstWithPrev.previousClose)) * 100
      : null
    out.push({
      isYearSummary: true,
      year: y,
      cashDividend: totalCash,
      stockDividend: totalStock,
      yieldPct: yearYield
    })
    out.push(...items)
  }
  return out
})

function dividendRowClass({ row }) {
  return row.isYearSummary ? 'dividend-year-summary' : ''
}

const isEtf = computed(() => {
  const s = props.stock
  if (!s) return false
  if (s.market === '台股') return /^00/.test(s.stockCode || '')
  if (s.market === '美股') {
    const white = ['VOO','VT','VTI','VGT','VYM','VNQ','VXUS','SPY','QQQ','DIA','IVV','IWM','AVGO','SCHD','JEPI','JEPQ']
    return white.includes((s.stockCode || '').toUpperCase())
  }
  if (s.market === '英股') {
    const white = ['CSPX','VWRA','VUSA','EIMI','IWDA']
    return white.includes((s.stockCode || '').toUpperCase())
  }
  return false
})

const etfExternalLinks = computed(() => {
  const s = props.stock
  if (!s) return []
  const code = s.stockCode || ''
  if (s.market === '台股') {
    return [
      { label: `MoneyDJ 成分股（${code}）`, url: `https://www.moneydj.com/etf/x/basic/basic0007A.xdjhtm?etfid=${code}.TW` },
      { label: `玩股網 成分股（${code}）`, url: `https://www.wantgoo.com/stock/etf/${code.toLowerCase()}/constituent` },
      { label: `Yahoo 奇摩股市（${code}）`, url: `https://tw.stock.yahoo.com/quote/${code}.TW/holding` },
    ]
  }
  if (s.market === '美股') {
    return [
      { label: `ETFdb 成分股（${code}）`, url: `https://etfdb.com/etf/${code}/#holdings` },
      { label: `Morningstar 成分股（${code}）`, url: `https://www.morningstar.com/etfs/arcx/${code}/portfolio` },
      { label: `Yahoo Finance（${code}）`, url: `https://finance.yahoo.com/quote/${code}/holdings` },
    ]
  }
  if (s.market === '英股') {
    return [
      { label: `iShares 官網（${code}）`, url: `https://www.ishares.com/uk/individual/en/products/search?keyword=${code}` },
    ]
  }
  return []
})

// Task 359：ETF 成分股圓餅圖。lazy-fetch（見 onTabChange，比照 quoteDetailAttempted 模式），
// holdingsAttempted 避免每次切回本頁籤都重打 API；onOpen() 另外做一輪手動 reset（同 quoteDetail 慣例），
// 確保切換股票後舊資料不殘留在畫面上。fetch 失敗、或 API 回傳 supported=false／holdings 為空陣列時，
// holdingsData.holdings 為空／null，模板降級回上面 etfExternalLinks 既有的靜態連結 fallback，
// 不顯示空白圖表、不拋出未捕捉例外（getEtfHoldings 呼叫已加 skipErrorToast:true，比照 getQuoteDetail
// 慣例，避免全域 toast 汙染其他頁籤）。
const holdingsData = ref(null)
const holdingsLoading = ref(false)
const holdingsAttempted = ref(false)
async function fetchHoldings() {
  if (!props.stock) return
  holdingsLoading.value = true
  try {
    holdingsData.value = await bffApi.stockAnalysis.getEtfHoldings(props.stock.stockCode, props.stock.market)
  } catch (e) {
    holdingsData.value = null
  } finally {
    holdingsLoading.value = false
  }
}

// 配色比照 DashboardView.vue 的 TW_PIE_COLORS（該檔 <script setup> 頂層綁定不會被匯出，無法跨檔
// import，故在此重複定義同一組色階以維持視覺一致）。Task 359.4 起 holdings 陣列可能含 BFF
// 端聚合出的「其它」列（stockCode 為 null，見 EtfHoldingsAggregator）：「其它」固定套用灰階
// ETF_HOLDINGS_OTHERS_COLOR（同 Dashboard TW_PIE_COLORS 陣列最後一色），不進入下方迴圈色票，
// 避免與前 10 大的實際持股撞色。排序／截斷本身已下放到 BFF 完成，本檔只單純映射陣列。
const ETF_HOLDINGS_PIE_COLORS = ['#2563eb', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6', '#06b6d4', '#f97316', '#84cc16', '#ec4899', '#0ea5e9']
const ETF_HOLDINGS_OTHERS_COLOR = '#94a3b8'
const holdingsPieOption = computed(() => {
  const list = holdingsData.value?.holdings || []
  const data = list
    .filter(h => Number(h.weight) > 0)
    .map((h, i) => ({
      value: Number(h.weight),
      name: h.stockName || h.stockCode || '',
      code: h.stockCode || '',
      shares: h.shares,
      itemStyle: { color: h.stockCode == null ? ETF_HOLDINGS_OTHERS_COLOR : ETF_HOLDINGS_PIE_COLORS[i % ETF_HOLDINGS_PIE_COLORS.length] }
    }))
  return {
    tooltip: {
      trigger: 'item',
      // weight 已是 API 回傳的百分比數值，直接格式化顯示，不透過 ECharts {d} 的自動歸一化——
      // Yahoo「前 10 大」來源（359.3c）可視 slice 總和恆小於 100%，{d} 會相對可視總和重新換算，
      // 失真放大單一持股的顯示比例，與真實權重不符。
      formatter: p => {
        const code = p.data?.code ? `${escapeHtml(p.data.code)} ` : ''
        const sharesTxt = p.data?.shares == null ? '—' : `${Number(p.data.shares).toLocaleString('zh-TW')} 股`
        return `${code}${escapeHtml(p.name)}<br/>${Number(p.value).toFixed(2)}%（${sharesTxt}）`
      }
    },
    legend: { show: false },
    series: [{
      type: 'pie',
      radius: ['46%', '78%'],
      center: ['50%', '50%'],
      data,
      // 同上：不用 {d}，理由同 tooltip formatter 註解
      label: { formatter: p => `${p.name}\n${Number(p.value).toFixed(2)}%`, fontSize: 11 },
      labelLayout: { hideOverlap: true },
      itemStyle: { borderRadius: 6 }
    }]
  }
})

// Task 261：MA 與 KD 一律由後端 TechnicalIndicatorService 供給（經 BFF chart-series 對齊），
// 前端不再自算——原本的 calcMA()／calcKD() 已刪除。理由：走勢圖自算的值與觀察清單表格顯示的
// 後端 computeAll() 值在盤中會不一致（後端併今日 live 的真實盤中高低價且不濾 source，
// 前端資料源只併 source 不含括號的實際成交、且合成列沒有 high/low），
// 同一畫面雙擊同一列會看到兩組 K/D。

// 各市場交易時段 [開盤, 收盤] HH:mm（市場當地時區、DST 不變）。鏡射後端 MarketZones（單一事實來源）：
// 美股 09:30–16:00 / 英股 08:00–16:30 / 其餘（台股、大盤 0000）09:00–13:30。
// 供「當日」走勢 X 軸建「開盤→收盤」整段網格、固定延伸到收盤時間（非現在時間）。
function sessionHours(market) {
  if (market === '美股') return ['09:30', '16:00']
  if (market === '英股') return ['08:00', '16:30']
  return ['09:00', '13:30']
}

// 陣列最後一筆非 null 值（當日網格末段恆為未來 null，legend / 成本漲跌色須取此而非末格）
const lastNonNull = arr => {
  for (let i = arr.length - 1; i >= 0; i--) if (arr[i] != null) return arr[i]
  return null
}

// 在可視索引區間 [lo, hi] 內找股價「最高 / 最低」點，回傳 ECharts markPoint data（紅最高、綠最低，符合紅漲綠跌）
// coord 以 x 軸類別字串（labels[i]）定位，避免 dataZoom filterMode 重新索引後絕對索引對不準
// labels[i]＝日線模式為日期、當日模式為 HH:mm 時間（標籤第二行直接顯示，不必分支）
function maxMinMarkPoints(data, labels, lo, hi) {
  let maxI = -1, minI = -1, maxV = -Infinity, minV = Infinity
  for (let i = lo; i <= hi; i++) {
    const v = data[i]
    if (v == null) continue
    if (v > maxV) { maxV = v; maxI = i }
    if (v < minV) { minV = v; minI = i }
  }
  if (maxI < 0) return []
  const fmt = v => Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const span = Math.max(1, hi - lo)
  // 色塊位置依該點在可視窗的水平位置避邊：靠右→放左、靠左→放右、其餘上下（最高在下、最低在上，避免撞 legend / 縮放軸）
  const pos = (i, isMax) => {
    const fx = (i - lo) / span
    if (fx > 0.82) return 'left'
    if (fx < 0.18) return 'right'
    return isMax ? 'bottom' : 'top'
  }
  const mk = (i, v, isMax) => ({
    name: isMax ? '最高' : '最低',
    coord: [labels[i], v],
    value: fmt(v),
    xlabel: labels[i],
    itemStyle: { color: isMax ? '#dc2626' : '#16a34a' },
    label: { position: pos(i, isMax), backgroundColor: isMax ? '#dc2626' : '#16a34a' }
  })
  const pts = [mk(maxI, maxV, true)]
  if (minI !== maxI) pts.push(mk(minI, minV, false))
  return pts
}

// 值接近時讓 endLabel 沿 Y 軸自動錯開（例：股價 2405.50 與季線MA60 2347.75 在 y 軸上僅差約 10px）。
// labelLayout 由 echarts/core 自動註冊（export/core.js 已 use(installLabelLayout)），無須額外 use()。
// 只影響 series 自己的 label：markPoint 的「最高/最低」色塊屬獨立的 MarkPointView，不在此列。
const SHIFT_Y = { moveOverlap: 'shiftY', hideOverlap: false }

// Task 262：子圖指標規格。lines/bars/legendOnly 的元素為 [顯示名稱, chart-series 欄位, 顏色, 是否虛線]。
//  legendOnly = 只在 legend 顯示數值、不畫線——但仍必須掛同名空 series，
//               否則 ECharts LegendView 找不到同名 series 會整項連數值都不繪製（production 無提示）。
//  refs       = 參考虛線；axis: kd|fixed|zero（見 subYAxis）
const INDICATOR_SPEC = {
  'KD,J': {
    lines: [['K9', 'k', '#f59e0b'], ['D9', 'd', '#15803d'], ['J9', 'j9', '#0ea5e9', true]],
    legendOnly: [['K3D2', 'k3d2'], ['RSV', 'rsv']],
    refs: [80, 20], axis: 'kd'
  },
  'MACD': {
    // 價基為 DI=(H+L+2C)/4（台股慣例）。EMA12/EMA26 與價格同量級故不畫線、只顯示數值，
    // 畫上去會把 DIF/MACD/OSC 壓成一條水平線；y 軸值域也只取 dif/macd/osc。
    lines: [['DIF9', 'dif', '#f59e0b'], ['MACD', 'macd', '#8b5cf6']],
    bars: [['OSC', 'osc']],
    legendOnly: [['EMA12', 'ema12'], ['EMA26', 'ema26']],
    refs: [0], axis: 'zero'
  },
  'RSI': {
    lines: [['RSI5', 'rsi5', '#f59e0b'], ['RSI10', 'rsi10', '#15803d']],
    refs: [70, 30], axis: 'fixed'
  },
  '乖離率': {
    lines: [['BIAS10', 'bias10', '#f59e0b'], ['BIAS20', 'bias20', '#15803d']],
    legendOnly: [['B10-B20', 'b10b20']],
    refs: [0], axis: 'zero'
  },
  '威廉指標': {
    // W%R9 = 100 − RSV9，值越小越超買 → 超買線 20 在下、超賣線 80 在上
    lines: [['W%R9', 'wr9', '#f59e0b']],
    refs: [20, 80], axis: 'fixed'
  },
}

const chartOption = computed(() => {
  const sr = series.value
  if (!sr?.dates?.length) return {}
  const s = props.stock || {}
  const candleMode = isCandle.value
  const frame = candleMode ? activeFrame.value : null
  if (candleMode && !frame?.dates?.length) return {}

  // 日線基礎：BFF chart-series 已聯集對齊，直接取用（前端不再計算 MA / KD）
  const num = v => (v == null ? null : Number(v))
  const col = key => (sr[key] ?? []).map(num)
  const dailyDates  = sr.dates
  const dailyPrices = col('prices')
  const dailyMa5    = col('ma5')
  const dailyMa20   = col('ma20')
  const dailyMa60   = col('ma60')
  const dailyMa240  = col('ma240')
  // legend／「當日」水平線的最新值一律取 BFF 挑好的「指標序列本身最後一筆」，
  // 不可取對齊後陣列的末格——0000 大盤盤中末格是「有指標、無股價」，兩者不同
  const lt = candleMode ? (frame.latest || {}) : (latestIndicators.value || {})

  const intraday = isIntraday.value
  // intraday 模式但 tick 序列還沒抓到 → 暫時不畫，由外層 v-if 的 loading 處理
  if (intraday && !intradayTicks.value.length) return {}

  const spec = INDICATOR_SPEC[selectedIndicator.value] || INDICATOR_SPEC['KD,J']
  // 子圖要用到的所有欄位（畫線的、畫柱的、只顯示數值的）
  const subKeys = [...spec.lines, ...(spec.bars || []), ...(spec.legendOnly || [])].map(x => x[1])

  let dates, prices, ma5, ma20, ma60, ma240, xLabelFormatter, candleValues = null, candleHighs = null, candleLows = null
  const subVals = {}
  // subYAxis 的可視區間切片以子圖 x 軸長度為準（intraday 為分鐘網格、日線為交易日）
  let subDates = []
  if (intraday) {
    const ticks = intradayTicks.value
    // 當日 X 軸固定延伸到「收盤時間」而非「現在時間」（與指數當日圖 Requirement 18 同設計、同視覺行為）：
    // 以該市場交易時段建整段「開盤→收盤」每分鐘 category 網格，真實 tick 依 HH:mm 落格、
    // 盤中尚未到達的時段留 null（畫空白、股價線只到最新一筆，靠 connectNulls 讓稀疏 tick 連續）。
    // time 為 ISO LocalDateTime（市場當地時區，如 "2026-06-05T13:25:00"）→ 取 HH:mm。
    const [openHHmm, closeHHmm] = sessionHours(s.market)
    const toMin = hhmm => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m }
    const openMin = toMin(openHHmm), closeMin = toMin(closeHHmm)
    const slots = Math.max(1, closeMin - openMin + 1)
    dates = Array.from({ length: slots }, (_, i) => {
      const mm = openMin + i
      return `${String(Math.floor(mm / 60)).padStart(2, '0')}:${String(mm % 60).padStart(2, '0')}`
    })
    const priceGrid = new Array(slots).fill(null)
    for (const t of ticks) {
      const hhmm = String(t.time).substring(11, 16)
      if (!/^\d\d:\d\d$/.test(hhmm) || t.price == null) continue
      // 邊界外（開盤前 / 剛收盤寬限窗）夾到端點避免遺漏最新一筆；同一分鐘後到者覆蓋＝取該分鐘最後成交
      const idx = Math.max(0, Math.min(slots - 1, toMin(hhmm) - openMin))
      priceGrid[idx] = parseFloat(Number(t.price).toFixed(2))
    }
    prices = priceGrid
    // intraday tick 數不足以重算日線 MA / KD → 取指標序列最新值、以整段網格常數填滿畫成水平參考線（畫到收盤）
    const fill = v => dates.map(() => v)
    ma5   = fill(num(lt.ma5))
    ma20  = fill(num(lt.ma20))
    ma60  = fill(num(lt.ma60))
    ma240 = fill(num(lt.ma240))
    // 子圖指標同樣以 latest 常數填滿——長度必須跟隨「分鐘網格」而非日線陣列，
    // 貼錯會在前 271 分鐘畫出十年前的值
    for (const key of subKeys) subVals[key] = fill(num(lt[key]))
    subDates = dates
    // 整段分鐘網格：軸標籤只在整點 / 半點顯示，避免數百格 HH:mm 全擠上
    xLabelFormatter = v => v
  } else {
    const source = candleMode ? frame : sr
    dates  = source.dates ?? []
    prices = candleMode ? (source.closes ?? []).map(num) : dailyPrices
    ma5    = (source.ma5 ?? []).map(num)
    ma20   = (source.ma20 ?? []).map(num)
    ma60   = (source.ma60 ?? []).map(num)
    ma240  = (source.ma240 ?? []).map(num)
    if (candleMode) {
      candleHighs = (source.highs ?? []).map(num)
      candleLows = (source.lows ?? []).map(num)
      candleValues = dates.map((_, i) => [num(source.opens?.[i]), num(source.closes?.[i]), num(source.lows?.[i]), num(source.highs?.[i])])
    }
    for (const key of subKeys) subVals[key] = (source[key] ?? []).map(num)
    xLabelFormatter = v => v.substring(0, 7)
    subDates = dates
  }
  const priceCurrent = candleMode ? frame.currentClose : activeClose.value
  const pricePrevious = candleMode ? frame.previousClose : null

  // 成本均價 = 買入均價（原幣，交易當下匯率鎖定）。一律取 BFF 已算好的 avgCostOriginal，
  // 與 Dashboard 表格「買入均價」同義同源；禁止用「台幣成本 ÷ 今日即時匯率」反推（今日匯率每日
  // 浮動，會與表格對不上且非真實買入成本）。詳見 spec/design.md BFF Enrichment 註記。
  let cost = null
  if (s.avgCostOriginal != null) {
    cost = Number(s.avgCostOriginal)
  } else if (s.shares > 0 && s.investmentCostOriginal != null) {
    cost = Number(s.investmentCostOriginal) / s.shares
  } else if (s.shares > 0 && s.investmentCost) {
    // fallback（呼叫端未帶原幣成本欄位時）：台股 investmentCost 即原幣 TWD；美/英股才用匯率反推
    const costTwd = s.investmentCost / s.shares
    const usd = props.usdRate ? Number(props.usdRate) : null
    cost = (s.market === '美股' || s.market === '英股') && usd ? costTwd / usd : costTwd
  }

  // ⚠ ez 必須宣告在子圖 y 軸之前：MACD／乖離率的值域只取「目前可視區間」，
  // 若沿用下方原本的宣告位置，y 軸區塊讀 ez 會落在 TDZ（Cannot access 'ez' before initialization）
  const ez = effectiveZoom.value

  // 子圖 Y 軸：三種型態，由指標決定
  //  kd     — 依 K9/D9/J9 值域自適應（有界 0~100，取整條陣列即可），強制涵蓋 20/80 讓超買超賣線恆在畫面內
  //  fixed  — 固定 0~100（RSI、威廉指標本就有界）
  //  zero   — MACD／乖離率：與價位同量級，**只取可視區間**的值域並強制涵蓋 0
  //           （取整條 10 年序列的話，2330 十年價位差兩個數量級，短期間視窗會被壓成一條平線）
  const subYAxis = (() => {
    const base = { gridIndex: 1, type: 'value',
      axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#f0f0f0' } } }
    if (spec.axis === 'fixed') return { ...base, min: 0, max: 100, interval: 50 }

    const pool = spec.axis === 'zero'
      ? (() => {   // 只取可視區間
          const n = subDates.length
          const lo = Math.max(0, Math.floor(n * ez.start / 100))
          const hi = Math.min(n - 1, Math.ceil(n * ez.end / 100))
          return spec.lines.concat(spec.bars || [])
            .flatMap(([, key]) => (subVals[key] || []).slice(lo, hi + 1))
        })()
      : spec.lines.flatMap(([, key]) => subVals[key] || [])

    const vals = pool.filter(v => v != null && Number.isFinite(v))
    if (!vals.length) return { ...base, min: 0, max: 100, interval: 50 }
    const lo = Math.min(...vals), hi = Math.max(...vals)

    if (spec.axis === 'zero') {
      // 不對齊 10 的倍數：BIAS 典型值域僅 ±5，10 的粒度會把線壓平。沿用股價軸的 pad fallback 鏈。
      const pad = (hi - lo) * 0.1 || Math.abs(hi) * 0.001 || 1
      const min = Math.min(0, lo - pad)
      const max = Math.max(0, hi + pad)
      return { ...base, min, max, interval: (max - min) / 2 }
    }
    // kd：刻度只放三個且必須等距——min/max 對齊 10 的倍數後 interval 取 (max-min)/2，
    // range 恆為 10 的倍數故 interval 必為整數。
    // ⚠ 不可只給 min/max 讓 echarts 自己挑間隔（會生出 -40/0/100/130 這種不等距刻度）；
    // ⚠ 也不可對齊到 50 的倍數（J9 跌破 0 一點點就被 floor 到 -50，線被壓成一半高度）。
    const pad = (hi - lo) * 0.1 || 5
    const min = Math.min(20, Math.floor((lo - pad) / 10) * 10)
    const max = Math.max(80, Math.ceil((hi + pad) / 10) * 10)
    return { ...base, min, max, interval: (max - min) / 2 }
  })()

  // 最高 / 最低點：只在目前可視區間內找（資料一次載 10 年、期間鈕只調縮放窗，不可用 ECharts 原生 markPoint max/min）

  // x 軸標籤：日線只在「月份切換」的那一格顯示——ECharts 預設每隔 N 格顯示一個，
  // 而 N 個交易日常落在同一個月，實機因此出現連續好幾個「2025-08」。
  // 再依目前可視範圍抽稀到最多 10 個，10 年期間才不會擠成一團；不旋轉，維持水平好讀。
  const xLabelInterval = intraday
    ? ((idx, val) => typeof val === 'string' && (val.endsWith(':00') || val.endsWith(':30')))
    : (() => {
        const lo = Math.max(0, Math.floor(dates.length * ez.start / 100))
        const hi = Math.min(dates.length - 1, Math.ceil(dates.length * ez.end / 100))
        const monthStarts = []
        for (let i = lo; i <= hi; i++) {
          const cur = String(dates[i] || '').substring(0, 7)
          const prev = i > 0 ? String(dates[i - 1] || '').substring(0, 7) : null
          if (cur && cur !== prev) monthStarts.push(i)
        }
        const stride = Math.max(1, Math.ceil(monthStarts.length / 10))
        const show = new Set(monthStarts.filter((_, k) => k % stride === 0))
        return idx => show.has(idx)
      })()
  let markData = []
  const totalPts = prices.length
  if (totalPts > 0) {
    const loIdx = Math.max(0, Math.floor((ez.start / 100) * (totalPts - 1)))
    const hiIdx = Math.min(totalPts - 1, Math.ceil((ez.end / 100) * (totalPts - 1)))
    if (candleMode) {
      const hi = candleHighs.map((v, i) => v == null ? null : { high: v, low: candleLows[i] })
      markData = maxMinMarkPoints(hi.map(v => v?.high), dates, loIdx, hiIdx)
      const lows = maxMinMarkPoints(hi.map(v => v?.low == null ? null : -v.low), dates, loIdx, hiIdx)
      if (lows.length) { const low = lows.find(x => x.name === '最高'); if (low) { low.name='最低'; low.value=Number(-Number(low.value.replace(/,/g,''))).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2}); low.coord=[low.xlabel,-Number(low.coord[1])]; low.itemStyle={color:'#16a34a'}; low.label={...low.label,backgroundColor:'#16a34a'}; markData = markData.filter(x=>x.name!=='最低').concat(low) } }
    } else markData = maxMinMarkPoints(prices, dates, loIdx, hiIdx)
  }

  // 「當日」模式 Y 軸鎖定當日股價區間（+10% padding），避免被遠離現價的均線水平線（尤其年線 MA240
  // 常在多頭時遠低於現價）撐平走勢、日內波動被壓成一條平線；日線模式維持 scale:true。均線 / 成本均價
  // 落在區間外時由 series clip 自動裁切，數值仍保留在 legend（比照指數圖 GdpTwseView Task 96.7 同一修法）。
  let priceYAxis = {
    gridIndex: 0, type: 'value', scale: true,
    axisLabel: { formatter: v => v.toFixed(0) }, splitLine: { lineStyle: { color: '#f0f0f0' } }
  }
  if (intraday) {
    const vals = prices.filter(v => v != null)
    if (vals.length) {
      const lo = Math.min(...vals), hi = Math.max(...vals)
      const pad = (hi - lo) * 0.1 || hi * 0.001 || 1
      priceYAxis = {
        gridIndex: 0, type: 'value', min: lo - pad, max: hi + pad,
        axisLabel: { formatter: v => v.toFixed(2) }, splitLine: { lineStyle: { color: '#f0f0f0' } }
      }
    }
  }

  return {
    backgroundColor: '#fff',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }] },
      formatter: params => {
        let html = `<strong>${params[0].axisValue}</strong><br/>`
        params.forEach(p => { if (p.value != null) { if (p.seriesName === '股價' && candleMode && Array.isArray(p.value)) html += `${p.marker} 股價：開 <b>${p.value[0]}</b>／高 <b>${p.value[3]}</b>／低 <b>${p.value[2]}</b>／收 <b>${p.value[1]}</b><br/>`; else html += `${p.marker} ${p.seriesName}: <b>${p.value}</b><br/>` } })
        return html
      }
    },
    legend: (() => {
      const fmt = v => (v == null ? '' : Number(v).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
      }))
      // 子圖指標（依 selectedIndicator 而定）的最新值一律取 BFF 的 latest（＝指標序列本身最後一筆），
      // 與「當日」水平線同值；均線同理，確保切換期間看到相同數字
      // 子圖 legend 的項目依選單而變（畫線的 + 只顯示數值的；OSC 柱狀不列 legend）
      const subLegend = [...spec.lines, ...(spec.legendOnly || [])]
      const map = {
        // 當日網格末格恆為未來 null → 取最後一筆非 null 分時價（日線模式 == 末格，行為不變）
        '股價':       fmt(priceCurrent),
        '週線MA5':    fmt(num(lt.ma5)),
        '月線MA20':   fmt(num(lt.ma20)),
        '季線MA60':   fmt(num(lt.ma60)),
        '年線MA240':  fmt(num(lt.ma240)),
        '成本均價':   cost != null ? fmt(cost) : ''
      }
      for (const [name, key] of subLegend) map[name] = fmt(num(lt[key]))
      // 漲跌箭頭：只加在子圖指標上（股價／均線／成本均價維持無箭頭）。
      // 比較基準為指標序列的最後兩筆（BFF 已備妥 prev*），台股慣例漲紅跌綠。
      const arrowOf = (cur, prev) => {
        const a = num(cur), b = num(prev)
        if (a == null || b == null || a === b) return null
        return a > b ? { ch: '▲', color: '#dc2626' } : { ch: '▼', color: '#16a34a' }
      }
      // BFF 的 latest 帶了每個欄位的 prev*（欄名為 prev + 首字大寫）
      const arrowMap = {}
      arrowMap['股價'] = arrowOf(priceCurrent, pricePrevious)
      for (const [name, key] of subLegend) {
        arrowMap[name] = arrowOf(lt[key], lt['prev' + key.charAt(0).toUpperCase() + key.slice(1)])
      }
      // 每個 series 在 legend 數值的色彩，對應線條顏色（與 logo 一致）。
      const colorMap = {
        '股價':      '#3b82f6',
        '週線MA5':   '#10b981',
        '月線MA20':  '#f59e0b',
        '季線MA60':  '#8b5cf6',
        '年線MA240': '#ef4444',
        '成本均價':  '#64748b'
      }
      // 畫線的用自己的顏色；只顯示數值的（K3D2/RSV、EMA12/EMA26、B10-B20）用中性深灰
      for (const [name, , color] of spec.lines) colorMap[name] = color
      for (const [name] of (spec.legendOnly || [])) colorMap[name] = '#334155'
      // 用 index 當 rich key：colorMap 仍含「股價」「月線MA20」等中文鍵，
      // 而 zrender 的 rich style key 只接受 [a-zA-Z0-9_]
      const keyByName = {}, arrowKeyByName = {}
      const richStyles = { n: { fontSize: 12, color: '#475569', lineHeight: 16 } }
      Object.entries(colorMap).forEach(([name, color], i) => {
        const k = 'v' + i
        keyByName[name] = k
        richStyles[k] = {
          fontSize: 12, color, lineHeight: 16, fontWeight: 700, padding: [2, 0, 0, 0]
        }
        const arrow = arrowMap[name]
        if (arrow) {
          const ak = 'a' + i
          arrowKeyByName[name] = ak
          richStyles[ak] = {
            fontSize: 11, color: arrow.color, lineHeight: 16, fontWeight: 700, padding: [2, 0, 0, 3]
          }
        }
      })
      const formatter = name => {
        if (!map[name]) return name
        const arrow = arrowMap[name]
        const valuePart = `{${keyByName[name] || 'n'}|${map[name]}}`
        const arrowPart = arrow ? `{${arrowKeyByName[name]}|${arrow.ch}}` : ''
        return `{n|${name}}\n${valuePart}${arrowPart}`
      }
      const textStyle = { fontSize: 12, color: '#475569', rich: richStyles }
      // 兩組 legend，各自貼著自己的 pane：股價／均線在上圖頂端，子圖指標移到
      // 兩張圖中間（＝KD 子圖正上方）。十項全擠在頂端一列會過密且與股價無關聯。
      // KD 那組用 bottom 定位（不依賴容器總高）：grid[1] 頂端距底部 = bottom 60 + height 135 = 195，
      // legend 兩行約 36px，加上下各 16px 間隙 → bottom 211，落在 211~247，grid[0] 則收在 263。
      return [
        {
          data: cost != null
            ? ['股價', '週線MA5', '月線MA20', '季線MA60', '年線MA240', '成本均價']
            : ['股價', '週線MA5', '月線MA20', '季線MA60', '年線MA240'],
          top: 8,
          itemGap: 30,
          formatter, textStyle
        },
        {
          data: subLegend.map(([name]) => name),
          bottom: 211,
          itemGap: 30,
          formatter, textStyle
        }
      ]
    })(),
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      // 容器總高 500（Task：850px 視窗下彈窗需捲動 63px，把高度預算收緊到不必捲動；
      // 原本 580／225／155 的分配見 git history）。上下 pane 約 177/135（57:43）——
      // 145 原本已是 KD,J 五線模式（K9/D9/J9/K3D2/RSV）貼著驗證過的下限；這次依威廉指標
      // （單線模式）的要求再降 10px 到 135，freed 的高度全數挪給上圖繪圖區（167→177），
      // grid[1] 底部留白(60，x 軸標籤＋dataZoom 空間)不變。KD,J 模式下線條密度已逼近可辨識
      // 下限，若之後還要再縮，需先切回 KD,J 肉眼複查交叉是否仍清楚。
      // bottom 263 = grid[1] 頂端(195) + 16 + KD legend 一列(36) + 16，
      // legend 上下各留 16px 呼吸空間（維持原本數字不動）；只留 6~8px 時它會緊貼上圖底軸，實機看起來很擠。
      { left: 64, right: 96, top: 60, bottom: 263 },
      { left: 64, right: 96, top: 'auto', height: 135, bottom: 60 }
    ],
    dataZoom: [
      // 期間按鈕只調 dataZoom 窗（不 roundtrip）；ez 合成自手動拖曳(zoomPct)優先、否則期間預設，最高/最低標記同窗
      { type: 'inside', xAxisIndex: [0, 1], start: ez.start, end: ez.end },
      { type: 'slider', xAxisIndex: [0, 1], start: ez.start, end: ez.end, height: 20, bottom: 8 }
    ],
    xAxis: [
      { gridIndex: 0, type: 'category', data: dates, boundaryGap: candleMode, axisLabel: { show: false }, axisLine: { onZero: false } },
      { gridIndex: 1, type: 'category', data: dates, boundaryGap: candleMode,
        axisLabel: { rotate: 0, fontSize: 10, margin: 12, hideOverlap: true,
          formatter: xLabelFormatter, interval: xLabelInterval } }
    ],
    yAxis: [
      priceYAxis,
      subYAxis
    ],
    series: [
      { name: '股價', type: candleMode ? 'candlestick' : 'line', xAxisIndex: 0, yAxisIndex: 0, data: candleMode ? candleValues : prices, labelLayout: SHIFT_Y,
        // 當日：稀疏 tick 落在整段分鐘網格上，connectNulls 讓 2 分輪詢 / 5 分 K 之間連成連續線；
        // 末端未來時段的 trailing null 無後續點不會被橋接，故線正確止於最新一筆
        connectNulls: intraday,
        lineStyle: { width: 2, color: '#3b82f6' }, itemStyle: candleMode ? { color: '#dc2626', borderColor: '#dc2626', color0: '#16a34a', borderColor0: '#16a34a' } : { color: '#3b82f6' }, showSymbol: false,
        endLabel: { show: !candleMode, formatter: '{c}', fontSize: 11, color: '#3b82f6', fontWeight: 700 },
        ...(candleMode ? {} : { areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(59,130,246,0.12)' }, { offset: 1, color: 'rgba(59,130,246,0)' }] } },
        }),
        markPoint: {
          symbol: 'circle',
          symbolSize: 9,
          data: markData,
          // 第一行「最高/最低 + 股價」、第二行日期（當日模式為時間 HH:mm）；色塊（紅/綠底白字）置於點外、位置自適應避邊
          label: {
            show: true, color: '#fff', fontSize: 11, fontWeight: 'bold', lineHeight: 15,
            align: 'center', padding: [3, 6], borderRadius: 4, distance: 7,
            formatter: p => `${p.name} ${p.value}\n${p.data.xlabel}`
          }
        }
      },
      { name: '週線MA5', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma5, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#10b981' }, itemStyle: { color: '#10b981' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#10b981' } },
      { name: '月線MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma20, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#f59e0b' } },
      { name: '季線MA60', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma60, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#8b5cf6' }, itemStyle: { color: '#8b5cf6' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#8b5cf6' } },
      { name: '年線MA240', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma240, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#ef4444' }, itemStyle: { color: '#ef4444' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#ef4444' } },
      ...(cost != null ? [{
        name: '成本均價', type: 'line', xAxisIndex: 0, yAxisIndex: 0, labelLayout: SHIFT_Y,
        data: dates.map(() => parseFloat(cost.toFixed(2))),
        lineStyle: { color: '#64748b', type: 'dashed', width: 1.5 },
        itemStyle: { color: '#64748b' }, showSymbol: false,
        endLabel: {
          show: true, formatter: '成本 {c}', fontSize: 11,
          color: priceCurrent != null && priceCurrent >= cost ? '#16a34a' : '#ef4444'
        }
      }] : []),
      // ── 子圖：依指標選單動態產生 ──────────────────────────────
      // 畫線的指標；參考虛線（80/20、70/30、20/80 或 0 軸）掛在第一條上
      ...spec.lines.map(([name, key, color, dashed], idx) => ({
        name, type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: subVals[key] || [],
        lineStyle: { width: 1.5, color, ...(dashed ? { type: 'dashed' } : {}) },
        itemStyle: { color }, showSymbol: false,
        ...(idx === 0 ? {
          markLine: {
            silent: true, data: spec.refs.map(v => ({ yAxis: v })),
            lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 },
            label: { formatter: '{c}', fontSize: 10, color: '#94a3b8' }
          }
        } : {})
      })),
      // MACD 的 OSC：柱狀，正紅負綠（台股慣例）。⚠ 需 use(BarChart)，漏註冊會靜默不畫
      ...(spec.bars || []).map(([name, key]) => ({
        name, type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: subVals[key] || [],
        itemStyle: { color: p => (p.value >= 0 ? '#dc2626' : '#16a34a') },
        barMaxWidth: 6
      })),
      // 只在 legend 顯示數值、不畫線的欄位（K3D2/RSV、EMA12/EMA26、B10-B20）。
      // 空 series 不可省：ECharts LegendView 找不到同名 series 時，該 legend 項目
      // 連同 formatter 產生的數值都不會被畫出來（production build 連 warning 都沒有）。
      ...(spec.legendOnly || []).map(([name]) => ({
        name, type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: [],
        itemStyle: { color: '#334155' }, showSymbol: false
      }))
    ]
  }
})
</script>

<style scoped>
.analysis-loading { display:flex;flex-direction:column;align-items:center;gap:12px;padding:60px 0;color:#64748b;font-size:14px }
.analysis-empty   { text-align:center;padding:60px 0;color:#94a3b8;font-size:14px }
/* 右邊保留的空間要對齊 echarts grid.right (96px)，這樣 period selector / 資料截止 才會
   和 chart 內容（endLabels 落點）的右緣切齊，不會越界到圖外。 */
.analysis-meta    { display:flex;align-items:flex-start;gap:12px;margin-bottom:8px;padding-right:96px }
.chart-controls { display:flex;flex:0 1 auto;align-items:center;margin-left:auto;min-width:0 }
/* max-width/overflow-x 常駐（非只在 <800px media query 內）：合併成一排後，寬度介於
   ~800~1093px 之間時（尚未觸發下方 media query，但已窄於 1100px 上限）单靠 gap 已擠不下，
   讓這排本身可橫向捲動，避免內容溢出 analysis-meta 的 96px 保留區、撞上對話框邊緣。
   ≥1093px（含 1100px 上限）時內容本就塞得下，不會出現捲軸。 */
.chart-control-row { display:flex;align-items:center;gap:5px;white-space:nowrap;max-width:100%;overflow-x:auto;padding-bottom:2px }
.chart-control-group { display:flex;align-items:center;gap:4px;flex:none;white-space:nowrap }
.chart-control-label,.weekly-candle-hint,.chart-zoom-hint { color:#64748b;font-size:12px;white-space:nowrap }
.indicator-select { width:104px;flex:none }
/* 合併兩排後單排要塞下 11 顆按鈕＋下拉＋兩段提示文字，把 small 按鈕預設的左右 padding
   (11px) 收緊，換回排面空間；字級與按鈕/文字內容本身不變。 */
.chart-control-row :deep(.el-button) { padding:5px }
/* 「當日」昨收 / 今日漲跌資訊列（僅當日期間顯示） */
.intraday-quote   { display:flex;align-items:baseline;gap:8px;margin:0 0 4px 2px;font-size:13px;line-height:1.4 }
.intraday-quote .iq-label { color:#64748b }
.intraday-quote .iq-val   { color:#1e293b;font-weight:700;margin-right:8px }
.tabs-wrap        { position: relative; }
.tabs-trailing    {
  position: absolute;
  right: 96px;
  top: 12px;
  color: #1e293b;
  font-size: 13px;
  font-weight: 600;
  z-index: 1;
}
.quote-detail-head { display:flex;align-items:center;justify-content:flex-end;gap:8px;margin:4px 0 12px;color:#64748b;font-size:12px }
.quote-summary { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1px;background:#e2e8f0;border:1px solid #e2e8f0 }
.quote-item { display:flex;justify-content:space-between;padding:10px 12px;background:#fff;font-size:14px }.quote-item span{color:#64748b}.quote-item b{font-variant-numeric:tabular-nums}
.quote-flow { margin:16px 0;color:#475569;font-size:13px;display:flex;gap:14px;flex-wrap:wrap }.quote-flow .inner{color:#16a34a}.quote-flow .outer{color:#dc2626}.flow-bar{display:flex;width:100%;height:8px;background:#e2e8f0;border-radius:4px;overflow:hidden}.flow-bar i{background:#16a34a}.flow-bar em{background:#dc2626}
.quote-flow-empty { margin:16px 0;color:#94a3b8;font-size:13px }
.orderbook { border:1px solid #e2e8f0;font-size:13px }.order-head,.order-row { display:grid;grid-template-columns:repeat(4,1fr);text-align:right;gap:8px;padding:8px 10px }.order-head{background:#f8fafc;color:#64748b}.order-row{border-top:1px solid #f1f5f9}.volume{position:relative;overflow:hidden;isolation:isolate;font-variant-numeric:tabular-nums}.volume i{position:absolute;inset:2px auto 2px 0;background:#dbeafe;z-index:0;pointer-events:none}.volume>span{position:relative;z-index:1}.order-total{display:flex;justify-content:space-between;padding:9px 10px;background:#f8fafc;font-weight:600}
@media (max-width:800px){.analysis-meta{flex-wrap:wrap;padding-right:0}.chart-controls{width:100%;align-items:flex-start;margin-left:0}.chart-control-row{max-width:100%;overflow-x:auto;padding-bottom:2px}.quote-summary{grid-template-columns:1fr}.quote-detail-head{justify-content:flex-start;flex-wrap:wrap}.order-head,.order-row{grid-template-columns:repeat(4,minmax(58px,1fr));overflow:auto}.tabs-trailing{display:none}}
</style>

<style>
/* 年度小計列（el-table row-class-name 注入後不在 scoped 範圍內，改用全域 style） */
.el-table .dividend-year-summary > td {
  background: #f1f5f9 !important;
  font-weight: 700;
  color: #0f172a;
}

/* 對話框高度上限＋內部捲動：與 top="calc(15vh - 25px)"、Element Plus 預設 dialog 底部
   margin 50px 同一份預算算出可用高度，避免矮視窗下內容（尤其走勢圖底部的 KD/MACD 等子圖
   x 軸）被裁掉看不到。flex-column 讓 header 保持原生尺寸、不參與捲動（天然固定，不必
   position:sticky），只有 body 在超出上限時捲動。
   .el-dialog__body 的 min-height:0 是必要項：flex 子元素預設 min-height:auto 會被內容
   撐開到原始高度，沒有這行 overflow-y:auto 會形同虛設、根本不會出現捲軸。
   放在全域 style（而非 scoped 的 :deep()）：實測 :deep(.stock-analysis-dialog) 不生效——
   el-dialog 的實際內容（.el-dialog／.el-dialog__header／.el-dialog__body）是 ElDialog 經
   Teleport＋ElOverlay／ElFocusTrap／ElDialogContent 多層元件轉手才渲染出來，scoped 的
   data-v-* attribute 沒有一路傳到那麼深（class 有傳到是因為 Element Plus 自己手動轉發
   $attrs，屬性含義不同），與下面 dividend-year-summary 是同一類問題，故同樣改走全域 style。
   用獨立 class .stock-analysis-dialog（而非裸 .el-dialog）限定只影響這個對話框，
   不外溢到全站其它 el-dialog。 */
.stock-analysis-dialog {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - (15vh - 25px) - 50px - 20px);
}
.stock-analysis-dialog .el-dialog__header { flex: none }
.stock-analysis-dialog .el-dialog__body { flex: 1; overflow-y: auto; min-height: 0 }
</style>
