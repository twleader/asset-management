<template>
  <div>
    <!-- KPI Cards -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">最新匯率 (USD/TWD)</div>
          <div class="kpi-value">{{ latestRate ? latestRate.midRate.toFixed(4) : '-' }}</div>
          <div class="kpi-sub" v-if="latestRate">{{ latestRate.rateDate }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">即期買入</div>
          <div class="kpi-value" style="color:#16a34a">{{ latestRate ? latestRate.buyRate?.toFixed(4) : '-' }}</div>
          <div class="kpi-sub">銀行買入美元</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">即期賣出</div>
          <div class="kpi-value" style="color:#dc2626">{{ latestRate ? latestRate.sellRate?.toFixed(4) : '-' }}</div>
          <div class="kpi-sub">銀行賣出美元</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">資料筆數</div>
          <div class="kpi-value">{{ rateData.length.toLocaleString() }}</div>
          <div class="kpi-sub" v-if="rateData.length">{{ rateData[0].rateDate }} ~ {{ rateData[rateData.length-1].rateDate }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Main Chart -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">台幣兌美元走勢 (USD/TWD)</span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button-group>
              <el-button size="small" v-for="r in rangeOptions" :key="r.key"
                :type="selectedRange === r.key ? 'primary' : ''"
                @click="selectedRange = r.key">{{ r.label }}</el-button>
            </el-button-group>
            <el-button size="small" @click="onBackfill" :loading="backfilling">回補資料</el-button>
            <el-button size="small" type="primary" @click="openExport">匯出 Excel</el-button>
          </div>
        </div>
      </template>
      <v-chart :option="mainChartOption" style="height:420px" autoresize />
    </el-card>

    <!-- Stats -->
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">區間統計</span></template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="最高">{{ stats.max?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最高日期">{{ stats.maxDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最低">{{ stats.min?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最低日期">{{ stats.minDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="平均">{{ stats.avg?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="波動幅度">{{ stats.range?.toFixed(4) || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">買入/賣出價差走勢</span></template>
          <v-chart :option="spreadChartOption" style="height:250px" autoresize />
        </el-card>
      </el-col>
    </el-row>

    <!-- 排程自動匯出設定（Requirement 42 / Task 204） -->
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
        <!--
          Google Drive 同步（Requirement 51 / Task 243）：本機一律照寫，這裡只是額外多上傳一份副本。
          僅「主要管理者」可見可設——rclone remote 全機只有一份且綁定某個 Google 帳號，
          若其他使用者能啟用，他的財務報表會被上傳到那個帳號的雲端硬碟。真正的閘門在後端。
        -->
        <el-form-item v-if="auth.isConfiguredAdmin" label="同步 Google Drive">
          <div style="display:flex; flex-direction:column; gap:6px">
            <div style="display:flex; align-items:center; gap:12px">
              <el-switch v-model="schedule.gdriveEnabled" />
              <el-input
                v-model="schedule.gdriveSubpath"
                readonly
                placeholder="（尚未選擇 Drive 資料夾）"
                :disabled="!schedule.gdriveEnabled"
                style="width:260px"
              >
                <template #append>
                  <el-button :disabled="!schedule.gdriveEnabled" @click="openDirPicker('gdrive')">選擇</el-button>
                </template>
              </el-input>
            </div>
            <div style="font-size:12px; color:var(--el-text-color-secondary); line-height:1.7">
              開啟後除了寫入上面的本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到 Google Drive 的所選資料夾；
              <strong>本機那一份永遠照寫、不受影響</strong>。
              <template v-if="schedule.gdriveEnabled && schedule.gdriveSubpath">
                <br />Drive 落點：<code>{{ schedule.gdriveRemote || 'GDriveOutput' }}:{{ schedule.gdriveSubpath }}</code>
              </template>
              <br />上次上傳：
              <template v-if="schedule.gdriveLastRunAt">
                {{ schedule.gdriveLastRunAt }} — <code>{{ schedule.gdriveLastStatus || '—' }}</code>
              </template>
              <template v-else>—（尚未執行過）</template>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <div class="schedule-hint">
        以主機家目錄 <code>{{ schedule.baseDir || '/home/steven' }}</code> 為根（對映主機
        <code>/Users/steven</code>）。按上方「選擇」開啟檔案總管式選擇器挑選子資料夾；例如選 <code>input</code> →
        主機 <code>/Users/steven/input</code>。每日於指定時間匯出台幣兌美元匯率為
        <code>台幣兌美元_{使用者ID}_YYYYMMDD.xlsx</code>（內容同上方「匯出 Excel」）。
        匯出範圍以<b>執行當日往前推</b>計算，故每日產出會隨時間滾動。
      </div>
      <div v-if="schedule.lastRunAt || schedule.lastRunStatus" class="schedule-status">
        上次執行：{{ schedule.lastRunAt || '—' }}　{{ schedule.lastRunStatus || '' }}
      </div>
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
    <el-dialog v-model="exportDialog.visible" title="匯出台幣兌美元匯率" width="480px">
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
            <b>日期／即期買入／即期賣出／中間價</b>，依日期遞增；
            當日無牌告則留空。
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
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi, apiErrorMessage } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { useAuthStore } from '@/stores/authStore'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent])

const rateData = ref([])
const backfilling = ref(false)
const selectedRange = ref('all')

const rangeOptions = [
  { key: '1m', label: '1月' },
  { key: '3m', label: '3月' },
  { key: '6m', label: '6月' },
  { key: '1y', label: '1年' },
  { key: '2y', label: '2年' },
  { key: '3y', label: '3年' },
  { key: '5y', label: '5年' },
  { key: 'all', label: '全部' },
]

const exporting = ref(false)
const exportDialog = reactive({ visible: false, range: [] })

// File System Access API：可讓使用者自選存檔目錄；Safari／舊版瀏覽器沒有，退回一般下載
const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window

// 排程自動匯出設定（Requirement 42 / Task 204）
const schedule = reactive({
  // Drive 同步（Task 243）；gdriveRemote 是後端給的顯示值，不入庫
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: '',
 
  enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input',
  rangeMonths: 120, lastRunAt: null, lastRunStatus: null, baseDir: ''
})
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
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
  fetchData()
  // 排程設定與匯率各自獨立，並行載入；設定讀取失敗不影響圖表
  loadSchedule().catch(() => {})
})

async function fetchData() {
  try {
    // BFF 一支端點：自動 refresh + 回傳 10 年歷史
    const res = await bffApi.exchangeRate.getHistory('USD')
    rateData.value = (res.rates ?? []).map(d => ({
      ...d,
      midRate: Number(d.midRate),
      buyRate: d.buyRate ? Number(d.buyRate) : null,
      sellRate: d.sellRate ? Number(d.sellRate) : null
    }))
  } catch {}
}

async function onBackfill() {
  backfilling.value = true
  try {
    const result = await bffApi.exchangeRate.backfill('USD')
    ElMessage.success(`匯率回補完成，新增 ${result.backfilled} 筆`)
    await fetchData()
  } catch {} finally {
    backfilling.value = false
  }
}

const latestRate = computed(() => {
  if (!rateData.value.length) return null
  return rateData.value[rateData.value.length - 1]
})

const filteredData = computed(() => {
  if (!rateData.value.length) return []
  if (selectedRange.value === 'all') return rateData.value

  const now = dayjs()
  const map = { '1m': 1, '3m': 3, '6m': 6, '1y': 12, '2y': 24, '3y': 36, '5y': 60 }
  const months = map[selectedRange.value] || 999
  const cutoff = now.subtract(months, 'month').format('YYYY-MM-DD')
  return rateData.value.filter(d => d.rateDate >= cutoff)
})

const stats = computed(() => {
  const data = filteredData.value
  if (!data.length) return {}
  const rates = data.map(d => d.midRate)
  const max = Math.max(...rates)
  const min = Math.min(...rates)
  const avg = rates.reduce((a, b) => a + b, 0) / rates.length
  const maxDate = data.find(d => d.midRate === max)?.rateDate
  const minDate = data.find(d => d.midRate === min)?.rateDate
  return { max, min, avg, range: max - min, maxDate, minDate }
})

const mainChartOption = computed(() => {
  const data = filteredData.value
  if (!data.length) return {}
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const p = params[0]
        const d = data[p.dataIndex]
        let s = `<strong>${p.axisValue}</strong><br/>`
        s += `中間價: ${d.midRate.toFixed(4)}<br/>`
        if (d.buyRate) s += `買入: ${d.buyRate.toFixed(4)}<br/>`
        if (d.sellRate) s += `賣出: ${d.sellRate.toFixed(4)}`
        return s
      }
    },
    grid: { left: 60, right: 30, top: 30, bottom: 70 },
    xAxis: {
      type: 'category',
      data: data.map(d => d.rateDate),
      axisLabel: { rotate: 30, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { formatter: v => v.toFixed(2) }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
    ],
    series: [{
      name: 'USD/TWD',
      type: 'line',
      data: data.map(d => d.midRate),
      smooth: false,
      symbol: 'none',
      lineStyle: { width: 1.5, color: '#3b82f6' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(59,130,246,0.2)' },
            { offset: 1, color: 'rgba(59,130,246,0)' }
          ]
        }
      },
      markPoint: {
        data: [
          { type: 'max', name: '最高' },
          { type: 'min', name: '最低' }
        ],
        symbolSize: 50,
        label: { formatter: p => p.value.toFixed(2) }
      },
      markLine: {
        data: [{ type: 'average', name: '平均' }],
        label: { formatter: p => p.value.toFixed(2) }
      }
    }]
  }
})

// ===== 手動匯出（指定區間、瀏覽器另存）=====

function openExport() {
  // 預設帶入目前圖表所選區間
  const data = filteredData.value
  const start = data.length ? data[0].rateDate : dayjs().subtract(10, 'year').format('YYYY-MM-DD')
  const end = data.length ? data[data.length - 1].rateDate : dayjs().format('YYYY-MM-DD')
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
    const blob = await bffApi.exchangeRate.exportExcel(start, end)
    const filename = `台幣兌美元_${start.replaceAll('-', '')}_${end.replaceAll('-', '')}.xlsx`
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

async function loadSchedule() {
  const s = await bffApi.exchangeRate.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.runHour = s.runHour ?? 8
  schedule.runMinute = s.runMinute ?? 0
  schedule.outputSubpath = s.outputSubpath ?? 'input'
  // 後端 null（未設定過的舊列）＝全部十年，映射成 120 讓下拉正確顯示
  schedule.rangeMonths = s.rangeMonths ?? ALL_TEN_YEARS_MONTHS
  schedule.lastRunAt = s.lastRunAt ?? null
  schedule.lastRunStatus = s.lastRunStatus ?? null
  schedule.baseDir = s.baseDir ?? ''
  applyGdrive(s)
  scheduleTime.value = `${String(schedule.runHour).padStart(2, '0')}:${String(schedule.runMinute).padStart(2, '0')}`
}

async function saveSchedule() {
  // 前後端都擋：開了同步卻沒選資料夾，後端也會回 400
  if (schedule.gdriveEnabled && !(schedule.gdriveSubpath || '').trim()) {
    ElMessage.warning('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
    return
  }
  savingSchedule.value = true
  try {
    const [h, m] = (scheduleTime.value || '08:00').split(':').map(Number)
    const s = await bffApi.exchangeRate.updateExportSchedule({
      enabled: schedule.enabled,
      gdriveEnabled: schedule.gdriveEnabled,
      gdriveSubpath: (schedule.gdriveSubpath || '').trim(),
      runHour: h,
      runMinute: m,
      outputSubpath: (schedule.outputSubpath || 'input').trim(),
      rangeMonths: schedule.rangeMonths
    })
    schedule.runHour = s.runHour ?? h
    schedule.runMinute = s.runMinute ?? m
    schedule.outputSubpath = s.outputSubpath ?? schedule.outputSubpath
    schedule.rangeMonths = s.rangeMonths ?? ALL_TEN_YEARS_MONTHS
    schedule.baseDir = s.baseDir ?? schedule.baseDir
    applyGdrive(s)
    ElMessage.success('排程設定已儲存')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)
  } catch (e) {
    ElMessage.error(apiErrorMessage(e, '儲存失敗，請稍後再試'))
  } finally {
    savingSchedule.value = false
  }
}

async function handleRunNow() {
  runningNow.value = true
  try {
    const r = await bffApi.exchangeRate.runExportNow()
    ElMessage.success(`已匯出到：${r.path}`)
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
  dirPicker.mode = mode
  dirPicker.picked = (mode === 'gdrive' ? schedule.gdriveSubpath : schedule.outputSubpath) || ''
  dirPicker.newSub = ''
  dirPicker.baseDir = ''         // 兩種 mode 的基底不同，重開時一律重新取
  dirPicker.error = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.exchangeRate.browseGdriveExportDir
    : bffApi.exchangeRate.browseExportDir
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
  let p = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) p = p ? `${p}/${sub}` : sub
  if (dirPicker.mode === 'gdrive') schedule.gdriveSubpath = p
  else schedule.outputSubpath = p
  dirPicker.visible = false
}

const spreadChartOption = computed(() => {
  const data = filteredData.value.filter(d => d.buyRate && d.sellRate)
  if (!data.length) return {}
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 10, bottom: 40 },
    xAxis: { type: 'category', data: data.map(d => d.rateDate), axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', scale: true, axisLabel: { formatter: v => v.toFixed(2) } },
    series: [
      { name: '買入', type: 'line', data: data.map(d => d.buyRate), symbol: 'none', lineStyle: { width: 1, color: '#16a34a' } },
      { name: '賣出', type: 'line', data: data.map(d => d.sellRate), symbol: 'none', lineStyle: { width: 1, color: '#dc2626' } }
    ]
  }
})
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.kpi-card { text-align: center; }
.kpi-label { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.kpi-value { font-size: 24px; font-weight: 700; color: #1e293b; }
.kpi-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }
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
