<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">🔔 股票到價警示</span>
          <div style="display:flex;gap:8px">
            <el-button :loading="checking" @click="triggerCheck">立即檢查</el-button>
            <el-button :icon="Plus" @click="openGroupDialog()">新增複合條件</el-button>
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

      <!-- row-key 必須帶 kind：獨立條件與群組的 id 分屬 stock_alert / stock_alert_group 兩表、值必然重疊，
           只用 id 會讓 el-table 與 Sortable 把兩列當成同一列（Task 253） -->
      <el-table ref="tableRef" :data="currentAlerts" v-loading="loading" border stripe :row-key="rowKey"
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
            <!-- 複合條件：分行列出各條件並在第二行起前綴「且」，讓使用者一眼看出是 AND（Task 253） -->
            <div v-if="row.kind === 'GROUP'">
              <el-tag size="small" type="warning" effect="plain" style="margin-bottom:2px">複合</el-tag>
              <div v-for="(c, i) in row.conditions || []" :key="i" style="font-size:13px;line-height:1.6">
                <span style="color:#94a3b8;margin-right:4px">{{ i === 0 ? '　' : '且' }}</span>{{ c.label }}
              </div>
              <!-- 成員被外力清掉的孤兒群組：conditions 為空，退回後端的合併 label（亦為空字串） -->
              <span v-if="!row.conditions?.length" style="font-size:13px">{{ row.conditionLabel }}</span>
            </div>
            <span v-else>{{ row.conditionLabel }}</span>
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
            <el-button size="small" :icon="Edit" circle
              @click="row.kind === 'GROUP' ? openGroupDialog(row) : openDialog(row)" />
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
            @blur="fetchStockName(form)" />
          <el-input v-model="form.stockName" placeholder="或輸入股名自動帶代號"
            style="width:200px"
            :suffix-icon="lookingUpName ? Loading : undefined"
            @blur="fetchStockCode(form)" />
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

    <!-- 新增/編輯複合條件 Dialog（Task 253）-->
    <el-dialog v-model="groupDialogVisible" :title="groupEditId ? '編輯複合條件' : '新增複合條件'" width="720px">
      <el-alert type="warning" :closable="false" style="margin-bottom:14px">
        <template #title>
          <span style="line-height:1.7">
            所有條件在同一次檢查中<strong>同時成立</strong>才觸發（AND）。任一條件成立就要通知的話，請改用個別的「新增警示」。複合條件不做盤中回溯補抓，只在每 2 分鐘的即時檢查判定。
          </span>
        </template>
      </el-alert>

      <el-form :model="groupForm" label-width="110px">
        <el-divider content-position="left">股票</el-divider>

        <el-form-item label="市場" required>
          <el-radio-group v-model="groupForm.market">
            <el-radio value="台股">台股</el-radio>
            <el-radio value="美股">美股</el-radio>
            <el-radio value="英股">英股</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="股票代號" required>
          <el-input v-model="groupForm.stockCode" placeholder="例：2330 或 AAPL"
            style="width:140px;margin-right:8px"
            :suffix-icon="lookingUpCode ? Loading : undefined"
            @blur="fetchStockName(groupForm)" />
          <el-input v-model="groupForm.stockName" placeholder="或輸入股名自動帶代號"
            style="width:200px"
            :suffix-icon="lookingUpName ? Loading : undefined"
            @blur="fetchStockCode(groupForm)" />
        </el-form-item>

        <el-divider content-position="left">
          警示條件（{{ groupForm.conditions.length }} / {{ MAX_GROUP_CONDITIONS }}）
        </el-divider>

        <div v-for="(c, idx) in groupForm.conditions" :key="c.uid" class="cond-row">
          <div class="cond-row-head">
            <span class="cond-row-no">
              <span v-if="idx > 0" class="cond-row-and">且</span>條件 {{ idx + 1 }}
            </span>
            <el-button size="small" type="danger" text :icon="Delete"
              :disabled="groupForm.conditions.length <= MIN_GROUP_CONDITIONS"
              @click="removeCondition(idx)">移除</el-button>
          </div>
          <div class="cond-row-body">
            <el-select v-model="c.conditionGroup" style="width:200px" @change="resetConditionDefaults(c)">
              <el-option value="PRICE"  label="價位" />
              <el-option value="MA_20"  label="月線偏離（20 日均線）" />
              <el-option value="MA_60"  label="季線偏離（60 日均線）" />
              <el-option value="MA_240" label="年線偏離（240 日均線）" />
              <el-option value="KD"     label="KD 值" />
            </el-select>
            <el-select v-if="c.conditionGroup === 'KD'" v-model="c.kdIndicator" style="width:92px">
              <el-option value="K" label="K 值" />
              <el-option value="D" label="D 值" />
            </el-select>
            <el-select v-model="c.direction" style="width:132px">
              <el-option value="ABOVE" :label="isMaCondition(c) ? `高於${maNameOf(c)}` : '高於'" />
              <el-option value="BELOW" :label="isMaCondition(c) ? `低於${maNameOf(c)}` : '低於'" />
            </el-select>
            <!-- 價位：金額；均線：固定五段幅度（與單一條件 dialog 同一組）；KD：0～100 門檻值 -->
            <el-input-number v-if="c.conditionGroup === 'PRICE'" v-model="c.priceThreshold"
              :min="0" :precision="2" :step="1" style="width:150px" />
            <el-select v-else-if="isMaCondition(c)" v-model="c.threshold" style="width:100px">
              <el-option v-for="p in MA_PCT_OPTIONS" :key="p" :value="p" :label="`${p}%`" />
            </el-select>
            <el-input-number v-else v-model="c.threshold" :min="0" :max="100" :step="1" style="width:130px" />
          </div>
        </div>

        <div class="cond-actions">
          <el-button size="small" :icon="Plus"
            :disabled="groupForm.conditions.length >= MAX_GROUP_CONDITIONS"
            @click="addCondition">新增條件</el-button>
          <span style="margin-left:8px;color:#94a3b8;font-size:12px">
            {{ MIN_GROUP_CONDITIONS }}～{{ MAX_GROUP_CONDITIONS }} 個條件；全部同時成立才觸發
          </span>
        </div>
        <div class="cond-preview">合併後：{{ groupPreview }}</div>

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
            <el-checkbox-group v-model="groupForm.recipientIds" style="display:flex;flex-direction:column">
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
          <el-switch v-model="groupForm.active" />
        </el-form-item>
      </el-form>

      <template #footer>
        <span v-if="groupForm.conditions.length < MIN_GROUP_CONDITIONS"
          style="float:left;color:#f56c6c;font-size:12px;line-height:32px">
          複合條件至少需要 {{ MIN_GROUP_CONDITIONS }} 個條件
        </span>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="groupSaving"
          :disabled="groupForm.conditions.length < MIN_GROUP_CONDITIONS"
          @click="saveGroup">儲存</el-button>
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
// 全選 / 半選的運算單一條件與複合條件兩個 dialog 共用（同一段語意不該各寫一份），
// 作用對象依目前開啟的 dialog 決定 —— 兩個 dialog 不會同時開啟（Task 253）
const recipientForm = computed(() => (groupDialogVisible.value ? groupForm : form))
const recipientAllChecked = computed({
  get: () => recipients.value.length > 0
    && recipientForm.value.recipientIds.length === recipients.value.length,
  set: (val) => { recipientForm.value.recipientIds = val ? allRecipientIds.value.slice() : [] }
})
const recipientIndeterminate = computed(() =>
  recipientForm.value.recipientIds.length > 0
  && recipientForm.value.recipientIds.length < recipients.value.length)

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
const MA_PCT_OPTIONS = [0, 5, 10, 15, 20]   // 均線偏離幅度的固定五段（複合條件以 select 呈現，省版面）

