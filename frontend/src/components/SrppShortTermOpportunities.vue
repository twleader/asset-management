<template>
  <section class="short-term-panel" aria-labelledby="srpp-short-term-title">
    <div class="short-term-heading">
      <div>
        <h3 id="srpp-short-term-title">短線策略草稿與目前雷達觀察</h3>
        <p>短線參數會沿用上方草稿保存；雷達清單只讀取此帳號目前資料。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" :disabled="!canRefresh" @click="loadList">
        重新讀取目前雷達
      </el-button>
    </div>

    <el-alert type="warning" show-icon :closable="false" class="short-notice"
      title="草稿參數尚未套用正式雷達；短線報酬未納入退休試算。"
      description="清單保留來源短線最終方向，不代表完整 SRPP 採用條件或交易授權已通過；本頁不會送出委託。" />

    <div class="strategy-grid">
      <article v-for="group in settingGroups" :key="group.key" class="strategy-card">
        <div class="strategy-title">
          <strong>{{ group.label }}</strong>
          <el-tag size="small" type="warning" effect="plain">僅存草稿／不納入退休模型</el-tag>
        </div>
        <div class="strategy-fields">
          <div v-for="field in group.fields" :key="field.id" class="strategy-field">
            <label :for="inputId(field)">{{ field.label }}</label>
            <p v-if="field.help" class="strategy-help">{{ field.help }}</p>
            <el-select v-if="field.type === 'symbols'" :id="inputId(field)" :model-value="arrayValue(values[field.id])"
              multiple collapse-tags collapse-tags-tooltip filterable clearable :disabled="disabled || !field.editable"
              placeholder="選擇來源白名單內標的" @update:model-value="emitValue(field.id, $event)">
              <el-option v-for="symbol in field.allowedSymbols || []" :key="symbol" :label="symbol" :value="symbol" />
            </el-select>
            <el-input v-else :id="inputId(field)" type="number" inputmode="decimal" :step="inputStep(field)"
              :min="displayBound(field.min, field)" :max="displayBound(field.max, field)"
              :model-value="displayNumber(values[field.id], field)" :disabled="disabled || !field.editable"
              @update:model-value="emitNumeric(field, $event)">
              <template #append>{{ unitLabel(field) }}</template>
            </el-input>
            <div v-if="field.min != null || field.max != null" class="strategy-bound">
              範圍：{{ displayBound(field.min, field) ?? '不限' }} 至 {{ displayBound(field.max, field) ?? '不限' }} {{ unitLabel(field) }}
            </div>
            <p v-if="errorFor(field.id)" class="strategy-error">{{ errorFor(field.id) }}</p>
          </div>
        </div>
      </article>
    </div>

    <div class="radar-heading">
      <div>
        <h4>目前雷達方向</h4>
        <p v-if="draftId && !draftIsCurrent" class="radar-scope warning-text">
          目前草稿來自舊版設定識別；為避免混用，短線清單尚未查詢。請以目前版本另存新草稿。
        </p>
        <p v-else-if="draftId && !draftIsSaved" class="radar-scope">
          草稿尚有未儲存修改；下方清單仍使用正式策略設定，未套用畫面中的短線草稿值。
        </p>
        <p v-else-if="draftIsSaved" class="radar-scope">
          正式參數與已儲存草稿參數並列預覽；草稿標的只縮小觀察範圍，其餘門檻尚未套用正式雷達方向。
        </p>
        <p v-else class="radar-scope">目前依正式策略設定讀取雷達；尚未建立草稿。</p>
      </div>
      <el-tag v-if="listData" :type="listData.configurationStatus === 'FORMAL' ? 'success' : 'warning'" effect="plain">
        {{ configurationLabel(listData.configurationStatus) }}
      </el-tag>
    </div>

    <el-alert v-if="listError" type="error" show-icon :closable="false" class="short-alert"
      title="目前無法載入短線雷達" :description="listError" />
    <el-alert v-if="listData && listData.thresholdsAppliedToRadar !== false" type="error" show-icon :closable="false"
      title="雷達設定預覽識別不符" description="來源未確認草稿門檻與正式雷達隔離；本頁不顯示此回應。" />

    <template v-if="listData && listData.thresholdsAppliedToRadar === false">
      <div class="radar-meta">
        <el-tag v-for="market in listData.markets || []" :key="market.market"
          :type="marketTagType(market)" effect="plain">
          {{ market.market }}：{{ marketStatusLabel(market) }}<template v-if="market.asOfDate">（{{ market.asOfDate }}）</template>
        </el-tag>
        <span>讀取時間：{{ formatTime(listData.generatedAt) }}</span>
        <span>規則版本：{{ listData.ruleVersion || '—' }}</span>
        <span>方向政策：{{ listData.actionPolicyVersion || '—' }}</span>
      </div>

      <div class="policy-grid">
        <article v-for="policy in listData.policies || []" :key="policy.group" class="policy-card">
          <strong>{{ policy.label || policy.group }}</strong>
          <div class="policy-values">
            <div>
              <b>正式參數</b>
              <p>{{ formatPolicyValues(policy.formalValues, policy.group) }}</p>
            </div>
            <div v-if="policy.draftValues">
              <b>已儲存草稿預覽</b>
              <p>{{ formatPolicyValues(policy.draftValues, policy.group) }}</p>
            </div>
          </div>
          <p v-if="policy.entrySummary" class="policy-summary"><b>來源進場規則：</b>{{ policy.entrySummary }}</p>
          <p v-if="policy.exitSummary" class="policy-summary"><b>來源退出規則：</b>{{ policy.exitSummary }}</p>
          <details v-if="policy.entryRule || policy.exitRule" class="source-rules">
            <summary>完整來源規則（進階）</summary>
            <p v-if="policy.entryRule"><b>來源進場規則：</b>{{ policy.entryRule }}</p>
            <p v-if="policy.exitRule"><b>來源退出規則：</b>{{ policy.exitRule }}</p>
          </details>
          <p class="policy-retirement">退休試算影響：{{ policy.retirementImpact === 'NOT_MODELED' ? '未建模' : '未提供' }}</p>
        </article>
      </div>

      <div v-if="listData.missingSymbols?.length" class="missing-symbols">
        <div class="subsection-heading">設定標的目前沒有雷達資料</div>
        <el-tag v-for="item in listData.missingSymbols" :key="`${item.market}:${item.stockCode}`" type="info" effect="plain">
          {{ item.market }} {{ item.stockCode }}：等待資料
        </el-tag>
      </div>

      <section v-for="bucket in listData.observationBuckets" :key="bucket.key" class="observation-section">
        <div class="subsection-heading">{{ bucket.label }}</div>
        <el-table :data="bucket.rows" :row-key="rowKey" size="small" stripe
          :empty-text="bucket.emptyText" @expand-change="handleExpandChange">
          <el-table-column type="expand" width="42">
            <template #default="{ row }">
              <div class="detail-panel">
                <p v-if="detailState(row).loading" class="detail-muted">正在讀取此標的短線理由與風險…</p>
                <template v-else-if="detailState(row).data" >
                  <div class="detail-header">
                    <strong>{{ row.market }} {{ row.stockCode }} {{ detailState(row).data.stockName || '' }}</strong>
                    <el-tag :type="detailState(row).data.status === 'RADAR_OBSERVATION' ? 'success' : 'warning'" effect="plain">
                      {{ detailStatusLabel(detailState(row).data) }}
                    </el-tag>
                    <el-button link size="small" :disabled="detailState(row).loading" @click="loadDetail(row, true)">重新讀取此標的</el-button>
                  </div>
                  <div class="detail-meta">
                    <span>來源短線方向：{{ detailState(row).data.shortActionLabel || '—' }}</span>
                    <span>短線分數：{{ detailState(row).data.shortScore ?? '—' }}</span>
                    <span>資料完成：{{ detailState(row).data.dataComplete ? '是' : '否' }}</span>
                    <span>評估時間：{{ formatTime(detailState(row).data.generatedAt) }}</span>
                  </div>
                  <div class="detail-columns">
                    <div>
                      <b>短線理由</b>
                      <ul><li v-for="(reason, index) in detailState(row).data.shortReasons || []" :key="`reason-${index}`">{{ reason }}</li></ul>
                      <p v-if="!detailState(row).data.shortReasons?.length" class="detail-muted">來源未提供短線理由。</p>
                    </div>
                    <div>
                      <b>短線風險</b>
                      <ul><li v-for="(risk, index) in detailState(row).data.shortRisks || []" :key="`risk-${index}`">{{ risk }}</li></ul>
                      <p v-if="!detailState(row).data.shortRisks?.length" class="detail-muted">來源未提供短線風險。</p>
                    </div>
                  </div>
                  <div class="reference-box">
                    <b>完成日布林參考區間</b>
                    <span>日期：{{ detailState(row).data.priceReference?.asOfDate || '—' }}</span>
                    <span>適用方向：{{ detailState(row).data.priceReference?.applicableSide || '—' }}</span>
                    <span>買進側：{{ formatPrice(detailState(row).data.priceReference?.buyLower) }} 至 {{ formatPrice(detailState(row).data.priceReference?.buyUpper) }}</span>
                    <span>賣出側：{{ formatPrice(detailState(row).data.priceReference?.sellLower) }} 至 {{ formatPrice(detailState(row).data.priceReference?.sellUpper) }}</span>
                    <p>布林參考不是可成交限價、停損價或報酬目標，也不是交易授權。</p>
                  </div>
                  <ul v-if="detailState(row).data.limitations?.length" class="detail-limitations">
                    <li v-for="(limitation, index) in detailState(row).data.limitations" :key="`limitation-${index}`">{{ limitation }}</li>
                  </ul>
                </template>
                <div v-else class="detail-error">
                  <span>{{ detailState(row).error || '尚無此標的詳細資料。' }}</span>
                  <el-button size="small" @click="loadDetail(row, true)">重新讀取此標的</el-button>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="stockCode" label="代號" width="100" />
          <el-table-column prop="stockName" label="名稱" min-width="130"><template #default="{ row }">{{ row.stockName || '—' }}</template></el-table-column>
          <el-table-column prop="market" label="市場" width="82" />
          <el-table-column prop="group" label="策略組別" min-width="120"><template #default="{ row }">{{ groupLabel(row.group) }}</template></el-table-column>
          <el-table-column prop="shortActionLabel" label="來源短線方向" min-width="150"><template #default="{ row }">{{ row.shortActionLabel || '—' }}</template></el-table-column>
          <el-table-column prop="shortScore" label="短線分數" width="100"><template #default="{ row }">{{ row.shortScore ?? '—' }}</template></el-table-column>
          <el-table-column label="參考價格" min-width="125"><template #default="{ row }">{{ formatPrice(row.price) }}</template></el-table-column>
          <el-table-column prop="quoteStatus" label="報價狀態" min-width="120"><template #default="{ row }">{{ quoteStatusLabel(row.quoteStatus) }}</template></el-table-column>
          <el-table-column prop="priceUpdatedAt" label="報價時間" min-width="165"><template #default="{ row }">{{ formatTime(row.priceUpdatedAt) }}</template></el-table-column>
          <el-table-column prop="dailyCandleAsOfDate" label="日 K 完成日" width="120"><template #default="{ row }">{{ row.dailyCandleAsOfDate || '—' }}</template></el-table-column>
          <el-table-column label="資料狀態" min-width="140"><template #default="{ row }">
            <el-tag :type="row.status === 'RADAR_OBSERVATION' ? 'success' : 'warning'" size="small" effect="plain">{{ rowStatusLabel(row) }}</el-tag>
            <span v-if="row.horizonConflict" class="conflict-mark">不同持有期間建議有分歧</span>
          </template></el-table-column>
        </el-table>
      </section>

      <el-collapse class="source-identities">
        <el-collapse-item name="source-identities">
          <template #title>來源識別與限制</template>
          <dl class="identity-grid">
            <dt>策略 bundle SHA-256</dt><dd>{{ listData.policyBundleSha256 || '—' }}</dd>
            <dt>模型輸入 SHA-256</dt><dd>{{ listData.modelInputsSha256 || '—' }}</dd>
            <dt>交易授權</dt><dd>{{ listData.tradeAuthorization === false ? '無；此頁僅供觀察' : '未確認' }}</dd>
          </dl>
          <ul class="limitations-list"><li v-for="(item, index) in listData.limitations || []" :key="index">{{ readableLimitation(item) }}</li></ul>
        </el-collapse-item>
      </el-collapse>
    </template>
    <div v-else-if="!listError && !loading && !(draftId && !draftIsCurrent)" class="radar-empty">目前尚無雷達資料。</div>
    <div v-if="loading" class="radar-loading">正在讀取此帳號目前的雷達資料…</div>
  </section>
