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
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

const realizedGains = ref([])
const brokerOptions = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const exporting = ref(false)
const selectedYear = ref(null)
const editingId = ref(null)

// 排程自動匯出設定（Requirement 39 / Task 196）
const schedule = reactive({ enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input', lastRunAt: null, lastRunStatus: null, baseDir: '' })
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
const runningNow = ref(false)

// 輸出資料夾選擇器（檔案總管式樹狀）
const dirPicker = reactive({ visible: false, baseDir: '', picked: '', newSub: '', treeKey: 0 })
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
  scheduleTime.value = `${String(schedule.runHour).padStart(2, '0')}:${String(schedule.runMinute).padStart(2, '0')}`
}

async function saveSchedule() {
  savingSchedule.value = true
  try {
    const [h, m] = (scheduleTime.value || '08:00').split(':').map(Number)
    const s = await bffApi.realizedGain.updateExportSchedule({
      enabled: schedule.enabled,
      runHour: h,
      runMinute: m,
      outputSubpath: (schedule.outputSubpath || 'input').trim()
    })
    schedule.runHour = s.runHour ?? h
    schedule.runMinute = s.runMinute ?? m
    schedule.outputSubpath = s.outputSubpath ?? schedule.outputSubpath
    schedule.baseDir = s.baseDir ?? schedule.baseDir
    ElMessage.success('排程設定已儲存')
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
      const res = await bffApi.realizedGain.browseExportDir('')
      dirPicker.baseDir = res.baseDir || ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await bffApi.realizedGain.browseExportDir(node.data.path || '')
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

// ===== Form with string fields for free typing =====
const gainForm = reactive({
  assetName: '', assetCode: '', market: '台股', currency: 'TWD', broker: '', tradeDate: '',
  sharesStr: '0', salePriceStr: '0', proceedsStr: '0', investmentCostStr: '0'
})

// 當市場改變時自動切換預設幣別
watch(() => gainForm.market, (m) => {
  gainForm.currency = (m === '美股' || m === '英股') ? 'USD' : 'TWD'
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
const resetForm = () => {
  editingId.value = null
  Object.assign(gainForm, {
    assetName: '', assetCode: '', market: '台股', currency: 'TWD', broker: '', tradeDate: '',
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