// 以下四個 helper 接「條件物件」而非只讀全域 form，讓單一條件 dialog 與複合條件的每一列共用同一份組裝邏輯
const isMaCondition = (c) => c.conditionGroup in MA_GROUPS
const maNameOf = (c) => MA_NAMES[MA_GROUPS[c.conditionGroup]] || '均線'
const maPeriodOf = (c) => (isMaCondition(c) ? MA_GROUPS[c.conditionGroup] : null)
const thresholdOf = (c) => (c.conditionGroup === 'PRICE' ? c.priceThreshold : c.threshold)

const isMaGroup = computed(() => isMaCondition(form))
const maName = computed(() => maNameOf(form))

function rowMaLabel(row) {
  return MA_NAMES[row.maPeriod] || '均線'
}

// 混合清單的列識別：群組 id 與獨立條件 id 分屬兩張表、值會相撞，必須帶 kind 才唯一（Task 253）
function rowKey(row) {
  return `${row.kind}-${row.id}`
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
      // Task 253：獨立條件與群組共用同一個排序空間，但 id 分屬兩張表、值會重疊，
      // 故送 [{kind, id}] 而非單純 id 陣列，後端才知道要更新哪一張表
      bffApi.stockAlert.reorder(
        [...twAlerts.value, ...usAlerts.value, ...ukAlerts.value].map(a => ({ kind: a.kind, id: a.id })))
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
    parseAlertType(form, row.alertType, row.threshold, row.maPeriod)
  } else {
    form.recipientIds = allRecipientIds.value.slice()   // 新警示預設全選
  }
  dialogVisible.value = true
}