</template>

<script setup>
import { Refresh } from '@element-plus/icons-vue'
import { bffApi, apiErrorMessage } from '@/api'
import {
  formatPreciseMoneyText,
  formatPreciseDecimal,
  validateShortStrategyValues
} from '@/utils/srppSettingsCenterFormatting'

const props = defineProps({
  fields: { type: Array, default: () => [] },
  values: { type: Object, default: () => ({}) },
  disabled: { type: Boolean, default: false },
  draftId: { type: String, default: null },
  draftRevision: { type: Number, default: null },
  draftIsCurrent: { type: Boolean, default: false },
  draftIsSaved: { type: Boolean, default: false },
  ownerKey: { type: String, default: '' }
})
const emit = defineEmits(['field-change'])

const listData = ref(null)
const listError = ref('')
const loading = ref(false)
const detailStates = ref({})
const disposed = ref(false)
let listSequence = 0
let detailEpoch = 0
const detailSequences = new Map()

const groupDefinitions = [
  { key: 'equity', label: '台股股票短線', ids: ['shortEquitySymbols', 'shortEquityReboundPct', 'shortEquityNetRewardRisk', 'shortEquitySleeveCap', 'shortEquityPositionCap', 'shortEquityMaxHoldingSessions'] },
  { key: 'bond', label: '台股債券波段', ids: ['shortBondSymbols', 'shortBondReboundPct', 'shortBondNetRewardRisk', 'shortBondSleeveCap', 'shortBondPositionCap', 'shortBondMaxHoldingSessions'] },
  { key: 'us', label: '美股短線觀察', ids: ['usTacticalNetUpsidePct', 'usTacticalBatchCap', 'usTacticalCompanyCap', 'usTacticalSleeveCap'] }
]
const settingGroups = computed(() => groupDefinitions.map(group => ({
  ...group,
  fields: group.ids.map(id => props.fields.find(field => field.id === id)).filter(Boolean)
})).filter(group => group.fields.length))
const validationErrors = computed(() => validateShortStrategyValues(props.values, props.fields))
const canRefresh = computed(() => !loading.value && !(props.draftId && !props.draftIsCurrent))

