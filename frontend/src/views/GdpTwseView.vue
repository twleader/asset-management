<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px">
          <div style="display:flex;align-items:baseline;gap:14px;flex-wrap:wrap">
            <span class="section-title">{{ cardTitle }}</span>
            <span v-if="isIntraday && intradayPrevClose != null"
                  style="display:flex;align-items:baseline;gap:12px;font-size:13px">
              <span style="color:#64748b">昨收 {{ fmtPoint(intradayPrevClose) }}</span>
              <span :style="{ color: priceColor(intradayChange), fontWeight: 600 }">{{ fmtChange(intradayChange) }}</span>
              <span :style="{ color: priceColor(intradayChange), fontWeight: 600 }">{{ fmtPct(intradayChangePct) }}</span>
            </span>
          </div>
          <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
            <el-select v-model="market" size="small" style="width:150px" @change="onMarketChange">
              <el-option v-for="m in MARKETS" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
            <el-radio-group v-model="dailyRange" size="small">
              <el-radio-button label="d">當日</el-radio-button>
              <el-radio-button label="1m">1 個月</el-radio-button>
              <el-radio-button label="3m">3 個月</el-radio-button>
              <el-radio-button label="6m">半年</el-radio-button>
              <el-radio-button label="1y">1 年</el-radio-button>
              <el-radio-button label="2y">2 年</el-radio-button>
              <el-radio-button label="5y">5 年</el-radio-button>
              <el-radio-button label="10y">10 年</el-radio-button>
            </el-radio-group>
            <el-button size="small" @click="onRefreshDaily" :loading="dailyRefreshing">
              回補日線（10 年）
            </el-button>
            <!-- 分時（當日）為 transient 資料、非日線 OHLC，與匯出內容不同源，故該模式停用 -->
            <el-tooltip :disabled="!isIntraday" content="「當日」為分時資料，請切換到日線區間再匯出" placement="top">
              <span>
                <el-button size="small" type="primary" :disabled="isIntraday" @click="openExport">
                  匯出 Excel
                </el-button>
              </span>
            </el-tooltip>
          </div>
        </div>
      </template>
      <v-chart v-if="hasDailyData" ref="dailyChartRef" :option="dailyChartOption" style="height:480px" autoresize @datazoom="onDailyZoom" />
      <el-empty v-else :description="emptyDesc" />
    </el-card>

    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">台日韓人均 GDP 比較（近 40 年）</span>
          <el-button size="small" @click="onRefresh" :loading="refreshing">
            回補 GDP（IMF）
          </el-button>
        </div>
      </template>
      <v-chart v-if="hasData" :option="compareChartOption" style="height:520px" autoresize />
      <el-empty v-else description="尚無資料" />
    </el-card>

    <!-- 排程自動匯出設定（Requirement 43 / Task 209） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :loading="runningNow" @click="handleRunNow">立即匯出到目錄</el-button>
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
        <el-form-item label="匯出指數">
          <el-select v-model="schedule.market" style="width:150px">
            <el-option v-for="m in MARKETS" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="匯出範圍">
          <el-select v-model="schedule.rangeMonths" style="width:140px">
            <el-option v-for="o in rangeMonthOptions" :key="String(o.value)"
              :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="輸出資料夾">
          <el-input v-model="schedule.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
            <template #append>
              <el-button @click="openDirPicker">選擇</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <div class="schedule-hint">
        以主機家目錄 <code>{{ schedule.baseDir || '/home/steven' }}</code> 為根（對映主機
        <code>/Users/steven</code>）。按上方「選擇」開啟檔案總管式選擇器挑選子資料夾；例如選 <code>input</code> →
        主機 <code>/Users/steven/input</code>。每日於指定時間匯出所選指數的日線為
        <code>{{ scheduleMarketLabel }}_{使用者ID}_YYYYMMDD.xlsx</code>（欄位為日期／開盤／最高／最低／收盤，
        內容同上方「匯出 Excel」）。匯出範圍以<b>執行當日往前推</b>計算，故每日產出會隨時間滾動。
      </div>
      <div v-if="schedule.lastRunAt || schedule.lastRunStatus" class="schedule-status">
        上次執行：{{ schedule.lastRunAt || '—' }}　{{ schedule.lastRunStatus || '' }}
      </div>
    </el-card>

    <!-- 輸出資料夾選擇器 -->
    <el-dialog v-model="dirPicker.visible" title="選擇輸出資料夾" width="560px">
      <div class="dir-picker-path">
        目前選擇：<code>{{ dirPicker.baseDir || '/home/steven' }}{{ dirPicker.picked ? '/' + dirPicker.picked : '' }}{{ dirPicker.newSub.trim() ? '/' + dirPicker.newSub.trim() : '' }}</code>
      </div>
      <el-tree
        :key="dirPicker.treeKey"
        lazy
        :load="loadDirNode"
        :props="dirTreeProps"
        node-key="key"
        highlight-current
        :expand-on-click-node="false"
        :default-expanded-keys="['__root__']"
        class="dir-tree"
        @node-click="onDirNodeClick" />
      <div class="dir-new-sub">
        <span class="dns-label">新增子資料夾</span>
        <el-input v-model="dirPicker.newSub" placeholder="（選填）在所選資料夾下新增，寫檔時自動建立"
          style="width:340px" clearable />
      </div>
      <template #footer>
        <el-button @click="dirPicker.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmDirPick">確定</el-button>
      </template>
    </el-dialog>

    <!-- 匯出對話框：指定時間區間，存檔位置由瀏覽器另存對話框決定 -->
    <el-dialog v-model="exportDialog.visible" :title="`匯出${marketLabel}日線`" width="480px">
      <el-form label-width="90px">
        <el-form-item label="時間區間">
          <el-date-picker
            v-model="exportDialog.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="起始日"
            end-placeholder="結束日"
            :clearable="false"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="輸出內容">
          <div class="dialog-note">
            單一 Excel 檔、一張工作表（{{ marketLabel }}），欄位為
            <b>日期／開盤／最高／最低／收盤</b>，依日期遞增；當日該欄無資料則留空。
          </div>
        </el-form-item>
        <el-form-item label="存檔位置">
          <div class="dialog-note">
            <template v-if="canPickDirectory">
              按下匯出後會開啟系統「另存新檔」對話框，可自行選擇資料夾與檔名。
            </template>
            <template v-else>
              目前瀏覽器不支援選擇資料夾，檔案將存到瀏覽器預設下載資料夾。
            </template>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">匯出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DataZoomComponent, MarkPointComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi, apiErrorMessage } from '@/api'
