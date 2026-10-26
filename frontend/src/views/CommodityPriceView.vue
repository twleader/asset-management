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

    <!-- 獨立排程匯出設定（Task 512） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <el-button size="small" type="primary" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError || schedules.length >= MAX_EXPORT_SCHEDULES" @click="openNewSchedule">新增排程</el-button>
        </div>
      </template>

      <el-alert v-if="scheduleLoadError" type="error" :closable="false" show-icon style="margin-bottom:12px" :title="scheduleLoadError">
        <template #default><el-button link type="primary" @click="loadSchedules">重試載入</el-button></template>
      </el-alert>
      <el-skeleton v-if="scheduleLoading" :rows="2" animated />
      <el-empty v-else-if="!scheduleLoadError && schedules.length === 0" description="尚未建立排程，按右上「新增排程」開始。" :image-size="80" />

      <div v-for="(item, index) in schedules" :key="item.id" class="schedule-row">
        <div class="schedule-row-head">
          <div style="display:flex;align-items:center;gap:8px">
            <strong>{{ item.name || `排程 #${index + 1}` }}</strong>
            <el-tag size="small" :type="item.enabled ? 'success' : 'info'">{{ item.enabled ? '啟用' : '停用' }}</el-tag>
          </div>
          <div style="display:flex;gap:8px">
            <el-button size="small" :loading="scheduleMutationBusy && activeScheduleId === item.id && activeScheduleAction === 'run'" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError" @click="handleRunNow(item)">立即匯出</el-button>
            <el-button size="small" type="primary" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError" @click="openEditSchedule(item)">編輯設定</el-button>
            <el-popconfirm title="確定刪除這筆排程？" width="200" @confirm="handleDeleteSchedule(item)">
              <template #reference><el-button size="small" type="danger" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError">刪除</el-button></template>
            </el-popconfirm>
          </div>
        </div>
        <div>每日 {{ formatExportScheduleTime(item.runHour, item.runMinute) }}；{{ formatExportRangeMonths(item.rangeMonths) }}；本機資料夾 {{ item.outputSubpath || '家目錄根' }}</div>
        <div class="schedule-status">上次執行：{{ item.lastRunAt || '—' }}　{{ item.lastRunStatus || '' }}</div>
        <div v-if="auth.isConfiguredAdmin" class="schedule-status">
          Google Drive：{{ item.gdriveEnabled ? '已啟用' : '停用' }}；{{ item.gdriveSubpath || '未選資料夾' }}；上次上傳 {{ item.gdriveLastRunAt || '—' }}　{{ item.gdriveLastStatus || '' }}
        </div>
      </div>

      <el-dialog v-model="scheduleDialog.visible" :title="scheduleDialog.mode === 'create' ? '新增油價金價匯出排程' : '編輯油價金價匯出排程'" width="680px" :close-on-click-modal="!scheduleMutationBusy" :close-on-press-escape="!scheduleMutationBusy" :show-close="!scheduleMutationBusy" :before-close="closeScheduleDialog">
        <el-form v-if="scheduleDialog.draft" :disabled="scheduleMutationBusy" :inline="true" label-width="100px" class="schedule-form">
          <el-form-item label="名稱"><el-input v-model="scheduleDialog.draft.name" maxlength="20" show-word-limit style="width:220px" placeholder="（選填，會進檔名）" /></el-form-item>
          <el-form-item label="啟用每日排程"><el-switch v-model="scheduleDialog.draft.enabled" /></el-form-item>
          <el-form-item label="每日執行時間"><el-time-picker v-model="scheduleDialog.draft._time" format="HH:mm" value-format="HH:mm" placeholder="時:分" style="width:130px" /></el-form-item>
          <el-form-item label="匯出範圍">
            <el-select v-model="scheduleDialog.draft.rangeMonths" style="width:170px">
              <el-option v-for="option in getExportRangeMonthOptions(scheduleDialog.draft.rangeMonths)" :key="String(option.value)" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="輸出資料夾">
            <el-input v-model="scheduleDialog.draft.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
              <template #append><el-button :disabled="scheduleMutationBusy" @click="openDirPicker('local')">選擇</el-button></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="auth.isConfiguredAdmin" label="同步 Google Drive">
            <div style="display:flex;flex-direction:column;gap:6px">
              <div style="display:flex;align-items:center;gap:12px">
                <el-switch v-model="scheduleDialog.draft.gdriveEnabled" />
                <el-input v-model="scheduleDialog.draft.gdriveSubpath" readonly placeholder="（尚未選擇 Drive 資料夾）" :disabled="scheduleMutationBusy || !scheduleDialog.draft.gdriveEnabled" style="width:260px">
                  <template #append><el-button :disabled="scheduleMutationBusy || !scheduleDialog.draft.gdriveEnabled" @click="openDirPicker('gdrive')">選擇</el-button></template>
                </el-input>
              </div>
              <div style="font-size:12px;color:var(--el-text-color-secondary);line-height:1.7">
                開啟後會在本機檔案之外，再上傳一份到 Google Drive。<strong>本機匯出不受影響</strong>。
                <template v-if="scheduleDialog.draft.gdriveEnabled && scheduleDialog.draft.gdriveSubpath"><br />Drive 落點：<code>{{ scheduleDialog.draft.gdriveRemote || 'GDriveOutput' }}:{{ scheduleDialog.draft.gdriveSubpath }}</code></template>
                <br />上次上傳：{{ scheduleDialog.draft.gdriveLastRunAt || '—' }}　{{ scheduleDialog.draft.gdriveLastStatus || '' }}
              </div>
            </div>
          </el-form-item>
        </el-form>
        <div class="schedule-hint">
          以主機家目錄 <code>{{ baseDir || '/home/steven' }}</code> 為根。每筆排程於自己的時間匯出所選區間，產生
          <code>油價金價_{使用者ID}[_{名稱}]_YYYYMMDD.xlsx</code> 與同名 <code>.json</code>。
          同一使用者、日期、資料夾與名稱會覆寫；請以不同名稱或資料夾保留多份。空白名稱沿用舊檔名；取消會丟棄草稿。
        </div>
        <div class="schedule-status">上次執行：{{ scheduleDialog.draft?.lastRunAt || '—' }}　{{ scheduleDialog.draft?.lastRunStatus || '' }}</div>
        <template #footer><el-button :disabled="scheduleMutationBusy" @click="cancelScheduleDialog">取消</el-button><el-button type="primary" :loading="scheduleMutationBusy && activeScheduleAction === 'save'" :disabled="scheduleMutationBusy" @click="saveSchedule">儲存排程</el-button></template>
      </el-dialog>

      <div class="schedule-hint" style="margin-top:10px">本機輸出根目錄：<code>{{ baseDir || '/home/steven' }}</code>。立即匯出使用該筆已儲存的設定。</div>
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
import { useAuthStore } from '@/stores/authStore'
import {
  MAX_EXPORT_SCHEDULES,
  buildExportSchedulePayload,
  createExportScheduleDraft,
  formatExportScheduleTime,
  formatExportRangeMonths,
  getExportRangeMonthOptions,
  getExportScheduleError,
  requireExportScheduleList
} from '@/utils/exportSchedules'
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