watch(() => [props.ownerKey, props.draftId, props.draftRevision, props.draftIsCurrent, props.draftIsSaved], () => {
  void loadList()
}, { immediate: true })

function inputId(field) { return `srpp-short-${field.id}` }
function arrayValue(value) { return Array.isArray(value) ? value : [] }
function errorFor(fieldId) { return validationErrors.value.find(error => error.fieldId === fieldId)?.message || '' }
function emitValue(fieldId, value) {
  if (!Array.isArray(value)) return
  emit('field-change', fieldId, [...value])
}
function emitNumeric(field, rawValue) {
  if (rawValue == null || rawValue === '') {
    emit('field-change', field.id, null)
    return
  }
  const numeric = Number(rawValue)
  if (!Number.isFinite(numeric)) return
  const stored = isFraction(field) ? numeric / 100 : numeric
  emit('field-change', field.id, stored)
}
function isFraction(field) { return String(field.unit || '').toLowerCase() === 'fraction' }
function displayNumber(value, field) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? (isFraction(field) ? number * 100 : number) : null
}
function displayBound(value, field) { return value == null ? undefined : isFraction(field) ? value * 100 : value }
function inputStep(field) {
  if (field.type === 'integer') return 1
  if (isFraction(field)) return 'any'
  return field.precision ? 10 ** -field.precision : 'any'
}
function unitLabel(field) {
  if (isFraction(field)) return '%'
  if (field.unit === '百分點') return '百分點'
  return field.unit || '數值'
}
function groupLabel(group) { return ({ TW_EQUITY: '台股股票短線', TW_BOND: '台股債券波段', US: '美股短線觀察' })[group] || group || '—' }
function configurationLabel(status) { return status === 'FORMAL' ? '正式策略值' : status === 'DRAFT_PARAMETER_PREVIEW_ONLY' ? '草稿參數預覽（未套用）' : status || '未知' }
function marketTagType(market) { return market?.stale || !market?.dataComplete ? 'warning' : 'success' }
function marketStatusLabel(market) {
  if (!market) return '等待資料'
  if (market.stale) return '資料可能過期'
  if (!market.dataComplete) return '資料不完整'
  return market.quoteStatus === 'LIVE' ? '即時報價' : market.quoteStatus === 'VERIFIED_CLOSE' ? '完成日收盤' : '來源資料完成'
}
function rowStatusLabel(row) {
  if (row.status === 'RADAR_OBSERVATION') return '來源資料完整'
  if (row.status === 'WAITING_DATA') return '等待資料'
  return row.status || '狀態未提供'
}
function detailStatusLabel(detail) {
  if (detail.status === 'RADAR_OBSERVATION' && detail.dataComplete) return '來源資料完整'
  return '等待資料；不推導動作'
}
function quoteStatusLabel(status) {
  return ({ LIVE: '即時報價', VERIFIED_CLOSE: '已驗證收盤', STALE: '可能過期', MISSING: '缺少報價' })[status] || status || '—'
}
function readableLimitation(value) { return String(value || '').replaceAll('SHORT', '短線') }
function formatPrice(value) {
  if (value == null || value === '') return '—'
  const formatted = formatPreciseMoneyText(value)
  return formatted === '—' ? '—' : `NT$ ${formatted}`
}
function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-TW', { hour12: false })
}
function formatPercent(value) {
  if (value == null || !Number.isFinite(Number(value))) return '—'
  return `${formatPreciseDecimal(Number(value) * 100, 6)}%`
}
function formatPolicyValues(values, group) {
  if (!values) return '未提供'
  const parts = []
  if (Array.isArray(values.symbols)) parts.push(`觀察標的：${values.symbols.join('、') || '未選取'}`)
  if (group === 'US') {
    parts.push(`最低淨上漲空間 ${values.netUpsidePct ?? '—'} 百分點`)
    parts.push(`單批上限 ${formatPercent(values.batchCap)}`)
    parts.push(`單公司上限 ${formatPercent(values.companyCap)}`)
    parts.push(`戰術總額上限 ${formatPercent(values.sleeveCap)}`)
  } else {
    parts.push(`最低反彈空間 ${values.reboundPct ?? '—'} 百分點`)
    parts.push(`最低淨報酬風險比 ${values.netRewardRisk ?? '—'} 倍`)
    parts.push(`短線總額上限 ${formatPercent(values.sleeveCap)}`)
    parts.push(`單一部位上限 ${formatPercent(values.positionCap)}`)
    parts.push(`最長持有 ${values.maxHoldingSessions ?? '—'} 個交易日`)
  }
  return parts.join('；')
}