import { ElMessage } from 'element-plus'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent, MarkPointComponent])

const years = ref([])
const gdp = ref([])
const japanGdp = ref([])
const koreaGdp = ref([])
const twGrowth = ref([])
const jpGrowth = ref([])
const krGrowth = ref([])
const refreshing = ref(false)

// 指數日線（近 10 年）— 可切換台股大盤、美股四大指數與海外主要指數（英德韓日）
const MARKETS = [
  { value: 'TWSE',  label: '台股大盤' },
  { value: 'DJI',   label: '道瓊工業' },
  { value: 'SPX',   label: '標普 500' },
  { value: 'IXIC',  label: '那斯達克綜合' },
  { value: 'SOX',   label: '費城半導體' },
  { value: 'FTSE',  label: '英國富時 100' },
  { value: 'DAX',   label: '德國 DAX' },
  { value: 'KOSPI', label: '韓國 KOSPI' },
  { value: 'N225',  label: '日經 225' }
]
const market = ref('TWSE')
const marketLabel = computed(() => MARKETS.find(m => m.value === market.value)?.label ?? '台股大盤')
const dailyDates = ref([])
const dailyCloses = ref([])
const dailyMa20 = ref([])
const dailyMa60 = ref([])
const dailyMa240 = ref([])
const dailyRange = ref('1y')
const dailyRefreshing = ref(false)
const dailyChartRef = ref(null)
// 使用者手動拖曳縮放後的 [start,end]%；null＝跟隨區間按鈕預設（最高/最低標記只在可視區間內計算）
const dailyZoomPct = ref(null)

// 「當日」分時（盤中即時 / 盤後最後交易日）
const intradayTimes = ref([])
const intradayCloses = ref([])
const intradayDate = ref(null)
const intradayPrevClose = ref(null)   // 昨日收盤（BFF 由日線表算，與觀察清單同一事實來源）
const intradayChange = ref(null)      // 漲跌＝最新點位 − 昨收
const intradayChangePct = ref(null)   // 漲跌%
const isIntraday = computed(() => dailyRange.value === 'd')

