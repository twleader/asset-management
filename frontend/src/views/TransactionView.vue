<template>
  <div>
    <!-- 年度彙總卡 -->
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card class="year-card" :class="{ active: selectedYear === null }" @click="selectedYear = null">
          <div class="year-title">全部年度</div>
          <div class="year-sub">買 {{ totalBuyCount }} 筆　賣 {{ totalSellCount }} 筆</div>
          <div class="year-sub">買入 {{ fmt(totalBuyAmountTwd) }}</div>
          <div class="year-sub">賣出 {{ fmt(totalSellAmountTwd) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6" v-for="s in summaries" :key="s.year">
        <el-card class="year-card" :class="{ active: selectedYear === s.year }" @click="selectedYear = s.year">
          <div class="year-title">{{ s.year }} 年</div>
          <div class="year-sub">買 {{ s.buyCount }} 筆　賣 {{ s.sellCount }} 筆</div>
          <div class="year-sub buy">買入 {{ fmt(s.totalBuyAmountTwd) }}</div>
          <div class="year-sub sell">賣出 {{ fmt(s.totalSellAmountTwd) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 明細表格 -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">{{ selectedYear ? selectedYear + ' 年交易紀錄明細' : '全部交易紀錄明細' }}</span>
          <div style="display:flex;gap:8px">
            <el-button size="small" :icon="Download" :loading="exporting" @click="handleExport">匯出 Excel</el-button>
            <el-button type="primary" size="small" :icon="Plus" @click="openCreateDialog">新增</el-button>
          </div>
        </div>
      </template>

      <el-empty v-if="!filteredRecords.length" description="尚無記錄，請點擊「新增」新增第一筆" />

      <el-table v-else :data="filteredRecords" size="small" stripe class="tx-table">
        <el-table-column prop="assetName" label="資產名稱" width="120" show-overflow-tooltip />
        <el-table-column prop="assetCode" label="代號" width="80">
          <template #default="{ row }">{{ row.assetCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="交易類型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.transactionType === '賣' ? 'danger' : 'success'" size="small">{{ row.transactionType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="資產類型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.assetType === '基金' ? 'warning' : 'primary'" size="small" effect="plain">{{ row.assetType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tradeDate" label="交易日期" width="105" />
        <el-table-column label="數量" align="right" width="100">
          <template #default="{ row }">
            <span v-if="row.shares != null">{{ fmtShares(row.shares, row.market) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="單價" align="right" width="100">
          <template #default="{ row }">
            <span v-if="row.price != null">{{ fmtCurrency(row.price, row.currency) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="成交金額" align="right" width="115">
          <template #default="{ row }">{{ fmtCurrency(row.amount, row.currency) }}</template>
        </el-table-column>
        <el-table-column label="台幣成交金額" align="right" width="120">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.currency === 'USD' && row.exchangeRate"
              :content="`匯率：${Number(row.exchangeRate).toFixed(4)}（${row.tradeDate}）`"
              placement="top"
              effect="light"
            >
              <span style="cursor:default">{{ fmt(row.amountTwd) }}</span>
            </el-tooltip>
            <span v-else>{{ fmt(row.amountTwd) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="市場" width="70" align="center">
          <template #default="{ row }">{{ row.market || '-' }}</template>
        </el-table-column>
        <el-table-column label="幣別" width="60" align="center">
          <template #default="{ row }">
            <el-tag :type="row.currency === 'USD' ? 'success' : 'info'" size="small" effect="plain">{{ row.currency || 'TWD' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="channel" label="券商通路" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.channel || '-' }}</template>
        </el-table-column>
        <el-table-column prop="notes" label="備註" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.notes || '-' }}</template>
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
    </el-card>

    <!-- 排程自動匯出設定（Requirement 49 / Task t238） -->
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
        主機 <code>/Users/steven/input</code>。每日於指定時間匯出交易紀錄為
        <code>交易紀錄_{使用者ID}_YYYYMMDD.xlsx</code>（內容同上方「匯出 Excel」，涵蓋全部年度）。
      </div>
      <div v-if="schedule.lastRunAt || schedule.lastRunStatus" class="schedule-status">
        上次執行：{{ schedule.lastRunAt || '—' }}　{{ schedule.lastRunStatus || '' }}
      </div>
    </el-card>

    <!-- 輸出資料夾選擇器（檔案總管式樹狀） -->
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

    <!-- 新增/編輯 dialog -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '編輯交易紀錄' : '新增交易紀錄'" width="760px"
      @closed="resetForm">
      <el-form ref="formRef" :model="txForm" :rules="rules" label-width="90px" size="default">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="交易類型" prop="transactionType">
              <el-select v-model="txForm.transactionType" style="width:100%">
                <el-option v-for="t in TX_TYPES" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="資產類型" prop="assetType">
              <el-select v-model="txForm.assetType" style="width:100%">
                <el-option v-for="t in ASSET_TYPES" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="代號">
              <el-input v-model="txForm.assetCode" @blur="autoFillAssetName" @change="autoFillAssetName"
                placeholder="輸入代號自動帶出名稱（股票）" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="資產名稱" prop="assetName">
              <el-input v-model="txForm.assetName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="市場" label-width="50px">
              <el-select v-model="txForm.market" clearable placeholder="選擇市場" style="width:100%">
                <el-option v-for="m in marketOptions" :key="m.code" :value="m.code" :label="m.label" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="幣別" label-width="50px">
              <el-select v-model="txForm.currency" style="width:100%">
                <el-option value="TWD" label="TWD" />
                <el-option value="USD" label="USD" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="交易日期" prop="tradeDate" label-width="70px">
              <el-date-picker v-model="txForm.tradeDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="券商通路">
              <el-select v-model="txForm.channel" filterable allow-create clearable default-first-option
                placeholder="選擇或輸入券商／通路" style="width:100%">
                <el-option v-for="b in brokerOptions" :key="b" :value="b" :label="b" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="數量">
              <el-input v-model="txForm.sharesStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('shares', 5)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="單價">
              <el-input v-model="txForm.priceStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('price', 4)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="成交金額" prop="amountStr">
              <el-input v-model="txForm.amountStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('amount', txForm.currency === 'USD' ? 2 : 0)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="匯率" v-if="txForm.currency === 'USD'">
              <el-input v-model="txForm.exchangeRateStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('exchangeRate', 4)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="台幣成交金額">
              <strong>{{ fmt(computedAmountTwd) }}</strong>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="備註">
              <el-input v-model="txForm.notes" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="saving">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Download, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'

const TX_TYPES = ['買', '賣']
const ASSET_TYPES = ['股票', '基金']

const summaries = ref([])
const marketOptions = ref([])   // { code, label }
const brokerOptions = ref([])   // displayName 字串
const selectedYear = ref(null)
const dialogVisible = ref(false)
const saving = ref(false)
const exporting = ref(false)
const editingId = ref(null)
const formRef = ref(null)

// 排程自動匯出設定（Task t238）
const schedule = reactive({ enabled: false, runHour: 8, runMinute: 0, outputSubpath: 'input', lastRunAt: null, lastRunStatus: null, baseDir: '' })
const scheduleTime = ref('08:00')
const savingSchedule = ref(false)
const runningNow = ref(false)

// 輸出資料夾選擇器（檔案總管式樹狀）
const dirPicker = reactive({ visible: false, baseDir: '', picked: '', newSub: '', treeKey: 0 })
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }

const rules = {
  transactionType: [{ required: true, message: '請選擇交易類型', trigger: 'change' }],
  assetType: [{ required: true, message: '請選擇資產類型', trigger: 'change' }],
  assetName: [{ required: true, message: '請輸入資產名稱', trigger: 'blur' }],
  tradeDate: [{ required: true, message: '請選擇交易日期', trigger: 'change' }],
  amountStr: [{ required: true, message: '請輸入成交金額', trigger: 'blur' }]
}

// ===== Form with string fields for free typing =====
const txForm = reactive({
  transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
  market: '台股', currency: 'TWD', channel: '', tradeDate: '', notes: '',
  sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: ''
})

// 市場改變時自動切換預設幣別
watch(() => txForm.market, (m) => {
  txForm.currency = (m === '美股' || m === '英股') ? 'USD' : 'TWD'
})

// Parse / format helpers（比照 RealizedGainView）
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

const fieldMap = {
  shares: 'sharesStr',
  price: 'priceStr',
  amount: 'amountStr',
  exchangeRate: 'exchangeRateStr'
}
const onBlurField = (field, precision) => {
  const strKey = fieldMap[field]
  if (!String(txForm[strKey] || '').trim()) { txForm[strKey] = ''; return }
  txForm[strKey] = fmtNum(parseNum(txForm[strKey]), precision)
}

// 輸入代號後自動帶出股名（僅股票類；查 stock 主檔，走同一支 business API，比照 RealizedGainView）
const autoFillAssetName = async () => {
  const code = (txForm.assetCode || '').trim().toUpperCase()
  if (!code) return
  txForm.assetCode = code
  if (txForm.assetType !== '股票') return          // 基金不查主檔
  if (txForm.assetName && txForm.assetName.trim()) return   // 已有名稱不覆寫（使用者手填優先）
  try {
    const res = await bffApi.transaction.lookupName({ code, market: txForm.market })
    const name = res?.stockName || res?.name
    if (name) txForm.assetName = name
  } catch (e) {
    /* 查無或錯誤靜默，由使用者手填 */
  }
}

// 台幣成交金額即時預覽
const computedAmountTwd = computed(() => {
  const amount = parseNum(txForm.amountStr)
  const rate = parseNum(txForm.exchangeRateStr)
  if (txForm.currency === 'USD' && rate > 0) return amount * rate
  return amount
})

// ===== 載入 =====
async function load() {
  const data = await bffApi.transaction.list()
  summaries.value = data.summaries ?? []
  marketOptions.value = (data.markets ?? []).map(m => ({ code: m.code, label: m.displayName || m.code }))
  brokerOptions.value = (data.brokers ?? []).map(b => b.displayName)
}

async function loadSchedule() {
  const s = await bffApi.transaction.getExportSchedule()
  schedule.enabled = !!s.enabled
  schedule.runHour = s.runHour ?? 8
  schedule.runMinute = s.runMinute ?? 0
  schedule.outputSubpath = s.outputSubpath ?? 'input'
  schedule.lastRunAt = s.lastRunAt ?? null
  schedule.lastRunStatus = s.lastRunStatus ?? null
  schedule.baseDir = s.baseDir ?? ''
  scheduleTime.value = `${String(schedule.runHour).padStart(2, '0')}:${String(schedule.runMinute).padStart(2, '0')}`
}

// 多 panel 並行載入
onMounted(() => {
  Promise.allSettled([load(), loadSchedule()])
})

// ===== 明細（依 selectedYear 過濾 summaries[].records）=====
const filteredRecords = computed(() => {
  if (selectedYear.value === null) {
    return summaries.value.flatMap(s => s.records || [])
  }
  const s = summaries.value.find(x => x.year === selectedYear.value)
  return s?.records || []
})

const totalBuyCount = computed(() => summaries.value.reduce((a, s) => a + (s.buyCount || 0), 0))
const totalSellCount = computed(() => summaries.value.reduce((a, s) => a + (s.sellCount || 0), 0))
const totalBuyAmountTwd = computed(() => summaries.value.reduce((a, s) => a + Number(s.totalBuyAmountTwd || 0), 0))
const totalSellAmountTwd = computed(() => summaries.value.reduce((a, s) => a + Number(s.totalSellAmountTwd || 0), 0))

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
    return n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 5 })
  }
  return n.toLocaleString('zh-TW', { maximumFractionDigits: 0 })
}

// ===== Dialog actions =====
const resetForm = () => {
  editingId.value = null
  Object.assign(txForm, {
    transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
    market: '台股', currency: 'TWD', channel: '', tradeDate: '', notes: '',
    sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: ''
  })
  formRef.value?.clearValidate()
}

const openCreateDialog = () => {
  resetForm()
  txForm.tradeDate = new Date().toISOString().slice(0, 10)
  dialogVisible.value = true
}

const openEditDialog = (row) => {
  editingId.value = row.id
  txForm.transactionType = row.transactionType || '買'
  txForm.assetType = row.assetType || '股票'
  txForm.assetName = row.assetName || ''
  txForm.assetCode = row.assetCode || ''
  txForm.market = row.market || ''
  txForm.currency = row.currency || ((row.market === '美股' || row.market === '英股') ? 'USD' : 'TWD')
  txForm.channel = row.channel || ''
  txForm.tradeDate = row.tradeDate || ''
  txForm.notes = row.notes || ''
  const isUsd = txForm.currency === 'USD'
  txForm.sharesStr = row.shares != null ? fmtNum(row.shares, (row.market === '美股' || row.market === '英股') ? 5 : 0) : ''
  txForm.priceStr = row.price != null ? fmtNum(row.price, 4) : ''
  txForm.amountStr = row.amount != null ? fmtNum(row.amount, isUsd ? 2 : 0) : ''
  txForm.exchangeRateStr = row.exchangeRate != null ? fmtNum(row.exchangeRate, 4) : ''
  dialogVisible.value = true
}

const submit = async () => {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    const shares = String(txForm.sharesStr || '').trim() ? parseNum(txForm.sharesStr) : null
    const price = String(txForm.priceStr || '').trim() ? parseNum(txForm.priceStr) : null
    const exchangeRate = (txForm.currency === 'USD' && String(txForm.exchangeRateStr || '').trim())
      ? parseNum(txForm.exchangeRateStr) : null
    const payload = {
      transactionType: txForm.transactionType,
      assetType: txForm.assetType,
      assetName: txForm.assetName,
      assetCode: txForm.assetCode || null,
      market: txForm.market || null,
      currency: txForm.currency || null,
      channel: txForm.channel || null,
      tradeDate: txForm.tradeDate,
      shares, price,
      amount: parseNum(txForm.amountStr),
      exchangeRate,
      notes: txForm.notes || null
    }
    if (editingId.value) {
      await bffApi.transaction.update(editingId.value, payload)
      ElMessage.success('更新成功')
    } else {
      await bffApi.transaction.create(payload)
      ElMessage.success('新增成功')
      selectedYear.value = txForm.tradeDate ? new Date(txForm.tradeDate).getFullYear() : selectedYear.value
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const handleDelete = async (id) => {
  await bffApi.transaction.remove(id)
  await load()
  ElMessage.success('已刪除')
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await bffApi.transaction.exportExcel()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `交易紀錄_${dayjs().format('YYYYMMDD')}.xlsx`
    document.body.appendChild(a); a.click(); document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('匯出完成')
  } finally {
    exporting.value = false
  }
}

// ===== 排程自動匯出 =====
async function saveSchedule() {
  savingSchedule.value = true
  try {
    const [h, m] = (scheduleTime.value || '08:00').split(':').map(Number)
    const s = await bffApi.transaction.updateExportSchedule({
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
    const r = await bffApi.transaction.runExportNow()
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
      const res = await bffApi.transaction.browseExportDir('')
      dirPicker.baseDir = res.baseDir || ''
      resolve([{ name: res.baseDir || '/', path: '', key: '__root__', leaf: false }])
      return
    }
    const res = await bffApi.transaction.browseExportDir(node.data.path || '')
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
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.year-card { cursor: pointer; transition: all 0.2s; }
.year-card.active { border: 2px solid #3b82f6 !important; }
.year-card:hover { transform: translateY(-2px); box-shadow: 0 4px 16px rgba(0,0,0,0.1) !important; }
.year-card :deep(.el-card__body) { padding: 16px; text-align: center; }
.year-title { font-size: 16px; font-weight: 600; color: #1e293b; margin-bottom: 8px; }
.year-sub { font-size: 12px; color: #64748b; line-height: 1.7; }
.year-sub.buy { color: #16a34a; }
.year-sub.sell { color: #dc2626; }
.tx-table :deep(.el-table__cell) { font-size: 13.5px; }

/* 排程自動匯出設定（Task t238） */
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