function requestIsCurrent(sequence, ownerKey) {
  return !disposed.value && sequence === listSequence && ownerKey === props.ownerKey
}
async function loadList() {
  const sequence = ++listSequence
  const ownerKey = props.ownerKey
  detailEpoch += 1
  detailSequences.clear()
  detailStates.value = {}
  listData.value = null
  listError.value = ''
  if (props.draftId && !props.draftIsCurrent) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const useSavedDraft = !!props.draftId && props.draftIsCurrent && props.draftIsSaved && Number.isInteger(props.draftRevision)
    const response = await bffApi.portfolioAdvice.srpp.shortOpportunities(useSavedDraft
      ? { draftId: props.draftId, revision: props.draftRevision }
      : {})
    if (!requestIsCurrent(sequence, ownerKey)) return
    if (!response || typeof response !== 'object' || response.thresholdsAppliedToRadar !== false ||
      !Array.isArray(response.rows) || !Array.isArray(response.observationBuckets) ||
      !Array.isArray(response.policies) || !Array.isArray(response.markets)) {
      throw new Error('短線雷達回應欄位不完整，為避免誤讀已停止顯示。')
    }
    listData.value = response
  } catch (error) {
    if (!requestIsCurrent(sequence, ownerKey)) return
    listError.value = apiErrorMessage(error, '目前無法讀取短線雷達資料。')
  } finally {
    if (requestIsCurrent(sequence, ownerKey)) loading.value = false
  }
}