const hasData = computed(() => years.value.length > 0)
const hasDailyData = computed(() =>
  isIntraday.value ? intradayTimes.value.length > 0 : dailyDates.value.length > 0)

const cardTitle = computed(() =>
  isIntraday.value
    ? `${marketLabel.value}當日走勢${intradayDate.value ? `（${intradayDate.value}）` : ''}`
    : `${marketLabel.value}每日收盤（近 10 年，含月線/季線/年線）`)
const emptyDesc = computed(() =>
  isIntraday.value
    ? `尚無${marketLabel.value}當日分時資料`
    : `尚無${marketLabel.value}日線資料，請先按「回補日線（10 年）」`)

function num(v) { return v == null ? null : Number(v) }
function lastOf(arr) { for (let i = arr.length - 1; i >= 0; i--) { if (arr[i] != null) return arr[i] } return null }

// 在可視索引區間 [lo, hi] 內找收盤「最高 / 最低」點，回傳 ECharts markPoint data（紅最高、綠最低，符合紅漲綠跌）
// coord 以類別字串（labels[i]）定位，避免 dataZoom filterMode:'filter' 重新索引後絕對索引對不準
// xlabel = labels[i]＝日線模式為日期、當日模式為 HH:mm 時間（標籤第二行直接顯示，不必分支）
function maxMinMarkPoints(data, labels, lo, hi) {
  let maxI = -1, minI = -1, maxV = -Infinity, minV = Infinity
  for (let i = lo; i <= hi; i++) {
    const v = data[i]
    if (v == null) continue
    if (v > maxV) { maxV = v; maxI = i }
    if (v < minV) { minV = v; minI = i }
  }
  if (maxI < 0) return []
  const fmt = v => Math.round(v).toLocaleString()
  const span = Math.max(1, hi - lo)
  // 標籤色塊位置依該點在可視窗的水平位置避邊：靠右→放左、靠左→放右、其餘上下（最高在下、最低在上，避免撞到頂部 legend / 底部縮放軸）
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

// 當日昨收/漲跌顯示（指數為點位、不帶 $；紅漲綠跌比照觀察清單 priceColor）
function fmtPoint(v) { return v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtChange(v) {
  if (v == null) return '—'
  const n = Number(v), sign = n > 0 ? '▲' : n < 0 ? '▼' : ''
  return `${sign}${Math.abs(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function fmtPct(v) { if (v == null) return '—'; const n = Number(v); return `${n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function priceColor(v) { if (v == null) return '#475569'; const n = Number(v); return n > 0 ? '#dc2626' : n < 0 ? '#16a34a' : '#475569' }

async function fetchData() {
  try {
    const res = await bffApi.gdpTwse.get(40)
    years.value = res.years ?? []
    gdp.value = (res.gdpPerCapitaUsd ?? []).map(num)
    japanGdp.value = (res.japanGdpPerCapitaUsd ?? []).map(num)
    koreaGdp.value = (res.koreaGdpPerCapitaUsd ?? []).map(num)
    twGrowth.value = (res.taiwanGdpGrowthRate ?? []).map(num)
    jpGrowth.value = (res.japanGdpGrowthRate ?? []).map(num)
    krGrowth.value = (res.koreaGdpGrowthRate ?? []).map(num)
  } catch {}
}

async function fetchDailyData() {
  try {
    const res = await bffApi.gdpTwse.getIndexDaily(market.value, 10)
    dailyDates.value = res.dates ?? []
    dailyCloses.value = (res.closes ?? []).map(num)
    dailyMa20.value = (res.ma20 ?? []).map(num)
    dailyMa60.value = (res.ma60 ?? []).map(num)
    dailyMa240.value = (res.ma240 ?? []).map(num)
  } catch {}
}

async function fetchIntraday() {
  try {
    const res = await bffApi.gdpTwse.getIndexIntraday(market.value)
    intradayTimes.value = res.times ?? []
    intradayCloses.value = (res.closes ?? []).map(num)
    intradayDate.value = res.tradingDate ?? null
    intradayPrevClose.value = num(res.previousClose)
    intradayChange.value = num(res.change)
    intradayChangePct.value = num(res.changePercent)
  } catch {}
}

function onMarketChange() {
  // 日線（含 MA 水平線值）必抓；當日模式同時重抓分時
  dailyZoomPct.value = null
  fetchDailyData()
  if (isIntraday.value) fetchIntraday()
}

// 切換區間：清掉手動縮放，最高/最低標記回到該區間預設窗；切到「當日」即時抓分時（每次切入都重抓以反映最新）
watch(dailyRange, v => { dailyZoomPct.value = null; if (v === 'd') fetchIntraday() })

onMounted(() => {
  // 排程設定與兩張圖各自獨立，並行載入；設定讀取失敗不影響圖表
  Promise.allSettled([fetchData(), fetchDailyData(), loadSchedule()])
})

// ===== Excel 匯出（Requirement 43 / Task 209）=====

const exporting = ref(false)
const exportDialog = reactive({ visible: false, range: [] })

// File System Access API：可讓使用者自選存檔目錄；Safari／舊版瀏覽器沒有，退回一般下載
const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window

function openExport() {
  // 預設帶入目前圖表可視區間（含手動拖曳後的縮放結果），與畫面所見一致
  const dates = dailyDates.value
  if (!dates.length) {
    ElMessage.warning('尚無日線資料，請先按「回補日線（10 年）」')
    return
  }
  const ez = effectiveDailyZoom.value
  const lo = Math.max(0, Math.floor((ez.start / 100) * (dates.length - 1)))
  const hi = Math.min(dates.length - 1, Math.ceil((ez.end / 100) * (dates.length - 1)))
  exportDialog.range = [dates[lo], dates[hi]]
  exportDialog.visible = true
}

async function onExport() {
  const [start, end] = exportDialog.range ?? []
  if (!start || !end) {
    ElMessage.warning('請選擇匯出時間區間')
    return
  }
  exporting.value = true
  try {
    const blob = await bffApi.gdpTwse.exportExcel(market.value, start, end)
    const filename = `${marketLabel.value}_${start.replaceAll('-', '')}_${end.replaceAll('-', '')}.xlsx`
    const saved = await saveBlob(blob, filename)
    if (saved) {
      ElMessage.success('匯出完成')
      exportDialog.visible = false
    }
  } catch {
  } finally {
    exporting.value = false
  }
}

/**
 * 優先開啟系統「另存新檔」對話框讓使用者選目錄；不支援時退回一般下載。
 * 回傳 false 代表使用者主動取消（不顯示成功訊息）。
 */
async function saveBlob(blob, filename) {
  if (canPickDirectory) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{
          description: 'Excel 活頁簿',
          accept: { 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'] }
        }]
      })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return true
    } catch (e) {
      if (e?.name === 'AbortError') return false   // 使用者按取消
      // 其他錯誤（權限、沙箱等）退回一般下載，功能不中斷
    }
  }
  downloadBlob(blob, filename)
  return true
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ===== 排程自動匯出 =====

const schedule = reactive({
  enabled: false, runHour: 8, runMinute: 0, market: 'TWSE', outputSubpath: 'input',
  rangeMonths: 120, lastRunAt: null, lastRunStatus: null, baseDir: ''
})
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
const runningNow = ref(false)

// 排程卡的指數可與圖表目前選取不同（使用者可能在看美股、但排程留存台股），故獨立取 label
const scheduleMarketLabel = computed(() =>
  MARKETS.find(m => m.value === schedule.market)?.label ?? '台股大盤')

// 全部十年以 120（月）表示而非 null：Element Plus 的 el-select 預設把 null 當成 empty value，
// 綁 null 會顯示灰色 placeholder 而非「全部十年」，使用者無法分辨「已選」與「尚未選擇」（同 Task 203／204）。
const ALL_TEN_YEARS_MONTHS = 120
const rangeMonthOptions = [
  { label: '近 1 個月', value: 1 },
  { label: '近 3 個月', value: 3 },
  { label: '近 6 個月', value: 6 },
  { label: '近 1 年', value: 12 },
  { label: '近 3 年', value: 36 },
  { label: '近 5 年', value: 60 },
  { label: '全部十年', value: ALL_TEN_YEARS_MONTHS }
]

// 輸出資料夾選擇器（檔案總管式樹狀）
const dirPicker = reactive({ visible: false, baseDir: '', picked: '', newSub: '', treeKey: 0 })
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

async function loadSchedule() {
  const s = await bffApi.gdpTwse.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.runHour = s.runHour ?? 8
  schedule.runMinute = s.runMinute ?? 0
  schedule.market = s.market ?? 'TWSE'
  schedule.outputSubpath = s.outputSubpath ?? 'input'
  // 後端 null（未設定過的舊列）＝全部十年，映射成 120 讓下拉正確顯示
  schedule.rangeMonths = s.rangeMonths ?? ALL_TEN_YEARS_MONTHS
  schedule.lastRunAt = s.lastRunAt ?? null
  schedule.lastRunStatus = s.lastRunStatus ?? null
  schedule.baseDir = s.baseDir ?? ''
  scheduleTime.value = `${String(schedule.runHour).padStart(2, '0')}:${String(schedule.runMinute).padStart(2, '0')}`
}

async function saveSchedule() {
  savingSchedule.value = true
  try {
    const [h, m] = (scheduleTime.value || '08:00').split(':').map(Number)
    const s = await bffApi.gdpTwse.updateExportSchedule({
      enabled: schedule.enabled,
      runHour: h,
      runMinute: m,
      market: schedule.market,
      outputSubpath: (schedule.outputSubpath || 'input').trim(),
      rangeMonths: schedule.rangeMonths
    })
    schedule.runHour = s.runHour ?? h
    schedule.runMinute = s.runMinute ?? m
    schedule.market = s.market ?? schedule.market
    schedule.outputSubpath = s.outputSubpath ?? schedule.outputSubpath
    schedule.rangeMonths = s.rangeMonths ?? ALL_TEN_YEARS_MONTHS
    schedule.baseDir = s.baseDir ?? schedule.baseDir
    ElMessage.success('排程設定已儲存')
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '儲存失敗，請稍後再試'))
  } finally {
    savingSchedule.value = false
  }
}

