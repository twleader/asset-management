<template>
  <div v-loading="loading" element-loading-text="讀取本地行情與技術指標…">
    <div class="header-row">
      <div>
        <div class="page-heading">今日交易雷達</div>
        <div class="page-sub">依大盤、MA20／60／240、KD 與連續兩日確認產生規則式決策；不呼叫 AI API</div>
      </div>
      <el-button :icon="Refresh" :loading="refreshing" @click="load(true)">重新整理</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="local-rule-alert"
      title="純本地規則運算"
      description="本頁只讀取系統既有 PostgreSQL 與 Redis 資料，不會送出 Claude、OpenAI 或其他 AI API 請求，也不會觸發外部行情回補。"
    />

    <el-card shadow="never" class="market-card" :class="marketClass">
      <template #header>
        <div class="card-head">
          <div>
            <span class="section-title">台股大盤風險</span>
            <el-tag size="small" effect="plain" type="info" class="rule-tag">{{ radar.ruleVersion || 'TW_RULES_V6' }}</el-tag>
          </div>
          <div class="as-of-group">
            <span class="as-of">完成日 K：{{ market.asOfDate || '資料不足' }}</span>
            <span v-if="market.intraday" class="as-of live-as-of">即時更新：{{ fmtTime(market.liveUpdatedAt) }}</span>
          </div>
        </div>
      </template>

      <el-alert
        v-if="market.stale"
        class="stale-alert"
        type="warning"
        show-icon
        :closable="false"
        title="大盤資料非最新，今日買進訊號暫停"
        description="本次未能取得即時大盤點位，已退回前一交易日資料；為避免以昨日的環境替今日背書，此期間不採計大盤加分，也不產生買進／加碼候選。偏空環境的扣分與限制仍照常生效。" />

      <div class="market-layout">
        <div class="regime-panel">
          <div class="regime-label">{{ market.regimeLabel || '載入中' }}</div>
          <div class="score-row">
            <span class="score-value">{{ market.score == null ? '—' : market.score }}</span>
            <span class="score-unit">/ 100</span>
          </div>
          <div class="regime-code">{{ market.regime || '—' }}</div>
        </div>

        <div class="market-metrics">
          <div class="metric">
            <span class="metric-label">最新點位</span>
            <strong>{{ fmtNumber(market.price, 2) }}</strong>
            <span :style="{ color: priceColor(market.changePercent) }">{{ fmtPct(market.changePercent) }}</span>
          </div>
          <div class="metric"><span class="metric-label">月線 MA20</span><strong>{{ fmtNumber(market.monthlyMa, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">季線 MA60</span><strong>{{ fmtNumber(market.quarterlyMa, 2) }}</strong><small>{{ confirmationLabel(market.quarterlyConfirmation) }}</small></div>
          <div class="metric"><span class="metric-label">年線 MA240</span><strong>{{ fmtNumber(market.annualMa, 2) }}</strong><small>{{ confirmationLabel(market.annualConfirmation) }}</small></div>
          <div class="metric"><span class="metric-label">KD</span><strong>K {{ fmtNumber(market.kValue, 1) }} / D {{ fmtNumber(market.dValue, 1) }}</strong></div>
        </div>
      </div>

      <el-row :gutter="18" class="reason-row">
        <el-col :xs="24" :md="12">
          <div class="reason-title positive">支持訊號</div>
          <ul v-if="market.reasons?.length" class="reason-list">
            <li v-for="(item, i) in market.reasons" :key="`mr-${i}`">{{ item }}</li>
          </ul>
          <div v-else class="muted">目前沒有足夠的正向確認。</div>
        </el-col>
        <el-col :xs="24" :md="12">
          <div class="reason-title risk">風險提醒</div>
          <ul v-if="market.risks?.length" class="reason-list">
            <li v-for="(item, i) in market.risks" :key="`mk-${i}`">{{ item }}</li>
          </ul>
          <div v-else class="muted">目前沒有額外風險提醒。</div>
        </el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="stocks-card">
      <template #header>
        <div class="card-head">
          <div>
            <span class="section-title">我的台股決策</span>
            <span class="stock-count">{{ stocks.length }} 檔</span>
          </div>
          <span v-if="radar.skippedNonTwStocks" class="as-of">第一版未評分美／英股 {{ radar.skippedNonTwStocks }} 檔</span>
        </div>
      </template>

      <el-table
        v-if="stocks.length"
        :data="stocks"
        row-key="stockCode"
        stripe
        style="width:100%"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-panel">
              <div class="confirm-grid">
                <div class="confirm-item">
                  <span>月線 MA20</span><strong>{{ fmtNumber(row.monthlyMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.monthlyConfirmation)" effect="plain">{{ confirmationLabel(row.monthlyConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>季線 MA60</span><strong>{{ fmtNumber(row.quarterlyMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.quarterlyConfirmation)" effect="plain">{{ confirmationLabel(row.quarterlyConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>年線 MA240</span><strong>{{ fmtNumber(row.annualMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.annualConfirmation)" effect="plain">{{ confirmationLabel(row.annualConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>KD</span><strong>K {{ fmtNumber(row.kValue, 1) }} / D {{ fmtNumber(row.dValue, 1) }}</strong>
                  <small>完成日 K：{{ row.asOfDate || '—' }}</small>
                </div>
              </div>

              <el-row :gutter="18" class="reason-row">
                <el-col :xs="24" :md="12">
                  <div class="reason-title positive">支持訊號</div>
                  <ul v-if="row.reasons?.length" class="reason-list">
                    <li v-for="(item, i) in row.reasons" :key="`sr-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">沒有足夠的支持訊號。</div>
                </el-col>
                <el-col :xs="24" :md="12">
                  <div class="reason-title risk">風險提醒</div>
                  <ul v-if="row.risks?.length" class="reason-list">
                    <li v-for="(item, i) in row.risks" :key="`sk-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">目前沒有額外風險提醒。</div>
                </el-col>
              </el-row>
              <div v-if="row.counterTrendState && row.counterTrendState !== 'NONE'" class="counter-trend-panel">
                <div class="counter-trend-head">
                  <div>
                    <span class="counter-trend-title">逆勢抄底（獨立狀態）</span>
                    <span class="counter-trend-note">不覆寫原分數與規則建議</span>
                  </div>
                  <el-tag :type="counterTrendType(row.counterTrendState)" effect="dark">
                    {{ row.counterTrendLabel }}
                  </el-tag>
                </div>
                <el-row :gutter="18">
                  <el-col :xs="24" :md="12">
                    <div class="reason-title positive">逆勢條件</div>
                    <ul v-if="row.counterTrendReasons?.length" class="reason-list">
                      <li v-for="(item, i) in row.counterTrendReasons" :key="`ctr-${row.stockCode}-${i}`">{{ item }}</li>
                    </ul>
                  </el-col>
                  <el-col :xs="24" :md="12">
                    <div class="reason-title risk">逆勢風險</div>
                    <ul v-if="row.counterTrendRisks?.length" class="reason-list">
                      <li v-for="(item, i) in row.counterTrendRisks" :key="`ctk-${row.stockCode}-${i}`">{{ item }}</li>
                    </ul>
                    <div v-else class="muted">目前沒有額外逆勢風險提醒。</div>
                  </el-col>
                </el-row>
              </div>
              <div class="updated-at">
                行情更新：{{ formatTime(row.priceUpdatedAt) }}　·　完成日 K：{{ row.asOfDate || '—' }}
                <span v-if="row.distributionAdjusted">　·　技術價基：還原權息</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="標的" min-width="175" fixed="left">
          <template #default="{ row }">
            <div class="stock-code">{{ row.stockCode }}</div>
            <div class="stock-name">{{ row.stockName }}</div>
            <div v-if="row.assetClass === 'BOND' || row.distributionAdjusted" class="stock-meta">
              <el-tag v-if="row.assetClass === 'BOND'" size="small" type="info" effect="plain">債券</el-tag>
              <el-tag v-if="row.distributionAdjusted" size="small" type="success" effect="plain">還原權息</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目前狀態" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.held ? 'warning' : 'info'" effect="plain">{{ row.held ? '持有' : '觀察' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="規則建議" min-width="150" align="center">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" effect="dark">{{ row.actionLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="逆勢抄底" min-width="135" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.counterTrendState && row.counterTrendState !== 'NONE'"
              :type="counterTrendType(row.counterTrendState)"
              effect="dark"
            >
              {{ row.counterTrendLabel }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="分數" width="88" align="center">
          <template #default="{ row }">
            <strong v-if="row.score != null" :style="{ color: scoreColor(row.score) }">{{ row.score }}</strong>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="現價／漲跌" min-width="130" align="right">
          <template #default="{ row }">
            <div class="price-value">{{ fmtNumber(row.price, 2) }}</div>
            <div :style="{ color: priceColor(row.changePercent) }">{{ fmtPct(row.changePercent) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="MA20／60／240" min-width="190" align="right">
          <template #default="{ row }">
            <span>{{ fmtNumber(row.monthlyMa, 2) }}</span>
            <span class="slash">／</span>
            <span>{{ fmtNumber(row.quarterlyMa, 2) }}</span>
            <span class="slash">／</span>
            <span>{{ fmtNumber(row.annualMa, 2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="KD" width="120" align="center">
          <template #default="{ row }">K {{ fmtNumber(row.kValue, 1) }} / D {{ fmtNumber(row.dValue, 1) }}</template>
        </el-table-column>
        <el-table-column prop="asOfDate" label="完成日 K" width="115" />
        <el-table-column label="通知" width="105" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Bell" @click.stop="openNotification(row)">設定</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="目前沒有可分析的台股標的">
        <el-button type="primary" @click="router.push('/stocks')">前往股票觀察新增標的</el-button>
      </el-empty>
    </el-card>

    <el-alert
      class="disclaimer"
      type="warning"
      :closable="false"
      show-icon
      title="規則式決策輔助，不是獲利保證"
      description="系統不會自動下單。實際交易前請自行確認即時價格、可用資金、持有部位、交易成本與可承受損失；資料不足時以「今日不交易」為準。"
    />

    <el-dialog
      v-model="notificationVisible"
      :title="`${notificationStock.stockCode || ''} ${notificationStock.stockName || ''}－通知設定`"
      width="680px"
      destroy-on-close
    >
      <div v-loading="notificationLoading">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="notification-help"
          title="只在狀態轉入時通知"
          description="儲存後第一次行情評估只建立目前狀態基準，不會立即寄信；之後進入所選狀態才寄一次，同一狀態持續期間不重複寄。"
        />

        <el-form label-position="top">
          <el-form-item label="主規則建議">
            <el-checkbox-group v-model="notificationForm.actionStates" class="state-options">
              <el-checkbox
                v-for="option in notificationOptions.actions"
                :key="option.code"
                :value="option.code"
                border
              >
                {{ option.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>

          <el-form-item label="逆勢抄底狀態">
            <el-checkbox-group v-model="notificationForm.counterTrendStates" class="state-options">
              <el-checkbox
                v-for="option in notificationOptions.counterTrends"
                :key="option.code"
                :value="option.code"
                border
              >
                {{ option.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>

          <el-form-item label="Email 收件人">
            <div v-if="!notificationOptions.recipients.length" class="no-recipient">
              <span>尚未建立通知收件人。</span>
              <el-button link type="primary" @click="goToNotificationSettings">前往警示通知設定</el-button>
            </div>
            <el-checkbox-group v-else v-model="notificationForm.recipientIds" class="recipient-options">
              <el-checkbox
                v-for="recipient in notificationOptions.recipients"
                :key="recipient.id"
                :value="recipient.id"
              >
                {{ recipient.email }}
                <span v-if="!recipient.active" class="inactive-recipient">（目前停用，不會收到信）</span>
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>

          <el-form-item label="啟用通知">
            <el-switch v-model="notificationForm.active" />
            <span class="switch-note">停用時保留選項，但不進行背景狀態評估與寄信。</span>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="notificationVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="notificationSaving"
          :disabled="notificationLoading"
          @click="saveNotification"
        >
          儲存通知設定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Bell, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'
import { useRouter } from 'vue-router'

const router = useRouter()
const loading = ref(false)
const refreshing = ref(false)
const radar = ref({ market: {}, stocks: [], skippedNonTwStocks: 0, ruleVersion: 'TW_RULES_V6' })
const notificationVisible = ref(false)
const notificationLoading = ref(false)
const notificationSaving = ref(false)
const notificationStock = ref({})
const notificationForm = reactive({
  active: false,
  actionStates: [],
  counterTrendStates: [],
  recipientIds: []
})
const notificationOptions = reactive({ actions: [], counterTrends: [], recipients: [] })
const RECALCULATE_DELAY_MS = 2000
const RECONNECT_DELAY_MS = 5000

let priceStream = null
let recalculationTimer = null
let reconnectTimer = null
let recalculating = false
let recalculationPending = false
let disposed = false

const market = computed(() => radar.value.market || {})
const stocks = computed(() => radar.value.stocks || [])
const marketClass = computed(() => `regime-${String(market.value.regime || 'DATA_INCOMPLETE').toLowerCase().replace('_', '-')}`)

async function load(manual = false, silent = false) {
  if (manual) refreshing.value = true
  else if (!silent) loading.value = true
  try {
    const next = await bffApi.tradingRadar.get()
    if (!disposed) radar.value = next
  } finally {
    if (!silent) loading.value = false
    if (manual) refreshing.value = false
  }
}

function scheduleRecalculation() {
  if (disposed) return
  if (recalculationTimer) clearTimeout(recalculationTimer)
  recalculationTimer = setTimeout(() => {
    recalculationTimer = null
    void recalculateRadar()
  }, RECALCULATE_DELAY_MS)
}

async function recalculateRadar() {
  if (disposed) return
  if (recalculating) {
    recalculationPending = true
    return
  }

  recalculating = true
  try {
    await load(false, true)
  } catch (e) {
    console.warn('交易雷達背景重算失敗:', e)
  } finally {
    recalculating = false
    if (recalculationPending && !disposed) {
      recalculationPending = false
      scheduleRecalculation()
    }
  }
}

function applyPriceUpdate(payload) {
  if (payload?.market !== '台股' || !payload.stockCode) return

  let matched = false
  const nextStocks = (radar.value.stocks || []).map(row => {
    if (row.market !== '台股' || String(row.stockCode) !== String(payload.stockCode)) return row
    matched = true
    return {
      ...row,
      price: payload.price ?? row.price,
      changePercent: payload.changePercent ?? payload.changePct ?? row.changePercent,
      priceUpdatedAt: payload.updatedAt ?? row.priceUpdatedAt
    }
  })

  if (!matched) return
  radar.value = { ...radar.value, stocks: nextStocks }
  scheduleRecalculation()
}

function openPriceStream() {
  if (disposed) return
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (priceStream) priceStream.close()

  priceStream = new EventSource('/api/market-data/prices/stream')
  priceStream.addEventListener('price-update', event => {
    try {
      applyPriceUpdate(JSON.parse(event.data))
    } catch (e) {
      console.warn('交易雷達 SSE 解析失敗:', e)
    }
  })
  priceStream.onerror = () => {
    if (disposed || !priceStream || priceStream.readyState !== EventSource.CLOSED) return
    priceStream.close()
    priceStream = null
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      openPriceStream()
    }, RECONNECT_DELAY_MS)
  }
}

async function openNotification(row) {
  notificationStock.value = row
  notificationVisible.value = true
  notificationLoading.value = true
  try {
    const data = await bffApi.tradingRadar.getNotification(row.stockCode, row.market)
    notificationForm.active = Boolean(data.active)
    notificationForm.actionStates = [...(data.actionStates || [])]
    notificationForm.counterTrendStates = [...(data.counterTrendStates || [])]
    notificationForm.recipientIds = [...(data.recipientIds || [])]
    notificationOptions.actions = data.actionOptions || []
    notificationOptions.counterTrends = data.counterTrendOptions || []
    notificationOptions.recipients = data.recipients || []
  } catch (e) {
    notificationVisible.value = false
    console.warn('讀取交易雷達通知設定失敗:', e)
  } finally {
    notificationLoading.value = false
  }
}

async function saveNotification() {
  if (notificationForm.active &&
      notificationForm.actionStates.length === 0 &&
      notificationForm.counterTrendStates.length === 0) {
    ElMessage.warning('啟用通知時至少選擇一個狀態')
    return
  }
  if (notificationForm.active && notificationForm.recipientIds.length === 0) {
    ElMessage.warning('啟用通知時至少選擇一位收件人')
    return
  }

  notificationSaving.value = true
  try {
    await bffApi.tradingRadar.updateNotification(
      notificationStock.value.stockCode,
      notificationStock.value.market,
      {
        active: notificationForm.active,
        actionStates: notificationForm.actionStates,
        counterTrendStates: notificationForm.counterTrendStates,
        recipientIds: notificationForm.recipientIds
      }
    )
    ElMessage.success('通知設定已儲存；下一次評估會先建立狀態基準')
    notificationVisible.value = false
  } finally {
    notificationSaving.value = false
  }
}

function goToNotificationSettings() {
  notificationVisible.value = false
  router.push('/settings/notifications')
}

function fmtNumber(value, digits = 2) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return Number(value).toLocaleString('zh-TW', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

function fmtPct(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const n = Number(value)
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
}

function fmtTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function priceColor(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n === 0) return '#64748b'
  return n > 0 ? '#dc2626' : '#16a34a'
}

function scoreColor(score) {
  if (score >= 65) return '#dc2626'
  if (score < 40) return '#16a34a'
  return '#d97706'
}

function confirmationLabel(value) {
  return ({
    ABOVE: '連續兩日站上',
    BELOW: '連續兩日跌破',
    MIXED: '尚未確認',
    UNAVAILABLE: '資料不足'
  })[value] || '資料不足'
}

function confirmationType(value) {
  return ({ ABOVE: 'danger', BELOW: 'success', MIXED: 'warning', UNAVAILABLE: 'info' })[value] || 'info'
}

function actionType(action) {
  if (['BUY_CANDIDATE', 'ADD_CANDIDATE'].includes(action)) return 'danger'
  if (['REDUCE_CANDIDATE', 'EXIT_CANDIDATE', 'AVOID'].includes(action)) return 'success'
  if (['HOLD', 'WATCH', 'HOLD_CAUTION', 'WAIT'].includes(action)) return 'warning'
  return 'info'
}

function counterTrendType(state) {
  if (state === 'TRIAL_CANDIDATE') return 'danger'
  if (state === 'OVERSOLD_WATCH') return 'warning'
  return 'info'
}

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString('zh-TW', { hour12: false })
}

onMounted(() => {
  load(false).catch(() => {}).finally(openPriceStream)
})

onUnmounted(() => {
  disposed = true
  if (priceStream) {
    priceStream.close()
    priceStream = null
  }
  if (recalculationTimer) {
    clearTimeout(recalculationTimer)
    recalculationTimer = null
  }
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
})
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.page-heading { font-size: 23px; font-weight: 750; color: #0f172a; }
.page-sub { margin-top: 4px; color: #64748b; font-size: 13px; }
.local-rule-alert { margin-bottom: 16px; }
.market-card { border-top: 4px solid #f59e0b; }
.market-card.regime-risk-on { border-top-color: #dc2626; }
.market-card.regime-risk-off { border-top-color: #16a34a; }
.market-card.regime-neutral { border-top-color: #64748b; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-title { font-size: 17px; font-weight: 700; color: #0f172a; }
.rule-tag { margin-left: 9px; }
.as-of, .stock-count { color: #64748b; font-size: 12px; }
.as-of-group { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.stock-count { margin-left: 8px; }
.market-layout { display: grid; grid-template-columns: 230px 1fr; gap: 22px; align-items: stretch; }
.regime-panel {
  border-radius: 10px;
  background: #f8fafc;
  padding: 20px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.regime-label { font-size: 18px; font-weight: 700; color: #0f172a; }
.score-row { margin-top: 8px; line-height: 1; }
.score-value { font-size: 42px; font-weight: 800; color: #0f172a; }
.score-unit { color: #64748b; }
.regime-code { margin-top: 8px; color: #64748b; font-size: 12px; letter-spacing: .08em; }
.market-metrics { display: grid; grid-template-columns: repeat(5, minmax(125px, 1fr)); gap: 10px; }
.metric { border: 1px solid #e2e8f0; border-radius: 9px; padding: 14px; display: flex; flex-direction: column; gap: 6px; }
.metric-label { color: #64748b; font-size: 12px; }
.metric strong { color: #0f172a; font-size: 15px; }
.metric small { color: #64748b; }
.reason-row { margin-top: 18px; }
.reason-title { font-weight: 700; font-size: 13px; margin-bottom: 7px; }
.reason-title.positive { color: #b91c1c; }
.reason-title.risk { color: #15803d; }
.reason-list { padding-left: 20px; color: #334155; line-height: 1.75; font-size: 13px; }
.counter-trend-panel { margin-top: 18px; border: 1px solid #f59e0b; border-radius: 9px; background: #fffbeb; padding: 14px 16px; }
.counter-trend-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 6px; }
.counter-trend-title { color: #92400e; font-size: 14px; font-weight: 700; }
.counter-trend-note { margin-left: 8px; color: #64748b; font-size: 12px; }
.muted { color: #94a3b8; }
.stale-alert { margin-bottom: 14px; }
.stocks-card { margin-top: 16px; }
.stock-code { font-weight: 750; color: #0f172a; }
.stock-name { margin-top: 2px; color: #64748b; font-size: 12px; }
.stock-meta { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.price-value { font-weight: 700; color: #0f172a; }
.slash { color: #cbd5e1; padding: 0 2px; }
.expand-panel { padding: 8px 28px 18px 56px; background: #f8fafc; }
.confirm-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; }
.confirm-item { border: 1px solid #e2e8f0; border-radius: 8px; background: white; padding: 12px; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.confirm-item span, .confirm-item small { color: #64748b; font-size: 12px; }
.updated-at { margin-top: 12px; color: #64748b; font-size: 12px; }
.disclaimer { margin-top: 16px; }
.notification-help { margin-bottom: 16px; }
.state-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; width: 100%; }
.state-options :deep(.el-checkbox) { margin-right: 0; }
.recipient-options { display: flex; flex-direction: column; align-items: flex-start; }
.no-recipient { display: flex; align-items: center; gap: 6px; color: #64748b; }
.inactive-recipient { color: #dc2626; font-size: 12px; }
.switch-note { margin-left: 10px; color: #64748b; font-size: 12px; }

@media (max-width: 1180px) {
  .market-layout { grid-template-columns: 1fr; }
  .market-metrics { grid-template-columns: repeat(3, 1fr); }
  .confirm-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .header-row, .card-head { align-items: flex-start; flex-direction: column; }
  .market-metrics, .confirm-grid { grid-template-columns: 1fr; }
  .expand-panel { padding-left: 16px; padding-right: 16px; }
  .state-options { grid-template-columns: 1fr; }
}
</style>
