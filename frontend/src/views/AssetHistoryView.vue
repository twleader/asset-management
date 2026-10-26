<template>
  <div>
    <!-- Summary Table -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">📅 歷年資產管理</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="exporting" @click="handleExport">匯出 Excel</el-button>
            <el-button size="small" :icon="Refresh" :loading="recalculating" @click="handleRecalcDividends">重算配息</el-button>
            <el-button size="small" type="primary" :icon="Plus" @click="$router.push('/snapshots/new')">新增快照</el-button>
          </div>
        </div>
      </template>
      <el-table :data="history" size="small" stripe>
        <el-table-column prop="snapshotDate" label="日期" width="110" />
        <el-table-column label="台幣存款" align="right" :formatter="(r) => fmt(r.totalTwdDeposit)" />
        <el-table-column label="美元存款" align="right" :formatter="(r) => fmt(r.totalUsdDeposit)" />
        <el-table-column label="信託基金" align="right" :formatter="(r) => fmt(r.totalFundValue)" />
        <el-table-column label="台股" align="right" min-width="100" :formatter="(r) => fmt(r.totalTwStockValue)" />
        <el-table-column label="美股" align="right" :formatter="(r) => fmt(r.totalUsStockValue)" />
        <el-table-column label="英股" align="right" :formatter="(r) => fmt(r.totalUkStockValue)" />
        <el-table-column label="資產總計" align="right" min-width="120">
          <template #default="{ row }">
            <strong>{{ fmt(row.totalAssets) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="增加金額" align="right">
          <template #default="{ row }">
            <span v-if="row.increase != null" :class="row.increase >= 0 ? 'profit' : 'loss'">
              {{ fmt(row.increase) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="增幅" align="right" width="80">
          <template #default="{ row }">
            <span v-if="row.increaseRate != null" :class="row.increaseRate >= 0 ? 'profit' : 'loss'">
              {{ pct(row.increaseRate) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="投資比例" align="right" width="90"
          :formatter="(r) => r.investmentRate ? pct(r.investmentRate) : '-'" />
        <el-table-column label="預估配息" align="right"
          :formatter="(r) => r.estimatedAnnualDividend ? fmt(r.estimatedAnnualDividend) : '-'" />
        <el-table-column label="已實現損益" align="right">
          <template #default="{ row }">
            {{ isLastOfYear(row) && row.realizedGain ? fmt(row.realizedGain) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary"
              @click="$router.push(`/snapshots/${row.id}/edit`)">管理</el-button>
            <el-popconfirm title="確定刪除此快照？" @confirm="deleteSnapshot(row.id)">
              <template #reference>
                <el-button size="small" :icon="Delete" link type="danger">刪除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 獨立排程匯出設定（Task 511） -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出最新資產</span>
          <el-button size="small" type="primary" :icon="Plus" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError || schedules.length >= MAX_EXPORT_SCHEDULES" @click="openNewSchedule">新增排程</el-button>
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
            <el-button size="small" :icon="Download" :loading="scheduleMutationBusy && activeScheduleId === item.id && activeScheduleAction === 'run'" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError" @click="handleRunNow(item)">立即匯出</el-button>
            <el-button size="small" type="primary" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError" @click="openEditSchedule(item)">編輯設定</el-button>
            <el-popconfirm title="確定刪除這筆排程？" width="200" @confirm="handleDeleteSchedule(item)">
              <template #reference><el-button size="small" type="danger" :icon="Delete" :disabled="scheduleLoading || scheduleMutationBusy || !!scheduleLoadError">刪除</el-button></template>
            </el-popconfirm>
          </div>
        </div>
        <div>每日 {{ formatExportScheduleTime(item.runHour, item.runMinute) }}；本機資料夾 {{ item.outputSubpath || '家目錄根' }}</div>
        <div class="schedule-status">上次執行：{{ item.lastRunAt || '—' }}　{{ item.lastRunStatus || '' }}</div>
        <div v-if="auth.isConfiguredAdmin" class="schedule-status">
          Google Drive：{{ item.gdriveEnabled ? '已啟用' : '停用' }}；{{ item.gdriveSubpath || '未選資料夾' }}；上次上傳 {{ item.gdriveLastRunAt || '—' }}　{{ item.gdriveLastStatus || '' }}
        </div>
      </div>

      <el-dialog v-model="scheduleDialog.visible" :title="scheduleDialog.mode === 'create' ? '新增資產匯出排程' : '編輯資產匯出排程'" width="680px" :close-on-click-modal="!scheduleMutationBusy" :close-on-press-escape="!scheduleMutationBusy" :show-close="!scheduleMutationBusy" :before-close="closeScheduleDialog">
        <el-form v-if="scheduleDialog.draft" :disabled="scheduleMutationBusy" :inline="true" label-width="100px" class="schedule-form">
          <el-form-item label="名稱"><el-input v-model="scheduleDialog.draft.name" maxlength="20" show-word-limit style="width:220px" placeholder="（選填，會進檔名）" /></el-form-item>
          <el-form-item label="啟用每日排程"><el-switch v-model="scheduleDialog.draft.enabled" /></el-form-item>
          <el-form-item label="每日執行時間"><el-time-picker v-model="scheduleDialog.draft._time" format="HH:mm" value-format="HH:mm" placeholder="時:分" style="width:130px" /></el-form-item>
          <el-form-item label="輸出資料夾">
            <el-input v-model="scheduleDialog.draft.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
              <template #append><el-button :disabled="scheduleMutationBusy" :icon="FolderOpened" @click="openDirPicker('local')">選擇</el-button></template>
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
          以主機家目錄 <code>{{ baseDir || '/home/steven' }}</code> 為根。每筆排程於自己的時間產生
          <code>資產總覽_{使用者ID}[_{名稱}]_YYYYMMDD.xlsx</code> 與同名 <code>.json</code>。
          同一使用者、日期、資料夾與名稱會覆寫；若要保留不同版本，請使用不同名稱或資料夾。
          空白名稱會沿用舊檔名。取消會丟棄這份草稿。
        </div>
        <div class="schedule-status">上次執行：{{ scheduleDialog.draft?.lastRunAt || '—' }}　{{ scheduleDialog.draft?.lastRunStatus || '' }}</div>
        <template #footer><el-button :disabled="scheduleMutationBusy" @click="cancelScheduleDialog">取消</el-button><el-button type="primary" :loading="scheduleMutationBusy && activeScheduleAction === 'save'" :disabled="scheduleMutationBusy" @click="saveSchedule">儲存排程</el-button></template>
      </el-dialog>

      <div class="schedule-hint" style="margin-top:10px">本機輸出根目錄：<code>{{ baseDir || '/home/steven' }}</code>。立即匯出使用已儲存的設定。</div>
    </el-card>

    <!-- 輸出資料夾選擇器（檔案總管式樹狀，Requirement 34 增修） -->
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

    <!-- Charts -->
    <el-row :gutter="20">
      <el-col :span="24">
        <el-card style="margin-bottom:20px">
          <template #header>
            <span class="section-title">總資產趨勢</span>
            <span class="card-sub">{{ trendDate }}</span>
          </template>
          <div class="trend-legend">
            <div v-for="item in trendLegendItems" :key="item.name" class="trend-legend-item">
              <span class="tl-dot" :style="{ background: item.color }"></span>
              <div class="tl-text">
                <div class="tl-name">{{ item.name }}</div>
                <div class="tl-amount" :style="{ color: item.color }">{{ fmt(item.value) }}</div>
                <div class="tl-pct" :style="{ color: item.color }">{{ item.pct }}</div>
              </div>
            </div>
          </div>
          <v-chart :option="totalTrendOption" style="height:300px" autoresize
            @updateAxisPointer="onTrendAxisPointer"
            @globalout="onTrendLeave" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">資產組成堆疊圖</span></template>
          <v-chart :option="stackedOption" style="height:300px" autoresize />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">歷次增幅</span></template>
          <v-chart :option="increaseOption" style="height:300px" autoresize />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Plus, Download, Edit, Delete, Refresh, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { useAuthStore } from '@/stores/authStore'
import {
  MAX_EXPORT_SCHEDULES,
  buildExportSchedulePayload,
  createExportScheduleDraft,
  formatExportScheduleTime,
  getExportScheduleError,
  requireExportScheduleList
} from '@/utils/exportSchedules'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent])

const history = ref([])
const exporting = ref(false)
const recalculating = ref(false)

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

// 輸出資料夾選擇器（檔案總管式樹狀，Requirement 34 增修）
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
const dirPicker = reactive({
  visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const auth = useAuthStore()
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

const reload = async () => { history.value = await bffApi.assetHistory.getHistory() }

async function loadSchedules() {
  if (scheduleLoadInFlight.value) return false
  scheduleLoadInFlight.value = true
  scheduleLoading.value = true
  scheduleLoadError.value = ''
  try {
    const response = requireExportScheduleList(await bffApi.assetHistory.getExportSchedules())
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

onMounted(() => { Promise.allSettled([reload(), loadSchedules()]) })

const handleRecalcDividends = async () => {
  recalculating.value = true
  try {
    const result = await bffApi.assetHistory.recalcDividends()
    ElMessage.success(`配息重算完成：${result.updated} 個快照已更新`)
    await reload()
  } catch (e) {
    ElMessage.error('重算失敗，請稍後再試')
  } finally {
    recalculating.value = false
  }
}

// BFF 已預先標註 isLastOfYear（每年最後一筆，用於「已實現損益」欄位顯示判斷）
const isLastOfYear = (row) => !!row?.isLastOfYear

const deleteSnapshot = async (id) => {
  await bffApi.assetHistory.deleteSnapshot(id)
  await reload()
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.assetHistory.exportExcel()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `資產管理_${dayjs().format('YYYYMMDD')}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

function applyScheduleList(response) {
  const canonical = requireExportScheduleList(response)
  schedules.value = canonical.schedules
  baseDir.value = canonical.baseDir || ''
  gdriveRemote.value = canonical.gdriveRemote || ''
}

function openNewSchedule() {
  if (scheduleMutationBusy.value || scheduleLoading.value || scheduleLoadError.value || schedules.value.length >= MAX_EXPORT_SCHEDULES) return
  scheduleDialog.mode = 'create'
  scheduleDialog.draft = { ...createExportScheduleDraft(), gdriveRemote: gdriveRemote.value }
  scheduleDialog.visible = true
}

function openEditSchedule(item) {
  if (scheduleMutationBusy.value || scheduleLoading.value || scheduleLoadError.value) return
  scheduleDialog.mode = 'edit'
  scheduleDialog.draft = { ...createExportScheduleDraft(item), gdriveRemote: gdriveRemote.value }
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
      ? await bffApi.assetHistory.createExportSchedule(payload)
      : await bffApi.assetHistory.updateExportSchedule(draft.id, payload)
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
    const response = await bffApi.assetHistory.deleteExportSchedule(item.id)
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
    const result = await bffApi.assetHistory.runExportScheduleNow(item.id)
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
    ? bffApi.assetHistory.browseGdriveExportDir
    : bffApi.assetHistory.browseExportDir
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

const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const pct = (v) => v ? `${(Number(v) * 100).toFixed(1)}%` : '-'

const dates = computed(() => history.value.map(h => h.snapshotDate))

// hover 連動 legend：未 hover 時顯示最新一筆
const hoveredDate = ref(null)
function onTrendAxisPointer(e) {
  const idx = e?.axesInfo?.[0]?.value
  if (typeof idx !== 'number') return
  hoveredDate.value = history.value[idx]?.snapshotDate ?? null
}
function onTrendLeave() { hoveredDate.value = null }
const trendDate = computed(() =>
  hoveredDate.value ?? history.value[history.value.length - 1]?.snapshotDate ?? '')

const trendLegendItems = computed(() => {
  const r = history.value.find(h => h.snapshotDate === trendDate.value)
        ?? history.value[history.value.length - 1]
  if (!r) return []
  const total = Number(r.totalAssets || 0)
  const pctOf = v => total > 0 ? `${(v / total * 100).toFixed(1)}%` : '-'
  return [
    { name: '總資產',   value: Number(r.totalAssets || 0),       pct: '100.0%',                                color: '#8b5cf6' },
    { name: '台幣存款', value: Number(r.totalTwdDeposit || 0),   pct: pctOf(Number(r.totalTwdDeposit || 0)),   color: '#3b82f6' },
    { name: '美元存款', value: Number(r.totalUsdDeposit || 0),   pct: pctOf(Number(r.totalUsdDeposit || 0)),   color: '#60a5fa' },
    { name: '台股',     value: Number(r.totalTwStockValue || 0), pct: pctOf(Number(r.totalTwStockValue || 0)), color: '#f59e0b' },
    { name: '美股',     value: Number(r.totalUsStockValue || 0), pct: pctOf(Number(r.totalUsStockValue || 0)), color: '#ef4444' },
    { name: '英股',     value: Number(r.totalUkStockValue || 0), pct: pctOf(Number(r.totalUkStockValue || 0)), color: '#0ea5e9' },
    { name: '基金',     value: Number(r.totalFundValue || 0),    pct: pctOf(Number(r.totalFundValue || 0)),    color: '#10b981' }
  ]
})

const totalTrendOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: params => {
      let s = `<strong>${params[0].axisValue}</strong><br/>`
      params.forEach(p => {
        if (p.value != null) s += `${p.marker} ${p.seriesName}: $${Number(p.value).toLocaleString('zh-TW', { maximumFractionDigits: 0 })}<br/>`
      })
      return s
    }
  },
  legend: { show: false },
  grid: { left: 70, right: 30, top: 20, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v/1e4).toFixed(0)}萬` } },
  series: [
    {
      name: '總資產', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalAssets || 0)),
      itemStyle: { color: '#8b5cf6' },
      lineStyle: { width: 3 },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
        colorStops: [{ offset: 0, color: 'rgba(139,92,246,0.15)' }, { offset: 1, color: 'rgba(139,92,246,0)' }] } }
    },
    {
      name: '台幣存款', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalTwdDeposit || 0)),
      itemStyle: { color: '#3b82f6' },
      lineStyle: { width: 2 }
    },
    {
      name: '美元存款', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUsdDeposit || 0)),
      itemStyle: { color: '#60a5fa' },
      lineStyle: { width: 2 }
    },
    {
      name: '基金', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalFundValue || 0)),
      itemStyle: { color: '#10b981' },
      lineStyle: { width: 2 }
    },
    {
      name: '台股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalTwStockValue || 0)),
      itemStyle: { color: '#f59e0b' },
      lineStyle: { width: 2 }
    },
    {
      name: '美股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUsStockValue || 0)),
      itemStyle: { color: '#ef4444' },
      lineStyle: { width: 2 }
    },
    {
      name: '英股', type: 'line', smooth: true,
      data: history.value.map(h => Number(h.totalUkStockValue || 0)),
      itemStyle: { color: '#0ea5e9' },
      lineStyle: { width: 2 }
    }
  ]
}))

const stackedOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['台幣存款', '美元存款', '基金', '台股', '美股', '英股'] },
  grid: { left: 70, right: 20, top: 40, bottom: 50 },
  xAxis: { type: 'category', data: dates.value, axisLabel: { rotate: 30, fontSize: 11 } },
  yAxis: { type: 'value', axisLabel: { formatter: v => `$${(v/1e4).toFixed(0)}萬` } },
  series: [
    { name: '台幣存款', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalTwdDeposit||0)), itemStyle: { color: '#3b82f6' } },
    { name: '美元存款', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalUsdDeposit||0)), itemStyle: { color: '#60a5fa' } },
    { name: '基金', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalFundValue||0)), itemStyle: { color: '#10b981' } },
    { name: '台股', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalTwStockValue||0)), itemStyle: { color: '#f59e0b' } },
    { name: '美股', type: 'bar', stack: 'total', data: history.value.map(h => Number(h.totalUsStockValue||0)), itemStyle: { color: '#ef4444' } },
    {
      name: '英股', type: 'bar', stack: 'total',
      data: history.value.map(h => Number(h.totalUkStockValue||0)),
      itemStyle: { color: '#0ea5e9' },
      label: {
        show: true,
        position: 'top',
        formatter: p => {
          const h = history.value[p.dataIndex]
          if (!h) return ''
          const total = Number(h.totalAssets || 0)
          return `$${(total / 1e4).toFixed(0)}萬`
        },
        fontSize: 11,
        color: '#374151',
        fontWeight: '600'
      }
    }
  ]
}))