async function handleRunNow() {
  runningNow.value = true
  try {
    const r = await bffApi.gdpTwse.runExportNow()
    ElMessage.success(`已匯出到：${r.path}`)
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '立即匯出失敗，請確認目錄與權限'))
  } finally {
    runningNow.value = false
  }
  loadSchedule().catch(() => {}) // 刷新上次執行資訊，失敗不影響匯出結果
}

function openDirPicker() {
  dirPicker.picked = schedule.outputSubpath || ''
  dirPicker.newSub = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  try {
    if (node.level === 0) {
      const res = await bffApi.gdpTwse.browseExportDir('')
      dirPicker.baseDir = res.baseDir || ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await bffApi.gdpTwse.browseExportDir(node.data.path || '')
    resolve((res.directories || []).map(d => ({ name: d.name, path: d.path, key: d.path, leaf: false })))
  } catch (e) {
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  schedule.outputSubpath = p
  dirPicker.visible = false
}

async function onRefresh() {
  refreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refresh(40)
    const g = r.gdp?.upserted ?? 0
    const j = r.japan?.upserted ?? 0
    const k = r.korea?.upserted ?? 0
    ElMessage.success(`GDP 回補完成：台灣 ${g} 筆、日本 ${j} 筆、韓國 ${k} 筆`)
    await fetchData()
  } catch {} finally {
    refreshing.value = false
  }
}

async function onRefreshDaily() {
  dailyRefreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refreshIndexDaily(market.value, 10)
    ElMessage.success(`${marketLabel.value}日線回補完成：${r.upserted ?? 0} 筆（${r.from} ~ ${r.to}）`)
    await fetchDailyData()
  } catch {} finally {
    dailyRefreshing.value = false
  }
}

