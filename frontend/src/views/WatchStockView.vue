<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">👁️ 觀察股票</span>
          <div style="display:flex;align-items:center;gap:8px">
            <el-button :icon="Promotion" :loading="resending" @click="resendDigest">補發{{ marketTab }}</el-button>
            <el-button type="primary" :icon="Plus" @click="emit('request-new-alert', marketTab)">新增觀察</el-button>
          </div>
        </div>
      </template>

      <el-tabs v-model="marketTab" style="margin-bottom:12px" @tab-change="onMarketTabChange">
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
              <UsFlag :size="22" />
              美股 <el-tag size="small" style="margin-left:2px">{{ usList.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="英股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <span style="font-size:18px">🇬🇧</span>
              英股 <el-tag size="small" style="margin-left:2px">{{ ukList.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-table ref="tableRef" :data="currentList" v-loading="loading" border stripe
        :row-key="row => `${row.market}_${row.stockCode}`" size="small"
        @row-dblclick="onStockDblClick">
        <el-table-column width="36" align="center" fixed="left">
          <template #default>
            <el-icon class="drag-handle" style="cursor:grab;color:#94a3b8"><Operation /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="股名／股號" min-width="150">
          <template #default="{ row }">
            <div style="font-weight:600">{{ row.stockCode }}</div>
            <div style="color:#475569;font-size:12px">{{ row.stockName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="股價" width="90" align="right">
          <template #default="{ row }">
            <span v-if="isClosePending(row)" style="color:#d97706;font-size:12px">收盤價待補</span>
            <strong v-else :style="{ color: priceColor(row.priceChange) }">
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
        <el-table-column label="開盤" width="90" align="right">
          <template #default="{ row }">{{ fmtNum(row.openPrice) }}</template>
        </el-table-column>
        <el-table-column label="昨收" width="90" align="right">
          <template #default="{ row }">{{ fmtNum(row.previousClose) }}</template>
        </el-table-column>
        <el-table-column label="最高" width="90" align="right">
          <template #default="{ row }">
            <span style="color:#dc2626">{{ fmtNum(row.highPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最低" width="90" align="right">
          <template #default="{ row }">
            <span style="color:#16a34a">{{ fmtNum(row.lowPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="volumeLabel" width="100" align="right">
          <template #default="{ row }">{{ fmtVolume(row.volume) }}</template>
        </el-table-column>
        <el-table-column label="警示條件" min-width="140">
          <template #default="{ row }">
            <template v-if="row.conditions && row.conditions.length">
              <div v-for="(c, i) in row.conditions" :key="i"
                :style="{ fontSize: '12px', color: conditionColor(c) }">
                <!-- Task 253：複合條件群組已由後端合併成單一 condition（label 以「 且 」串好），
                     這裡只標示它是 AND 群組，避免使用者誤讀成「任一成立就會通知」 -->
                <el-tag v-if="isGroupCondition(c)" size="small" type="warning"
                  style="margin-right:4px;vertical-align:middle">複合</el-tag>
                {{ c.label }}<span v-if="!c.active"> (停用)</span>
              </div>
            </template>
            <span v-else style="font-size:12px;color:#94a3b8">—</span>
          </template>
        </el-table-column>
        <el-table-column label="警示（觸發時間／股價／月線／季線／年線／KD）" min-width="230">
          <template #default="{ row }">
            <template v-if="row.lastTriggeredAt">
              <div style="font-size:12px;color:#64748b">{{ fmtDt(row.lastTriggeredAt, row.market) }}</div>
              <div style="font-size:12px;color:#0f172a">
                股價 <strong>{{ row.lastTriggeredPrice != null ? '$' + Number(row.lastTriggeredPrice).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#0f172a">
                月線 <strong>{{ row.monthlyMa != null ? '$' + Number(row.monthlyMa).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#0f172a">
                季線 <strong>{{ row.quarterlyMa != null ? '$' + Number(row.quarterlyMa).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#0f172a">
                年線 <strong>{{ row.annualMa != null ? '$' + Number(row.annualMa).toLocaleString() : '—' }}</strong>
              </div>
              <div style="font-size:12px;color:#2563eb">
                <span>K {{ row.kValue != null ? Number(row.kValue).toFixed(1) : '—' }}</span>
                <span style="margin-left:6px">D {{ row.dValue != null ? Number(row.dValue).toFixed(1) : '—' }}</span>
              </div>
            </template>
            <span v-else style="font-size:12px;color:#94a3b8">尚未觸發</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />
  </div>
</template>

<script setup>
import { Plus, Operation, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import Sortable from 'sortablejs'
import dayjs from 'dayjs'
import { bffApi } from '@/api/index.js'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsFlag from '@/components/UsFlag.vue'
import { isClosePending } from '@/utils/displayQuote'

// 觀察清單由 stock_alert 衍生：「新增觀察」按鈕請父層 (StockMonitorView) 切到警示條件 tab 並彈出新增 dialog
const emit = defineEmits(['request-new-alert'])

// ===== State =====
const marketTab = ref('台股')
const list = ref([])
const twList = computed(() => list.value.filter(w => w.market === '台股'))
const usList = computed(() => list.value.filter(w => w.market === '美股'))
const ukList = computed(() => list.value.filter(w => w.market === '英股'))
const currentList = computed(() => {
  if (marketTab.value === '美股') return usList.value
  if (marketTab.value === '英股') return ukList.value
  return twList.value
})
const loading = ref(false)
const tableRef = ref(null)

const volumeLabel = computed(() => marketTab.value === '台股' ? '成交量(張)' : '成交量(股)')
// 台股單位為「張」(=1000 股)；美股 / 英股單位為「股」

// ===== Load =====
async function load() {
  loading.value = true
  try {
    list.value = await bffApi.watchStock.getAll()
  } finally {
    loading.value = false
  }
}

// ===== 補發：當前市場 tab 最後交易日的觸發事件彙整成單封 email 重寄（Task 128：只補當前市場）=====
const resending = ref(false)
// 切市場 tab：清掉上一個市場補發殘留的 loading（補發進行中切 tab → spinner 不會視覺上黏到新 tab），並重綁拖曳排序
function onMarketTabChange() {
  resending.value = false
  nextTick(initSortable)
}
async function resendDigest() {
  resending.value = true
  try {
    const res = await bffApi.watchStock.resendDigest(marketTab.value)
    if (res.sent) ElMessage.success(res.message)
    else ElMessage.warning(res.message)
  } catch {
    // 錯誤訊息已由 axios 攔截器統一提示
  } finally {
    resending.value = false
  }
}

// ===== Sortable（觀察清單拖一列 = 該股票所有 alert 整組移動）=====
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

      const arr = marketTab.value === '美股' ? usList.value
        : marketTab.value === '英股' ? ukList.value
        : twList.value
      const moved = arr[oldIndex]
      const otherMarket = list.value.filter(w => w.market !== marketTab.value)
      const reordered = [...arr]
      reordered.splice(oldIndex, 1)
      reordered.splice(newIndex, 0, moved)
      list.value = [...reordered, ...otherMarket]
      // 後端會把每個股票所有 alert 的 displayOrder 整組依此順序連續重排
      const orderedKeys = list.value.map(w => ({ stockCode: w.stockCode, market: w.market }))
      bffApi.watchStock.reorder(orderedKeys)
        .catch(() => ElMessage.error('排序儲存失敗'))
    }
  })
}

onMounted(async () => {
  await load()
  nextTick(initSortable)
})

// 父層在警示條件儲存後會呼叫此 reload，讓觀察清單重新載入
defineExpose({ reload: async () => { await load(); nextTick(initSortable) } })

// ===== Formatters =====
const fmtDt = (dt, market) => {
  if (!dt) return ''
  const tz = market === '美股' ? 'NY' : market === '英股' ? 'LON' : 'TW'
  return `${dayjs(dt).format('MM/DD HH:mm')} ${tz}`
}

const fmtNum = (v) => {
  if (v == null) return '—'
  const n = Number(v)
  return `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const fmtChange = (v) => {
  if (v == null) return '—'
  const n = Number(v)
  const sign = n > 0 ? '▲' : n < 0 ? '▼' : ''
  return `${sign}$${Math.abs(n).toFixed(2)}`
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

// Task 253：該條 condition 是否為複合條件（AND 群組）。
// 一律取後端旗標，不從 label 是否含「且」反推——股名與門檻文字都可能含該字，猜錯就會給獨立條件亂貼標籤。
// 兩種旗標名皆接受：後端若把群組旗標做成 boolean（group）或沿用清單頁的 kind（'GROUP'）都能正確顯示。
// 注意：撰寫時 WatchStockDto.Condition 只有 label / active / triggered 三欄，兩個旗標都還沒有，
// 在後端補上任一欄之前這個標籤不會出現（畫面退回 Task 253 前的樣子，不會顯示錯誤資訊）。
const isGroupCondition = (c) => c?.group === true || c?.kind === 'GROUP'

const conditionColor = (c) => {
  if (!c.active) return '#94a3b8'   // 停用：淺色
  if (c.triggered) return '#dc2626' // 已觸發（最後交易日及前一日內）：紅
  return '#0f172a'                  // 啟用未觸發：深色
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
