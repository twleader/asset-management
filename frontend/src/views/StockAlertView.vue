<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">🔔 股票到價警示</span>
          <div style="display:flex;gap:8px">
            <el-button :loading="checking" @click="triggerCheck">立即檢查</el-button>
            <el-button type="primary" :icon="Plus" @click="openDialog()">新增警示</el-button>
          </div>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom:16px">
        <template #title>
          盤中（台股 09:00–13:30、美股 09:30–16:00 ET、英股 08:00–16:30 LON）每次股價更新（每 2 分鐘）即時檢查，條件符合時記錄觸發時間與股價（同一條件 24 小時內不重複觸發）。
        </template>
      </el-alert>

      <el-tabs v-model="marketTab" style="margin-bottom:12px" @tab-change="() => nextTick(initSortable)">
        <el-tab-pane name="台股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <TaiwanMap :size="18" />
              台股 <el-tag size="small" style="margin-left:2px">{{ twAlerts.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="美股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <UsFlag :size="22" />
              美股 <el-tag size="small" style="margin-left:2px">{{ usAlerts.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="英股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <span style="font-size:18px">🇬🇧</span>
              英股 <el-tag size="small" style="margin-left:2px">{{ ukAlerts.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-table ref="tableRef" :data="currentAlerts" v-loading="loading" border stripe row-key="id"
        @row-dblclick="onStockDblClick">
        <!-- 拖拽把手 -->
        <el-table-column width="36" align="center">
          <template #default>
            <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="股號／股名" min-width="180">
          <template #default="{ row }">
            <span style="font-weight:600;margin-right:8px">{{ row.stockCode }}</span>
            <span style="color:#475569;font-size:13px">{{ row.stockName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="警示條件" min-width="180">
          <template #default="{ row }">
            <span>{{ row.conditionLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="建立時間" width="120">
          <template #default="{ row }">
            <span style="font-size:12px;color:#64748b">{{ fmtDt(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="狀態" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.active" @change="toggleActive(row)" />
          </template>
        </el-table-column>
        <el-table-column label="觸發時間 / 股價 / 均線 / KD" width="200">
          <template #default="{ row }">
            <template v-if="row.lastTriggeredAt">
              <div style="font-size:12px;color:#64748b">{{ fmtDt(row.lastTriggeredAt, row.market) }}</div>
              <div style="font-size:12px;color:#0f172a">
                股價 <strong>{{ row.lastTriggeredPrice != null ? '$' + Number(row.lastTriggeredPrice).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#0f172a">
                {{ rowMaLabel(row) }} <strong>{{ row.lastTriggeredMaValue != null ? '$' + Number(row.lastTriggeredMaValue).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#2563eb">
                <span>K {{ row.lastTriggeredKdValue != null ? Number(row.lastTriggeredKdValue).toFixed(1) : '—' }}</span>
                <span style="margin-left:6px">D {{ row.lastTriggeredDValue != null ? Number(row.lastTriggeredDValue).toFixed(1) : '—' }}</span>
              </div>
            </template>
            <span v-else style="font-size:12px;color:#94a3b8">尚未觸發</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" circle @click="openDialog(row)" />
            <el-button size="small" type="danger" :icon="Delete" circle @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />

    <!-- 新增/編輯 Dialog -->
    <el-dialog v-model="dialogVisible" :title="editId ? '編輯警示' : '新增警示'" width="520px">
      <el-form :model="form" label-width="110px" ref="formRef">
        <el-divider content-position="left">股票</el-divider>

        <el-form-item label="市場" required>
          <el-radio-group v-model="form.market">
            <el-radio value="台股">台股</el-radio>
            <el-radio value="美股">美股</el-radio>
            <el-radio value="英股">英股</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="股票代號" required>
          <el-input v-model="form.stockCode" placeholder="例：2330 或 AAPL"
            style="width:140px;margin-right:8px"
            :suffix-icon="lookingUpCode ? Loading : undefined"
            @blur="fetchStockName" />
          <el-input v-model="form.stockName" placeholder="或輸入股名自動帶代號"
            style="width:200px"
            :suffix-icon="lookingUpName ? Loading : undefined"
            @blur="fetchStockCode" />
        </el-form-item>

        <el-divider content-position="left">警示條件</el-divider>

        <el-form-item label="條件類型" required>
          <el-select v-model="form.conditionGroup" style="width:100%" @change="onGroupChange">
            <el-option value="PRICE"  label="價位" />
            <el-option value="MA_20"  label="月線偏離（20 日均線）" />
            <el-option value="MA_60"  label="季線偏離（60 日均線）" />
            <el-option value="MA_240" label="年線偏離（240 日均線）" />
            <el-option value="KD"     label="KD 值" />
          </el-select>
        </el-form-item>

        <!-- 價位 -->
        <template v-if="form.conditionGroup === 'PRICE'">
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於</el-radio>
              <el-radio value="BELOW">低於</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="目標價位">
            <el-input-number v-model="form.priceThreshold" :min="0" :precision="2" :step="1" style="width:160px" />
          </el-form-item>
        </template>

        <!-- 均線（月線 / 季線 / 年線共用） -->
        <template v-if="isMaGroup">
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於{{ maName }}</el-radio>
              <el-radio value="BELOW">低於{{ maName }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="幅度">
            <el-radio-group v-model="form.threshold">
              <el-radio :value="0">0%</el-radio>
              <el-radio :value="5">5%</el-radio>
              <el-radio :value="10">10%</el-radio>
              <el-radio :value="15">15%</el-radio>
              <el-radio :value="20">20%</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>

        <!-- KD -->
        <template v-if="form.conditionGroup === 'KD'">
          <el-form-item label="指標">
            <el-radio-group v-model="form.kdIndicator">
              <el-radio value="K">K 值</el-radio>
              <el-radio value="D">D 值</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="方向">
            <el-radio-group v-model="form.direction">
              <el-radio value="ABOVE">高於</el-radio>
              <el-radio value="BELOW">低於</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="門檻值">
            <el-input-number v-model="form.threshold" :min="0" :max="100" :step="1" />
            <span style="margin-left:8px;color:#64748b">（例：高於 80 / 低於 20）</span>
          </el-form-item>
        </template>

        <el-divider content-position="left">通知對象</el-divider>

        <el-form-item label="收件人">
          <div v-if="recipients.length === 0" style="color:#94a3b8;font-size:13px;line-height:1.6">
            尚未設定任何收件人，請先到「警示通知設定」新增 email；此警示觸發時不會寄信。
          </div>
          <div v-else style="width:100%">
            <el-checkbox
              v-model="recipientAllChecked"
              :indeterminate="recipientIndeterminate"
              style="margin-bottom:2px">全選</el-checkbox>
            <el-checkbox-group v-model="form.recipientIds" style="display:flex;flex-direction:column">
              <el-checkbox v-for="r in recipients" :key="r.id" :value="r.id">
                {{ r.email }}<span v-if="!r.active" style="color:#f56c6c;margin-left:4px">(停用)</span>
              </el-checkbox>
            </el-checkbox-group>
            <div style="color:#94a3b8;font-size:12px;margin-top:2px;line-height:1.5">
              觸發時只寄給有勾選且「啟用」中的收件人；未勾選任何人則此警示不寄信。
            </div>
          </div>
        </el-form-item>

        <el-form-item label="啟用">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Edit, Delete, Loading, Operation, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import dayjs from 'dayjs'
import { bffApi, apiErrorMessage } from '@/api/index.js'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsFlag from '@/components/UsFlag.vue'

// 父層（StockMonitorView）監聽 alert-saved，藉以重新載入觀察清單（觀察清單由 stock_alert 衍生）
const emit = defineEmits(['alert-saved'])

// ===== State =====
const marketTab = ref('台股')
const twAlerts  = ref([])
const usAlerts  = ref([])
const ukAlerts  = ref([])
const currentAlerts = computed(() => {
  if (marketTab.value === '美股') return usAlerts.value
  if (marketTab.value === '英股') return ukAlerts.value
  return twAlerts.value
})
const loading = ref(false)
const saving = ref(false)
const lookingUpName = ref(false)
const lookingUpCode = ref(false)
const dialogVisible = ref(false)
const editId = ref(null)
const tableRef = ref(null)

// ===== 通知收件人（Task 125）=====
const recipients = ref([])               // 可挑選的全部收件人 [{id,email,active}]
const allRecipientIds = computed(() => recipients.value.map(r => r.id))
const recipientAllChecked = computed({
  get: () => recipients.value.length > 0 && form.recipientIds.length === recipients.value.length,
  set: (val) => { form.recipientIds = val ? allRecipientIds.value.slice() : [] }
})
const recipientIndeterminate = computed(() =>
  form.recipientIds.length > 0 && form.recipientIds.length < recipients.value.length)

async function loadRecipients() {
  try { recipients.value = await bffApi.stockAlert.getRecipients() }
  catch { recipients.value = [] }
}

const defaultForm = () => ({
  market: '台股',
  stockCode: '',
  stockName: '',
  conditionGroup: 'MA_60',
  direction: 'ABOVE',
  kdIndicator: 'K',
  threshold: 5,
  priceThreshold: 0,
  active: true,
  recipientIds: [],
})
const form = reactive(defaultForm())

const MA_GROUPS = { MA_20: 20, MA_60: 60, MA_240: 240 }
const MA_NAMES = { 20: '月線', 60: '季線', 240: '年線' }
const isMaGroup = computed(() => form.conditionGroup in MA_GROUPS)
const maName = computed(() => MA_NAMES[MA_GROUPS[form.conditionGroup]] || '均線')

function rowMaLabel(row) {
  return MA_NAMES[row.maPeriod] || '均線'
}

// ===== Load =====
async function loadAlerts() {
  loading.value = true
  try {
    const res = await bffApi.stockAlert.getAll()
    twAlerts.value = res.filter(a => a.market === '台股')
    usAlerts.value = res.filter(a => a.market === '美股')
    ukAlerts.value = res.filter(a => a.market === '英股')
  } finally {
    loading.value = false
  }
}

// ===== Drag & Drop 排序 =====
let sortableInstance = null
function initSortable() {
  const tbody = tableRef.value?.$el?.querySelector('tbody')
  if (!tbody) return
  if (sortableInstance) { sortableInstance.destroy(); sortableInstance = null }
  sortableInstance = Sortable.create(tbody, {
    handle: '.drag-handle',
    animation: 150,
    onEnd({ oldIndex, newIndex, item, from }) {
      if (oldIndex === newIndex) return
      // 還原 Sortable 的 DOM 變動，避免與 el-table 的渲染衝突
      from.removeChild(item)
      if (oldIndex >= from.children.length) from.appendChild(item)
      else from.insertBefore(item, from.children[oldIndex])
      const arr = marketTab.value === '美股' ? usAlerts.value
        : marketTab.value === '英股' ? ukAlerts.value
        : twAlerts.value
      const moved = arr.splice(oldIndex, 1)[0]
      arr.splice(newIndex, 0, moved)
      bffApi.stockAlert.reorder( [...twAlerts.value, ...usAlerts.value, ...ukAlerts.value].map(a => a.id))
        .catch(() => ElMessage.error('排序儲存失敗'))
    }
  })
}

onMounted(async () => {
  await Promise.allSettled([loadAlerts(), loadRecipients()])
  nextTick(initSortable)
})


// ===== Dialog =====
function openDialog(row = null) {
  Object.assign(form, defaultForm())
  form.market = marketTab.value
  editId.value = null
  if (row) {
    editId.value = row.id
    form.market = row.market
    form.stockCode = row.stockCode
    form.stockName = row.stockName || ''
    form.active = row.active
    // 編輯：回填該警示已選收件人（Task 125）；舊資料若無則視為未選
    form.recipientIds = Array.isArray(row.recipientIds) ? [...row.recipientIds] : []
    parseAlertType(row.alertType, row.threshold, row.maPeriod)
  } else {
    form.recipientIds = allRecipientIds.value.slice()   // 新警示預設全選
  }
  dialogVisible.value = true
}

function parseAlertType(alertType, threshold, maPeriod) {
  if (alertType.startsWith('PRICE')) {
    form.conditionGroup = 'PRICE'
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.priceThreshold = Number(threshold)
  } else if (alertType.startsWith('MA_')) {
    form.conditionGroup = `MA_${maPeriod}`
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.threshold = Number(threshold)
  } else {
    form.conditionGroup = 'KD'
    form.kdIndicator = alertType.startsWith('KD_D') ? 'D' : 'K'
    form.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    form.threshold = Number(threshold)
  }
}

function onGroupChange() {
  form.direction = 'ABOVE'
  form.kdIndicator = 'K'
  form.threshold = form.conditionGroup === 'KD' ? 80 : 5
  form.priceThreshold = 0
}

function buildAlertType() {
  const dir = form.direction
  if (form.conditionGroup === 'PRICE') return `PRICE_${dir}`
  if (isMaGroup.value)                 return `MA_${dir}_PCT`
  return form.kdIndicator === 'D' ? `KD_D_${dir}` : `KD_${dir}`
}

async function fetchStockName() {
  if (!form.stockCode || !form.market) return
  lookingUpName.value = true
  try {
    const res = await bffApi.stockAlert.lookupName({
      code: form.stockCode.trim().toUpperCase(), market: form.market
    })
    if (res.stockName) {
      form.stockName = res.stockName
    } else {
      ElMessage.warning('找不到此股票名稱，請手動填寫')
    }
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e.message || ''))
  } finally {
    lookingUpName.value = false
  }
}

// 反向：輸入股名 → 自動帶代號（只查本地 stock 主檔，精確匹配）
async function fetchStockCode() {
  if (form.stockCode || !form.stockName || !form.market) return
  lookingUpCode.value = true
  try {
    const res = await bffApi.stockAlert.lookupCode({
      name: form.stockName.trim(), market: form.market
    })
    if (res.stockCode) {
      form.stockCode = res.stockCode
    } else {
      ElMessage.warning('本地查無此股名，請改輸入股票代號')
    }
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e.message || ''))
  } finally {
    lookingUpCode.value = false
  }
}

async function save() {
  if (!form.stockCode || !form.market) {
    ElMessage.warning('請填寫股票代號與市場')
    return
  }
  saving.value = true
  try {
    const payload = {
      market: form.market,
      stockCode: form.stockCode.toUpperCase(),
      stockName: form.stockName || null,
      alertType: buildAlertType(),
      maPeriod: isMaGroup.value ? MA_GROUPS[form.conditionGroup] : null,
      threshold: form.conditionGroup === 'PRICE' ? form.priceThreshold : form.threshold,
      active: form.active,
      recipientIds: form.recipientIds,   // Task 125：此警示的通知收件人
    }
    if (editId.value) {
      await bffApi.stockAlert.update(editId.value, payload)
    } else {
      await bffApi.stockAlert.create(payload)
    }
    ElMessage.success('儲存成功')
    dialogVisible.value = false
    loadAlerts()
    emit('alert-saved')
  } catch (e) {
    // 存檔錯誤（如重複條件「已存在相同的警示條件…」、名稱不符）以 dialog 呈現，
    // 不走頂部 toast（stockAlert.create/update 已帶 skipErrorToast 抑制全域攔截器）。
    ElMessageBox.alert(apiErrorMessage(e, '儲存失敗'), '無法儲存警示', {
      type: 'warning',
      confirmButtonText: '我知道了',
    }).catch(() => {})
  } finally {
    saving.value = false
  }
}

async function toggleActive(row) {
  try {
    const res = await bffApi.stockAlert.toggleActive(row.id)
    Object.assign(row, res.data)
  } catch {
    row.active = !row.active
    ElMessage.error('操作失敗')
  }
}

async function remove(row) {
  await ElMessageBox.confirm(
    `確定刪除 ${row.stockCode} 的「${row.conditionLabel}」警示？`,
    '刪除警示', { type: 'warning' }
  )
  await bffApi.stockAlert.delete(row.id)
  ElMessage.success('已刪除')
  loadAlerts()
}

const fmtDt = (dt, market) => {
  if (!dt) return ''
  const base = dayjs(dt).format('MM/DD HH:mm')
  if (!market) return base
  const tz = market === '美股' ? 'NY' : market === '英股' ? 'LON' : 'TW'
  return `${base} ${tz}`
}

// ===== 股價走勢分析 =====
const analysisVisible = ref(false)
const analysisStock = ref(null)

function onStockDblClick(row) {
  analysisStock.value = { stockCode: row.stockCode, stockName: row.stockName, market: row.market }
  analysisVisible.value = true
}

// 父層（StockMonitorView）會在「觀察清單頁的新增按鈕」被按下時，切到此頁籤後呼叫 openNewDialog
defineExpose({
  openNewDialog(initMarket) {
    if (initMarket) marketTab.value = initMarket
    openDialog(null)
  }
})
</script>

<style scoped>
.page-container { padding: 4px; }
.section-title { font-size: 16px; font-weight: 600; }
</style>
