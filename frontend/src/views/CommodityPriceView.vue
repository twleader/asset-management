<template>
  <div v-loading="loading">
    <!-- KPI Cards：三標的最新價與對前一交易日漲跌 -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="6" v-for="c in COMMODITIES" :key="c.code">
        <el-card class="kpi-card">
          <div class="kpi-label">{{ c.label }}</div>
          <div class="kpi-value">{{ kpiValue(c.code) }}</div>
          <!-- 即時報價存在時優先顯示（Requirement 77 / Task 337）；不存在時完全維持現行歷史收盤顯示 -->
          <div class="kpi-sub" v-if="liveQuotes[c.code]">
            <span :style="{ color: liveChangeColor(c.code) }">{{ liveChangeText(c.code) }}</span>
            ｜{{ liveTimeLabel(c.code) }}
          </div>
          <div class="kpi-sub" v-else-if="latest[c.code]">
            <span :style="{ color: latest[c.code].change >= 0 ? '#dc2626' : '#16a34a' }">
              {{ latest[c.code].change >= 0 ? '▲' : '▼' }}
              {{ Math.abs(latest[c.code].change).toFixed(2) }}
              ({{ latest[c.code].changePct.toFixed(2) }}%)
            </span>
            ｜{{ latest[c.code].date }}
          </div>
          <div class="kpi-sub" v-else>查無資料</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">資料筆數</div>
          <div class="kpi-value">{{ totalRows.toLocaleString() }}</div>
          <div class="kpi-sub" v-if="dateSpan">{{ dateSpan }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Main Chart -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">國際油價／金價走勢（美元計價）</span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button-group>
              <el-button size="small" v-for="r in rangeOptions" :key="r.key"
                :type="selectedRange === r.key ? 'primary' : ''"
                @click="selectedRange = r.key">{{ r.label }}</el-button>
            </el-button-group>
            <el-button size="small" @click="onRefresh" :loading="refreshing">更新資料</el-button>
            <el-button size="small" type="primary" @click="openExport">匯出 Excel</el-button>
          </div>
        </div>
      </template>
      <v-chart :option="mainChartOption" style="height:460px" autoresize />
      <div class="chart-note">
        左軸為原油（USD/桶）、右軸為黃金（USD/盎司）——兩者量級差距大，同軸會使油價曲線貼底。
        點選圖例可單獨顯示某一序列。
      </div>
    </el-card>

    <!-- 區間統計 -->
    <el-card>
      <template #header><span class="section-title">區間統計（{{ currentRangeLabel }}）</span></template>
      <el-table :data="statsRows" size="small" border>
        <el-table-column prop="label" label="標的" width="200" />
        <el-table-column prop="latest" label="最新" align="right" />
        <el-table-column prop="max" label="最高" align="right" />
        <el-table-column prop="min" label="最低" align="right" />
        <el-table-column prop="avg" label="平均" align="right" />
        <el-table-column prop="changePct" label="區間漲跌幅" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.rawChangePct >= 0 ? '#dc2626' : '#16a34a' }">
              {{ row.changePct }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 排程自動匯出設定（Requirement 41 / Task 203） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :loading="runningNow" :disabled="savingSchedule" @click="handleRunNow">立即匯出到目錄</el-button>
            <el-button size="small" type="primary" :disabled="savingSchedule || runningNow" @click="openScheduleDialog">編輯設定</el-button>
          </div>
        </div>
      </template>
      <div class="schedule-status">已儲存：{{ schedule.enabled ? '啟用' : '停用' }}；{{ scheduleTimes.map(t => t.value + (t.enabled ? '' : '（停用）')).join('、') }}；{{ schedule.outputSubpath || '家目錄根' }}</div>
      <el-dialog v-model="scheduleDialog.visible" title="編輯排程匯出設定" width="680px" :close-on-click-modal="!savingSchedule" :close-on-press-escape="!savingSchedule" :show-close="!savingSchedule" :before-close="closeScheduleDialog">
      <el-form v-if="scheduleDialog.draft" :disabled="savingSchedule" :inline="true" label-width="100px" class="schedule-form">
        <el-form-item label="啟用每日排程">
          <el-switch v-model="scheduleDialog.draft.enabled" />
        </el-form-item>
        <el-form-item label="每日執行時間">
          <div style="display:flex; flex-direction:column; gap:6px">
            <div v-for="item in scheduleDialog.times" :key="item.key" style="display:flex; gap:8px; align-items:center">
              <el-time-picker v-model="item.value" format="HH:mm" value-format="HH:mm" placeholder="時:分" style="width:130px" />
              <el-switch v-model="item.enabled" active-text="啟用" inactive-text="停用" />
              <el-button text type="danger" :disabled="savingSchedule || (scheduleDialog.times.length === 1)" @click="removeScheduleTime(item.key)">移除</el-button>
              <small v-if="item.lastRunAt" style="color:var(--el-text-color-secondary)">{{ item.lastRunAt }} {{ item.lastRunStatus || '' }}</small>
            </div>
            <el-button :disabled="savingSchedule" text type="primary" style="align-self:flex-start" @click="addScheduleTime">＋ 新增時間</el-button>
          </div>
        </el-form-item>
        <el-form-item label="匯出範圍">
          <el-select v-model="scheduleDialog.draft.rangeMonths" style="width:140px">
            <el-option v-for="o in rangeMonthOptions" :key="String(o.value)"
              :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="輸出資料夾">
          <el-input v-model="scheduleDialog.draft.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
            <template #append>
              <el-button :disabled="savingSchedule" @click="openDirPicker">選擇</el-button>
            </template>
          </el-input>
        </el-form-item>
        <!--
          Google Drive 同步（Requirement 51 / Task 243）：本機一律照寫，這裡只是額外多上傳一份副本。
          僅「主要管理者」可見可設——rclone remote 全機只有一份且綁定某個 Google 帳號，
          若其他使用者能啟用，他的財務報表會被上傳到那個帳號的雲端硬碟。真正的閘門在後端。
        -->
        <el-form-item v-if="auth.isConfiguredAdmin" label="同步 Google Drive">
          <div style="display:flex; flex-direction:column; gap:6px">
            <div style="display:flex; align-items:center; gap:12px">
              <el-switch v-model="scheduleDialog.draft.gdriveEnabled" />
              <el-input
                v-model="scheduleDialog.draft.gdriveSubpath"
                readonly
                placeholder="（尚未選擇 Drive 資料夾）"
                :disabled="savingSchedule || (!scheduleDialog.draft.gdriveEnabled)"
                style="width:260px"
              >
                <template #append>
                  <el-button :disabled="savingSchedule || (!scheduleDialog.draft.gdriveEnabled)" @click="openDirPicker('gdrive')">選擇</el-button>
                </template>
              </el-input>
            </div>
            <div style="font-size:12px; color:var(--el-text-color-secondary); line-height:1.7">
              開啟後除了寫入上面的本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到 Google Drive 的所選資料夾；
              <strong>本機那一份永遠照寫、不受影響</strong>。
              <template v-if="scheduleDialog.draft.gdriveEnabled && scheduleDialog.draft.gdriveSubpath">
                <br />Drive 落點：<code>{{ scheduleDialog.draft.gdriveRemote || 'GDriveOutput' }}:{{ scheduleDialog.draft.gdriveSubpath }}</code>
              </template>
              <br />上次上傳：
              <template v-if="scheduleDialog.draft.gdriveLastRunAt">
                {{ scheduleDialog.draft.gdriveLastRunAt }} — <code>{{ scheduleDialog.draft.gdriveLastStatus || '—' }}</code>
              </template>
              <template v-else>—（尚未執行過）</template>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <div class="schedule-hint">
        以主機家目錄 <code>{{ scheduleDialog.draft.baseDir || '/home/steven' }}</code> 為根（對映主機
        <code>/Users/steven</code>）。按上方「選擇」開啟檔案總管式選擇器挑選子資料夾；例如選 <code>input</code> →
        主機 <code>/Users/steven/input</code>。於下列每個啟用時間更新同日最新檔為
        <code>油價金價_{使用者ID}_YYYYMMDD.xlsx</code> 與 <code>.json</code> <strong>兩份</strong>（主檔名相同、只差副檔名；內容同上方「匯出 Excel」），較晚時段會覆寫同名檔為較新內容。
        匯出範圍以<b>執行當日往前推</b>計算，故每日產出會隨時間滾動。
      </div>
      <div v-if="scheduleDialog.draft.lastRunAt || scheduleDialog.draft.lastRunStatus" class="schedule-status">
        上次執行：{{ scheduleDialog.draft.lastRunAt || '—' }}　{{ scheduleDialog.draft.lastRunStatus || '' }}
      </div>
      <template #footer><el-button :disabled="savingSchedule" @click="scheduleDialog.visible = false">取消</el-button><el-button type="primary" :loading="savingSchedule" :disabled="savingSchedule" @click="saveSchedule">儲存設定</el-button></template>
      </el-dialog>
    </el-card>

    <!-- 輸出資料夾選擇器 -->
    <el-dialog v-model="dirPicker.visible" :title="dirPickerTitle" width="560px">
      <div class="dir-picker-path">
        目前選擇：<code>{{ dirPickerPreview }}</code>
      </div>
      <!-- Drive 端讀取失敗必須顯示原因；空樹會被誤讀為「Drive 裡沒有資料夾」而以為選錯位置 -->
      <el-alert
        v-if="dirPicker.error"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        :title="dirPicker.error"
      />
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
    <el-dialog v-model="exportDialog.visible" title="匯出油價金價" width="480px">
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
            單一 Excel 檔、一張工作表，欄位為
            <b>日期／WTI原油／布蘭特原油／黃金</b>，三個標的依日期對齊；
            某標的當日無報價則留空。
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
import { LineChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { ElMessage } from 'element-plus'
import { bffApi, apiErrorMessage } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { cloneExportSetting, replaceExportSetting } from '@/utils/exportSettingDraft'
import { useAuthStore } from '@/stores/authStore'
import dayjs from 'dayjs'
import dayjsUtc from 'dayjs/plugin/utc'
import dayjsTimezone from 'dayjs/plugin/timezone'

dayjs.extend(dayjsUtc)
dayjs.extend(dayjsTimezone)

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent])

