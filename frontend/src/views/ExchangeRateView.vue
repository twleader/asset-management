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

      <el-dialog v-model="scheduleDialog.visible" :title="scheduleDialog.mode === 'create' ? '新增匯率匯出排程' : '編輯匯率匯出排程'" width="680px" :close-on-click-modal="!scheduleMutationBusy" :close-on-press-escape="!scheduleMutationBusy" :show-close="!scheduleMutationBusy" :before-close="closeScheduleDialog">
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
          以主機家目錄 <code>{{ baseDir || '/home/steven' }}</code> 為根。每筆排程於自己的時間匯出所選匯率範圍，產生
          <code>台幣兌美元_{使用者ID}[_{名稱}]_YYYYMMDD.xlsx</code> 與同名 <code>.json</code>。
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
  fetchData()
  // 排程設定與匯率各自獨立，並行載入；設定讀取失敗不影響圖表
  loadSchedules()
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

// ===== 獨立排程自動匯出 =====

async function loadSchedules() {
  if (scheduleLoadInFlight.value) return false
  scheduleLoadInFlight.value = true
  scheduleLoading.value = true
  scheduleLoadError.value = ''
  try {
    const response = requireExportScheduleList(await bffApi.exchangeRate.getExportSchedules(), { requireRangeMonths: true })
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
      ? await bffApi.exchangeRate.createExportSchedule(payload)
      : await bffApi.exchangeRate.updateExportSchedule(draft.id, payload)
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
    const response = await bffApi.exchangeRate.deleteExportSchedule(item.id)
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
    const result = await bffApi.exchangeRate.runExportScheduleNow(item.id)
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