// 每列是一筆獨立排程；所有 mutation 共用單一 busy guard。
const schedules = ref([])
const baseDir = ref('')
const gdriveRemote = ref('')
const scheduleLoading = ref(true)
const scheduleLoadInFlight = ref(false)
const scheduleLoadError = ref('')
const scheduleMutationBusy = ref(false)
const activeScheduleId = ref(null)
const activeScheduleAction = ref('')
const scheduleDialog = reactive({ visible: false, mode: 'create', draft: null })

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
  Promise.allSettled([fetchData(), loadSchedules(), fetchLiveQuotes()])
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

// ===== 獨立排程自動匯出 =====

async function loadSchedules() {
  if (scheduleLoadInFlight.value) return false
  scheduleLoadInFlight.value = true
  scheduleLoading.value = true
  scheduleLoadError.value = ''
  try {
    const response = requireExportScheduleList(await bffApi.commodityPrice.getExportSchedules(), { requireRangeMonths: true })
    schedules.value = response.schedules
    baseDir.value = response.baseDir || ''
    gdriveRemote.value = response.gdriveRemote || ''
    return true
  } catch (error) {
    scheduleLoadError.value = getExportScheduleError(error, '排程清單載入失敗')
    return false
  } finally {
    scheduleLoading.value = false
    scheduleLoadInFlight.value = false
  }
}

function applyScheduleList(response) {
  const canonical = requireExportScheduleList(response, { requireRangeMonths: true })
  schedules.value = canonical.schedules
  baseDir.value = canonical.baseDir || ''
  gdriveRemote.value = canonical.gdriveRemote || ''
}

function openNewSchedule() {
  if (scheduleMutationBusy.value || scheduleLoading.value || scheduleLoadError.value || schedules.value.length >= MAX_EXPORT_SCHEDULES) return
  scheduleDialog.mode = 'create'
  scheduleDialog.draft = { ...createExportScheduleDraft(null, { includeRangeMonths: true }), gdriveRemote: gdriveRemote.value }
  scheduleDialog.visible = true
}