// 標的定義：yAxis 0＝原油（USD/桶）、1＝黃金（USD/盎司）
const COMMODITIES = [
  { code: 'WTI', label: 'WTI 原油 (USD/桶)', color: '#16a34a', yAxisIndex: 0, unit: 'USD/桶' },
  { code: 'BRENT', label: '布蘭特原油 (USD/桶)', color: '#78716c', yAxisIndex: 0, unit: 'USD/桶' },
  { code: 'GOLD', label: 'COMEX 黃金 (USD/盎司)', color: '#eab308', yAxisIndex: 1, unit: 'USD/盎司' }
]

const rangeOptions = [
  { key: '1m', label: '1M', months: 1 },
  { key: '3m', label: '3M', months: 3 },
  { key: '6m', label: '6M', months: 6 },
  { key: '1y', label: '1Y', months: 12 },
  { key: '3y', label: '3Y', months: 36 },
  { key: '5y', label: '5Y', months: 60 },
  { key: 'all', label: '10Y', months: null }
]

const series = ref({})       // { WTI: [{date, close}], BRENT: [...], GOLD: [...] }
const selectedRange = ref('1y')
const loading = ref(false)
const refreshing = ref(false)
const exporting = ref(false)
const exportDialog = reactive({ visible: false, range: [] })