// 區間 → dataZoom start/end（百分比，以陣列末端對齊）
const RANGE_TRADING_DAYS = {
  '1m': 21, '3m': 63, '6m': 125, '1y': 250, '2y': 500, '5y': 1250, '10y': 2500
}
const dailyZoomRange = computed(() => {
  const total = dailyDates.value.length
  if (total === 0) return { start: 0, end: 100 }
  const want = RANGE_TRADING_DAYS[dailyRange.value] ?? 250
  const startIdx = Math.max(0, total - want)
  return { start: (startIdx / total) * 100, end: 100 }
})

// 目前實際可視窗（手動拖曳優先，否則跟隨區間按鈕；當日模式恆為整段）— 最高/最低標記依此計算
const effectiveDailyZoom = computed(() =>
  isIntraday.value ? { start: 0, end: 100 } : (dailyZoomPct.value ?? dailyZoomRange.value))

// 使用者拖曳 dataZoom 後，讀回圖表目前 start/end%，讓最高/最低標記跟著可視區間更新
function onDailyZoom() {
  const opt = dailyChartRef.value?.getOption?.()
  const dz = opt?.dataZoom?.[0]
  if (!dz || dz.start == null || dz.end == null) return
  const cur = dailyZoomPct.value
  if (cur && Math.abs(cur.start - dz.start) < 1e-6 && Math.abs(cur.end - dz.end) < 1e-6) return
  dailyZoomPct.value = { start: dz.start, end: dz.end }
}

