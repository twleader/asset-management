<template>
  <div>
    <!-- Year Summary Cards -->
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6" v-for="s in yearSummaries" :key="s.year">
        <el-card class="year-card" :class="{ active: selectedYear === s.year }" @click="selectedYear = s.year">
          <div class="year-title">{{ s.year }} 年</div>
          <div class="year-profit" :class="s.totalProfitTwd >= 0 ? 'profit' : 'loss'">
            {{ fmt(s.totalProfitTwd) }}
          </div>
          <div class="year-sub">{{ pct(s.avgProfitRate) }} 平均報酬</div>
          <div class="year-sub">總收帳 {{ fmt(s.totalProceedsTwd) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Detail Table -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">{{ selectedYear ? selectedYear + ' 年已實現損益明細' : '已實現損益明細' }}</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="exporting" @click="handleExport">匯出 Excel</el-button>
            <el-button type="primary" size="small" :icon="Plus" @click="openCreateDialog">新增</el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!selectedData" description="尚無記錄，請點擊「新增」新增第一筆" />

      <template v-if="selectedData">
      <!-- Year Stats (all in TWD) -->
      <el-descriptions :column="4" size="small" border style="margin-bottom:16px">
        <el-descriptions-item label="總收帳 (台幣)">{{ fmt(filteredStats.totalProceedsTwd) }}</el-descriptions-item>
        <el-descriptions-item label="總成本 (台幣)">{{ fmt(filteredStats.totalCostTwd) }}</el-descriptions-item>
        <el-descriptions-item label="總獲利 (台幣)">
          <span :class="filteredStats.totalProfitTwd >= 0 ? 'profit' : 'loss'">
            {{ fmt(filteredStats.totalProfitTwd) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="平均報酬率">
          <span :class="filteredStats.avgProfitRate >= 0 ? 'profit' : 'loss'">
            {{ pct(filteredStats.avgProfitRate) }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <!-- Market Tabs -->
      <el-tabs v-model="marketFilter" class="market-tabs">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="台股" name="台股" />
        <el-tab-pane label="美股" name="美股" />
        <el-tab-pane label="英股" name="英股" />
      </el-tabs>

      <div v-if="stockOptions.length" class="stock-filter-row">
        <span class="stock-filter-label">股票：</span>
        <el-select
          v-model="stockFilter"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          placeholder="全部"
          style="min-width:260px"
          size="small"
        >
          <el-option v-for="option in stockOptions" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
      </div>

      <el-table ref="realizedGainTable" :data="filteredRecords" size="small" stripe class="gain-table"
        @sort-change="handleDateSortChange" @row-dblclick="onRowDblClick">
        <el-table-column prop="broker" label="券商" width="90">
          <template #default="{ row }">
            <span class="broker-text">{{ row.broker || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tradeDate" label="交易日期" width="105"
          sortable="custom" :sort-orders="['ascending', 'descending']" />
        <el-table-column prop="assetCode" label="股號" width="75" />
        <el-table-column prop="assetName" label="股名" width="110" show-overflow-tooltip />
        <el-table-column label="市場" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.market === '台股' ? 'primary' : 'warning'" size="small">{{ row.market }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="幣別" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.currency === 'USD' ? 'success' : 'info'" size="small" effect="plain">{{ row.currency || 'TWD' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="股數" align="right" width="95">
          <template #default="{ row }">
            <span v-if="row.shares != null">{{ fmtShares(row.shares, row.market) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="買入均價" align="right" width="95">
          <template #default="{ row }">
            <span v-if="row.shares > 0">${{ Number(row.investmentCost / row.shares).toLocaleString('zh-TW', { maximumFractionDigits: 4 }) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="賣出價格" align="right" width="95">
          <template #default="{ row }">
            <span v-if="row.salePrice != null">${{ Number(row.salePrice).toLocaleString('zh-TW', { maximumFractionDigits: 4 }) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="收帳金額" align="right" width="115">
          <template #default="{ row }">{{ fmtCurrency(row.proceeds, row.currency) }}</template>
        </el-table-column>
        <el-table-column label="投資成本" align="right" width="115">
          <template #default="{ row }">{{ fmtCurrency(row.investmentCost, row.currency) }}</template>
        </el-table-column>
        <el-table-column label="獲利(原幣)" align="right" width="115">
          <template #default="{ row }">
            <span :class="row.profit >= 0 ? 'profit' : 'loss'">
              {{ fmtCurrency(row.profit, row.currency) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="獲利(台幣)" align="right" width="115">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.currency === 'USD' && row.exchangeRate"
              :content="`匯率：${Number(row.exchangeRate).toFixed(4)}（${row.tradeDate}）`"
              placement="top"
              effect="light"
            >
              <span :class="row.profitTwd >= 0 ? 'profit' : 'loss'" style="cursor:default">
                {{ fmt(row.profitTwd) }}
              </span>
            </el-tooltip>
            <span v-else :class="row.profitTwd >= 0 ? 'profit' : 'loss'">
              {{ fmt(row.profitTwd) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="獲利率" align="right" width="82">
          <template #default="{ row }">
            <span :class="row.profitRate >= 0 ? 'profit' : 'loss'">{{ pct(row.profitRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" link type="primary" @click="openEditDialog(row)" />
            <el-popconfirm title="確定刪除？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" :icon="Delete" link type="danger" />
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      </template>
    </el-card>

    <!-- 獨立排程匯出設定（Task 511） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
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

      <el-dialog v-model="scheduleDialog.visible" :title="scheduleDialog.mode === 'create' ? '新增已實現損益匯出排程' : '編輯已實現損益匯出排程'" width="680px" :close-on-click-modal="!scheduleMutationBusy" :close-on-press-escape="!scheduleMutationBusy" :show-close="!scheduleMutationBusy" :before-close="closeScheduleDialog">
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
          以主機家目錄 <code>{{ baseDir || '/home/steven' }}</code> 為根。每筆排程於自己的時間匯出已實現損益，產生
          <code>已實現損益_{使用者ID}[_{名稱}]_YYYYMMDD.xlsx</code> 與同名 <code>.json</code>。
          同一使用者、日期、資料夾與名稱會覆寫；若要保留不同版本，請使用不同名稱或資料夾。
          空白名稱會沿用舊檔名。取消會丟棄這份草稿。
        </div>
        <div class="schedule-status">上次執行：{{ scheduleDialog.draft?.lastRunAt || '—' }}　{{ scheduleDialog.draft?.lastRunStatus || '' }}</div>
        <template #footer><el-button :disabled="scheduleMutationBusy" @click="cancelScheduleDialog">取消</el-button><el-button type="primary" :loading="scheduleMutationBusy && activeScheduleAction === 'save'" :disabled="scheduleMutationBusy" @click="saveSchedule">儲存排程</el-button></template>
      </el-dialog>

      <div class="schedule-hint" style="margin-top:10px">本機輸出根目錄：<code>{{ baseDir || '/home/steven' }}</code>。立即匯出使用已儲存的設定。</div>
    </el-card>

    <!-- 輸出資料夾選擇器（檔案總管式樹狀，Requirement 39 / Task 196） -->
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

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '編輯損益記錄' : '新增損益記錄'" width="760px"
      @closed="resetForm">
      <el-form :model="gainForm" label-width="90px" size="default">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="股號">
              <el-input v-model="gainForm.assetCode" @blur="autoFillAssetName" @change="autoFillAssetName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="股名">
              <el-input v-model="gainForm.assetName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="市場" label-width="50px">
              <el-select v-model="gainForm.market" style="width:100%">
                <el-option value="台股" label="台股" />
                <el-option value="美股" label="美股" />
                <el-option value="英股" label="英股" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="幣別" label-width="50px">
              <el-select v-model="gainForm.currency" style="width:100%">
                <el-option value="TWD" label="TWD" />
                <el-option value="USD" label="USD" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="交易日期" label-width="70px">
              <el-date-picker v-model="gainForm.tradeDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="券商">
              <el-select v-model="gainForm.broker" clearable placeholder="選擇券商" style="width:100%">
                <el-option v-for="b in brokerOptions" :key="b" :value="b" :label="b" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="股數">
              <el-input v-model="gainForm.sharesStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('shares', 5)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="賣出單價">
              <el-input v-model="gainForm.salePriceStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('salePrice', 4)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="收帳金額">
              <el-input v-model="gainForm.proceedsStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('proceeds', gainForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="投資成本">
              <el-input v-model="gainForm.investmentCostStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('investmentCost', gainForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="實現獲利">
              <strong :class="computedProfit >= 0 ? 'profit' : 'loss'">
                {{ fmtCurrency(computedProfit, gainForm.currency) }}
                ({{ computedCost > 0 ? (computedProfit / computedCost * 100).toFixed(2) + '%' : '-' }})
              </strong>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitGain" :loading="saving">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Download, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { resolveRealizedGainDateSortOrder, sortRealizedGainRecords } from '@/utils/realizedGainDateSort'
import { buildRealizedGainStockOptions, filterRealizedGainByStock, pruneInvalidRealizedGainStockFilter } from '@/utils/realizedGainStockFilter'
import { useAuthStore } from '@/stores/authStore'
import { todayLocal } from '@/utils/localDate'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import {
  MAX_EXPORT_SCHEDULES,
  buildExportSchedulePayload,
  createExportScheduleDraft,
  formatExportScheduleTime,
  getExportScheduleError,
  requireExportScheduleList
} from '@/utils/exportSchedules'

const realizedGains = ref([])
const brokerOptions = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const exporting = ref(false)
const selectedYear = ref(null)
const editingId = ref(null)

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

async function loadSchedules() {
  if (scheduleLoadInFlight.value) return false
  scheduleLoadInFlight.value = true
  scheduleLoading.value = true
  scheduleLoadError.value = ''
  try {
    const response = requireExportScheduleList(await bffApi.realizedGain.getExportSchedules())
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
      ? await bffApi.realizedGain.createExportSchedule(payload)
      : await bffApi.realizedGain.updateExportSchedule(draft.id, payload)
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
    const response = await bffApi.realizedGain.deleteExportSchedule(item.id)
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
    const result = await bffApi.realizedGain.runExportScheduleNow(item.id)
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
  dirPicker.baseDir = ''
  dirPicker.error = ''
  dirPicker.treeKey++
  dirPicker.visible = true
}

async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.realizedGain.browseGdriveExportDir
    : bffApi.realizedGain.browseExportDir
  try {
    if (node.level === 0) {
      const response = await browse('')
      dirPicker.baseDir = response.baseDir || ''
      dirPicker.error = ''
      resolve([{ name: response.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const response = await browse(node.data.path || '')
    resolve((response.directories || []).map(directory => ({ name: directory.name, path: directory.path, key: directory.path, leaf: false })))
  } catch (error) {
    dirPicker.error = getExportScheduleError(error, dirPicker.mode === 'gdrive' ? 'Google Drive 資料夾讀取失敗' : '輸出資料夾讀取失敗')
    resolve([])
  }
}

const onDirNodeClick = (data) => { dirPicker.picked = data.path || '' }

function confirmDirPick() {
  if (scheduleMutationBusy.value || !scheduleDialog.draft) return
  let path = dirPicker.picked || ''
  const sub = (dirPicker.newSub || '').trim().replace(/^\/+|\/+$/g, '')
  if (sub) path = path ? `${path}/${sub}` : sub
  if (dirPicker.mode === 'gdrive') scheduleDialog.draft.gdriveSubpath = path
  else scheduleDialog.draft.outputSubpath = path
  dirPicker.visible = false
}

// ===== Form with string fields for free typing =====
const gainForm = reactive({
  assetName: '', assetCode: '', market: '台股', currency: 'TWD', broker: '', tradeDate: '',
  sharesStr: '0', salePriceStr: '0', proceedsStr: '0', investmentCostStr: '0'
})

// 當市場改變時自動切換預設幣別（判斷式與 resetForm 共用 defaultCurrency，不另立第二套對照）
watch(() => gainForm.market, (m) => {
  gainForm.currency = defaultCurrency(m)
})

// Parse helpers
const parseNum = (s) => {
  const n = parseFloat(String(s || '').replace(/,/g, ''))
  return isNaN(n) ? 0 : n
}
const fmtNum = (v, precision) => {
  if (v === null || v === undefined || v === '') return ''
  const n = Number(v)
  if (isNaN(n)) return ''
  const s = precision > 0 ? n.toFixed(precision) : String(Math.round(n))
  const dot = s.indexOf('.')
  const intPart = dot >= 0 ? s.slice(0, dot) : s
  const decPart = dot >= 0 ? s.slice(dot) : ''
  return intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + decPart
}

// On blur: format the string field with commas
const fieldMap = {
  shares: { str: 'sharesStr', prec: 5 },
  salePrice: { str: 'salePriceStr', prec: 4 },
  proceeds: { str: 'proceedsStr', prec: 0 },
  investmentCost: { str: 'investmentCostStr', prec: 0 }
}
const onBlurField = (field, precision) => {
  const strKey = fieldMap[field].str
  const val = parseNum(gainForm[strKey])
  gainForm[strKey] = fmtNum(val, precision)
}

// 輸入股號後自動帶出股名（先查 stock 主檔，找不到再打外部 API）
const autoFillAssetName = async () => {
  const code = (gainForm.assetCode || '').trim().toUpperCase()
  if (!code) return
  gainForm.assetCode = code
  // 已有股名就不覆寫（使用者自填的優先）
  if (gainForm.assetName && gainForm.assetName.trim()) return
  try {
    const res = await bffApi.realizedGain.lookupName({ code, market: gainForm.market })
    const name = res?.stockName || res?.name
    if (name) gainForm.assetName = name
  } catch (e) {
    /* silent */
  }
}

// Computed profit from string fields
const computedProceeds = computed(() => parseNum(gainForm.proceedsStr))
const computedCost = computed(() => parseNum(gainForm.investmentCostStr))
const computedProfit = computed(() => computedProceeds.value - computedCost.value)

const reload = async () => {
  const data = await bffApi.realizedGain.getAll()
  realizedGains.value = data.gains ?? []
  brokerOptions.value = (data.brokers ?? []).map(b => b.displayName)
}
onMounted(() => { Promise.allSettled([reload(), loadSchedules()]) })

const marketFilter = ref('')
const stockFilter = ref([])
const dateSortOrder = ref(null)
const realizedGainTable = ref(null)

const yearSummaries = computed(() => realizedGains.value)
const selectedData = computed(() => realizedGains.value.find(g => g.year === selectedYear.value) || realizedGains.value[0])
const marketYearRecords = computed(() => {
  const records = selectedData.value?.records || []
  return marketFilter.value ? records.filter(record => record.market === marketFilter.value) : records
})
const stockOptions = computed(() => buildRealizedGainStockOptions(marketYearRecords.value))

const filteredRecords = computed(() => {
  const stockFiltered = filterRealizedGainByStock(marketYearRecords.value, stockFilter.value)
  return sortRealizedGainRecords(stockFiltered, dateSortOrder.value)
})

watch(stockOptions, (options) => {
  const next = pruneInvalidRealizedGainStockFilter(stockFilter.value, options)
  if (next !== stockFilter.value) stockFilter.value = next
})

function handleDateSortChange({ prop, order }) {
  if (prop !== 'tradeDate') return

  const resolvedOrder = resolveRealizedGainDateSortOrder(dateSortOrder.value, order)
  dateSortOrder.value = resolvedOrder
  if (order === resolvedOrder) return

  // 點擊目前啟用的 caret 時，Element Plus 會送出 order=null；在下一次畫面更新後
  // 用公開 table API 還原同一方向的指示器。sort() 隨後送出的同方向事件會在上方 return，
  // 因此不會形成遞迴。
  nextTick(() => realizedGainTable.value?.sort('tradeDate', resolvedOrder))
}

// 使用 API 回傳的台幣金額計算統計
const filteredStats = computed(() => {
  const records = filteredRecords.value
  const totalProceedsTwd = records.reduce((a, r) => a + Number(r.proceedsTwd || 0), 0)
  const totalCostTwd     = records.reduce((a, r) => a + Number(r.investmentCostTwd || 0), 0)
  const totalProfitTwd   = records.reduce((a, r) => a + Number(r.profitTwd || 0), 0)
  const avgProfitRate    = totalCostTwd > 0 ? totalProfitTwd / totalCostTwd : 0
  return { totalProceedsTwd, totalCostTwd, totalProfitTwd, avgProfitRate }
})

watch(realizedGains, (v) => {
  if (v.length && !selectedYear.value) selectedYear.value = v[0].year
}, { immediate: true })

// ===== Display formatters =====
const fmt = (v) => {
  if (v == null) return '-'
  const n = Number(v)
  return `$${n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })}`
}
const fmtCurrency = (v, currency) => {
  if (v == null) return '-'
  const n = Number(v)
  const decimals = currency === 'USD' ? 2 : 0
  return `$${n.toLocaleString('zh-TW', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`
}
const fmtShares = (v, market) => {
  if (v == null) return '-'
  const n = Number(v)
  if (market === '美股' || market === '英股') {
    // 最多 5 位小數，去掉尾端零
    return n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 5 })
  }
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}
const pct = (v) => v ? `${(Number(v) * 100).toFixed(2)}%` : '-'

// ===== Dialog actions =====
// Task 250：新增表單的市場預設＝當前市場 tab（''＝全部 → 台股）
const defaultMarket = () => marketFilter.value || '台股'
// 幣別必須在 resetForm 內一併算：tab 未變時 market 同值寫回不會 trigger 上面那個 watch
const defaultCurrency = (m) => (m === '美股' || m === '英股') ? 'USD' : 'TWD'

const resetForm = () => {
  editingId.value = null
  const market = defaultMarket()
  Object.assign(gainForm, {
    assetName: '', assetCode: '', market, currency: defaultCurrency(market), broker: '', tradeDate: '',
    sharesStr: '0', salePriceStr: '0', proceedsStr: '0', investmentCostStr: '0'
  })
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.realizedGain.exportExcel()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `已實現損益_${dayjs().format('YYYYMMDD')}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

const openCreateDialog = () => {
  resetForm()
  gainForm.tradeDate = todayLocal()
  dialogVisible.value = true
}

const openEditDialog = (row) => {
  editingId.value = row.id
  gainForm.assetName = row.assetName || ''
  gainForm.assetCode = row.assetCode || ''
  gainForm.market = row.market || '台股'
  gainForm.currency = row.currency || ((row.market === '美股' || row.market === '英股') ? 'USD' : 'TWD')
  gainForm.broker = row.broker || ''
  gainForm.tradeDate = row.tradeDate || ''
  const isUsd = gainForm.currency === 'USD'
  gainForm.sharesStr = fmtNum(row.shares, (row.market === '美股' || row.market === '英股') ? 5 : 0)
  gainForm.salePriceStr = fmtNum(row.salePrice, 4)
  gainForm.proceedsStr = fmtNum(row.proceeds, isUsd ? 2 : 0)
  gainForm.investmentCostStr = fmtNum(row.investmentCost, isUsd ? 2 : 0)
  dialogVisible.value = true
}

const submitGain = async () => {
  saving.value = true
  try {
    const shares = parseNum(gainForm.sharesStr)
    const salePrice = parseNum(gainForm.salePriceStr)
    const proceeds = parseNum(gainForm.proceedsStr)
    const investmentCost = parseNum(gainForm.investmentCostStr)

    const payload = {
      assetName: gainForm.assetName,
      assetCode: gainForm.assetCode,
      market: gainForm.market,
      currency: gainForm.currency,
      broker: gainForm.broker || null,
      tradeDate: gainForm.tradeDate,
      shares, salePrice, proceeds, investmentCost
    }

    if (editingId.value) {
      await bffApi.realizedGain.update(editingId.value, payload)
      ElMessage.success('更新成功')
    } else {
      await bffApi.realizedGain.create(payload)
      ElMessage.success('新增成功')
      selectedYear.value = gainForm.tradeDate ? new Date(gainForm.tradeDate).getFullYear() : selectedYear.value
    }
    dialogVisible.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id) => {
  await bffApi.realizedGain.delete(id)
  await reload()
  ElMessage.success('已刪除')
}

// ===== 雙擊開啟股票走勢分析 =====
const analysisVisible = ref(false)
const analysisStock = ref(null)
function onRowDblClick(row) {
  if (!row?.assetCode || !row?.market) return
  analysisStock.value = { stockCode: row.assetCode, stockName: row.assetName, market: row.market }
  analysisVisible.value = true
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.year-card { cursor: pointer; transition: all 0.2s; }
.year-card.active { border: 2px solid #3b82f6 !important; }
.year-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.1) !important; }
.year-card :deep(.el-card__body) { padding: 16px; text-align: center; }
.year-title { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 8px; }
.year-profit { font-size: 22px; font-weight: 700; margin-bottom: 4px; }
.year-sub { font-size: 12px; color: #64748b; }
.profit { color: #16a34a; font-weight: 600; }
.loss { color: #dc2626; font-weight: 600; }
.market-tabs { margin-bottom: 4px; }
.market-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.stock-filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.stock-filter-label { font-size: 13px; color: #475569; white-space: nowrap; }
.broker-text { font-size: 13px; color: #475569; }
.gain-table :deep(.el-table__cell) { font-size: 13.5px; }

/* 排程自動匯出設定（Requirement 39 / Task 196） */
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