// 盤中即時報價（Requirement 77 / Task 337）：獨立 state，與上面裝載 commodity_price_history
// 的 series ref 完全分開，絕不可寫入 series／filtered，避免污染區間統計與匯出預設區間。
// { WTI: {commodityCode, price, change, changePercent, sessionDate, quoteTime, polledAt,
//         status, dayHigh, dayLow, provider} | undefined, ... }
const liveQuotes = ref({})
const TAIPEI_TZ = 'Asia/Taipei'
const LIVE_QUOTE_POLL_INTERVAL_MS = 60_000
const LIVE_STATUS_PREFIX = { LIVE: '盤中', STALE: '盤中（來源未更新）', SETTLED: '收盤' }
let liveQuoteTimer = null

// File System Access API：可讓使用者自選存檔目錄；Safari／舊版瀏覽器沒有，退回一般下載
const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window

// 排程自動匯出設定（Requirement 41 / Task 203；多時間點 Requirement 72 / Task 330）
const schedule = reactive({
  // Drive 同步（Task 243）；gdriveRemote 是後端給的顯示值，不入庫
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: '',

  enabled: false, outputSubpath: 'input',
  rangeMonths: 120, lastRunAt: null, lastRunStatus: null, baseDir: ''
})
const scheduleTimes = ref([{ key: 1, value: '08:00', enabled: true, lastRunAt: null, lastRunStatus: null }])
let nextScheduleTimeKey = 2
const savingSchedule = ref(false)
const scheduleDialog = reactive({ visible: false, draft: null, times: [] })
const runningNow = ref(false)