function rowKey(row) { return `${row.market}:${row.stockCode}` }
function detailState(row) { return detailStates.value[rowKey(row)] || { data: null, loading: false, error: '' } }
function handleExpandChange(row, expandedRows) {
  const expanded = Array.isArray(expandedRows) && expandedRows.some(item => rowKey(item) === rowKey(row))
  if (expanded) void loadDetail(row)
}
async function loadDetail(row, force = false) {
  if (!listData.value || listData.value.thresholdsAppliedToRadar !== false) return
  const key = rowKey(row)
  const current = detailState(row)
  if (current.loading || (!force && current.data)) return
  const epoch = detailEpoch
  const ownerKey = props.ownerKey
  const sequence = (detailSequences.get(key) || 0) + 1
  detailSequences.set(key, sequence)
  detailStates.value = { ...detailStates.value, [key]: { data: current.data, loading: true, error: '' } }
  try {
    const response = await bffApi.portfolioAdvice.srpp.shortOpportunityStock(row.stockCode, row.market)
    if (disposed.value || epoch !== detailEpoch || ownerKey !== props.ownerKey || detailSequences.get(key) !== sequence) return
    if (!response || response.stockCode !== row.stockCode || response.market !== row.market || response.tradeAuthorization !== false) {
      throw new Error('短線明細識別不符或未確認唯讀狀態。')
    }
    detailStates.value = { ...detailStates.value, [key]: { data: response, loading: false, error: '' } }
  } catch (error) {
    if (disposed.value || epoch !== detailEpoch || ownerKey !== props.ownerKey || detailSequences.get(key) !== sequence) return
    detailStates.value = { ...detailStates.value, [key]: { data: null, loading: false, error: apiErrorMessage(error, '此標的資料尚未完整。') } }
  }
}