const increaseOption = computed(() => {
  const data = history.value.map(h => ({ value: h.increaseRate ? Number(h.increaseRate)*100 : null, date: h.snapshotDate }))
    .filter(d => d.value != null)
  return {
    tooltip: { trigger: 'axis', formatter: p => `${p[0].name}: ${p[0].value?.toFixed(1)}%` },
    grid: { left: 60, right: 20, top: 20, bottom: 50 },
    xAxis: { type: 'category', data: data.map(d => d.date), axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: { type: 'value', axisLabel: { formatter: v => `${v}%` } },
    series: [{
      type: 'bar',
      data: data.map(d => ({
        value: d.value,
        itemStyle: { color: d.value >= 0 ? '#16a34a' : '#dc2626', borderRadius: [4,4,0,0] }
      })),
      label: {
        show: true,
        position: 'top',
        formatter: p => `${Number(p.value).toFixed(1)}%`,
        fontSize: 11,
        fontWeight: '600',
        color: '#374151'
      }
    }]
  }
})
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.card-sub { font-size: 12px; color: #94a3b8; margin-left: 8px; font-weight: 400; }
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
:deep(.el-table .el-table__cell) { font-family: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', ui-monospace, monospace; }
:deep(.el-table .el-table__cell:first-child) { font-family: inherit; }

/* 趨勢圖自訂 legend：7 項集中置中、間距 32px、下方寫金額（同色） */
.trend-legend { display: flex; justify-content: center; padding: 6px 16px 12px; gap: 32px; flex-wrap: wrap; }
.trend-legend-item { display: flex; align-items: center; gap: 8px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.tl-text { display: flex; flex-direction: column; line-height: 1.25; }
.tl-name   { font-size: 12px; color: #64748b; }
.tl-amount { font-size: 14px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tl-pct    { font-size: 11px; font-weight: 600; }

/* 排程自動匯出設定 */
.schedule-form { margin-bottom: 4px; }
.schedule-row { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); line-height: 1.7; }
.schedule-row:last-of-type { border-bottom: 0; }
.schedule-row-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.schedule-hint { font-size: 12px; color: #94a3b8; line-height: 1.6; }
.schedule-hint code { background: #f1f5f9; color: #475569; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.schedule-status { margin-top: 8px; font-size: 12px; color: #64748b; }
.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
</style>