// 全部十年以 120（月）表示而非 null：Element Plus 的 el-select 預設把 null 當成 empty value
// （DEFAULT_EMPTY_VALUES 含 null），綁 null 會顯示灰色 placeholder 而非「全部十年」，
// 使用者無法分辨「已選全部十年」與「尚未選擇」。後端 end.minusMonths(120) 與 minusYears(10) 等價，
// 且 CHECK 允許 1..120，故語意零變動。後端仍保留 null 分支以相容未設定過的舊列。
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
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
const dirPicker = reactive({
  visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const auth = useAuthStore()
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

onMounted(() => {
  // 既有歷史載入、排程設定載入、新的即時報價載入三者並行啟動，不序列 await
  // （本專案多 panel 一律並行的既有慣例）；三者各自吞掉自己的錯誤，互不影響。
  Promise.allSettled([fetchData(), loadSchedule().catch(() => {}), fetchLiveQuotes()])
  document.addEventListener('visibilitychange', handleLiveQuoteVisibilityChange)
  startLiveQuotePolling()
})

onUnmounted(() => {
  stopLiveQuotePolling()
  document.removeEventListener('visibilitychange', handleLiveQuoteVisibilityChange)
})

async function fetchData() {
  loading.value = true
  try {
    const res = await bffApi.commodityPrice.getHistory()
    series.value = normalize(res.series)
  } catch {
  } finally {
    loading.value = false
  }
}

/** 每分鐘輪詢即時報價；失敗保留前一次顯示值，不清空、不跳錯誤 toast（getLive 已 skipErrorToast）。 */
async function fetchLiveQuotes() {
  try {
    const res = await bffApi.commodityPrice.getLive()
    const quotes = res?.quotes || {}
    const next = {}
    for (const c of COMMODITIES) {
      if (quotes[c.code]) next[c.code] = quotes[c.code]
    }
    liveQuotes.value = next
  } catch {
    // 輪詢失敗：什麼都不做，liveQuotes 維持上一輪的值
  }
}

function startLiveQuotePolling() {
  stopLiveQuotePolling()
  liveQuoteTimer = setInterval(fetchLiveQuotes, LIVE_QUOTE_POLL_INTERVAL_MS)
}

function stopLiveQuotePolling() {
  if (liveQuoteTimer) {
    clearInterval(liveQuoteTimer)
    liveQuoteTimer = null
  }
}

/** 分頁切到背景時暫停輪詢；恢復可見時立即補抓一次再重啟 interval。 */
function handleLiveQuoteVisibilityChange() {
  if (document.hidden) {
    stopLiveQuotePolling()
  } else {
    fetchLiveQuotes()
    startLiveQuotePolling()
  }
}

/** KPI 卡即時值優先；不存在時 fallback 到歷史收盤（見下方 latest computed，行為不變）。 */
function kpiValue(code) {
  const live = liveQuotes.value[code]
  if (live && live.price != null) return Number(live.price).toFixed(2)
  return latest.value[code] ? latest.value[code].close.toFixed(2) : '-'
}

function liveChangeColor(code) {
  const live = liveQuotes.value[code]
  if (!live || live.change == null) return undefined
  return Number(live.change) >= 0 ? '#dc2626' : '#16a34a'
}

/** change 為 null 時顯示「—」，不得顯示成 0。 */
function liveChangeText(code) {
  const live = liveQuotes.value[code]
  if (!live) return ''
  if (live.change == null || live.changePercent == null) return '—'
  const change = Number(live.change)
  const pct = Number(live.changePercent)
  return `${change >= 0 ? '▲' : '▼'} ${Math.abs(change).toFixed(2)} (${pct.toFixed(2)}%)`
}

/**
 * 三種狀態一律取 quoteTime 轉台北時制，不顯示 sessionDate
 * （夜盤的日期歸屬未經實測證實，quoteTime 是來源直接給的時間戳，永遠為真）。
 */
function liveTimeLabel(code) {
  const live = liveQuotes.value[code]
  if (!live?.quoteTime) return ''
  const prefix = LIVE_STATUS_PREFIX[live.status] || live.status || ''
  const time = dayjs(live.quoteTime).tz(TAIPEI_TZ).format('HH:mm:ss')
  return `${prefix} · ${time}`
}

/** 後端 BigDecimal 序列化為字串／數字皆有可能，統一轉 Number。 */
function normalize(raw) {
  const out = {}
  for (const c of COMMODITIES) {
    out[c.code] = (raw?.[c.code] ?? []).map(d => ({
      date: d.priceDate,
      close: Number(d.closePrice)
    }))
  }
  return out
}

const cutoffDate = computed(() => {
  const opt = rangeOptions.find(r => r.key === selectedRange.value)
  if (!opt?.months) return null
  return dayjs().subtract(opt.months, 'month').format('YYYY-MM-DD')
})

// 區間切換為記憶體切片，不重打 API（日期為 YYYY-MM-DD 字串，可直接比較）
const filtered = computed(() => {
  const cutoff = cutoffDate.value
  const out = {}
  for (const c of COMMODITIES) {
    const rows = series.value[c.code] ?? []
    out[c.code] = cutoff ? rows.filter(d => d.date >= cutoff) : rows
  }
  return out
})

const currentRangeLabel = computed(() =>
  rangeOptions.find(r => r.key === selectedRange.value)?.label ?? '')

/** 三序列聯集的日期軸——各市場假日不完全重疊，取聯集才不會漏掉單邊有報價的日子。 */
const axisDates = computed(() => {
  const set = new Set()
  for (const c of COMMODITIES) for (const d of filtered.value[c.code]) set.add(d.date)
  return [...set].sort()
})

const totalRows = computed(() =>
  COMMODITIES.reduce((sum, c) => sum + (series.value[c.code]?.length ?? 0), 0))

const dateSpan = computed(() => {
  const all = COMMODITIES.flatMap(c => series.value[c.code] ?? []).map(d => d.date).sort()
  return all.length ? `${all[0]} ~ ${all[all.length - 1]}` : ''
})

/** 各標的最新價與對前一交易日漲跌（衍生值，前端即時算，不入庫）。 */
const latest = computed(() => {
  const out = {}
  for (const c of COMMODITIES) {
    const rows = series.value[c.code] ?? []
    if (!rows.length) continue
    const last = rows[rows.length - 1]
    const prev = rows.length > 1 ? rows[rows.length - 2] : null
    const change = prev ? last.close - prev.close : 0
    out[c.code] = {
      close: last.close,
      date: last.date,
      change,
      changePct: prev && prev.close ? (change / prev.close) * 100 : 0
    }
  }
  return out
})

const statsRows = computed(() => COMMODITIES.map(c => {
  const rows = filtered.value[c.code] ?? []
  if (!rows.length) {
    return { label: c.label, latest: '-', max: '-', min: '-', avg: '-', changePct: '-', rawChangePct: 0 }
  }
  const values = rows.map(d => d.close)
  const first = values[0]
  const last = values[values.length - 1]
  const pct = first ? ((last - first) / first) * 100 : 0
  return {
    label: c.label,
    latest: last.toFixed(2),
    max: Math.max(...values).toFixed(2),
    min: Math.min(...values).toFixed(2),
    avg: (values.reduce((a, b) => a + b, 0) / values.length).toFixed(2),
    changePct: `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`,
    rawChangePct: pct
  }
}))

const mainChartOption = computed(() => {
  const dates = axisDates.value
  if (!dates.length) return {}

  // 盤中點只能在此 computed 函式內部現算局部變數，不得改動 axisDates／filtered 本身
  // ——openExport() 直接讀 axisDates.value 決定匯出對話框預設區間，若動了 axisDates
  // 本身會把盤中日期一併帶進匯出（Requirement 77 / Task 337，匯出行為不得變動）。
  // 只在「sessionDate 晚於該標的序列最後一筆 DB 日期」且「該日期尚未存在於聯集軸上」時附加，
  // 已存在於 DB 序列的日期一律不覆蓋。
  const extraDates = new Set()
  for (const c of COMMODITIES) {
    const live = liveQuotes.value[c.code]
    if (!live?.sessionDate || live.price == null) continue
    if (dates.includes(live.sessionDate)) continue
    const codeRows = filtered.value[c.code]
    const lastCodeDate = codeRows.length ? codeRows[codeRows.length - 1].date : null
    if (lastCodeDate && live.sessionDate <= lastCodeDate) continue
    extraDates.add(live.sessionDate)
  }
  const chartDates = extraDates.size ? [...dates, ...[...extraDates].sort()] : dates

  // 依聯集日期軸對齊；缺報價的日子放 null，ECharts 會斷點而非畫成 0
  const byDate = {}
  for (const c of COMMODITIES) {
    const map = new Map(filtered.value[c.code].map(d => [d.date, d.close]))
    const live = liveQuotes.value[c.code]
    byDate[c.code] = chartDates.map(d => {
      if (map.has(d)) return map.get(d)
      if (live && live.sessionDate === d && live.price != null) return Number(live.price)
      return null
    })
  }
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        let s = `<strong>${params[0].axisValue}</strong><br/>`
        for (const p of params) {
          if (p.value == null) continue
          s += `${p.marker}${p.seriesName}: ${Number(p.value).toFixed(2)}<br/>`
        }
        return s
      }
    },
    legend: { data: COMMODITIES.map(c => c.label), top: 0 },
    grid: { left: 60, right: 70, top: 40, bottom: 70 },
    xAxis: { type: 'category', data: chartDates, axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: [
      {
        type: 'value', scale: true, name: '原油 USD/桶',
        nameTextStyle: { fontSize: 11, color: '#64748b' },
        axisLabel: { formatter: v => v.toFixed(0) }
      },
      {
        type: 'value', scale: true, name: '黃金 USD/盎司',
        nameTextStyle: { fontSize: 11, color: '#64748b' },
        axisLabel: { formatter: v => v.toFixed(0) },
        splitLine: { show: false }
      }
    ],
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
    ],
    series: COMMODITIES.map(c => ({
      name: c.label,
      type: 'line',
      yAxisIndex: c.yAxisIndex,
      data: byDate[c.code],
      // 十年約 2,500 點，畫 symbol 會嚴重掉幀
      symbol: 'none',
      smooth: false,
      connectNulls: false,
      lineStyle: { width: 1.5, color: c.color },
      itemStyle: { color: c.color }
    }))
  }
})