function openEditSchedule(item) {
  if (scheduleMutationBusy.value || scheduleLoading.value || scheduleLoadError.value) return
  scheduleDialog.mode = 'edit'
  scheduleDialog.draft = { ...createExportScheduleDraft(item, { includeRangeMonths: true }), gdriveRemote: gdriveRemote.value }
  scheduleDialog.visible = true
}

function cancelScheduleDialog() {
  if (scheduleMutationBusy.value) return
  scheduleDialog.visible = false
  scheduleDialog.draft = null
  dirPicker.visible = false
}

function closeScheduleDialog(done) {
  if (scheduleMutationBusy.value) return
  scheduleDialog.draft = null
  dirPicker.visible = false
  done()
}

async function saveSchedule() {
  const draft = scheduleDialog.draft
  if (!draft || scheduleMutationBusy.value || scheduleLoadError.value) return
  let payload
  try {
    payload = buildExportSchedulePayload(draft, { isConfiguredAdmin: auth.isConfiguredAdmin })
  } catch (error) {
    ElMessage.warning(error.message)
    return
  }

  scheduleMutationBusy.value = true
  activeScheduleId.value = draft.id
  activeScheduleAction.value = 'save'
  let requestSucceeded = false
  try {
    const response = scheduleDialog.mode === 'create'
      ? await bffApi.commodityPrice.createExportSchedule(payload)
      : await bffApi.commodityPrice.updateExportSchedule(draft.id, payload)
    requestSucceeded = true
    applyScheduleList(response)
    scheduleDialog.visible = false
    scheduleDialog.draft = null
    dirPicker.visible = false
    ElMessage.success(scheduleDialog.mode === 'create' ? '排程已新增' : '排程設定已儲存')
    showGdriveSelfCheckWarning(response.gdriveSelfCheckWarning)
  } catch (error) {
    ElMessage.error(`儲存失敗：${getExportScheduleError(error, '請稍後再試')}`)
    if (requestSucceeded) await loadSchedules()
  } finally {
    scheduleMutationBusy.value = false
    activeScheduleId.value = null
    activeScheduleAction.value = ''
  }
}

async function handleDeleteSchedule(item) {
  if (scheduleLoading.value || scheduleMutationBusy.value || scheduleLoadError.value) return
  scheduleMutationBusy.value = true
  activeScheduleId.value = item.id
  activeScheduleAction.value = 'delete'
  let requestSucceeded = false
  try {
    const response = await bffApi.commodityPrice.deleteExportSchedule(item.id)
    requestSucceeded = true
    applyScheduleList(response)
    ElMessage.success('排程已刪除')
    showGdriveSelfCheckWarning(response.gdriveSelfCheckWarning)
  } catch (error) {
    ElMessage.error(`刪除失敗：${getExportScheduleError(error, '請稍後再試')}`)
    if (requestSucceeded) await loadSchedules()
  } finally {
    scheduleMutationBusy.value = false
    activeScheduleId.value = null
    activeScheduleAction.value = ''
  }
}

async function handleRunNow(item) {
  if (scheduleLoading.value || scheduleMutationBusy.value || scheduleLoadError.value) return
  scheduleMutationBusy.value = true
  activeScheduleId.value = item.id
  activeScheduleAction.value = 'run'
  try {
    const result = await bffApi.commodityPrice.runExportScheduleNow(item.id)
    showDualExportResult({ jsonPath: result.jsonPath, xlsxPath: result.path, gdriveStatus: result.gdriveStatus })
    await loadSchedules()
  } catch (error) {
    ElMessage.error(`立即匯出失敗：${getExportScheduleError(error, '請確認目錄與權限')}`)
  } finally {
    scheduleMutationBusy.value = false
    activeScheduleId.value = null
    activeScheduleAction.value = ''
  }
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
    || (isGdrive ? (scheduleDialog.draft?.gdriveRemote || gdriveRemote.value || 'GDriveOutput') + ':' : (baseDir.value || '/home/steven'))
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  const joined = parts.join('/')
  if (!joined) return base
  return isGdrive ? base + joined : base + '/' + joined
})

function openDirPicker(mode = 'local') {
  if (scheduleMutationBusy.value || !scheduleDialog.draft || (mode === 'gdrive' && !auth.isConfiguredAdmin)) return
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
    dirPicker.error = getExportScheduleError(e, dirPicker.mode === 'gdrive' ? 'Google Drive 資料夾讀取失敗' : '輸出資料夾讀取失敗')
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  if (scheduleMutationBusy.value || !scheduleDialog.draft) return
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
.schedule-row { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); line-height: 1.7; }
.schedule-row-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
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