const dailyChartOption = computed(() => {
  const intraday = isIntraday.value
  // 當日模式：x 軸為分時 HH:mm、收盤＝分時 closes、月/季/年線改畫水平參考線（取日線最新 MA 值，同口徑）
  const xData = intraday ? intradayTimes.value : dailyDates.value
  const closeData = intraday ? intradayCloses.value : dailyCloses.value
  const ma20Data = intraday ? xData.map(() => lastOf(dailyMa20.value)) : dailyMa20.value
  const ma60Data = intraday ? xData.map(() => lastOf(dailyMa60.value)) : dailyMa60.value
  const ma240Data = intraday ? xData.map(() => lastOf(dailyMa240.value)) : dailyMa240.value
  const ez = effectiveDailyZoom.value
  const dataZoom = intraday
    ? [{ type: 'inside', start: 0, end: 100 }, { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }]
    : [
        { type: 'inside', start: ez.start, end: ez.end },
        { type: 'slider', start: ez.start, end: ez.end, height: 20, bottom: 10 }
      ]
  // 最高 / 最低點：只在目前可視區間內找（資料為完整 10 年、區間按鈕只調縮放，不可用 ECharts 原生 max/min）
  let markData = []
  const totalPts = closeData.length
  if (totalPts > 0) {
    const loIdx = Math.max(0, Math.floor((ez.start / 100) * (totalPts - 1)))
    const hiIdx = Math.min(totalPts - 1, Math.ceil((ez.end / 100) * (totalPts - 1)))
    markData = maxMinMarkPoints(closeData, xData, loIdx, hiIdx)
  }
  // 當日模式 Y 軸鎖定「當日價格區間」(+10% padding)，避免被遠離當日價位的均線水平線撐平走勢；
  // 日線模式維持 scale:true。均線水平線落在區間外時由 series clip 自動裁切，數值仍保留在 legend。
  let yAxis = { type: 'value', name: '收盤點位', scale: true, axisLabel: { formatter: v => v.toLocaleString() } }
  if (intraday) {
    const vals = closeData.filter(v => v != null)
    if (vals.length) {
      const lo = Math.min(...vals), hi = Math.max(...vals)
      const pad = (hi - lo) * 0.1 || hi * 0.001 || 1
      yAxis = {
        type: 'value', name: '收盤點位',
        min: lo - pad, max: hi + pad,
        axisLabel: { formatter: v => v.toLocaleString() }
      }
    }
  }
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        if (!params || params.length === 0) return ''
        let s = `<strong>${params[0].axisValue}</strong><br/>`
        params.forEach(p => {
          const v = p.value
          const txt = v == null ? '-' : Number(v).toLocaleString(undefined, {
            minimumFractionDigits: 2, maximumFractionDigits: 2
          })
          s += `${p.marker}${p.seriesName}: ${txt}<br/>`
        })
        return s
      }
    },
    legend: {
      data: ['收盤', '月線 (MA20)', '季線 (MA60)', '年線 (MA240)'],
      top: 0,
      itemGap: 30,
      textStyle: { lineHeight: 18 },
      formatter: name => {
        const map = {
          '收盤': closeData,
          '月線 (MA20)': ma20Data,
          '季線 (MA60)': ma60Data,
          '年線 (MA240)': ma240Data
        }
        const arr = map[name] ?? []
        let v = null
        for (let i = arr.length - 1; i >= 0; i--) {
          if (arr[i] != null) { v = arr[i]; break }
        }
        if (v == null) return `${name}\n-`
        return `${name}\n${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
      }
    },
    grid: { left: 70, right: 30, top: 70, bottom: 60 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { fontSize: 11 }
    },
    yAxis,
    dataZoom,
    series: [
      {
        name: '收盤',
        type: 'line',
        data: closeData,
        showSymbol: false,
        sampling: 'lttb',
        lineStyle: { width: 1.5, color: '#1f2937' },
        itemStyle: { color: '#1f2937' },
        markPoint: {
          symbol: 'circle',
          symbolSize: 9,
          data: markData,
          // 第一行「最高/最低 + 點位」、第二行日期（當日模式為時間）；色塊（紅/綠底白字）置於 pin 外，位置由各點自適應避邊
          label: {
            show: true,
            color: '#fff',
            fontSize: 11,
            fontWeight: 'bold',
            lineHeight: 15,
            align: 'center',
            padding: [3, 6],
            borderRadius: 4,
            distance: 7,
            formatter: p => `${p.name} ${p.value}\n${p.data.xlabel}`
          }
        }
      },
      {
        name: '月線 (MA20)',
        type: 'line',
        data: ma20Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#f59e0b', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#f59e0b' }
      },
      {
        name: '季線 (MA60)',
        type: 'line',
        data: ma60Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#10b981', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#10b981' }
      },
      {
        name: '年線 (MA240)',
        type: 'line',
        data: ma240Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#3b82f6', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#3b82f6' }
      }
    ]
  }
})

// 成長率改用 IMF NGDP_RPCH（實質 GDP 成長率，twGrowth），不再以人均 GDP（USD）相減推算
// ——USD 相減會被匯率波動扭曲（如 2022 台幣貶值會被算成負成長，但實質仍為 +2.7%）

const compareChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: params => {
      const year = params[0]?.axisValue
      let s = `<strong>${year}</strong><br/>`
      params.forEach(p => {
        const v = p.value
        const isGrowth = p.seriesName.includes('成長率')
        const txt = v == null ? '-'
          : isGrowth ? `${Number(v).toFixed(2)}%`
          : `US$ ${Number(v).toLocaleString()}`
        s += `${p.marker}${p.seriesName}: ${txt}<br/>`
      })
      return s
    }
  },
  legend: { data: ['台灣 GDP', '日本 GDP', '韓國 GDP', '台灣成長率', '日本成長率', '韓國成長率'], top: 0 },
  grid: { left: 70, right: 70, top: 50, bottom: 60 },
  xAxis: {
    type: 'category',
    data: years.value,
    axisLabel: { rotate: 30, fontSize: 11 }
  },
  yAxis: [
    {
      type: 'value',
      name: '人均 GDP (USD)',
      position: 'left',
      axisLine: { show: true, lineStyle: { color: '#3b82f6' } },
      axisLabel: { formatter: v => v.toLocaleString() }
    },
    {
      type: 'value',
      name: '年增率 (%)',
      position: 'right',
      axisLine: { show: true, lineStyle: { color: '#64748b' } },
      axisLabel: { formatter: v => `${v}%` }
    }
  ],
  dataZoom: [
    { type: 'inside', start: 0, end: 100 },
    { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
  ],
  series: [
    {
      name: '台灣成長率',
      type: 'bar',
      yAxisIndex: 1,
      data: twGrowth.value,
      itemStyle: { color: 'rgba(59,130,246,0.55)' },
      barGap: 0
    },
    {
      name: '日本成長率',
      type: 'bar',
      yAxisIndex: 1,
      data: jpGrowth.value,
      itemStyle: { color: 'rgba(34,197,94,0.5)' }
    },
    {
      name: '韓國成長率',
      type: 'bar',
      yAxisIndex: 1,
      data: krGrowth.value,
      itemStyle: { color: 'rgba(239,68,68,0.55)' }
    },
    {
      name: '台灣 GDP',
      type: 'line',
      yAxisIndex: 0,
      data: gdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5, color: '#1d4ed8' },
      itemStyle: { color: '#1d4ed8' },
      z: 5
    },
    {
      name: '日本 GDP',
      type: 'line',
      yAxisIndex: 0,
      data: japanGdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5, color: '#15803d' },
      itemStyle: { color: '#15803d' },
      z: 5
    },
    {
      name: '韓國 GDP',
      type: 'line',
      yAxisIndex: 0,
      data: koreaGdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5, color: '#b91c1c' },
      itemStyle: { color: '#b91c1c' },
      z: 5
    }
  ]
}))
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.dialog-note { font-size: 12px; color: #64748b; line-height: 1.6; }
.schedule-form { margin-bottom: 4px; }
.schedule-hint { font-size: 12px; color: #94a3b8; line-height: 1.6; }
.schedule-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.schedule-status { margin-top: 8px; font-size: 12px; color: #64748b; }
.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
</style>