async function onRefresh() {
  refreshing.value = true
  try {
    await bffApi.commodityPrice.refresh()
    await fetchData()
    ElMessage.success('油金價資料已更新')
  } catch {
  } finally {
    refreshing.value = false
  }
}

function openExport() {
  // 預設帶入目前圖表所選區間
  const dates = axisDates.value
  const start = cutoffDate.value ?? (dates.length ? dates[0] : dayjs().subtract(10, 'year').format('YYYY-MM-DD'))
  const end = dates.length ? dates[dates.length - 1] : dayjs().format('YYYY-MM-DD')
  exportDialog.range = [start, end]
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
    const blob = await bffApi.commodityPrice.exportExcel(start, end)
    const filename = `油價金價_${start.replaceAll('-', '')}_${end.replaceAll('-', '')}.xlsx`
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

// ===== 排程自動匯出 =====

async function loadSchedule() {
  const s = await bffApi.commodityPrice.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.outputSubpath = s.outputSubpath ?? 'input'
  // 後端 null（未設定過的舊列）＝全部十年，映射成 120 讓下拉正確顯示
  schedule.rangeMonths = s.rangeMonths ?? ALL_TEN_YEARS_MONTHS
  schedule.lastRunAt = s.lastRunAt ?? null
  schedule.lastRunStatus = s.lastRunStatus ?? null
  schedule.baseDir = s.baseDir ?? ''
  applyGdrive(s)
  if (Array.isArray(s.times) && s.times.length) {
    scheduleTimes.value = s.times
      .slice().sort((a, b) => (a.runHour - b.runHour) || (a.runMinute - b.runMinute))
      .map(t => ({ key: nextScheduleTimeKey++, value: `${String(t.runHour).padStart(2, '0')}:${String(t.runMinute).padStart(2, '0')}`,
        enabled: !!t.enabled, lastRunAt: t.lastRunAt || null, lastRunStatus: t.lastRunStatus || null }))
  } else if (!Array.isArray(s.times)) {
    // rolling upgrade only：舊後端才讀 legacy single time；新後端回空 times 是異常，不能靜默補預設。
    scheduleTimes.value = [{ key: nextScheduleTimeKey++, value: `${String(s.runHour ?? 8).padStart(2, '0')}:${String(s.runMinute ?? 0).padStart(2, '0')}`, enabled: true }]
  } else {
    ElMessage.error('排程時間資料異常，請重新載入後再試')
  }
}

async function saveSchedule() {
  if (savingSchedule.value || !scheduleDialog.draft) return
  const draft = scheduleDialog.draft
  // 前後端都擋：開了同步卻沒選資料夾，後端也會回 400
  if (draft.gdriveEnabled && !(draft.gdriveSubpath || '').trim()) {
    ElMessage.warning('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
    return
  }
  savingSchedule.value = true
  try {
    if (!scheduleDialog.times.length) { ElMessage.warning('至少需要一個執行時間'); return }
    const times = []
    const seen = new Set()
    for (const item of scheduleDialog.times) {
      const [h, m] = String(item.value || '').split(':').map(Number)
      if (!Number.isInteger(h) || !Number.isInteger(m) || h < 0 || h > 23 || m < 0 || m > 59) {
        ElMessage.warning('請輸入有效的執行時間'); return
      }
      const key = `${h}:${m}`
      if (seen.has(key)) { ElMessage.warning('執行時間不可重複'); return }
      seen.add(key); times.push({ runHour: h, runMinute: m, enabled: !!item.enabled })
    }
    if (draft.enabled && !times.some(t => t.enabled)) { ElMessage.warning('啟用排程時至少需啟用一個時間'); return }
    const s = await bffApi.commodityPrice.updateExportSchedule({
      enabled: draft.enabled,
      gdriveEnabled: draft.gdriveEnabled,
      gdriveSubpath: (draft.gdriveSubpath || '').trim(),
      times,
      outputSubpath: (draft.outputSubpath || 'input').trim(),
      rangeMonths: draft.rangeMonths
    })
    if (!Array.isArray(s.times) || !s.times.length) throw new Error('伺服器未回傳有效的排程時間')
    replaceExportSetting(schedule, s)
    scheduleTimes.value = s.times.map(t => ({ key: nextScheduleTimeKey++, value: `${String(t.runHour).padStart(2, '0')}:${String(t.runMinute).padStart(2, '0')}`, enabled: !!t.enabled, lastRunAt: t.lastRunAt ?? null, lastRunStatus: t.lastRunStatus ?? null }))
    scheduleDialog.visible = false
    ElMessage.success('排程設定已儲存')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '儲存失敗，請稍後再試'))
  } finally {
    savingSchedule.value = false
  }
}

