<template>
  <div>
    <!-- Market Status -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="8">
        <el-card>
          <template #header><span class="section-title" style="display:inline-flex;align-items:center;gap:6px"><TaiwanMap :size="14" /> 台股</span></template>
          <div class="market-info">
            <div class="status-row">
              <span class="status-dot" :class="status.twMarketOpen ? 'open' : 'closed'" />
              <span class="status-text">{{ status.twMarketOpen ? '開盤中' : '休市' }}</span>
            </div>
            <div class="info-item">
              <span class="label">台灣時間</span>
              <span class="value">{{ twTimeDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">交易時間</span>
              <span class="value">週一～五 09:00 ~ 13:30</span>
            </div>
            <div class="info-item">
              <span class="label">交易所</span>
              <span class="value">TWSE 臺灣證券交易所</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span class="section-title" style="display:inline-flex;align-items:center;gap:6px"><UsFlag :size="22" /> 美股</span></template>
          <div class="market-info">
            <div class="status-row">
              <span class="status-dot" :class="status.usMarketOpen ? 'open' : 'closed'" />
              <span class="status-text">{{ status.usMarketOpen ? '開盤中' : '休市' }}</span>
            </div>
            <div class="info-item">
              <span class="label">美東時間</span>
              <span class="value">{{ usTimeDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">交易時間</span>
              <span class="value">{{ usSessionDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">日光節約</span>
              <span class="value">
                <el-tag :type="isDst ? 'success' : 'info'" size="small">{{ isDst ? '夏令時間 (DST)' : '標準時間 (EST)' }}</el-tag>
                　{{ isDst ? 'UTC-4 → 台灣 21:30~04:00' : 'UTC-5 → 台灣 22:30~05:00' }}
              </span>
            </div>
            <div class="info-item">
              <span class="label">交易所</span>
              <span class="value">NYSE / NASDAQ</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><span class="section-title" style="display:inline-flex;align-items:center;gap:6px"><span style="font-size:18px">🇬🇧</span> 英股</span></template>
          <div class="market-info">
            <div class="status-row">
              <span class="status-dot" :class="status.ukMarketOpen ? 'open' : 'closed'" />
              <span class="status-text">{{ status.ukMarketOpen ? '開盤中' : '休市' }}</span>
            </div>
            <div class="info-item">
              <span class="label">倫敦時間</span>
              <span class="value">{{ ukTimeDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">交易時間</span>
              <span class="value">週一～五 08:00 ~ 16:30</span>
            </div>
            <div class="info-item">
              <span class="label">交易所</span>
              <span class="value">LSE 倫敦證券交易所</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Calendar -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">交易日曆 {{ calendarYear }}年{{ String(calendarMonth).padStart(2,'0') }}月
            <el-tag v-if="holidaysLoading" type="info" size="small" style="margin-left:8px">載入假日中…</el-tag>
          </span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button size="small" type="primary" :icon="Download"
              :disabled="exportYears.length !== 2" @click="openExportDialog">匯出</el-button>
            <el-select v-model="calendarYear" size="small" style="width:110px" aria-label="年度">
              <el-option v-for="year in availableYears" :key="year" :label="`${year} 年`" :value="year" />
            </el-select>
            <el-button size="small" :disabled="atMinMonth" @click="prevMonth">上月</el-button>
            <el-button size="small" @click="goToday">今天</el-button>
            <el-button size="small" :disabled="atMaxMonth" @click="nextMonth">下月</el-button>
          </div>
        </div>
      </template>
      <div class="legend" style="margin-bottom:12px">
        <span class="legend-item"><TaiwanMap :size="16" /> 台股交易日</span>
        <span class="legend-item"><UsFlag :size="20" /> 美股交易日</span>
        <span class="legend-item"><span style="font-size:18px;line-height:1;display:inline-flex;align-items:center">🇬🇧</span> 英股交易日</span>
        <span class="legend-item">
          <span style="display:inline-flex;align-items:center;gap:3px">
            <TaiwanMap :size="16" /><UsFlag :size="20" /><span style="font-size:18px;line-height:1;display:inline-flex;align-items:center">🇬🇧</span>
          </span>
          三市同交易
        </span>
        <span class="legend-item"><span class="legend-dot holiday" /> 假日/休市</span>
      </div>
      <el-alert v-for="market in unavailableMarkets" :key="market"
        type="warning" :closable="false" show-icon style="margin-bottom:8px"
        :title="`${marketLabels[market]} ${calendarYear} 年度日曆尚未取得`" />
      <table class="cal-table">
        <thead>
          <tr>
            <th v-for="d in ['日','一','二','三','四','五','六']" :key="d">{{ d }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(week, wi) in calendarWeeks" :key="wi">
            <td v-for="(day, di) in week" :key="di"
              :class="calendarDayClass(day)"
              @click="day.date && (selectedDate = day.date)">
              <div v-if="day.day" class="cal-cell">
                <span class="cal-day" :class="{ today: day.isToday }">{{ day.day }}</span>
                <div class="cal-tags">
                  <TaiwanMap v-if="day.tw" :size="12" />
                  <UsFlag v-if="day.us" :size="14" />
                  <span v-if="day.uk" style="font-size:14px;line-height:1;display:inline-flex;align-items:center">🇬🇧</span>
                </div>
                <div v-if="day.twHoliday || day.usHoliday || day.ukHoliday" class="cal-holiday">
                  <small v-if="day.twHoliday" style="color:#ef4444">{{ day.twHoliday }}</small>
                  <small v-if="day.usHoliday" style="color:#3b82f6">{{ day.usHoliday }}</small>
                  <small v-if="day.ukHoliday" style="color:#0ea5e9">{{ day.ukHoliday }}</small>
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </el-card>

    <!-- 匯出對話框：固定伺服器定義的今年與明年 -->
    <el-dialog v-model="exportDialog.visible" title="匯出交易日曆" width="560px">
      <el-form label-width="90px">
        <el-form-item label="輸出資料夾">
          <el-input v-model="exportDialog.subpath" readonly placeholder="（家目錄根）" style="width:300px">
            <template #append>
              <el-button :icon="FolderOpened" @click="openDirPicker">選擇</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <div class="export-hint">
        以主機家目錄 <code>{{ exportDialog.baseDir || '/home/steven' }}</code> 為根（對映主機 <code>/Users/steven</code>）。
        固定匯出今年與明年各自的整年交易日曆，共四份：
        <template v-for="year in exportYears" :key="year"><code>交易日曆_{{ year }}.json</code>、<code>交易日曆_{{ year }}.xlsx</code>　</template>
      </div>
      <!--
        兩個落點都列出（Requirement 55 / Task 282）：這一列留在畫面上，比一閃即逝的 toast
        更常被當成事實，只列一份等於把「只匯出 excel」那個誤解釘在畫面上。
        不再顯示 KB——sizeBytes 只有 xlsx 那一份，配上兩個落點會變成「兩個檔案、一個大小」。
      -->
      <div v-if="exportDialog.lastResult" class="export-status">
        <div v-for="result in exportDialog.lastResult.results || []" :key="result.year" style="margin-top:6px">
          <strong>{{ result.year }} 年</strong>：{{ result.localStatus || '未產出' }}
          <template v-if="hasLocalExport(result)"> ✅ 已匯出：
            <code v-if="result.path">{{ result.path }}</code><template v-if="result.path && result.jsonPath"> 與 </template><code v-if="result.jsonPath">{{ result.jsonPath }}</code>
          </template>
        </div>
      </div>

      <!-- 每日排程自動匯出（Task 185）：共用上方格式／資料夾 -->
      <el-divider content-position="left">每日排程自動匯出</el-divider>
      <el-form label-width="90px">
        <el-form-item label="啟用排程">
          <el-switch v-model="exportDialog.scheduleEnabled" />
        </el-form-item>
        <el-form-item label="每日時間">
          <el-time-picker v-model="exportDialog.scheduleTime" format="HH:mm" value-format="HH:mm"
            placeholder="時:分" style="width:130px" />
          <el-button size="small" type="primary" :loading="exportDialog.savingSchedule"
            style="margin-left:12px" @click="saveSchedule">儲存排程</el-button>
        </el-form-item>
        <!--
          Google Drive 同步（Requirement 51 / Task 244）掛在「每日排程自動匯出」之下而非上方共用的
          「輸出資料夾」旁：Drive 子路徑存在<b>排程設定列</b>上，放上方會讓使用者以為它跟著本次匯出的
          資料夾一起變。僅「主要管理者」可見可設，真正的閘門在後端。
        -->
        <el-form-item v-if="auth.isConfiguredAdmin" label="同步 Drive">
          <el-switch v-model="exportDialog.gdriveEnabled" />
          <el-input
            v-model="exportDialog.gdriveSubpath"
            readonly
            placeholder="（尚未選擇 Drive 資料夾）"
            :disabled="!exportDialog.gdriveEnabled"
            style="width:280px; margin-left:12px"
          >
            <template #append>
              <el-button :disabled="!exportDialog.gdriveEnabled" @click="openDirPicker('gdrive')">選擇</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <div v-if="auth.isConfiguredAdmin" class="export-hint">
        開啟後除了寫入本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到 Google Drive 的所選資料夾；
        <strong>本機那一份永遠照寫、不受影響</strong>。
        <strong>手動按「匯出到目錄」時也會同步</strong>——但 Drive 的目的地固定為這裡選的資料夾，
        不隨上方「輸出資料夾」改變（上方是「這次匯出到哪」，這裡是「Drive 同步的固定目的地」）。
        <template v-if="exportDialog.gdriveEnabled && exportDialog.gdriveSubpath">
          <br />Drive 落點：<code>{{ exportDialog.gdriveRemote || 'GDriveOutput' }}:{{ exportDialog.gdriveSubpath }}</code>
        </template>
        <br />上次上傳：
        <template v-if="exportDialog.gdriveLastRunAt">
          {{ exportDialog.gdriveLastRunAt }} — <code>{{ exportDialog.gdriveLastStatus || '—' }}</code>
        </template>
        <template v-else>—（尚未執行過）</template>
      </div>
      <div class="export-hint">
        啟用後每日於指定時間，自動匯出今年與明年各自同時產出 JSON 與 Excel 兩份，共四檔。
      </div>
      <div v-if="exportDialog.scheduleLastRunAt || exportDialog.scheduleLastRunStatus" class="export-status">
        上次排程執行：{{ exportDialog.scheduleLastRunAt || '—' }}　{{ exportDialog.scheduleLastRunStatus || '' }}
      </div>

      <template #footer>
        <el-button @click="exportDialog.visible = false">關閉</el-button>
        <el-button type="primary" :icon="Download" :loading="exportDialog.exporting" @click="doExport">匯出到目錄</el-button>
      </template>
    </el-dialog>

    <!--
      輸出資料夾選擇器（檔案總管式樹狀，比照 AssetHistoryView）。
      本頁它會在「已開啟的匯出對話框」內再開一層——兩個 el-dialog 是平行的兄弟節點（不是真的巢狀），
      既有的本機選擇器已在同樣情境下正常疊加，故不需 append-to-body 或 z-index 特別處理。
    -->
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

    <!-- US DST Info -->
    <el-card style="margin-top:20px">
      <template #header><span class="section-title">美國日光節約時間說明</span></template>
      <el-descriptions :column="1" border class="dst-desc">
        <el-descriptions-item label="夏令時間 (DST)">3月第二個週日 02:00 起 ~ 11月第一個週日 02:00 止</el-descriptions-item>
        <el-descriptions-item label="夏令交易時間">美東 09:30~16:00 = 台灣 21:30~04:00 (隔日)</el-descriptions-item>
        <el-descriptions-item label="標準交易時間">美東 09:30~16:00 = 台灣 22:30~05:00 (隔日)</el-descriptions-item>
        <el-descriptions-item :label="`${calendarYear} 夏令期間`">{{ dstRange }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import {
  calendarDayClass,
  calendarEntry,
  calendarExportYears,
  hasLocalExport,
  isMaxMonth,
  isMinMonth,
  marketTradingFlag,
  moveCalendarMonth,
  taipeiDateParts,
  unavailableMarketKeys
} from '@/utils/tradingCalendarYearWindow'
import { useAuthStore } from '@/stores/authStore'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import { Download, FolderOpened } from '@element-plus/icons-vue'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsFlag from '@/components/UsFlag.vue'

const status = ref({ twMarketOpen: false, usMarketOpen: false, ukMarketOpen: false, twTime: '', usTime: '', ukTime: '' })
const initialTaipeiDate = taipeiDateParts()
const serverYear = ref(initialTaipeiDate.year)
const availableYears = ref([])
const minYear = ref(null)
const maxYear = ref(null)
const calendarYear = ref(serverYear.value)
const calendarMonth = ref(initialTaipeiDate.month)
const selectedDate = ref(null)

// 匯出到指定路徑（Requirement 37）＋每日排程（Task 185）
const exportDialog = reactive({
  visible: false, subpath: 'input', baseDir: '', exporting: false, lastResult: null,
  scheduleEnabled: false, scheduleTime: '08:00', savingSchedule: false, scheduleLastRunAt: null, scheduleLastRunStatus: null,
  // Drive 同步（Task 244）：目的地存在排程設定列上，與上方 subpath（本次匯出到哪）刻意不同
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: ''
})
// 輸出資料夾選擇器（檔案總管式樹狀，比照 AssetHistoryView）
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
const dirPicker = reactive({
  visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const auth = useAuthStore()

const dirPickerTitle = computed(() =>
  dirPicker.mode === 'gdrive' ? '選擇 Google Drive 資料夾' : '選擇輸出資料夾')

// 兩種 mode 的分隔符不同：Drive 基底是 `remote:`（已含冒號，後面直接接子路徑），
// 本機基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財`——
// 多一個斜線、不是 rclone 的路徑格式，會誤導使用者。
const dirPickerPreview = computed(() => {
  const isGdrive = dirPicker.mode === 'gdrive'
  const base = dirPicker.baseDir
    || (isGdrive ? (exportDialog.gdriveRemote || 'GDriveOutput') + ':' : (exportDialog.baseDir || '/home/steven'))
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  const joined = parts.join('/')
  if (!joined) return base
  return isGdrive ? base + joined : base + '/' + joined
})
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

// 每年同時保存 holidays 與 authority availability，不能把最近一次請求的資料套到另一年。
const holidayCache = ref({})
const holidaysLoading = ref(false)
const marketLabels = { tw: '台股', us: '美股', uk: '英股' }
const currentCalendarEntry = computed(() => calendarEntry(holidayCache.value, calendarYear.value))
const unavailableMarkets = computed(() => unavailableMarketKeys(currentCalendarEntry.value))
const atMinMonth = computed(() => isMinMonth(calendarYear.value, calendarMonth.value, minYear.value))
const atMaxMonth = computed(() => isMaxMonth(calendarYear.value, calendarMonth.value, maxYear.value))
const exportYears = computed(() => calendarExportYears(availableYears.value, serverYear.value))

async function loadHolidays(year, initial = false) {
  if (year != null && holidayCache.value[year]) return
  holidaysLoading.value = true
  try {
    // BFF 一支端點：holidays + marketStatus
    const res = await bffApi.tradingCalendar.get(initial ? undefined : year)
    const resolvedYear = res.year ?? year
    serverYear.value = initial ? resolvedYear : serverYear.value
    if (Array.isArray(res.availableYears)) availableYears.value = res.availableYears
    minYear.value = res.minYear ?? availableYears.value[0] ?? resolvedYear - 1
    maxYear.value = res.maxYear ?? availableYears.value.at(-1) ?? resolvedYear + 1
    if (initial) {
      calendarYear.value = resolvedYear
      calendarMonth.value = taipeiDateParts().month
    }
    holidayCache.value = { ...holidayCache.value, [resolvedYear]: {
      holidays: res.holidays ?? { tw: {}, us: {}, uk: {} },
      availability: res.availability ?? { tw: 'UNAVAILABLE', us: 'UNAVAILABLE', uk: 'UNAVAILABLE' }
    } }
    if (res.marketStatus) status.value = res.marketStatus
  } catch (e) {
    const failedYear = year ?? calendarYear.value
    holidayCache.value = { ...holidayCache.value, [failedYear]: {
      holidays: { tw: {}, us: {}, uk: {} },
      availability: { tw: 'UNAVAILABLE', us: 'UNAVAILABLE', uk: 'UNAVAILABLE' }
    } }
  } finally {
    holidaysLoading.value = false
  }
}

watch(calendarYear, (y) => loadHolidays(y), { immediate: false })

onMounted(async () => {
  await loadHolidays(undefined, true)
  setInterval(async () => {
    try { status.value = await bffApi.tradingCalendar.marketStatus() } catch {}
  }, 60000)
})

const twTimeDisplay = computed(() => {
  if (!status.value.twTime) return '-'
  return dayjs(status.value.twTime).format('YYYY/MM/DD (dd) HH:mm:ss')
})

const usTimeDisplay = computed(() => {
  if (!status.value.usTime) return '-'
  return dayjs(status.value.usTime).format('YYYY/MM/DD (dd) HH:mm:ss')
})

const ukTimeDisplay = computed(() => {
  if (!status.value.ukTime) return '-'
  return dayjs(status.value.ukTime).format('YYYY/MM/DD (dd) HH:mm:ss')
})

// US DST calculation
function getDstStart(year) {
  // 3月第二個週日
  let d = dayjs(`${year}-03-01`)
  let count = 0
  while (count < 2) {
    if (d.day() === 0) count++
    if (count < 2) d = d.add(1, 'day')
  }
  return d
}

function getDstEnd(year) {
  // 11月第一個週日
  let d = dayjs(`${year}-11-01`)
  while (d.day() !== 0) {
    d = d.add(1, 'day')
  }
  return d
}

const isDst = computed(() => {
  const now = dayjs()
  const start = getDstStart(now.year())
  const end = getDstEnd(now.year())
  return now.isAfter(start) && now.isBefore(end)
})

const usSessionDisplay = computed(() => {
  return isDst.value
    ? '週一～五 09:30~16:00 (美東夏令 UTC-4)'
    : '週一～五 09:30~16:00 (美東標準 UTC-5)'
})

const dstRange = computed(() => {
  const y = calendarYear.value
  const s = getDstStart(y)
  const e = getDstEnd(y)
  return `${s.format('MM/DD')} ~ ${e.format('MM/DD')}`
})

// Calendar generation
const calendarWeeks = computed(() => {
  const y = calendarYear.value
  const m = calendarMonth.value
  const firstDay = dayjs(`${y}-${String(m).padStart(2,'0')}-01`)
  const daysInMonth = firstDay.daysInMonth()
  const startDow = firstDay.day() // 0=Sun

  const cached = currentCalendarEntry.value
  const twHolidays = cached.holidays?.tw || {}
  const usHolidays = cached.holidays?.us || {}
  const ukHolidays = cached.holidays?.uk || {}
  const today = taipeiDateParts().date

  const weeks = []
  let week = []

  // Fill leading blanks
  for (let i = 0; i < startDow; i++) {
    week.push({})
  }

  for (let d = 1; d <= daysInMonth; d++) {
    const date = dayjs(`${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`)
    const dateStr = date.format('YYYY-MM-DD')
    const dow = date.day()
    const isWeekend = dow === 0 || dow === 6

    // 台股交易日: 週一～五，非台灣假日
    const tw = marketTradingFlag(cached, 'tw', isWeekend, dateStr)
    // 美股交易日: 週一～五，非美國假日
    const us = marketTradingFlag(cached, 'us', isWeekend, dateStr)
    // 英股交易日: 週一～五，非英國銀行假日（LSE）
    const uk = marketTradingFlag(cached, 'uk', isWeekend, dateStr)

    week.push({
      day: d,
      date: dateStr,
      dow,
      isToday: dateStr === today,
      tw,
      us,
      uk,
      isWeekend,
      twHoliday: twHolidays[dateStr] || null,
      usHoliday: usHolidays[dateStr] || null,
      ukHoliday: ukHolidays[dateStr] || null
    })

    if (week.length === 7) {
      weeks.push(week)
      week = []
    }
  }

  // Fill trailing blanks
  if (week.length > 0) {
    while (week.length < 7) week.push({})
    weeks.push(week)
  }

  return weeks
})

function prevMonth() {
  const target = moveCalendarMonth(
    calendarYear.value, calendarMonth.value, -1, minYear.value, maxYear.value)
  calendarYear.value = target.year
  calendarMonth.value = target.month
}

function nextMonth() {
  const target = moveCalendarMonth(
    calendarYear.value, calendarMonth.value, 1, minYear.value, maxYear.value)
  calendarYear.value = target.year
  calendarMonth.value = target.month
}

function goToday() {
  calendarYear.value = serverYear.value
  calendarMonth.value = taipeiDateParts().month
}

// ===== 匯出到指定路徑（Requirement 37）＋每日排程（Task 185）=====
async function openExportDialog() {
  exportDialog.lastResult = null
  exportDialog.visible = true
  try {
    const res = await bffApi.tradingCalendar.browseExportDir('')
    exportDialog.baseDir = res.baseDir || ''
  } catch { /* 顯示提示用，失敗不影響匯出 */ }
  loadSchedule().catch(() => {})
}

// 載入排程設定：若已有設定，帶入格式／資料夾／時間／啟用狀態
async function loadSchedule() {
  const s = await bffApi.tradingCalendar.getExportSchedule()
  exportDialog.scheduleEnabled = !!s.enabled
  if (s.outputSubpath != null) exportDialog.subpath = s.outputSubpath
  if (s.baseDir) exportDialog.baseDir = s.baseDir
  const h = s.runHour ?? 8, m = s.runMinute ?? 0
  exportDialog.scheduleTime = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
  exportDialog.scheduleLastRunAt = s.lastRunAt ?? null
  exportDialog.scheduleLastRunStatus = s.lastRunStatus ?? null
  applyGdrive(s)
}

/** 把後端回的 Drive 欄位寫回本地狀態（讀取一律不驗證，不合法值也照顯示供使用者修正）。 */
function applyGdrive(s) {
  exportDialog.gdriveEnabled = !!s.gdriveEnabled
  exportDialog.gdriveSubpath = s.gdriveSubpath || ''
  exportDialog.gdriveRemote = s.gdriveRemote || ''
  exportDialog.gdriveLastRunAt = s.gdriveLastRunAt || null
  exportDialog.gdriveLastStatus = s.gdriveLastStatus || ''
}

async function saveSchedule() {
  // 前後端都擋：開了同步卻沒選資料夾，後端也會回 400
  if (exportDialog.gdriveEnabled && !(exportDialog.gdriveSubpath || '').trim()) {
    ElMessage.warning('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
    return
  }
  exportDialog.savingSchedule = true
  try {
    const [h, m] = (exportDialog.scheduleTime || '08:00').split(':').map(Number)
    const s = await bffApi.tradingCalendar.updateExportSchedule({
      enabled: exportDialog.scheduleEnabled,
      runHour: h,
      runMinute: m,
      outputSubpath: exportDialog.subpath,
      gdriveEnabled: exportDialog.gdriveEnabled,
      gdriveSubpath: (exportDialog.gdriveSubpath || '').trim()
    })
    exportDialog.scheduleLastRunAt = s.lastRunAt ?? exportDialog.scheduleLastRunAt
    exportDialog.scheduleLastRunStatus = s.lastRunStatus ?? exportDialog.scheduleLastRunStatus
    applyGdrive(s)
    ElMessage.success('排程設定已儲存')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)
  } catch (e) {
    ElMessage.error('排程儲存失敗，請確認格式與目錄權限')
  } finally {
    exportDialog.savingSchedule = false
  }
}

function openDirPicker(mode = 'local') {
  dirPicker.mode = mode
  dirPicker.baseDir = ''         // 兩種 mode 的基底不同，重開時一律重新取
  dirPicker.error = ''
  dirPicker.picked = (mode === 'gdrive' ? exportDialog.gdriveSubpath : exportDialog.subpath) || ''
  dirPicker.newSub = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  const isGdrive = dirPicker.mode === 'gdrive'
  const browse = isGdrive
    ? bffApi.tradingCalendar.browseGdriveExportDir
    : bffApi.tradingCalendar.browseExportDir
  try {
    if (node.level === 0) {
      const res = await browse('')
      dirPicker.baseDir = res.baseDir || ''
      dirPicker.error = ''
      // Drive 的 baseDir 是 `remote:`，不可寫進本機的 exportDialog.baseDir（那是家目錄提示）
      if (!isGdrive) exportDialog.baseDir = res.baseDir || ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await browse(node.data.path || '')
    resolve((res.directories || []).map(d => ({ name: d.name, path: d.path, key: d.path, leaf: false })))
  } catch (e) {
    // Drive 端失敗要顯示原因（remote 未設定／授權失效）；空樹會被誤讀為「Drive 裡沒有資料夾」
    if (isGdrive) {
      dirPicker.error = e?.response?.data?.detail || e?.message || 'Google Drive 資料夾讀取失敗'
    }
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  if (dirPicker.mode === 'gdrive') exportDialog.gdriveSubpath = p
  else exportDialog.subpath = p
  dirPicker.visible = false
}

async function doExport() {
  exportDialog.exporting = true
  try {
    const r = await bffApi.tradingCalendar.exportToDir(exportDialog.subpath)
    exportDialog.lastResult = r
    // 手動匯出也會同步 Drive（Task 244.5.1）；狀態即時反映在「上次上傳」
    if (r.gdriveStatus) {
      exportDialog.gdriveLastStatus = r.gdriveStatus
      exportDialog.gdriveLastRunAt = dayjs().format('YYYY-MM-DD HH:mm:ss')
    }
    for (const result of r.results || []) {
      showDualExportResult({ jsonPath: result.jsonPath, xlsxPath: result.path,
        gdriveStatus: result.gdriveStatus, localStatus: result.localStatus, prefix: `${result.year} 年` })
    }
  } catch (e) {
    ElMessage.error('匯出失敗，請確認輸出資料夾')
  } finally {
    exportDialog.exporting = false
  }
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }

.market-info { display: flex; flex-direction: column; gap: 12px; }

.status-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.status-dot { width: 12px; height: 12px; border-radius: 50%; display: inline-block; }
.status-dot.open { background: #16a34a; box-shadow: 0 0 6px #16a34a; }
.status-dot.closed { background: #94a3b8; }
.status-text { font-size: 16px; font-weight: 600; }

.info-item { display: flex; gap: 12px; }
.info-item .label { color: #64748b; min-width: 80px; }
.info-item .value { font-weight: 500; }

/* Legend */
.legend { display: flex; gap: 20px; font-size: 13px; color: #475569; }
.legend-item { display: flex; align-items: center; gap: 4px; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.legend-dot.tw { background: #ef4444; }
.legend-dot.us { background: #3b82f6; }
.legend-dot.both { background: linear-gradient(135deg, #ef4444 50%, #3b82f6 50%); }
.legend-dot.holiday { background: #e2e8f0; }

/* Calendar */
.cal-table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.cal-table th { padding: 8px; text-align: center; color: #64748b; font-weight: 600; font-size: 13px; border-bottom: 2px solid #e2e8f0; }
.cal-table td { padding: 4px; vertical-align: top; min-height: 70px; height: 70px; border: 1px solid #f1f5f9; cursor: pointer; transition: background 0.15s; }
.cal-table td:hover:not(.empty) { background: #f8fafc; }
.cal-table td.empty { background: #fafafa; cursor: default; }
.cal-table td.weekend { background: #fef2f2; }
.cal-table td.holiday { background: #fff7ed; }
.cal-table td.both { background: #f0fdf4; }

.cal-cell { display: flex; flex-direction: column; align-items: center; gap: 2px; min-height: 60px; }
.cal-day { font-size: 14px; font-weight: 600; color: #1e293b; }
.cal-day.today { background: #3b82f6; color: white; border-radius: 50%; width: 26px; height: 26px; display: flex; align-items: center; justify-content: center; }

.cal-tags { display: flex; gap: 3px; align-items: center; line-height: 1; }
.cal-tags .dot { width: 8px; height: 8px; border-radius: 50%; }
.cal-tags .dot.tw { background: #ef4444; }
.cal-tags .dot.us { background: #3b82f6; }

.cal-holiday { text-align: center; line-height: 1.2; width: 100%; }
.cal-holiday small { font-size: 10px; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }

/* DST descriptions */
.dst-desc :deep(.el-descriptions__label) { font-size: 15px; font-weight: 600; width: 200px; }
.dst-desc :deep(.el-descriptions__content) { font-size: 15px; }

/* 匯出對話框（Requirement 37） */
.export-hint { font-size: 12px; color: #94a3b8; line-height: 1.6; }
.export-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.export-status { margin-top: 10px; font-size: 12px; color: #64748b; }
.export-status code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
</style>
