<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">👁️ 觀察股票</span>
          <el-button type="primary" :icon="Plus" @click="openDialog()">新增觀察</el-button>
        </div>
      </template>

      <el-tabs v-model="marketTab" style="margin-bottom:12px" @tab-change="() => nextTick(initSortable)">
        <el-tab-pane name="台股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <TaiwanMap :size="18" />
              台股 <el-tag size="small" style="margin-left:2px">{{ twList.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="美股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <span style="font-size:16px;line-height:1">🇺🇸</span>
              美股 <el-tag size="small" style="margin-left:2px">{{ usList.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-table ref="tableRef" :data="currentList" v-loading="loading" border stripe row-key="id" size="small"
        @row-dblclick="onStockDblClick">
        <el-table-column width="36" align="center">
          <template #default>
            <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="股名／股號" min-width="170" fixed>
          <template #default="{ row }">
            <div style="font-weight:600">{{ row.stockCode }}</div>
            <div style="color:#475569;font-size:12px">{{ row.stockName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="股價" width="90" align="right">
          <template #default="{ row }">
            <strong :style="{ color: priceColor(row.priceChange) }">
              {{ fmtNum(row.price) }}
            </strong>
          </template>
        </el-table-column>
        <el-table-column label="漲跌" width="80" align="right">
          <template #default="{ row }">
            <span :style="{ color: priceColor(row.priceChange) }">
              {{ fmtChange(row.priceChange) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="漲跌幅(%)" width="92" align="right">
          <template #default="{ row }">
            <span :style="{ color: priceColor(row.changePercent) }">
              {{ fmtPct(row.changePercent) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="買進" width="80" align="right">
          <template #default="{ row }">{{ fmtNum(row.buyPrice) }}</template>
        </el-table-column>
        <el-table-column label="賣出" width="80" align="right">
          <template #default="{ row }">{{ fmtNum(row.sellPrice) }}</template>
        </el-table-column>
        <el-table-column label="開盤" width="80" align="right">
          <template #default="{ row }">{{ fmtNum(row.openPrice) }}</template>
        </el-table-column>
        <el-table-column label="昨收" width="80" align="right">
          <template #default="{ row }">{{ fmtNum(row.previousClose) }}</template>
        </el-table-column>
        <el-table-column label="最高" width="80" align="right">
          <template #default="{ row }">
            <span style="color:#dc2626">{{ fmtNum(row.highPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最低" width="80" align="right">
          <template #default="{ row }">
            <span style="color:#16a34a">{{ fmtNum(row.lowPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="volumeLabel" width="100" align="right">
          <template #default="{ row }">{{ fmtVolume(row.volume) }}</template>
        </el-table-column>
        <el-table-column label="警示（觸發時間／股價／季線／KD）" min-width="210">
          <template #default="{ row }">
            <template v-if="row.lastTriggeredAt">
              <div style="font-size:12px;color:#64748b">{{ fmtDt(row.lastTriggeredAt) }}</div>
              <div style="font-size:12px;color:#0f172a">
                股價 <strong>{{ row.lastTriggeredPrice != null ? Number(row.lastTriggeredPrice).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#0f172a">
                季線 <strong>{{ row.quarterlyMa != null ? Number(row.quarterlyMa).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#2563eb">
                <span>K {{ row.kValue != null ? Number(row.kValue).toFixed(1) : '—' }}</span>
                <span style="margin-left:6px">D {{ row.dValue != null ? Number(row.dValue).toFixed(1) : '—' }}</span>
              </div>
            </template>
            <span v-else style="font-size:12px;color:#94a3b8">尚未觸發</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" :icon="Delete" circle @click="remove(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />

    <!-- 新增觀察 Dialog -->
    <el-dialog v-model="dialogVisible" title="新增觀察股票" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="市場" required>
          <el-radio-group v-model="form.market">
            <el-radio value="台股">台股</el-radio>
            <el-radio value="美股">美股</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="股票代號" required>
          <el-input v-model="form.stockCode" placeholder="例：2330 或 AAPL"
            style="width:140px;margin-right:8px"
            @blur="fetchStockName" />
          <el-input v-model="form.stockName" placeholder="（離開欄位自動帶入）"
            style="width:200px"
            :suffix-icon="lookingUpName ? Loading : undefined" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">新增</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Plus, Delete, Loading, Operation } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import dayjs from 'dayjs'
import api, { watchStockApi } from '@/api/index.js'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import TaiwanMap from '@/components/TaiwanMap.vue'

// ===== State =====
const marketTab = ref('台股')
const list = ref([])
const twList = computed(() => list.value.filter(w => w.market === '台股'))
const usList = computed(() => list.value.filter(w => w.market === '美股'))
const currentList = computed(() => marketTab.value === '台股' ? twList.value : usList.value)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const lookingUpName = ref(false)
const tableRef = ref(null)

const volumeLabel = computed(() => marketTab.value === '台股' ? '成交量(張)' : '成交量(股)')

const defaultForm = () => ({ market: '台股', stockCode: '', stockName: '' })
const form = reactive(defaultForm())

// ===== Load =====
async function load() {
  loading.value = true
  try {
    list.value = await watchStockApi.getAll()
  } finally {
    loading.value = false
  }
}

// ===== Sortable =====
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
      from.removeChild(item)
      if (oldIndex >= from.children.length) from.appendChild(item)
      else from.insertBefore(item, from.children[oldIndex])

      const arr = marketTab.value === '台股' ? twList.value : usList.value
      const moved = arr[oldIndex]
      // 在原始 list 中重排該市場的順序
      const otherMarket = list.value.filter(w => w.market !== marketTab.value)
      const reordered = [...arr]
      reordered.splice(oldIndex, 1)
      reordered.splice(newIndex, 0, moved)
      list.value = [...reordered, ...otherMarket]
      watchStockApi.reorder(list.value.map(w => w.id))
        .catch(() => ElMessage.error('排序儲存失敗'))
    }
  })
}

onMounted(async () => {
  await load()
  nextTick(initSortable)
})

// ===== CRUD =====
function openDialog() {
  Object.assign(form, defaultForm())
  form.market = marketTab.value
  dialogVisible.value = true
}

async function fetchStockName() {
  if (!form.stockCode || !form.market) return
  lookingUpName.value = true
  try {
    const res = await api.get('/stock-alerts/lookup-name', {
      params: { code: form.stockCode.trim().toUpperCase(), market: form.market }
    })
    if (res.stockName) form.stockName = res.stockName
    else ElMessage.warning('找不到此股票名稱，請手動填寫')
  } catch (e) {
    ElMessage.error('查詢失敗：' + (e.message || ''))
  } finally {
    lookingUpName.value = false
  }
}

async function save() {
  if (!form.stockCode || !form.market) {
    ElMessage.warning('請填寫股票代號與市場')
    return
  }
  saving.value = true
  try {
    await watchStockApi.create({
      market: form.market,
      stockCode: form.stockCode.toUpperCase(),
      stockName: form.stockName || null
    })
    ElMessage.success('已加入觀察清單')
    dialogVisible.value = false
    await load()
    nextTick(initSortable)
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(
    `確定將 ${row.stockCode} ${row.stockName || ''} 從觀察清單移除？`,
    '移除觀察', { type: 'warning' }
  )
  await watchStockApi.delete(row.id)
  ElMessage.success('已移除')
  await load()
}

// ===== Formatters =====
const fmtDt = (dt) => dayjs(dt).format('MM/DD HH:mm')

const fmtNum = (v) => {
  if (v == null) return '—'
  const n = Number(v)
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const fmtChange = (v) => {
  if (v == null) return '—'
  const n = Number(v)
  const sign = n > 0 ? '▲' : n < 0 ? '▼' : ''
  return `${sign}${Math.abs(n).toFixed(2)}`
}

const fmtPct = (v) => {
  if (v == null) return '—'
  const n = Number(v)
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toFixed(2)}%`
}

const fmtVolume = (v) => {
  if (v == null) return '—'
  return Number(v).toLocaleString()
}

const priceColor = (v) => {
  if (v == null) return '#0f172a'
  const n = Number(v)
  if (n > 0) return '#dc2626'
  if (n < 0) return '#16a34a'
  return '#475569'
}

// ===== 股價走勢分析 =====
const analysisVisible = ref(false)
const analysisStock = ref(null)

function onStockDblClick(row) {
  analysisStock.value = { stockCode: row.stockCode, stockName: row.stockName, market: row.market }
  analysisVisible.value = true
}

</script>

<style scoped>
.page-container { padding: 4px; }
.section-title { font-size: 16px; font-weight: 600; }
</style>