function addScheduleTime() {
  if (savingSchedule.value) return
  scheduleDialog.times.push({ key: nextScheduleTimeKey++, value: '08:00', enabled: true, lastRunAt: null, lastRunStatus: null })
}
function removeScheduleTime(key) {
  if (savingSchedule.value) return
  if (scheduleDialog.times.length > 1) scheduleDialog.times = scheduleDialog.times.filter(item => item.key !== key)
}

function openScheduleDialog() {
  if (savingSchedule.value || runningNow.value) return
  scheduleDialog.draft = cloneExportSetting(schedule)
  scheduleDialog.times = cloneExportSetting(scheduleTimes.value)
  scheduleDialog.visible = true
}
function closeScheduleDialog(done) { if (!savingSchedule.value) done() }

async function handleRunNow() {
  if (savingSchedule.value || runningNow.value) return
  runningNow.value = true
  try {
    const r = await bffApi.commodityPrice.runExportNow()
    // path 依契約一律指 xlsx、jsonPath 指 json（Requirement 55 / Task 282）
    showDualExportResult({ jsonPath: r.jsonPath, xlsxPath: r.path, gdriveStatus: r.gdriveStatus })
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '立即匯出失敗，請確認目錄與權限'))
  } finally {
    runningNow.value = false
  }
  loadSchedule().catch(() => {}) // 刷新上次執行資訊，失敗不影響匯出結果
}


