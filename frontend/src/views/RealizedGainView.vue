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

      <el-table :data="filteredRecords" size="small" stripe class="gain-table"
        @row-dblclick="onRowDblClick">
        <el-table-column prop="broker" label="券商" width="90">
          <template #default="{ row }">
            <span class="broker-text">{{ row.broker || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tradeDate" label="交易日期" width="105" />
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

    <!-- 排程自動匯出設定（Requirement 39 / Task 196） -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">⏱️ 排程自動匯出</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="runningNow" @click="handleRunNow">立即匯出到目錄</el-button>
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
        <el-form-item label="輸出資料夾">
          <el-input v-model="schedule.outputSubpath" readonly placeholder="（家目錄根）" style="width:240px">
            <template #append>
              <el-button :icon="FolderOpened" @click="openDirPicker">選擇</el-button>
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
        主機 <code>/Users/steven/input</code>。每日於指定時間匯出已實現損益為
        <code>已實現損益_{使用者ID}_YYYYMMDD.xlsx</code>（內容同上方「匯出 Excel」，涵蓋全部年度）。
      </div>
      <div v-if="schedule.lastRunAt || schedule.lastRunStatus" class="schedule-status">
        上次執行：{{ schedule.lastRunAt || '—' }}　{{ schedule.lastRunStatus || '' }}
      </div>
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
import { useAuthStore } from '@/stores/authStore'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

const realizedGains = ref([])
const brokerOptions = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const exporting = ref(false)
const selectedYear = ref(null)
const editingId = ref(null)

// 排程自動匯出設定（Requirement 39 / Task 196）
const schedule = reactive({
  // Drive 同步（Task 243）；gdriveRemote 是後端給的顯示值，不入庫
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: '',
  enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input', lastRunAt: null, lastRunStatus: null, baseDir: '' })
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
const runningNow = ref(false)

// 輸出資料夾選擇器（檔案總管式樹狀）
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
const dirPicker = reactive({
  visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const auth = useAuthStore()
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

async function loadSchedule() {
  const s = await bffApi.realizedGain.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.runHour = s.runHour ?? 8
  schedule.runMinute = s.runMinute ?? 0
  schedule.outputSubpath = s.outputSubpath ?? 'input'
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
    const s = await bffApi.realizedGain.updateExportSchedule({
      enabled: schedule.enabled,
      gdriveEnabled: schedule.gdriveEnabled,
      gdriveSubpath: (schedule.gdriveSubpath || '').trim(),
      runHour: h,
      runMinute: m,
      outputSubpath: (schedule.outputSubpath || 'input').trim()
    })
    schedule.runHour = s.runHour ?? h
    schedule.runMinute = s.runMinute ?? m
    schedule.outputSubpath = s.outputSubpath ?? schedule.outputSubpath
    schedule.baseDir = s.baseDir ?? schedule.baseDir
    applyGdrive(s)
    ElMessage.success('排程設定已儲存')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)
  } catch (e) {
    ElMessage.error('儲存失敗，請稍後再試')
  } finally {
    savingSchedule.value = false
  }
}

async function handleRunNow() {
  runningNow.value = true
  try {
    const r = await bffApi.realizedGain.runExportNow()
    ElMessage.success(`已匯出到：${r.path}`)
  } catch (e) {
    ElMessage.error('立即匯出失敗，請確認目錄與權限')
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
    ? bffApi.realizedGain.browseGdriveExportDir
    : bffApi.realizedGain.browseExportDir
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
onMounted(() => { reload(); loadSchedule().catch(() => {}) })

const marketFilter = ref('')

const yearSummaries = computed(() => realizedGains.value)
const selectedData = computed(() => realizedGains.value.find(g => g.year === selectedYear.value) || realizedGains.value[0])

const filteredRecords = computed(() => {
  const records = selectedData.value?.records || []
  return marketFilter.value ? records.filter(r => r.market === marketFilter.value) : records
})

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
  gainForm.tradeDate = new Date().toISOString().slice(0, 10)
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
.broker-text { font-size: 13px; color: #475569; }
.gain-table :deep(.el-table__cell) { font-size: 13.5px; }

/* 排程自動匯出設定（Requirement 39 / Task 196） */
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