/**
 * alertType 反解回表單欄位。target 為要寫入的條件物件 —— 複合條件編輯時要逐條反解回
 * groupForm.conditions 的各列，不能像 Task 253 之前那樣寫死全域 form。
 */
function parseAlertType(target, alertType, threshold, maPeriod) {
  if (alertType.startsWith('PRICE')) {
    target.conditionGroup = 'PRICE'
    target.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    target.priceThreshold = Number(threshold)
  } else if (alertType.startsWith('MA_')) {
    target.conditionGroup = `MA_${maPeriod}`
    target.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    target.threshold = Number(threshold)
  } else {
    target.conditionGroup = 'KD'
    target.kdIndicator = alertType.startsWith('KD_D') ? 'D' : 'K'
    target.direction = alertType.includes('ABOVE') ? 'ABOVE' : 'BELOW'
    target.threshold = Number(threshold)
  }
}

// 條件類型改變時重設同列其餘欄位（KD 預設 80、其餘 5%），避免留下前一個類型的門檻值
function resetConditionDefaults(c) {
  c.direction = 'ABOVE'
  c.kdIndicator = 'K'
  c.threshold = c.conditionGroup === 'KD' ? 80 : 5
  c.priceThreshold = 0
}

function onGroupChange() {
  resetConditionDefaults(form)
}

function buildAlertType(c) {
  const dir = c.direction
  if (c.conditionGroup === 'PRICE') return `PRICE_${dir}`
  if (isMaCondition(c))             return `MA_${dir}_PCT`
  return c.kdIndicator === 'D' ? `KD_D_${dir}` : `KD_${dir}`
}

