<template>
  <div v-loading="loading" :element-loading-text="generating ? '送出批次分析中…' : '載入中…'">
    <!-- 頂列：標題 + 重新分析 -->
    <div class="header-row">
      <div>
        <span class="page-heading">今日股市分析</span>
        <span class="page-sub">每個台股交易日 08:45 由 AI 綜合台股/美股走勢與近期財經新聞判斷當日走向</span>
      </div>
      <div v-if="auth.isAdmin" class="header-actions">
        <span class="model-label">每日自動分析</span>
        <el-switch
          v-model="enabledFlag"
          :disabled="busy"
          inline-prompt
          active-text="開"
          inactive-text="關"
          title="停用後每日 08:45 不自動分析（零花費）；仍可手動按「重新分析」"
          @change="onEnabledChange"
        />
        <span class="model-label">分析模型</span>
        <el-select
          v-model="selectedModel"
          size="default"
          style="width: 210px"
          :disabled="busy"
          title="切換分析模型（下次分析生效）"
          @change="onModelChange"
        >
          <el-option
            v-for="m in availableModels"
            :key="m.id"
            :label="m.label"
            :value="m.id"
          />
        </el-select>
        <span class="model-label">思考深度</span>
        <el-select
          v-model="selectedEffort"
          size="default"
          style="width: 170px"
          :disabled="busy"
          title="切換思考深度 effort（越低越省，下次分析生效）"
          @change="onEffortChange"
        >
          <el-option
            v-for="e in availableEfforts"
            :key="e.id"
            :label="e.label"
            :value="e.id"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="Refresh"
          :loading="generating"
          @click="regenerate"
        >重新分析</el-button>
      </div>
    </div>

    <!-- 未設定金鑰 -->
    <el-alert
      v-if="today && today.status === 'NOT_CONFIGURED'"
      type="warning" show-icon :closable="false" style="margin-bottom:16px"
      title="尚未設定 Anthropic API 金鑰"
      description="請於部署環境設定 ANTHROPIC_API_KEY 後，排程或按「重新分析」即可產生今日股市分析。"
    />
    <!-- 失敗 -->
    <el-alert
      v-else-if="today && today.status === 'FAILED'"
      type="error" show-icon :closable="false" style="margin-bottom:16px"
      title="今日分析產生失敗"
      :description="today.errorMessage || '請稍後重試，或由管理者按「重新分析」。'"
    />
    <!-- 批次處理中（Batch API 非同步；poller 完成後自動更新） -->
    <el-alert
      v-else-if="today && today.status === 'PROCESSING'"
      type="info" show-icon :closable="false" style="margin-bottom:16px"
      title="分析中（批次處理中）"
      description="已透過 Batch API 送出分析（省 50% 成本），完成後畫面會自動更新——通常數分鐘，最長不超過數十分鐘。"
    />
    <!-- 尚無資料 -->
    <el-empty
      v-else-if="!today || today.status === 'NONE'"
      :description="emptyDesc"
    />

    <!-- 今日判斷卡片 -->
    <el-card v-if="isOk" shadow="never" class="main-card" :style="biasBorder">
      <div class="bias-banner" :style="{ background: biasColor }">
        <div class="bias-main">
          <span class="bias-label">{{ biasText }}</span>
          <span class="bias-date">{{ today.analysisDate }}　台股當日走向研判</span>
        </div>
        <div class="bias-conf" v-if="today.confidence != null">
          <span class="conf-num">{{ today.confidence }}</span><span class="conf-unit"> / 100 信心</span>
        </div>
      </div>

      <div class="summary" v-if="today.summary">{{ today.summary }}</div>

      <el-row :gutter="16">
        <el-col :xs="24" :md="12">
          <div class="block-title">關鍵因素</div>
          <ul class="factor-list" v-if="today.keyFactors && today.keyFactors.length">
            <li v-for="(f, i) in today.keyFactors" :key="i">{{ f }}</li>
          </ul>
          <div v-else class="muted">—</div>
        </el-col>
        <el-col :xs="24" :md="12">
          <div class="block-title">參考新聞</div>
          <ul class="news-list" v-if="today.newsHighlights && today.newsHighlights.length">
            <li v-for="(n, i) in today.newsHighlights" :key="i">
              <a v-if="safeUrl(n.url)" :href="safeUrl(n.url)" target="_blank" rel="noopener noreferrer">{{ n.title }}</a>
              <span v-else>{{ n.title }}</span>
              <span class="news-meta">{{ [n.source, n.publishedAt].filter(Boolean).join(' · ') }}</span>
            </li>
          </ul>
          <div v-else class="muted">—</div>
        </el-col>
      </el-row>

      <el-row :gutter="16" style="margin-top:8px">
        <el-col :xs="24" :md="12" v-if="today.twContext">
          <div class="block-title">台股近期走勢</div>
          <div class="context-text">{{ today.twContext }}</div>
        </el-col>
        <el-col :xs="24" :md="12" v-if="today.usContext">
          <div class="block-title">美股近期走勢</div>
          <div class="context-text">{{ today.usContext }}</div>
        </el-col>
      </el-row>

      <div class="foot-meta">
        由 {{ today.model || 'Claude' }} 產生於 {{ formatTime(today.generatedAt) }}
        <span class="disclaimer">　·　本分析由 AI 產生，僅供參考，不構成投資建議</span>
      </div>
    </el-card>

    <!-- 歷史 -->
    <el-card shadow="never" style="margin-top:16px" v-if="history.length">
      <template #header><span class="section-title">歷史判斷</span></template>
      <el-table :data="history" size="small" style="width:100%">
        <el-table-column prop="analysisDate" label="日期" width="120" />
        <el-table-column label="方向" width="90">
          <template #default="{ row }">
            <el-tag :type="tagType(row.bias)" size="small" effect="dark">{{ biasLabel(row.bias) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="confidence" label="信心" width="80">
          <template #default="{ row }">{{ row.confidence != null ? row.confidence : '—' }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="總結" show-overflow-tooltip>
          <template #default="{ row }">{{ row.status === 'OK' ? row.summary : statusLabel(row.status) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 分析結果寄送對象（Task 151）：沿用通知收件人，選擇哪些 email 每日自動收到分析 -->
    <el-card shadow="never" style="margin-top:16px">
      <template #header>
        <div class="recipients-head">
          <span class="section-title">分析結果寄送對象</span>
          <span class="recipients-hint">每個台股交易日 08:45 分析完成後，自動寄給下方開啟「接收」的收件人</span>
        </div>
      </template>
      <el-table v-if="recipients.length" :data="recipients" size="small" style="width:100%">
        <el-table-column prop="email" label="Email" show-overflow-tooltip />
        <el-table-column label="接收每日股市分析" width="180" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.receiveMarketAnalysis"
              :loading="togglingId === row.id"
              :disabled="togglingId === row.id"
              @change="(val) => toggleRecipient(row, val)"
            />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="60" description="尚無收件人，請至「系統設定 → 通知設定」新增" />
      <div v-if="recipients.length" class="recipients-foot">
        新增／刪除收件人請至「系統設定 → 通知設定」；此處僅選擇是否接收每日股市分析。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const loading = ref(false)
const generating = ref(false)
const savingModel = ref(false)
const savingEffort = ref(false)
const savingEnabled = ref(false)
const today = ref(null)
const history = ref([])
const settings = ref({ model: '', effort: '', enabled: true, availableModels: [], availableEfforts: [] })
const selectedModel = ref('')
const selectedEffort = ref('')
const enabledFlag = ref(true)
const recipients = ref([])
const togglingId = ref(null)

const isOk = computed(() => today.value && today.value.status === 'OK')
const availableModels = computed(() => settings.value.availableModels || [])
const availableEfforts = computed(() => settings.value.availableEfforts || [])
// 任一設定儲存中或分析中 → 所有控制項停用，避免併發覆蓋
const busy = computed(() => generating.value || savingModel.value || savingEffort.value || savingEnabled.value)
// 尚無資料時的說明文字：停用中則點明「已停用、需手動」
const emptyDesc = computed(() => settings.value.enabled === false
  ? '每日自動分析已停用；由管理者按「重新分析」手動產生'
  : '尚無分析結果（等待下一個交易日 08:45 排程，或由管理者手動觸發）')

const biasText = computed(() => biasLabel(today.value?.bias))
const biasColor = computed(() => biasHex(today.value?.bias))
const biasBorder = computed(() => ({ borderTop: `3px solid ${biasHex(today.value?.bias)}` }))

// 台股漲紅跌綠：偏多紅、偏空綠、中性灰
function biasLabel(bias) {
  switch (bias) {
    case 'BULLISH': return '偏多'
    case 'BEARISH': return '偏空'
    case 'NEUTRAL': return '中性'
    default: return '未定'
  }
}
function biasHex(bias) {
  switch (bias) {
    case 'BULLISH': return '#c0392b'
    case 'BEARISH': return '#27ae60'
    case 'NEUTRAL': return '#7f8c8d'
    default: return '#909399'
  }
}
function tagType(bias) {
  switch (bias) {
    case 'BULLISH': return 'danger'
    case 'BEARISH': return 'success'
    case 'NEUTRAL': return 'info'
    default: return 'info'
  }
}
function statusLabel(status) {
  if (status === 'NOT_CONFIGURED') return '（未設定金鑰）'
  if (status === 'FAILED') return '（產生失敗）'
  if (status === 'PROCESSING') return '（分析中）'
  return '—'
}
// 新聞連結來自 web_search（不可信）；只允許 http(s)，擋 javascript:/data: 等 scheme（防 XSS）。後端另有一層過濾。
function safeUrl(url) {
  if (typeof url !== 'string') return null
  const u = url.trim()
  return /^https?:\/\//i.test(u) ? u : null
}
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return isNaN(d.getTime()) ? ts : d.toLocaleString('zh-TW', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const data = await bffApi.todayMarketAnalysis.get(30)
    today.value = data.today || null
    history.value = data.history || []
    settings.value = data.settings && data.settings.availableModels
      ? data.settings
      : { model: '', effort: '', enabled: true, availableModels: [], availableEfforts: [] }
    selectedModel.value = settings.value.model || ''
    selectedEffort.value = settings.value.effort || ''
    enabledFlag.value = settings.value.enabled !== false
  } finally {
    loading.value = false
  }
}

// 分析結果寄送對象（Task 151）：載入使用者自己的收件人（owner-scoped）。
async function loadRecipients() {
  try {
    recipients.value = await bffApi.todayMarketAnalysis.getRecipients()
  } catch (e) {
    recipients.value = []
  }
}

// 切換某收件人「接收每日股市分析」訂閱；後端 PATCH 翻轉，回傳權威值。失敗還原開關。
async function toggleRecipient(row, val) {
  togglingId.value = row.id
  try {
    const res = await bffApi.todayMarketAnalysis.toggleMarketAnalysis(row.id)
    row.receiveMarketAnalysis = res.receiveMarketAnalysis
    ElMessage.success(row.receiveMarketAnalysis ? `已訂閱：${row.email}` : `已取消訂閱：${row.email}`)
  } catch (e) {
    row.receiveMarketAnalysis = !val   // 還原（錯誤 toast 由 api 攔截器統一處理）
  } finally {
    togglingId.value = null
  }
}

// 管理者切換分析模型 → 持久化；成功後下次分析（排程或手動）生效。失敗則還原選項。
async function onModelChange(model) {
  savingModel.value = true
  try {
    const res = await bffApi.todayMarketAnalysis.updateSettings({ model })
    if (res && res.model) {
      settings.value = res
      selectedModel.value = res.model
      selectedEffort.value = res.effort || ''
    }
    ElMessage.success('已切換分析模型，下次分析生效')
  } catch (e) {
    selectedModel.value = settings.value.model || ''  // 還原
  } finally {
    savingModel.value = false
  }
}

// 管理者切換思考深度 effort（成本控管）→ 持久化；成功後下次分析生效。失敗則還原選項。
async function onEffortChange(effort) {
  savingEffort.value = true
  try {
    const res = await bffApi.todayMarketAnalysis.updateSettings({ effort })
    if (res && res.effort) {
      settings.value = res
      selectedModel.value = res.model || ''
      selectedEffort.value = res.effort
    }
    ElMessage.success('已切換思考深度，下次分析生效')
  } catch (e) {
    selectedEffort.value = settings.value.effort || ''  // 還原
  } finally {
    savingEffort.value = false
  }
}

// 管理者切換「每日自動分析」開關 → 持久化。停用＝08:45 cron 跳過（零花費）；手動仍可跑。失敗則還原。
async function onEnabledChange(enabled) {
  savingEnabled.value = true
  try {
    const res = await bffApi.todayMarketAnalysis.updateSettings({ enabled })
    if (res && res.enabled != null) {
      settings.value = res
      enabledFlag.value = res.enabled
    }
    ElMessage.success(enabled ? '已啟用每日自動分析' : '已停用每日自動分析（仍可手動重新分析）')
  } catch (e) {
    enabledFlag.value = settings.value.enabled !== false  // 還原
  } finally {
    savingEnabled.value = false
  }
}

async function regenerate() {
  generating.value = true
  loading.value = true
  try {
    await bffApi.todayMarketAnalysis.generate()
    ElMessage.success('已送出分析（批次處理中，完成後自動更新）')
    await load()
  } catch (e) {
    // 錯誤 toast 由 api 攔截器統一處理
  } finally {
    generating.value = false
    loading.value = false
  }
}

// PROCESSING（批次在製）時每 30 秒自動 reload，直到狀態改變；離開頁面時清除。
let pollTimer = null
function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
watch(() => today.value && today.value.status, (status) => {
  if (status === 'PROCESSING') {
    if (!pollTimer) pollTimer = setInterval(load, 30000)
  } else {
    stopPoll()
  }
})

// 多 panel 並行載入（分析結果 + 寄送對象收件人），互不阻塞
onMounted(() => { Promise.allSettled([load(), loadRecipients()]) })
onUnmounted(stopPoll)
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
}
.page-heading { font-size: 20px; font-weight: 700; color: #1e293b; }
.page-sub { display: block; font-size: 13px; color: #94a3b8; margin-top: 4px; }
.header-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.model-label { font-size: 13px; color: #64748b; }
.main-card { overflow: hidden; }
.bias-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fff;
  padding: 16px 20px;
  border-radius: 6px;
  margin-bottom: 16px;
}
.bias-main { display: flex; align-items: baseline; gap: 14px; flex-wrap: wrap; }
.bias-label { font-size: 30px; font-weight: 800; letter-spacing: 2px; }
.bias-date { font-size: 14px; opacity: 0.9; }
.bias-conf { text-align: right; white-space: nowrap; }
.conf-num { font-size: 30px; font-weight: 800; }
.conf-unit { font-size: 13px; opacity: 0.9; }
.summary {
  font-size: 15px;
  line-height: 1.8;
  color: #334155;
  margin-bottom: 16px;
}
.block-title {
  font-weight: 700;
  color: #1e293b;
  margin: 10px 0 6px;
  border-left: 3px solid #cbd5e1;
  padding-left: 8px;
}
.factor-list, .news-list { margin: 0; padding-left: 18px; }
.factor-list li { line-height: 1.9; color: #334155; }
.news-list { list-style: none; padding-left: 0; }
.news-list li { line-height: 1.6; margin-bottom: 8px; }
.news-list a { color: #2563eb; text-decoration: none; }
.news-list a:hover { text-decoration: underline; }
.news-meta { display: block; font-size: 12px; color: #94a3b8; }
.context-text { font-size: 14px; line-height: 1.8; color: #475569; }
.muted { color: #94a3b8; }
.foot-meta {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
  font-size: 12px;
  color: #94a3b8;
}
.disclaimer { color: #cbd5e1; }
.section-title { font-weight: 700; color: #1e293b; }
.recipients-head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.recipients-hint { font-size: 12px; color: #94a3b8; }
.recipients-foot { margin-top: 10px; font-size: 12px; color: #94a3b8; }
</style>