// 雙模式資料夾選擇器（Task 243，沿用 Task 241 於 CrawlerDataView 的寫法）
const dirPickerTitle = computed(() =>
  dirPicker.mode === 'gdrive' ? '選擇 Google Drive 資料夾' : '選擇輸出資料夾')

// 兩種 mode 的分隔符不同：Drive 基底是 `remote:`（已含冒號，後面直接接子路徑），
// 本機基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財`——
// 多一個斜線、不是 rclone 的路徑格式，會誤導使用者。
const dirPickerPreview = computed(() => {
  const isGdrive = dirPicker.mode === 'gdrive'
  const base = dirPicker.baseDir
    || (isGdrive ? (schedule.gdriveRemote || 'GDriveOutput') + ':' : (schedule.baseDir || '/home/steven'))
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  const joined = parts.join('/')
  if (!joined) return base
  return isGdrive ? base + joined : base + '/' + joined
})

/** 把後端回的 Drive 欄位寫回本地狀態（讀取一律不驗證，不合法值也照顯示供使用者修正）。 */
function applyGdrive(s) {
  schedule.gdriveEnabled = !!s.gdriveEnabled
  schedule.gdriveSubpath = s.gdriveSubpath || ''
  schedule.gdriveRemote = s.gdriveRemote || ''
  schedule.gdriveLastRunAt = s.gdriveLastRunAt || null
  schedule.gdriveLastStatus = s.gdriveLastStatus || ''
}