// target 預設是單一條件 dialog 的 form；複合條件 dialog 傳 groupForm 進來共用同一支查詢
async function fetchStockName(target = form) {
  if (!target.stockCode || !target.market) return
  lookingUpName.value = true
  try {
    const res = await bffApi.stockAlert.lookupName({
      code: target.stockCode.trim().toUpperCase(), market: target.market
    })
    if (res.stockName) {
      target.stockName = res.stockName
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
async function fetchStockCode(target = form) {
  if (target.stockCode || !target.stockName || !target.market) return
  lookingUpCode.value = true
  try {
    const res = await bffApi.stockAlert.lookupCode({
      name: target.stockName.trim(), market: target.market
    })
    if (res.stockCode) {
      target.stockCode = res.stockCode
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
      alertType: buildAlertType(form),
      maPeriod: maPeriodOf(form),
      threshold: thresholdOf(form),
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

// ===== 複合條件（Task 253）=====
// 一個群組綁 2～5 個條件，全部在同一次檢查中同時成立才觸發一次（單層 AND，不支援 OR / 巢狀）。
const MIN_GROUP_CONDITIONS = 2
const MAX_GROUP_CONDITIONS = 5
const groupDialogVisible = ref(false)
const groupEditId = ref(null)
const groupSaving = ref(false)

// v-for 的 key 用 uid 而非陣列索引：移除中間某一列時，索引 key 會讓 Vue 沿用原本的 DOM，
// 下拉選單顯示的值會與底層資料錯位
let conditionUid = 0
const defaultCondition = () => ({
  uid: ++conditionUid,
  conditionGroup: 'MA_60',
  direction: 'BELOW',
  kdIndicator: 'K',
  threshold: 5,
  priceThreshold: 0,
})
const groupForm = reactive({
  market: '台股',
  stockCode: '',
  stockName: '',
  active: true,
  recipientIds: [],
  conditions: [],
})

// 表單即時預覽用的單條文案，與後端 StockAlertService.buildLabel 同口徑
// （後端在 MA 條件另會附上換算後的觸發價，此處無指標資料故省略；存檔後清單顯示的是後端版本）
function conditionPreview(c) {
  const dir = c.direction === 'ABOVE' ? '高於' : '低於'
  if (c.conditionGroup === 'PRICE') return `股價${dir} ${c.priceThreshold ?? 0}`
  if (isMaCondition(c)) {
    return Number(c.threshold) === 0 ? `${dir}${maNameOf(c)}` : `${dir}${maNameOf(c)} ${c.threshold}%`
  }
  return `${c.kdIndicator} 值${dir} ${c.threshold ?? 0}`
}
// 分隔符「 且 」（半形空白 + 且 + 半形空白）與後端 buildGroupLabel 逐字一致
const groupPreview = computed(() => groupForm.conditions.map(conditionPreview).join(' 且 '))

function openGroupDialog(row = null) {
  Object.assign(groupForm, {
    market: marketTab.value,   // 預設跟隨當前市場 tab（比照單一條件 dialog）
    stockCode: '',
    stockName: '',
    active: true,
    recipientIds: [],
    conditions: [],
  })
  groupEditId.value = null
  if (row) {
    groupEditId.value = row.id
    groupForm.market = row.market
    groupForm.stockCode = row.stockCode
    groupForm.stockName = row.stockName || ''
    groupForm.active = row.active
    groupForm.recipientIds = Array.isArray(row.recipientIds) ? [...row.recipientIds] : []
    // 逐條反解回表單列；順序即後端成員 displayOrder 升冪，與合併 label 的串接順序一致
    groupForm.conditions = (row.conditions || []).map(c => {
      const item = defaultCondition()
      parseAlertType(item, c.alertType, c.threshold, c.maPeriod)
      return item
    })
  } else {
    groupForm.recipientIds = allRecipientIds.value.slice()   // 新警示預設全選（比照單一條件）
  }
  // 補滿到下限：新增時給兩列空白條件；編輯到異常資料（成員少於 2）時也不讓表單卡在無法儲存的狀態
  while (groupForm.conditions.length < MIN_GROUP_CONDITIONS) {
    groupForm.conditions.push(defaultCondition())
  }
  groupDialogVisible.value = true
}

function addCondition() {
  if (groupForm.conditions.length >= MAX_GROUP_CONDITIONS) return
  groupForm.conditions.push(defaultCondition())
}

function removeCondition(idx) {
  if (groupForm.conditions.length <= MIN_GROUP_CONDITIONS) return
  groupForm.conditions.splice(idx, 1)
}

async function saveGroup() {
  if (!groupForm.stockCode || !groupForm.market) {
    ElMessage.warning('請填寫股票代號與市場')
    return
  }
  if (groupForm.conditions.length < MIN_GROUP_CONDITIONS) {
    ElMessage.warning(`複合條件至少需要 ${MIN_GROUP_CONDITIONS} 個條件`)
    return
  }
  groupSaving.value = true
  try {
    const payload = {
      market: groupForm.market,
      stockCode: groupForm.stockCode.toUpperCase(),
      stockName: groupForm.stockName || null,
      conditions: groupForm.conditions.map(c => ({
        alertType: buildAlertType(c),
        maPeriod: maPeriodOf(c),
        threshold: thresholdOf(c),
      })),
      active: groupForm.active,
      recipientIds: groupForm.recipientIds,
    }
    if (groupEditId.value) {
      await bffApi.stockAlert.updateGroup(groupEditId.value, payload)
    } else {
      await bffApi.stockAlert.createGroup(payload)
    }
    ElMessage.success('儲存成功')
    groupDialogVisible.value = false
    loadAlerts()
    emit('alert-saved')
  } catch (e) {
    // 與單一條件同一套呈現：後端驗證訊息（組內條件重複、筆數超限、股名與代號不符）以 dialog 顯示，
    // 不走頂部 toast（createGroup / updateGroup 已帶 skipErrorToast）
    ElMessageBox.alert(apiErrorMessage(e, '儲存失敗'), '無法儲存警示', {
      type: 'warning',
      confirmButtonText: '我知道了',
    }).catch(() => {})
  } finally {
    groupSaving.value = false
  }
}

async function toggleActive(row) {
  try {
    // Task 253：群組與獨立條件是兩張表、id 會重疊，一律依 kind 分派端點
    const res = row.kind === 'GROUP'
      ? await bffApi.stockAlert.toggleGroupActive(row.id)
      : await bffApi.stockAlert.toggleActive(row.id)
    Object.assign(row, res.data)
  } catch {
    row.active = !row.active
    ElMessage.error('操作失敗')
  }
}

async function remove(row) {
  const isGroup = row.kind === 'GROUP'
  await ElMessageBox.confirm(
    `確定刪除 ${row.stockCode} 的「${row.conditionLabel}」${isGroup ? '複合條件' : '警示'}？`,
    isGroup ? '刪除複合條件' : '刪除警示', { type: 'warning' }
  )
  if (isGroup) await bffApi.stockAlert.deleteGroup(row.id)
  else await bffApi.stockAlert.delete(row.id)
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

/* 複合條件 dialog 的條件列（Task 253）：每列一組「類型／方向／門檻」，行首以「且」提示 AND 語意 */
.cond-row {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px 10px;
  margin: 0 0 8px 110px;   /* 左邊距對齊 el-form 的 label-width */
  background: #f8fafc;
}
.cond-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.cond-row-no { font-size: 13px; color: #475569; font-weight: 600; }
.cond-row-and { color: #d97706; margin-right: 6px; }
.cond-row-body { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.cond-actions { margin: 0 0 6px 110px; }
.cond-preview {
  margin: 0 0 4px 110px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.6;
}
</style>