onUnmounted(() => {
  disposed.value = true
  listSequence += 1
  detailEpoch += 1
  detailSequences.clear()
})
</script>

<style scoped>
.short-term-panel { margin: 18px 0 4px; padding: 16px; border: 1px solid #cbd5e1; border-radius: 10px; background: #f8fafc; }
.short-term-heading, .strategy-title, .radar-heading, .detail-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
.short-term-heading h3, .radar-heading h4 { margin: 0; color: #0f172a; font-size: 16px; }
.short-term-heading p, .radar-scope { margin: 4px 0 0; color: #64748b; font-size: 12px; line-height: 1.6; }
.short-notice { margin: 12px 0; }
.strategy-grid, .policy-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.strategy-card, .policy-card { min-width: 0; padding: 12px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.strategy-title strong, .policy-card > strong { color: #334155; font-size: 13px; }
.strategy-fields { display: grid; grid-template-columns: 1fr; gap: 10px; margin-top: 10px; }
.strategy-field { min-width: 0; }
.strategy-field label { display: block; margin-bottom: 4px; color: #475569; font-size: 11px; font-weight: 650; }
.strategy-field :deep(.el-select), .strategy-field :deep(.el-input) { width: 100%; }
.strategy-field :deep(.el-input-group__append) { color: #64748b; font-size: 10px; }
.strategy-help, .strategy-bound { margin: 3px 0 5px; color: #94a3b8; font-size: 10px; line-height: 1.5; }
.strategy-error { margin: 4px 0 0; color: #b91c1c; font-size: 11px; }
.radar-heading { margin-top: 22px; align-items: flex-start; }
.warning-text { color: #b45309; }
.radar-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; margin: 12px 0; color: #64748b; font-size: 11px; }
.policy-grid { margin-top: 10px; }
.policy-card { font-size: 11px; line-height: 1.6; }
.policy-values { display: grid; grid-template-columns: 1fr; gap: 7px; margin-top: 8px; }
.policy-values b, .detail-columns b, .reference-box b { color: #475569; }
.policy-values p, .policy-summary, .policy-rule, .policy-retirement { margin: 3px 0; color: #64748b; overflow-wrap: anywhere; white-space: pre-line; }
.source-rules { margin-top: 7px; color: #64748b; font-size: 10px; line-height: 1.55; }
.source-rules summary { cursor: pointer; color: #64748b; }
.source-rules p { margin: 5px 0; white-space: pre-line; overflow-wrap: anywhere; }
.policy-retirement { color: #b45309; }
.missing-symbols { margin: 12px 0; }
.missing-symbols :deep(.el-tag) { margin: 0 5px 5px 0; }
.observation-section { margin-top: 16px; }
.subsection-heading { margin: 0 0 7px; color: #334155; font-size: 13px; font-weight: 700; }
.detail-panel { padding: 8px 12px 12px; background: #f8fafc; }
.detail-header { justify-content: flex-start; margin-bottom: 7px; }
.detail-header strong { color: #334155; }
.detail-meta { display: flex; flex-wrap: wrap; gap: 6px 16px; color: #64748b; font-size: 11px; }
.detail-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 10px; }
.detail-columns ul, .detail-limitations, .limitations-list { margin: 5px 0; padding-left: 19px; color: #64748b; font-size: 11px; line-height: 1.6; }
.detail-muted, .detail-error { color: #b45309; font-size: 11px; }
.detail-error { display: flex; align-items: center; gap: 10px; }
.reference-box { display: flex; flex-wrap: wrap; gap: 5px 16px; margin-top: 10px; padding: 9px; border: 1px solid #cbd5e1; border-radius: 7px; color: #475569; font-size: 11px; }
.reference-box b { flex-basis: 100%; }
.reference-box p { flex-basis: 100%; margin: 3px 0 0; color: #b45309; }
.conflict-mark { display: block; margin-top: 3px; color: #b45309; font-size: 10px; }
.source-identities { margin-top: 16px; }
.identity-grid { display: grid; grid-template-columns: minmax(140px, .7fr) minmax(0, 2fr); gap: 7px 12px; margin: 0; }
.identity-grid dt { color: #64748b; font-size: 11px; }
.identity-grid dd { min-width: 0; margin: 0; color: #334155; font: 10px ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
.limitations-list { margin-top: 12px; }
.short-alert { margin-top: 10px; }
.radar-empty, .radar-loading { padding: 12px 0 0; color: #94a3b8; font-size: 11px; }
@media (max-width: 900px) { .strategy-grid, .policy-grid { grid-template-columns: 1fr; } }
@media (max-width: 650px) { .detail-columns, .identity-grid { grid-template-columns: 1fr; } .identity-grid dd { margin-bottom: 6px; } }
</style>