function openDirPicker(mode = 'local') {
  if (savingSchedule.value || !scheduleDialog.draft) return
  dirPicker.mode = mode
  dirPicker.picked = (mode === 'gdrive' ? scheduleDialog.draft.gdriveSubpath : scheduleDialog.draft.outputSubpath) || ''
  dirPicker.newSub = ''
  dirPicker.baseDir = ''         // 兩種 mode 的基底不同，重開時一律重新取
  dirPicker.error = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.commodityPrice.browseGdriveExportDir
    : bffApi.commodityPrice.browseExportDir
  try {
    if (node.level === 0) {
      const res = await browse('')
      dirPicker.baseDir = res.baseDir || ''
      dirPicker.error = ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await browse(node.data.path || '')
    resolve((res.directories || []).map(d => ({ name: d.name, path: d.path, key: d.path, leaf: false })))
  } catch (e) {
    // Drive 端失敗要顯示原因（remote 未設定／授權失效）；空樹會被誤讀為「Drive 裡沒有資料夾」
    if (dirPicker.mode === 'gdrive') {
      dirPicker.error = e?.response?.data?.detail || e?.message || 'Google Drive 資料夾讀取失敗'
    }
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  if (savingSchedule.value || !scheduleDialog.draft) return
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  if (dirPicker.mode === 'gdrive') scheduleDialog.draft.gdriveSubpath = p
  else scheduleDialog.draft.outputSubpath = p
  dirPicker.visible = false
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
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.kpi-card { text-align: center; }
.kpi-label { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.kpi-value { font-size: 24px; font-weight: 700; color: #1e293b; }
.kpi-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.chart-note { font-size: 12px; color: #94a3b8; margin-top: 8px; }
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
