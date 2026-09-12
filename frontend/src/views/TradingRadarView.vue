<template>
  <div v-loading="loading" element-loading-text="讀取本地行情與技術指標…">
    <div class="header-row">
      <div>
        <div class="page-heading">今日交易雷達</div>
        <div class="page-sub">一周、1周~1月、1月~6月三種持有期；新增日K 棒與週K 指標，並綜合技術面、量能、美股科技、匯率、個股基本面與產業發展</div>
      </div>
      <div class="header-actions">
        <el-button :icon="Download" @click="openExport">匯出 Excel</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="manualRefresh">重新整理</el-button>
        <el-button v-if="auth.isConfiguredAdmin" :icon="Promotion" :loading="publishingBlog" @click="onPublishBlog">匯出到 blog</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="local-rule-alert"
      title="純本地規則運算"
      description="判斷全由本地規則產生，不會送出 Claude、OpenAI 或其他 AI API 請求。按下「重新整理」會先回補一次台股行情再重算；頁面評分只讀取已入庫的 PostgreSQL／Redis 資料。個股質性證據先取 public_info_*，結構化財報與估值由背景排程依交易所→Yahoo→玩股網→FinMind 補齊，不在本頁請求時即時抓外網。"
    />

    <el-card shadow="never" class="market-card" :class="marketClass">
      <template #header>
        <div class="card-head">
          <div>
            <span class="section-title">{{ marketCardTab }}大盤風險</span>
            <el-tag size="small" effect="plain" type="info" class="rule-tag">{{ radar.ruleVersion || 'TW_RULES_V18' }}</el-tag>
          </div>
          <div class="as-of-group">
            <span class="as-of">完成日 K：{{ currentMarket.asOfDate || '資料不足' }}</span>
            <span v-if="currentMarket.intraday" class="as-of live-as-of">即時更新：{{ fmtTime(currentMarket.liveUpdatedAt) }}</span>
          </div>
        </div>
      </template>

      <el-tabs v-model="marketCardTab" style="margin-bottom:12px">
        <el-tab-pane name="台股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <TaiwanMap :size="18" />
              台股
              <el-badge v-if="twStale" is-dot type="warning" />
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="美股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <UsFlag :size="22" />
              美股
              <el-badge v-if="usStale" is-dot type="warning" />
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-alert
        v-if="currentMarket.stale"
        class="stale-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="`${marketCardTab}大盤資料非最新，今日買進訊號暫停`"
        :description="marketCardTab === '美股'
          ? '最近一個已完成美股交易日的日線尚未回補，已退回前一交易日資料；為避免以昨日的環境替今日背書，此期間不採計大盤加分，也不產生買進／加碼候選。偏空環境的扣分與限制仍照常生效。'
          : '本次未能取得即時大盤點位，已退回前一交易日資料；為避免以昨日的環境替今日背書，此期間不採計大盤加分，也不產生買進／加碼候選。偏空環境的扣分與限制仍照常生效。'" />

      <div class="market-layout">
        <div class="regime-panel">
          <div class="regime-label">{{ currentMarket.regimeLabel || '載入中' }}</div>
          <div class="score-row">
            <span class="score-value">{{ currentMarket.score == null ? '—' : currentMarket.score }}</span>
            <span class="score-unit">/ 100</span>
          </div>
          <div class="regime-code">{{ currentMarket.regime || '—' }}</div>
        </div>

        <div class="market-metrics">
          <div class="metric">
            <span class="metric-label">最新點位</span>
            <strong v-if="isClosePending(currentMarket)" style="color:#d97706;font-size:13px">收盤價待補</strong>
            <strong v-else>{{ fmtNumber(currentMarket.price, 2) }}</strong>
            <span :style="{ color: priceColor(currentMarket.changePercent) }">{{ fmtPct(currentMarket.changePercent) }}</span>
          </div>
          <div class="metric"><span class="metric-label">日線 MA5</span><strong>{{ fmtNumber(currentMarket.weeklyMa, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">月線 MA20</span><strong>{{ fmtNumber(currentMarket.monthlyMa, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">季線 MA60</span><strong>{{ fmtNumber(currentMarket.quarterlyMa, 2) }}</strong><small>{{ confirmationLabel(currentMarket.quarterlyConfirmation) }}</small></div>
          <div class="metric"><span class="metric-label">年線 MA240</span><strong>{{ fmtNumber(currentMarket.annualMa, 2) }}</strong><small>{{ confirmationLabel(currentMarket.annualConfirmation) }}</small></div>
          <div class="metric"><span class="metric-label">KD</span><strong>K {{ fmtNumber(currentMarket.kValue, 1) }} / D {{ fmtNumber(currentMarket.dValue, 1) }}</strong></div>
          <div class="metric"><span class="metric-label">大盤完成日量比</span><strong>{{ fmtRatio(currentMarket.marketVolumeRatio) }}</strong><small>{{ currentMarket.marketVolumeAsOfDate || '資料不足' }}</small></div>
          <div v-if="marketCardTab === '台股'" class="metric"><span class="metric-label">成交金額比</span><strong>{{ fmtRatio(currentMarket.marketTurnoverRatio) }}</strong></div>
          <div v-if="marketCardTab === '台股'" class="metric"><span class="metric-label">NASDAQ 前一日</span><strong :style="{ color: priceColor(currentMarket.nasdaqChangePercent) }">{{ fmtPct(currentMarket.nasdaqChangePercent) }}</strong></div>
          <div v-if="marketCardTab === '台股'" class="metric"><span class="metric-label">SOX 前一日</span><strong :style="{ color: priceColor(currentMarket.soxChangePercent) }">{{ fmtPct(currentMarket.soxChangePercent) }}</strong><small>{{ currentMarket.usTechAsOfDate || '資料不足' }}</small></div>
        </div>
      </div>

      <div class="fundamental-panel">
        <div class="fundamental-head">
          <span class="fundamental-title">{{ marketCardTab }}週線</span>
          <span v-if="currentMarket.weeklyIndicators?.weekEndDate" class="as-of">
            上一完成週：{{ currentMarket.weeklyIndicators.weekEndDate }}（{{ currentMarket.weeklyIndicators.completedWeeks ?? '—' }} 根完成週）
          </span>
        </div>
        <div v-if="currentMarket.weeklyIndicators" class="market-metrics">
          <div class="metric"><span class="metric-label">週開／高／低／收</span><strong>{{ fmtNumber(currentMarket.weeklyIndicators.open, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.high, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.low, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.close, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">週MA5／10／20</span><strong>{{ fmtNumber(currentMarket.weeklyIndicators.ma5, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.ma10, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.ma20, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">週KD／J9</span><strong>K {{ fmtNumber(currentMarket.weeklyIndicators.k, 1) }} / D {{ fmtNumber(currentMarket.weeklyIndicators.d, 1) }} / J {{ fmtNumber(currentMarket.weeklyIndicators.j9, 1) }}</strong></div>
          <div class="metric"><span class="metric-label">週DIF／MACD／OSC</span><strong>{{ fmtNumber(currentMarket.weeklyIndicators.dif, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.macd, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.osc, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">週RSI5／10</span><strong>{{ fmtNumber(currentMarket.weeklyIndicators.rsi5, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.rsi10, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">週BIAS10／20</span><strong>{{ fmtNumber(currentMarket.weeklyIndicators.bias10, 2) }}／{{ fmtNumber(currentMarket.weeklyIndicators.bias20, 2) }}</strong></div>
          <div class="metric"><span class="metric-label">週量比</span><strong>{{ fmtRatio(currentMarket.weeklyIndicators.volumeRatio) }}</strong></div>
          <div class="metric"><span class="metric-label">週漲跌</span><strong :style="{ color: priceColor(currentMarket.weeklyIndicators.changePercent) }">{{ fmtPct(currentMarket.weeklyIndicators.changePercent) }}</strong></div>
          <div class="metric"><span class="metric-label">週收盤區間位置</span><strong>{{ currentMarket.weeklyIndicators.closePosition == null ? '—' : Math.round(currentMarket.weeklyIndicators.closePosition * 100) + '%' }}</strong></div>
          <div class="metric"><span class="metric-label">週實體方向</span><strong>{{ candleDirectionLabel(currentMarket.weeklyIndicators.bodyDirection) }}</strong></div>
        </div>
        <div v-else class="muted">完成週不足 60 根或舊快照未含週線欄位，本次不採計{{ marketCardTab }}大盤週線因子。</div>
      </div>

      <el-row :gutter="18" class="reason-row">
        <el-col :xs="24" :md="12">
          <div class="reason-title positive">支持訊號</div>
          <ul v-if="currentMarket.reasons?.length" class="reason-list">
            <li v-for="(item, i) in currentMarket.reasons" :key="`mr-${i}`">{{ item }}</li>
          </ul>
          <div v-else class="muted">目前沒有足夠的正向確認。</div>
        </el-col>
        <el-col :xs="24" :md="12">
          <div class="reason-title risk">風險提醒</div>
          <ul v-if="currentMarket.risks?.length" class="reason-list">
            <li v-for="(item, i) in currentMarket.risks" :key="`mk-${i}`">{{ item }}</li>
          </ul>
          <div v-else class="muted">目前沒有額外風險提醒。</div>
        </el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="public-info-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">台美公開財經資訊</span>
          <span class="as-of">近 72 小時；原文揭露，不做關鍵字情緒評分</span>
        </div>
      </template>
      <el-row :gutter="18">
        <el-col v-for="group in informationGroups" :key="group.region" :xs="24" :md="12">
          <div class="reason-title">{{ group.label }}</div>
          <div v-if="group.items.length" class="info-list">
            <div v-for="item in group.items" :key="`${group.region}-${item.url}-${item.publishedAt}`" class="info-item">
              <a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
              <small>{{ item.source || '來源未標示' }} · {{ formatTime(item.publishedAt) }}</small>
            </div>
          </div>
          <div v-else class="muted">近 72 小時無可用資訊。</div>
        </el-col>
      </el-row>
    </el-card>

    <el-card shadow="never" class="stocks-card">
      <template #header>
        <div class="card-head">
          <div>
            <span class="section-title">我的{{ marketTab }}決策</span>
            <span class="stock-count">{{ currentStocks.length }} 檔</span>
          </div>
          <span v-if="radar.skippedNonTwStocks" class="as-of">第一版未評分英股 {{ radar.skippedNonTwStocks }} 檔</span>
        </div>
      </template>

      <el-tabs v-model="marketTab" style="margin-bottom:12px">
        <el-tab-pane name="台股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <TaiwanMap :size="18" />
              台股 <el-tag size="small" style="margin-left:2px">{{ twStocks.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
        <el-tab-pane name="美股">
          <template #label>
            <span style="display:inline-flex;align-items:center;gap:6px">
              <UsFlag :size="22" />
              美股 <el-tag size="small" style="margin-left:2px">{{ usStocks.length }}</el-tag>
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div class="holding-period-focus" aria-label="本次資金預定持有期">
        <div>
          <div class="holding-period-title">本次資金預定持有期</div>
          <div v-if="selectedHorizon == null" class="holding-period-note">{{ HOLDING_PERIOD_PROMPT }}</div>
          <div v-else class="holding-period-note">已聚焦{{ selectedHorizonPresentation?.label }}；其餘兩軌仍完整顯示，不會合成單一建議。</div>
        </div>
        <el-radio-group v-model="selectedHorizon" size="small">
          <el-radio-button v-for="option in HOLDING_PERIOD_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <el-button v-if="selectedHorizon != null" text size="small" @click="selectedHorizon = HOLDING_PERIOD_STATES.NONE">清除聚焦</el-button>
      </div>

      <el-alert
        v-if="marketTab === '美股'"
        type="info"
        :closable="false"
        show-icon
        class="us-market-note"
        title="美股大盤情境採 NASDAQ 綜合指數（IXIC）自身技術面，非台股加權指數"
        description="本分頁個股的買賣建議依據那斯達克綜合指數（IXIC）自身的均線與 KD 技術面判斷大盤環境，與台股加權指數 regime 為不同的大盤情境、彼此不互相影響，請勿誤以為兩者同源；IXIC 的完整 regime、分數與各項指標可至頁面頂部「美股大盤風險」分頁檢視。"
      />

      <el-table
        v-if="currentStocks.length"
        :data="currentStocks"
        :row-key="row => `${row.market}_${row.stockCode}`"
        stripe
        style="width:100%"
        @row-dblclick="onStockDblClick"
        @expand-change="onRowExpand"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-panel">
              <el-alert
                v-if="isDetailLoading(row)"
                type="info"
                :closable="false"
                show-icon
                title="正在載入此股票的明細…"
              />
              <el-alert
                v-else-if="detailError(row)"
                type="error"
                :closable="false"
                show-icon
                :title="detailError(row)"
              />
              <template v-else-if="hasDetail(row)">
              <div class="evidence-summary">
                <div class="confirm-grid">
                  <div class="confirm-item">
                    <span>一周證據信心</span>
                    <strong>{{ row.shortEvidenceConfidence == null ? '—' : row.shortEvidenceConfidence + ' / 100' }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>1周~1月 證據信心</span>
                    <strong>{{ row.swingEvidenceConfidence == null ? '—' : row.swingEvidenceConfidence + ' / 100' }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>1月~6月 證據信心</span>
                    <strong>{{ row.mediumEvidenceConfidence == null ? '—' : row.mediumEvidenceConfidence + ' / 100' }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>三軌下檔風險</span>
                    <strong>{{ row.shortDownsideRisk == null ? '—' : row.shortDownsideRisk }} ／ {{ row.swingDownsideRisk == null ? '—' : row.swingDownsideRisk }} ／ {{ row.mediumDownsideRisk == null ? '—' : row.mediumDownsideRisk }}</strong>
                    <small>風險覆蓋 {{ row.shortRiskCoverage == null ? '—' : fmtPct(row.shortRiskCoverage * 100) }} ／ {{ row.swingRiskCoverage == null ? '—' : fmtPct(row.swingRiskCoverage * 100) }} ／ {{ row.mediumRiskCoverage == null ? '—' : fmtPct(row.mediumRiskCoverage * 100) }}</small>
                  </div>
                  <div
                    v-for="decision in presentAllHorizonDecisions(row, selectedHorizon)"
                    :key="`${row.market}-${row.stockCode}-${decision.horizon}`"
                    class="confirm-item action-decision-card"
                    :class="{ 'action-decision-card-focused': decision.selected }"
                  >
                    <span>{{ decision.label }} 候選／實際動作</span>
                    <strong>{{ decision.candidateAction || '—' }} → {{ decision.action || '—' }}</strong>
                    <el-tag v-if="decision.selected" size="small" type="primary" effect="plain">本次持有期聚焦</el-tag>
                    <small v-if="decision.selected">{{ completedCandleDisclosure(row) }}</small>
                  </div>
                  <div class="confirm-item dividend-confirm-item" v-if="row.evidence?.nextDistributionStatus">
                    <span>下一配息（已知時點）· {{ row.evidence.nextDistributionStatus }}</span>
                    <div class="dividend-date-grid">
                      <div class="dividend-date-cell">
                        <small>除息</small>
                        <strong>{{ row.evidence.nextExDividendDate || '—' }}</strong>
                      </div>
                      <div class="dividend-date-cell">
                        <small>除權</small>
                        <strong>{{ row.evidence.nextExRightsDate || '—' }}</strong>
                      </div>
                      <div class="dividend-date-cell">
                        <small>發放股息</small>
                        <strong>{{ row.evidence.nextCashPaymentDate || '—' }}</strong>
                      </div>
                      <div class="dividend-date-cell">
                        <small>發放股權</small>
                        <strong>{{ row.evidence.nextStockPaymentDate || '—' }}</strong>
                      </div>
                    </div>
                    <small>取得時點 {{ fmtDateOnly(row.evidence.nextDistributionKnownAt) }} · {{ row.evidence.nextDistributionProvider || '—' }}</small>
                    <small v-if="row.evidence.nextDistributionSourceUrls?.length">來源：{{ row.evidence.nextDistributionSourceUrls.join('、') }}</small>
                    <small>5／20 個交易日內：{{ row.evidence.distributionsWithinFiveSessions ?? '—' }}／{{ row.evidence.distributionsWithinTwentySessions ?? '—' }}</small>
                    <small v-if="row.evidence.nextDistributionMissingReason">證據說明：{{ row.evidence.nextDistributionMissingReason }}</small>
                  </div>
                </div>
                <div class="completed-candle-disclosure">{{ completedCandleDisclosure(row) }}</div>
              </div>
              <div class="confirm-grid">
                <div class="confirm-item">
                  <span>日線 MA5</span><strong>{{ fmtNumber(row.weeklyMa, 2) }}</strong>
                </div>
                <div class="confirm-item">
                  <span>月線 MA20</span><strong>{{ fmtNumber(row.monthlyMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.monthlyConfirmation)" effect="plain">{{ confirmationLabel(row.monthlyConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>季線 MA60</span><strong>{{ fmtNumber(row.quarterlyMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.quarterlyConfirmation)" effect="plain">{{ confirmationLabel(row.quarterlyConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>季線乖離</span><strong>{{ row.ma60BiasPercent == null ? '—' : fmtPct(row.ma60BiasPercent) }}</strong>
                </div>
                <div class="confirm-item">
                  <span>52 週位置</span>
                  <strong>{{ row.week52Position == null ? '—' : Math.round(row.week52Position * 100) + '%' }}</strong>
                </div>
                <div v-if="row.etfPremiumPct != null" class="confirm-item">
                  <!-- 主表格另有「折溢價(即時)」欄；此處是進 veto 的完成日值，盤中兩者本來就會不同，必須能分辨 -->
                  <span>折溢價(完成日)</span>
                  <strong>{{ fmtPct(row.etfPremiumPct) }}<small
                    v-if="row.etfPremiumPercentile != null"> · 自身歷史 {{ Math.round(row.etfPremiumPercentile) }} 分位</small><small
                    v-else> · 分位資料累積中（需滿 60 個交易日）</small></strong>
                </div>
                <div class="confirm-item">
                  <span>年線 MA240</span><strong>{{ fmtNumber(row.annualMa, 2) }}</strong>
                  <el-tag size="small" :type="confirmationType(row.annualConfirmation)" effect="plain">{{ confirmationLabel(row.annualConfirmation) }}</el-tag>
                </div>
                <div class="confirm-item">
                  <span>KD</span><strong>K {{ fmtNumber(row.kValue, 1) }} / D {{ fmtNumber(row.dValue, 1) }}</strong>
                  <small>完成日 K：{{ row.dailyCandle?.asOfDate || '資料不足' }}</small>
                </div>
                <div class="confirm-item"><span>J9</span><strong>{{ fmtNumber(row.extendedIndicators?.j9, 2) }}</strong></div>
                <div class="confirm-item"><span>MACD／DIF／OSC</span><strong>{{ fmtNumber(row.extendedIndicators?.macd, 2) }}／{{ fmtNumber(row.extendedIndicators?.dif, 2) }}／{{ fmtNumber(row.extendedIndicators?.osc, 2) }}</strong></div>
                <div class="confirm-item"><span>RSI5／RSI10</span><strong>{{ fmtNumber(row.extendedIndicators?.rsi5, 2) }}／{{ fmtNumber(row.extendedIndicators?.rsi10, 2) }}</strong></div>
                <div class="confirm-item"><span>BIAS10／BIAS20</span><strong>{{ fmtNumber(row.extendedIndicators?.bias10, 2) }}／{{ fmtNumber(row.extendedIndicators?.bias20, 2) }}</strong></div>
                <div class="confirm-item"><span>W%R9</span><strong>{{ fmtNumber(row.extendedIndicators?.wr9, 2) }}</strong></div>
                <div class="confirm-item"><span>個股完成日量比</span><strong>{{ fmtRatio(row.volumeRatio) }}</strong></div>
                <div v-if="row.underlyingCurrency && row.underlyingCurrency !== 'TWD'" class="confirm-item"><span>匯率完成日</span><strong>{{ row.fxAsOfDate || '精確資料不可得' }}</strong></div>
              </div>

              <div class="fundamental-panel">
                <div class="fundamental-head">
                  <span class="fundamental-title">日K 棒</span>
                  <span v-if="row.dailyCandle?.asOfDate" class="as-of">完成日 K：{{ row.dailyCandle.asOfDate }}</span>
                </div>
                <div v-if="row.dailyCandle" class="confirm-grid">
                  <div class="confirm-item">
                    <span>開／高／低／收</span>
                    <strong>{{ fmtNumber(row.dailyCandle.open, 2) }}／{{ fmtNumber(row.dailyCandle.high, 2) }}／{{ fmtNumber(row.dailyCandle.low, 2) }}／{{ fmtNumber(row.dailyCandle.close, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>收盤區間位置</span>
                    <strong>{{ row.dailyCandle.closePosition == null ? '—' : Math.round(row.dailyCandle.closePosition * 100) + '%' }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>實體方向</span>
                    <strong>{{ candleDirectionLabel(row.dailyCandle.bodyDirection) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>下影線比例</span>
                    <strong>{{ row.dailyCandle.lowerShadowRatio == null ? '—' : Math.round(row.dailyCandle.lowerShadowRatio * 100) + '%' }}</strong>
                  </div>
                </div>
                <div v-else class="muted">完成日 K 資料不足；舊快照尚未含日K 棒欄位時請重新整理。</div>
              </div>

              <div class="fundamental-panel">
                <div class="fundamental-head">
                  <span class="fundamental-title">週K</span>
                  <span v-if="row.weeklyIndicators?.weekEndDate" class="as-of">
                    上一完成週：{{ row.weeklyIndicators.weekEndDate }}（{{ row.weeklyIndicators.completedWeeks ?? '—' }} 根完成週）
                  </span>
                </div>
                <div v-if="row.weeklyIndicators" class="confirm-grid">
                  <div class="confirm-item">
                    <span>週開／高／低／收</span>
                    <strong>{{ fmtNumber(row.weeklyIndicators.open, 2) }}／{{ fmtNumber(row.weeklyIndicators.high, 2) }}／{{ fmtNumber(row.weeklyIndicators.low, 2) }}／{{ fmtNumber(row.weeklyIndicators.close, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週量</span>
                    <strong>{{ row.weeklyIndicators.volume == null ? '—' : Number(row.weeklyIndicators.volume).toLocaleString('zh-TW') }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週MA5／10／20</span>
                    <strong>{{ fmtNumber(row.weeklyIndicators.ma5, 2) }}／{{ fmtNumber(row.weeklyIndicators.ma10, 2) }}／{{ fmtNumber(row.weeklyIndicators.ma20, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週KD／J9</span>
                    <strong>K {{ fmtNumber(row.weeklyIndicators.k, 1) }} / D {{ fmtNumber(row.weeklyIndicators.d, 1) }} / J {{ fmtNumber(row.weeklyIndicators.j9, 1) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週DIF／MACD／OSC</span>
                    <strong>{{ fmtNumber(row.weeklyIndicators.dif, 2) }}／{{ fmtNumber(row.weeklyIndicators.macd, 2) }}／{{ fmtNumber(row.weeklyIndicators.osc, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週RSI5／10</span>
                    <strong>{{ fmtNumber(row.weeklyIndicators.rsi5, 2) }}／{{ fmtNumber(row.weeklyIndicators.rsi10, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週BIAS10／20</span>
                    <strong>{{ fmtNumber(row.weeklyIndicators.bias10, 2) }}／{{ fmtNumber(row.weeklyIndicators.bias20, 2) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週量比</span>
                    <strong>{{ fmtRatio(row.weeklyIndicators.volumeRatio) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週漲跌</span>
                    <strong :style="{ color: priceColor(row.weeklyIndicators.changePercent) }">{{ fmtPct(row.weeklyIndicators.changePercent) }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週收盤區間位置</span>
                    <strong>{{ row.weeklyIndicators.closePosition == null ? '—' : Math.round(row.weeklyIndicators.closePosition * 100) + '%' }}</strong>
                  </div>
                  <div class="confirm-item">
                    <span>週實體方向</span>
                    <strong>{{ candleDirectionLabel(row.weeklyIndicators.bodyDirection) }}</strong>
                  </div>
                </div>
                <div v-else class="muted">完成週不足 60 根或舊快照未含週K 欄位，本檔今日不採計週線因子。</div>
              </div>

              <!--
                Task408：technicalResolution 是後端已完成 context binding、freshness 與
                field-level overlay 後的稽核資料。這裡只分組與格式化，不重新計算任何技術值，
                也不得把 null 誤呈現成 0 或當作目前的富邦資料。
              -->
              <div class="fundamental-panel technical-resolution-panel">
                <div class="fundamental-head">
                  <span class="fundamental-title">技術指標來源與採用</span>
                  <el-tag size="small" :type="technicalResolutionType(row.technicalResolution)" effect="plain">
                    {{ technicalResolutionLabel(row.technicalResolution) }}
                  </el-tag>
                </div>
                <template v-if="row.technicalResolution">
                  <div class="technical-resolution-meta">
                    <div class="technical-resolution-meta-item">
                      <span>決策輸入版本</span>
                      <strong>{{ row.technicalResolution.decisionInputVersion || '—' }}</strong>
                    </div>
                    <div class="technical-resolution-meta-item">
                      <span>來源／綁定</span>
                      <strong>{{ technicalSourceLabel(row.technicalResolution.source) }}／{{ technicalBindingLabel(row.technicalResolution.binding) }}</strong>
                    </div>
                    <div class="technical-resolution-meta-item">
                      <span>capture／context</span>
                      <strong>{{ row.technicalResolution.captureId || '—' }}</strong>
                      <small>{{ row.technicalResolution.contextFingerprint || '—' }}</small>
                    </div>
                    <div class="technical-resolution-meta-item">
                      <span>最早觀測／有效至</span>
                      <strong>{{ formatTime(row.technicalResolution.oldestObservedAt) }}／{{ formatTime(row.technicalResolution.freshUntil) }}</strong>
                    </div>
                    <div class="technical-resolution-meta-item">
                      <span>回應時資料年齡</span>
                      <strong>{{ technicalAgeLabel(row.technicalResolution.ageSeconds) }}</strong>
                      <small>100 秒為 Redis／DB re-project 的固定新鮮度邊界。</small>
                    </div>
                  </div>

                  <div v-if="technicalProfileGroups(row.technicalResolution).length" class="technical-profile-groups">
                    <div v-for="group in technicalProfileGroups(row.technicalResolution)" :key="`${row.market}-${row.stockCode}-${group.key}`" class="technical-profile-group">
                      <div class="technical-profile-group-title">{{ group.label }} profiles</div>
                      <div class="technical-profile-grid">
                        <div v-for="profile in group.profiles" :key="profile.profileId" class="technical-profile-card">
                          <div class="technical-profile-card-head">
                            <strong>{{ profile.profileId || '—' }}</strong>
                            <el-tag size="small" :type="technicalProfileType(profile.status)" effect="plain">{{ profile.status || '—' }}</el-tag>
                          </div>
                          <small>資格：{{ technicalEligibilityLabel(profile.eligibility) }}</small>
                          <small>來源日：{{ profile.sourceDate || '—' }} · 觀測：{{ formatTime(profile.observedAt) }}</small>
                          <small>參數：{{ formatTechnicalMap(profile.parameters) }}</small>
                          <small>payload：{{ formatTechnicalMap(profile.payload) }}</small>
                          <small v-if="profile.reason">未納入／狀態原因：{{ profile.reason }}</small>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div v-else class="muted">本次 technicalResolution 未含 profile 資料；不以任何預設值補齊。</div>

                  <div class="technical-provenance-panel">
                    <div class="technical-profile-group-title">欄位來源與未納入原因</div>
                    <ul v-if="row.technicalResolution.fieldProvenance?.length" class="technical-provenance-list">
                      <li v-for="field in row.technicalResolution.fieldProvenance" :key="`${field.field}-${field.profileId}-${field.reason}`">
                        <strong>{{ field.field || '—' }}</strong>
                        <span>{{ technicalOriginLabel(field.origin) }}</span>
                        <small>profile：{{ field.profileId || '—' }} · {{ field.reason || '—' }}</small>
                      </li>
                    </ul>
                    <div v-else class="muted">欄位級 provenance 不可得；不推測富邦或本地來源。</div>
                  </div>
                </template>
                <div v-else class="muted">舊快照（LEGACY_LOCAL_V0）未含 technicalResolution；請重新整理，畫面不將其視為 0 或最新富邦來源。</div>
              </div>

              <div class="fundamental-panel">
                <div class="fundamental-head">
                  <span class="fundamental-title">基本面與產業</span>
                  <el-tag v-if="row.fundamental?.applicable" size="small" type="info" effect="plain">
                    基本面 {{ row.fundamental.coverage ?? 0 }}/{{ row.market === '美股' ? 3 : 4 }}
                  </el-tag>
                </div>
                <div v-if="!row.fundamental" class="muted">舊快照尚未含基本面欄位，請重新整理。</div>
                <div v-else-if="!row.fundamental.applicable" class="muted">
                  ETF 不適用個股財報與產業營收因子，其權重已重分配至其餘可用因子。
                </div>
                <div class="valuation-component-grid">
                  <div
                    v-for="component in valuationComponents(row)"
                    :key="`${row.market}-${row.stockCode}-valuation-${component.key}`"
                    class="fundamental-item valuation-component-item"
                  >
                    <div class="valuation-component-head">
                      <span>{{ component.label }}</span>
                      <el-tag size="small" :type="component.tagType" effect="plain">{{ component.statusLabel }}</el-tag>
                    </div>
                    <strong v-if="component.key === 'pe' && component.loss === true">可信來源顯示虧損</strong>
                    <strong v-else>{{ valuationValueLabel(component) }}</strong>
                    <small v-if="component.legacy">{{ component.legacyMessage }}</small>
                    <template v-else>
                      <small>自身分位：{{ component.percentile == null ? '—' : Math.round(component.percentile) + ' 分位' }}</small>
                      <small>Provider：{{ component.provider || '—' }}</small>
                      <small>資料日期：{{ component.asOf || '—' }}</small>
                      <small>可得時間：{{ component.availableAt ? formatTime(component.availableAt) : '—' }}</small>
                      <small v-if="component.missingReason">缺漏原因：{{ component.missingReason }}</small>
                      <small v-if="component.key === 'pe' && component.loss === true">該虧損 observation 的來源與日期如上，不以 generic 估值來源代填。</small>
                      <div v-if="component.sourceLinks.length" class="source-links">
                        <a v-for="(url, i) in component.sourceLinks" :key="`${component.key}-${i}`" :href="url" target="_blank" rel="noopener noreferrer">來源 {{ i + 1 }}</a>
                      </div>
                      <small v-else-if="!component.sourceNotes.length">來源網址：—</small>
                      <!-- 非 http(s) 的項目（如 SEC_DERIVED 的 derived://... 推導標記）以純文字揭露，
                           不得 render 成超連結：那會是一個看起來像官方來源、點下去卻是死連結的「來源 N」。 -->
                      <small v-for="(note, i) in component.sourceNotes" :key="`${component.key}-note-${i}`">來源標記：{{ note }}</small>
                    </template>
                  </div>
                </div>
                <template v-if="row.fundamental?.applicable">
                  <div class="fundamental-grid">
                    <div class="fundamental-item">
                      <span>估值 composite／覆蓋</span>
                      <strong>{{ fmtNumber(row.fundamental.valuationContribution, 4) }} ／ {{ row.fundamental.valuationCoverage == null ? '—' : row.fundamental.valuationCoverage }}</strong>
                      <small>沿用後端 composite 與 coverage；畫面不重算分位、freshness 或樣本門檻。</small>
                    </div>
                    <div class="fundamental-item">
                      <span>EPS 趨勢／ROE fallback</span>
                      <strong>{{ row.fundamental.epsTrendType || '—' }} ／ {{ boolLabel(row.fundamental.roeApproximationFallback) }}</strong>
                      <small>僅呈現後端已確認的趨勢與近似 ROE 來源狀態，不在前端重算。</small>
                    </div>
                    <div class="fundamental-item">
                      <span>EPS TTM 年增</span><strong>{{ fmtPct(row.fundamental.epsYoyPct) }}</strong>
                      <small>{{ sourceLine(row.fundamental.epsProvider, row.fundamental.epsAsOf) }}</small>
                      <div class="source-links"><a v-for="(url, i) in row.fundamental.epsSourceUrls || []" :key="`eps-${i}`" :href="url" target="_blank" rel="noopener noreferrer">來源 {{ i + 1 }}</a></div>
                    </div>
                    <div class="fundamental-item">
                      <span>近似 ROE</span><strong>{{ fmtPct(row.fundamental.approximateRoePct) }}</strong>
                      <small>{{ roeBasisLabel(row.fundamental.roeApproximationFallback) }}</small>
                      <small>{{ sourceLine(row.fundamental.roeProvider, row.fundamental.roeAsOf) }}</small>
                      <div class="source-links"><a v-for="(url, i) in row.fundamental.roeSourceUrls || []" :key="`roe-${i}`" :href="url" target="_blank" rel="noopener noreferrer">來源 {{ i + 1 }}</a></div>
                    </div>
                    <div class="fundamental-item">
                      <span>近 3 月營收年增</span><strong>{{ fmtPct(row.fundamental.revenueYoy3mPct) }}</strong>
                      <small>{{ sourceLine(row.fundamental.revenueProvider, row.fundamental.revenueAsOf) }}</small>
                      <div class="source-links"><a v-for="(url, i) in row.fundamental.revenueSourceUrls || []" :key="`rev-${i}`" :href="url" target="_blank" rel="noopener noreferrer">來源 {{ i + 1 }}</a></div>
                    </div>
                    <div class="fundamental-item">
                      <span>產業發展</span><strong>{{ row.fundamental.industryName || '—' }} · {{ fmtPct(row.fundamental.industryRevenueYoyPct) }}</strong>
                      <small>{{ row.fundamental.industryPeriod || '資料累積中' }}<template v-if="row.fundamental.industryCompanyCount != null"> · {{ row.fundamental.industryCompanyCount }} 家</template></small>
                      <small>{{ sourceLine(row.fundamental.industryProvider, row.fundamental.industryAsOf) }}</small>
                      <div class="source-links"><a v-for="(url, i) in row.fundamental.industrySourceUrls || []" :key="`ind-${i}`" :href="url" target="_blank" rel="noopener noreferrer">來源 {{ i + 1 }}</a></div>
                    </div>
                  </div>
                  <el-row :gutter="18" class="public-evidence-row">
                    <el-col :xs="24" :md="12">
                      <div class="reason-title">public_info_* 個股證據</div>
                      <div v-if="row.fundamental.companyPublicInformation?.length" class="info-list compact">
                        <div v-for="item in row.fundamental.companyPublicInformation" :key="`co-${item.url}-${item.publishedAt}`" class="info-item">
                          <a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
                          <small>{{ item.source || '來源未標示' }} · {{ formatTime(item.publishedAt) }}</small>
                        </div>
                      </div>
                      <div v-else class="muted">近 120 日無相關公開資訊；不加分也不扣分。</div>
                    </el-col>
                    <el-col :xs="24" :md="12">
                      <div class="reason-title">public_info_* 產業證據</div>
                      <div v-if="row.fundamental.industryPublicInformation?.length" class="info-list compact">
                        <div v-for="item in row.fundamental.industryPublicInformation" :key="`in-${item.url}-${item.publishedAt}`" class="info-item">
                          <a :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
                          <small>{{ item.source || '來源未標示' }} · {{ formatTime(item.publishedAt) }}</small>
                        </div>
                      </div>
                      <div v-else class="muted">近 120 日無相關公開資訊；不加分也不扣分。</div>
                    </el-col>
                  </el-row>
                </template>
              </div>

              <div v-if="row.evidence" class="evidence-detail-panel">
                <div class="fundamental-head">
                  <span class="fundamental-title">判斷證據與資料完整性</span>
                  <el-tag size="small" :type="row.evidence.assetProfile?.profileComplete == null ? 'info' : (row.evidence.assetProfile.profileComplete ? 'success' : 'warning')" effect="plain">
                    嚴格輪廓 {{ row.evidence.assetProfile?.profileComplete == null ? '資料不足' : (row.evidence.assetProfile.profileComplete ? '完整' : '不完整') }}
                  </el-tag>
                </div>
                <div class="confirm-grid">
                  <div v-if="isClassifiableRadarSecurity(row)" class="confirm-item">
                    <span>資產類別設定（生效值）</span>
                    <template v-if="row.evidence.settingsClassification">
                      <strong>{{ settingsAssetClassLabel(row.evidence.settingsClassification) }} · {{ settingsSubdivisionLabel(row.evidence.settingsClassification) }}</strong>
                      <small>資產類別來源：{{ classificationSourceLabel(row.evidence.settingsClassification.assetClassSource) }}</small>
                      <small>股票風格：{{ row.evidence.settingsClassification.effectiveStockStyle ? `${radarStockStyleLabel(row.evidence.settingsClassification.effectiveStockStyle)}（${classificationSourceLabel(row.evidence.settingsClassification.stockStyleSource)}）` : '不適用' }}</small>
                      <small>債券期別：{{ row.evidence.settingsClassification.effectiveBondTerm ? `${radarBondTermLabel(row.evidence.settingsClassification.effectiveBondTerm)}（${classificationSourceLabel(row.evidence.settingsClassification.bondTermSource)}）` : '不適用' }}</small>
                      <small v-if="settingsClassificationOverrideSummary(row.evidence.settingsClassification)">使用者指定覆寫：{{ settingsClassificationOverrideSummary(row.evidence.settingsClassification) }}</small>
                    </template>
                    <small v-else>舊快照尚未提供資產類別設定投影；不以嚴格雷達輪廓替代。</small>
                  </div>
                  <div v-if="isClassifiableRadarSecurity(row)" class="confirm-item">
                    <span>嚴格交易雷達資產輪廓</span>
                    <strong>{{ radarAssetClassLabel(row.evidence.assetProfile) }} · {{ radarAssetSubdivisionLabel(row.evidence.assetProfile) }}</strong>
                    <small>資產類別來源：{{ classificationSourceLabel(row.evidence.assetProfile?.assetClassSource) }} · 完整 {{ boolLabel(row.evidence.assetProfile?.assetClassComplete) }}</small>
                    <small>股票風格：{{ radarStockStyleLabel(row.evidence.assetProfile?.stockStyle) }}（{{ classificationSourceLabel(row.evidence.assetProfile?.stockStyleSource) }}）</small>
                    <small>債券期別：{{ radarBondTermLabel(row.evidence.assetProfile?.bondTerm) }}（{{ classificationSourceLabel(row.evidence.assetProfile?.bondTermSource) }}）</small>
                    <small v-if="assetProfileOverrideSummary(row.evidence.assetProfile)">使用者指定覆寫：{{ assetProfileOverrideSummary(row.evidence.assetProfile) }}</small>
                    <small>此輪廓供雷達風險與資料完整性判定，不等同資產類別設定頁分類。</small>
                  </div>
                  <div class="confirm-item">
                    <span>幣別證據</span>
                    <strong>{{ row.evidence.assetProfile?.quoteCurrency || '—' }} → {{ row.evidence.assetProfile?.underlyingCurrency || '—' }}</strong>
                    <small>報價完整 {{ boolLabel(row.evidence.assetProfile?.quoteCurrencyComplete) }} · 底層幣別完整 {{ boolLabel(row.evidence.assetProfile?.underlyingCurrencyComplete) }}</small>
                    <small>幣別資料完整 {{ boolLabel(row.evidence.assetProfile?.currencyDataComplete) }}</small>
                    <small v-if="row.evidence.assetProfile?.missingReasons?.length">缺漏：{{ row.evidence.assetProfile.missingReasons.join('；') }}</small>
                  </div>
                  <div class="confirm-item">
                    <span>60 日波動</span>
                    <strong>{{ fmtNumber(row.evidence.returnStdDev60Ratio, 4) }}</strong>
                    <small>{{ sourceLine(row.evidence.returnStdDev60Source, row.evidence.returnStdDev60AsOfDate) }}</small>
                  </div>
                  <div class="confirm-item">
                    <span>接受價格證據</span>
                    <strong>{{ row.evidence.acceptedPriceQuality || '—' }}</strong>
                    <small>{{ sourceLine(row.evidence.acceptedPriceSource, row.evidence.acceptedPriceAsOfDate) }} · 即時採用 {{ row.evidence.livePriceAccepted ? '是' : '否' }}</small>
                  </div>
                  <div class="confirm-item">
                    <span>折溢價證據</span>
                    <strong>{{ row.evidence.premiumSource || '—' }} · {{ row.evidence.premiumSource ? (row.evidence.premiumStale ? 'stale' : 'fresh') : '—' }}</strong>
                    <small>{{ row.evidence.premiumAsOfDate || '—' }}</small>
                  </div>
                  <div v-if="bondRateEvidence(row)" class="confirm-item">
                    <span>債券利率證據</span>
                    <strong>{{ bondRateEvidence(row).applicability || '—' }} · {{ bondRateEvidence(row).provider || '—' }}</strong>
                    <small>{{ bondRateEvidence(row).missingReason || '已提供可用 observation' }}</small>
                  </div>
                  <div v-if="row.evidence.treasuryRateContext" class="confirm-item">
                    <span>美債殖利率情境</span>
                    <strong>{{ row.evidence.treasuryRateContext.tenor || '—' }} · {{ fmtNumber(row.evidence.treasuryRateContext.value, 4) }}%</strong>
                    <small>殖利率數值本身不計入評分；這筆曲線資料是否齊備，會影響債券標的的證據閘門。</small>
                    <small>曲線日 {{ row.evidence.treasuryRateContext.curveDate || '—' }} · {{ row.evidence.treasuryRateContext.provider || '—' }} · batch #{{ row.evidence.treasuryRateContext.batchId ?? '—' }}</small>
                    <small>批次完整 {{ boolLabel(row.evidence.treasuryRateContext.complete) }} · 落後 {{ row.evidence.treasuryRateContext.lagDays ?? '—' }} 日</small>
                    <small>可得 {{ formatTime(row.evidence.treasuryRateContext.availableAt) }} · {{ row.evidence.treasuryRateContext.availabilityBasis || '可得時間基礎未標示' }}</small>
                    <small>抓取 {{ formatTime(row.evidence.treasuryRateContext.fetchedAt) }}</small>
                    <small v-if="row.evidence.treasuryRateContext.staleReason">時效說明：{{ row.evidence.treasuryRateContext.staleReason }}</small>
                    <div v-if="treasurySourceEntries(row.evidence.treasuryRateContext).length" class="source-links">
                      <a v-for="source in treasurySourceEntries(row.evidence.treasuryRateContext)" :key="`treasury-${source.tenor}`" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.tenor }} 來源</a>
                    </div>
                  </div>
                </div>
                <div v-if="evidenceGroups(row).length" class="evidence-groups-panel">
                  <div class="fundamental-title">證據群組（後端已解析）</div>
                  <div class="evidence-group-grid">
                    <div v-for="group in evidenceGroups(row)" :key="`${row.market}-${row.stockCode}-${group.group}`" class="evidence-group-item">
                      <div class="evidence-group-head">
                        <strong>{{ group.group }}</strong>
                        <span>一周 {{ fmtPct(group.shortCoverage * 100) }}／1周~1月 {{ fmtPct(group.swingCoverage * 100) }}／1月~6月 {{ fmtPct(group.mediumCoverage * 100) }}</span>
                      </div>
                      <small>一周 {{ group.shortFresh ? 'fresh' : '缺漏／過期' }} · 1周~1月 {{ group.swingFresh ? 'fresh' : '缺漏／過期' }} · 1月~6月 {{ group.mediumFresh ? 'fresh' : '缺漏／過期' }} · provider {{ group.sourceCount ?? 0 }}</small>
                      <ul v-if="group.components?.length" class="evidence-component-list">
                        <li v-for="component in group.components" :key="`${group.group}-${component.name}`">
                          <span>{{ component.name }} · {{ component.applicability || '—' }}</span>
                          <small>{{ component.provider || '來源未標示' }} · {{ component.asOfDate || '日期未標示' }}<template v-if="component.missingReason"> · {{ component.missingReason }}</template></small>
                        </li>
                      </ul>
                    </div>
                  </div>
                </div>
                <div v-if="marketFeatureEntries(row).length" class="evidence-groups-panel market-feature-panel">
                  <div class="fundamental-title">市場數值特徵（僅供揭露，這些數值目前不進評分）</div>
                  <div class="muted">大盤趨勢另由盤勢因子計入評分，非本面板數值；狀態欄可能出現的「DISCLOSURE_ONLY」是候選／回測路徑用來標記「該項資訊已由盤勢因子代表」的去重狀態，不是正式評分中哪一項有效的區別——本面板九項數值在正式評分中一律不生效。</div>
                  <ul class="evidence-component-list">
                    <li v-for="feature in marketFeatureEntries(row)" :key="`${row.market}-${row.stockCode}-${feature.key}`">
                      <span>{{ feature.key }} · {{ feature.value ?? '—' }} · {{ feature.status || '—' }}</span>
                      <small>{{ feature.provider || '來源未標示' }} · as-of {{ feature.asOfDate || '—' }} · availableAt {{ feature.availableAt || '—' }} · {{ feature.availabilityBasis || 'basis 未標示' }}<template v-if="feature.missingReason"> · {{ feature.missingReason }}</template></small>
                    </li>
                  </ul>
                </div>
              </div>

              <el-row :gutter="18" class="reason-row">
                <el-col :xs="24" :md="12">
                  <div class="reason-title positive">一周支持訊號</div>
                  <ul v-if="row.shortReasons?.length" class="reason-list">
                    <li v-for="(item, i) in row.shortReasons" :key="`ssr-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">沒有足夠的支持訊號。</div>
                </el-col>
                <el-col :xs="24" :md="12">
                  <div class="reason-title risk">一周風險提醒</div>
                  <ul v-if="row.shortRisks?.length" class="reason-list">
                    <li v-for="(item, i) in row.shortRisks" :key="`ssk-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">目前沒有額外風險提醒。</div>
                </el-col>
              </el-row>
              <el-row :gutter="18" class="reason-row">
                <el-col :xs="24" :md="12">
                  <div class="reason-title positive">1周~1月 支持訊號</div>
                  <ul v-if="row.swingReasons?.length" class="reason-list">
                    <li v-for="(item, i) in row.swingReasons" :key="`swr-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">沒有足夠的支持訊號。</div>
                </el-col>
                <el-col :xs="24" :md="12">
                  <div class="reason-title risk">1周~1月 風險提醒</div>
                  <ul v-if="row.swingRisks?.length" class="reason-list">
                    <li v-for="(item, i) in row.swingRisks" :key="`swk-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">目前沒有額外風險提醒。</div>
                </el-col>
              </el-row>
              <el-row :gutter="18" class="reason-row">
                <el-col :xs="24" :md="12">
                  <div class="reason-title positive">1月~6月 支持訊號</div>
                  <ul v-if="row.reasons?.length" class="reason-list">
                    <li v-for="(item, i) in row.reasons" :key="`mr-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">沒有足夠的支持訊號。</div>
                </el-col>
                <el-col :xs="24" :md="12">
                  <div class="reason-title risk">1月~6月 風險提醒</div>
                  <ul v-if="row.risks?.length" class="reason-list">
                    <li v-for="(item, i) in row.risks" :key="`mk-${row.stockCode}-${i}`">{{ item }}</li>
                  </ul>
                  <div v-else class="muted">目前沒有額外風險提醒。</div>
                </el-col>
              </el-row>
              <div v-if="row.evidence?.actionGateReasons?.length" class="action-gate-panel">
                <div class="action-gate-title">動作閘門／風險</div>
                <div class="action-gate-note">這是 1月~6月 → 一周 → 1周~1月的稽核彙總，不是任一軌支持訊號或允許動作依據。</div>
                <ul class="reason-list">
                  <li v-for="(item, i) in row.evidence.actionGateReasons" :key="`agr-${row.stockCode}-${i}`">{{ item }}</li>
                </ul>
              </div>
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
                行情更新：{{ formatTime(row.priceUpdatedAt) }}　·　完成日 K：{{ row.dailyCandle?.asOfDate || '資料不足' }}
                <span v-if="row.distributionAdjusted">　·　技術價基：還原權息／分割</span>
              </div>
              </template>
              <div v-else class="muted">展開後才會載入此股票的明細。</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="標的" min-width="175" fixed="left">
          <template #default="{ row }">
            <div class="stock-code">{{ row.stockCode }}</div>
            <div class="stock-name">{{ row.stockName }}</div>
            <div v-if="row.assetClass === 'BOND' || row.distributionAdjusted || row.fxPercentile != null" class="stock-meta">
              <el-tag v-if="row.assetClass === 'BOND'" size="small" type="info" effect="plain">債券</el-tag>
              <el-tag v-if="row.distributionAdjusted" size="small" type="success" effect="plain">還原權息／分割</el-tag>
              <el-tooltip
                v-if="row.fxPercentile != null"
                placement="top"
                :content="fxTooltip(row)">
                <el-tag size="small" :type="fxTagType(row.fxPercentile)" effect="plain">
                  {{ row.underlyingCurrency }} {{ Math.round(row.fxPercentile) }}%
                </el-tag>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目前狀態" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.held ? 'warning' : 'info'" effect="plain">{{ row.held ? '持有' : '觀察' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="基本面／產業" min-width="150" align="center">
          <template #default="{ row }">
            <template v-if="row.fundamental?.applicable">
              <el-tag size="small" type="info" effect="plain">基本面 {{ row.fundamental.coverage ?? 0 }}/{{ row.market === '美股' ? 3 : 4 }}</el-tag>
              <div class="industry-inline">
                {{ row.fundamental.industryName || '產業累積中' }}
                <span v-if="row.fundamental.industryRevenueYoyPct != null">{{ fmtPct(row.fundamental.industryRevenueYoyPct) }}</span>
              </div>
            </template>
            <span v-else-if="row.fundamental" class="muted">ETF 不適用個股財報</span>
            <span v-else class="muted">資料尚未提供</span>
          </template>
        </el-table-column>
        <el-table-column label="一周" min-width="104" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.shortAction === 'TRIAL_BUY'" placement="top" :content="trialBuyHint">
              <el-tag :type="actionType(row.shortAction)" effect="dark">{{ row.shortActionLabel }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="actionType(row.shortAction)" effect="dark">{{ row.shortActionLabel || '今日不交易' }}</el-tag>
            <div class="score-inline" :style="{ color: scoreColor(row.shortScore) }">{{ row.shortScore == null ? '—' : row.shortScore + ' 分' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="1周~1月" min-width="108" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.swingAction === 'TRIAL_BUY'" placement="top" :content="trialBuyHint">
              <el-tag :type="actionType(row.swingAction)" effect="dark">{{ row.swingActionLabel }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="actionType(row.swingAction)" effect="dark">{{ row.swingActionLabel || '今日不交易' }}</el-tag>
            <div class="score-inline" :style="{ color: scoreColor(row.swingScore) }">{{ row.swingScore == null ? '—' : row.swingScore + ' 分' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="1月~6月" min-width="108" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.action === 'TRIAL_BUY'" placement="top" :content="trialBuyHint">
              <el-tag :type="actionType(row.action)" effect="dark">{{ row.actionLabel }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="actionType(row.action)" effect="dark">{{ row.actionLabel }}</el-tag>
            <div class="score-inline" :style="{ color: scoreColor(row.score) }">{{ row.score == null ? '—' : row.score + ' 分' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="持有期分歧" width="112" align="center">
          <template #default="{ row }"><el-tag v-if="row.horizonConflict" type="warning" effect="dark">持有期分歧</el-tag><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column label="時機" width="104" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.timingState && row.timingState !== 'NEUTRAL'" :content="timingHint(row)" placement="top">
              <el-tag :type="timingType(row.timingState)" :effect="timingEffect(row.timingState)">
                {{ row.timingLabel }}
              </el-tag>
            </el-tooltip>
            <span v-else class="muted">—</span>
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
        <el-table-column label="現價／漲跌" min-width="130" align="right">
          <template #default="{ row }">
            <div v-if="isClosePending(row)" style="color:#d97706;font-size:12px">收盤價待補</div>
            <template v-else>
              <div class="price-value">{{ fmtNumber(row.price, 2) }}</div>
              <div :style="{ color: priceColor(row.changePercent) }">{{ fmtPct(row.changePercent) }}</div>
            </template>
          </template>
        </el-table-column>
        <!--
          Task 320：ETF 即時折溢價（純揭露，不進任何規則）。值與該列現價同一 tick，
          個股與查無淨值者顯示「—」，不補 0。SSE 的 price-update 只帶報價、不帶淨值，
          因此本欄維持 list 回應的資料，不為 SSE 自行重算或重新載入整頁；
          前端一律不自行以 (price−nav)/nav 重算——台股必須直取證交所權威值。
        -->
        <el-table-column label="折溢價(即時)" min-width="120" align="right">
          <template #default="{ row }">
            <div>{{ row.etfPremiumLivePct == null ? '—' : fmtPct(row.etfPremiumLivePct) }}</div>
            <div v-if="row.etfPremiumLiveNavAsOf" style="font-size:12px;color:#909399">{{ row.etfPremiumLiveNavAsOf }}</div>
          </template>
        </el-table-column>
        <el-table-column label="MA5／20／60／240" min-width="250" align="right">
          <template #default="{ row }">
            <span class="ma-summary">
              <span>{{ fmtNumber(row.weeklyMa, 2) }}</span>
              <span class="slash">／</span>
              <span>{{ fmtNumber(row.monthlyMa, 2) }}</span>
              <span class="slash">／</span>
              <span>{{ fmtNumber(row.quarterlyMa, 2) }}</span>
              <span class="slash">／</span>
              <span>{{ fmtNumber(row.annualMa, 2) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="KD" width="168" align="center">
          <template #default="{ row }">
            <span :class="kdHeatClass(row.kdHeat)">K {{ fmtNumber(row.kValue, 1) }} / D {{ fmtNumber(row.dValue, 1) }}</span>
            <el-tag
              v-if="row.kdHeat === 'OVERHEATED' || row.kdHeat === 'ELEVATED'"
              size="small"
              class="kd-heat-tag"
              :type="row.kdHeat === 'OVERHEATED' ? 'danger' : 'warning'"
              :effect="row.kdHeat === 'OVERHEATED' ? 'dark' : 'plain'">
              {{ row.kdHeat === 'OVERHEATED' ? '過熱' : '偏熱' }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          Task 356.12c：收合列「週K」欄，僅摘要週KD／週漲跌；完整週K 數值仍在展開列。
          weeklyIndicators 為 null（舊快照或完成週不足 60 根）時整欄顯示「—」，不得補 0。
        -->
        <el-table-column label="週K" width="168" align="center">
          <template #default="{ row }">
            <span>K {{ fmtNumber(row.weeklyIndicators?.k, 1) }} / D {{ fmtNumber(row.weeklyIndicators?.d, 1) }}</span>
            <div class="score-inline" :style="{ color: priceColor(row.weeklyIndicators?.changePercent) }">{{ fmtPct(row.weeklyIndicators?.changePercent) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="完成日 K" width="115">
          <template #default="{ row }">{{ row.dailyCandleAsOfDate || row.dailyCandle?.asOfDate || '資料不足' }}</template>
        </el-table-column>
        <el-table-column label="通知" width="105" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Bell" @click.stop="openNotification(row)">設定</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else :description="`目前沒有可分析的${marketTab}標的`">
        <el-button type="primary" @click="router.push('/stocks')">前往股票觀察新增標的</el-button>
      </el-empty>
    </el-card>

    <el-alert
      class="disclaimer"
      type="warning"
      :closable="false"
      show-icon
      title="規則式決策輔助，不是獲利保證"
      description="評分只比較市場上的相對位置，不納入成本價、可用資金、配置或其他個人理財需求。財報、估值與產業歷史自上線後累積；缺值權重會重分配，因此不同標的的分數組成可能不同。系統不保證獲利、不會自動下單；資料不足時以「今日不交易」為準。一周、1周~1月、1月~6月三軌分數不是獲利機率或報酬預測；TW_RULES_V17 與 TW_RULES_V18 為不同規則版本，分數不可直接比較。V18 是訊號分類與閱讀改善，不代表任何獲利保證。"
    />

    <el-card shadow="never" class="sched-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">匯出執行時間設定</span>
          <span class="card-sub">交易雷達每天自動匯出 Excel 的時間點，可設定多個</span>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="sched-note"
        description="設定後即時生效（免重啟），下一分鐘起依新時間執行。清空全部時間點＝不再自動匯出。每次排程產檔前會先回補台股行情並重新計算一次雷達，不需先開本頁；匯出內容為當日累積的全部快照（含排程自己算出的那幾筆）；台股休市日不產檔。"
      />

      <div v-for="(row, idx) in exportTimes" :key="idx" class="sched-row">
        <el-time-picker
          v-model="row.time"
          format="HH:mm"
          value-format="HH:mm"
          placeholder="時:分"
          :clearable="false"
          style="width:150px"
        />
        <el-switch v-model="row.enabled" active-text="啟用" inactive-text="停用" inline-prompt />
        <el-button type="danger" plain size="small" @click="removeExportTime(idx)">移除</el-button>
      </div>

      <div class="sched-actions">
        <el-button :icon="Plus" @click="addExportTime">新增時間點</el-button>
        <el-button type="primary" :loading="savingTimes" @click="saveExportTimes">儲存設定</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="sched-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">匯出輸出檔案設定</span>
          <span class="card-sub">每次排程匯出的 Excel 要寫到哪個資料夾</span>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="sched-note"
        description="設定後即時生效（免重啟），下一次排程起寫入新資料夾。目錄不存在時會自動建立。"
      />

      <div class="sched-row">
        <span class="dir-label">輸出資料夾</span>
        <el-input v-model="exportSetting.outputSubpath" placeholder="例如 Project/radar-export" style="width:340px" />
        <el-button @click="openDirPicker">選擇</el-button>
        <el-button type="primary" :loading="savingDir" @click="saveExportSetting">儲存設定</el-button>
        <el-button :loading="runningNow" @click="runExportNow">立即匯出到目錄</el-button>
      </div>

      <div class="dialog-note dir-hint">
        以主機家目錄 <code>/home/steven</code>（對映主機 <code>/Users/steven</code>）為根，只能選其下的子資料夾。
        目前落點：<code>{{ exportSetting.resolvedDir || '—' }}/{{ exportSetting.filenamePattern || '交易雷達_{使用者ID}_{日期}.xlsx / .json' }}</code><br>
        檔名固定為 <code>交易雷達_&lt;使用者ID&gt;_&lt;日期&gt;</code>，<strong>同時產生 .xlsx 與 .json 兩份</strong>；同日多個時間點覆寫同一檔、跨日產生新檔。
        <template v-if="exportSetting.lastRunAt">
          <br>上次執行：{{ formatTime(exportSetting.lastRunAt) }}　{{ exportSetting.lastRunStatus || '' }}
        </template>
      </div>

      <!--
        Google Drive 同步（Requirement 51 / Task 244）：本機一律照寫，這裡只是額外多上傳一份副本。
        僅「主要管理者」可見可設——rclone remote 全機只有一份且綁定某個 Google 帳號。真正的閘門在後端。
      -->
      <div v-if="auth.isConfiguredAdmin" class="sched-row">
        <span class="dir-label">同步 Google Drive</span>
        <el-switch v-model="exportSetting.gdriveEnabled" />
        <el-input
          v-model="exportSetting.gdriveSubpath"
          readonly
          placeholder="（尚未選擇 Drive 資料夾）"
          :disabled="!exportSetting.gdriveEnabled"
          style="width:300px"
        >
          <template #append>
            <el-button :disabled="!exportSetting.gdriveEnabled" @click="openDirPicker('gdrive')">選擇</el-button>
          </template>
        </el-input>
      </div>

      <div v-if="auth.isConfiguredAdmin" class="dialog-note dir-hint">
        開啟後除了寫入上面的本機資料夾，會<strong>再上傳一份同樣的檔案</strong>到 Google Drive 的所選資料夾；
        <strong>本機那一份永遠照寫、不受影響</strong>。
        <template v-if="exportSetting.gdriveEnabled && exportSetting.gdriveSubpath">
          <br>Drive 落點：<code>{{ exportSetting.gdriveRemote || 'GDriveOutput' }}:{{ exportSetting.gdriveSubpath }}</code>
        </template>
        <!-- 本頁一天可能上傳多次（時間點存於另一張表），故是「上次上傳」而非「今日上傳」 -->
        <br>上次上傳：
        <template v-if="exportSetting.gdriveLastRunAt">
          {{ exportSetting.gdriveLastRunAt }} — <code>{{ exportSetting.gdriveLastStatus || '—' }}</code>
        </template>
        <template v-else>—（尚未執行過）</template>
      </div>
    </el-card>

    <!--
      匯出到 blog（Requirement 102 / Task 366、374）：把交易雷達精簡摘要公開發布到 myrader.blogspot.com。
      僅「主要管理者」可見可設——blog 是全機唯一、綁定特定 Google 帳號的目的地。真正的閘門在後端。
    -->
    <el-card v-if="auth.isConfiguredAdmin" id="blog-publish-card" shadow="never" class="sched-card">
      <template #header>
        <div class="card-head">
          <span class="card-title">匯出到 Blog 設定</span>
          <span class="card-sub">交易雷達精簡摘要公開發布到 {{ blogStatus.blogUrl || 'https://myrader.blogspot.com/' }}</span>
        </div>
      </template>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="sched-note"
        description="發布內容為公開頁面，任何人皆可瀏覽，內容含個股代號、三軌分數與加減碼建議；請確認您了解此為公開行為。"
      />

      <template v-if="!blogStatus.connected">
        <div class="dialog-note dir-hint">尚未連接 Blogger 帳號，請先完成連接才能發布。</div>
        <div class="sched-actions">
          <el-button type="primary" :loading="connectingBlog" @click="onConnectBlog">連接 Blogger 帳號</el-button>
        </div>
      </template>
      <template v-else>
        <div class="sched-row">
          <span class="dir-label">已連接：{{ blogStatus.accountLabel || '（未知帳號）' }}</span>
          <el-button type="danger" plain :loading="disconnectingBlog" @click="onDisconnectBlog">中斷連接</el-button>
        </div>

        <div class="sched-row">
          <span class="dir-label">同步發布到 blog</span>
          <el-switch
            v-model="blogStatus.blogEnabled"
            :loading="savingBlogEnabled"
            @change="onToggleBlogEnabled"
          />
          <span class="switch-note">沿用上方〔匯出執行時間設定〕的執行時間點</span>
        </div>

        <div class="dialog-note dir-hint">
          上次發布：
          <template v-if="blogStatus.lastRunAt">
            {{ formatTime(blogStatus.lastRunAt) }} — {{ blogStatus.lastStatus || '—' }}
          </template>
          <template v-else>尚未發布過</template>
          <template v-if="blogStatus.lastPostUrl">
            <br>文章連結：<a :href="blogStatus.lastPostUrl" target="_blank" rel="noopener">{{ blogStatus.lastPostUrl }}</a>
          </template>
        </div>
      </template>
    </el-card>

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
          <el-form-item label="1 月~6 月建議">
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

    <el-dialog v-model="exportDialog.visible" title="匯出交易雷達快照" width="480px">
      <el-form label-width="90px">
        <el-form-item label="時間區間">
          <el-date-picker
            v-model="exportDialog.range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="起始時間"
            end-placeholder="結束時間"
            :clearable="false"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="匯出內容">
          <div class="dialog-note">
            單一 Excel 檔，四張工作表：<b>快照索引／大盤總覽／個股決策／台美公開資訊</b>；
            內容取自區間內每次頁面計算存下的 Redis 快照（每 5 分鐘至多一筆）。
            區間若早於本功能上線日、或快照已逾保留期，該段可能無資料，並會在「快照索引」標示缺漏筆數。
          </div>
        </el-form-item>
        <el-form-item label="存檔位置">
          <div class="dialog-note">
            <template v-if="canPickDirectory">
              按下匯出後會開啟系統「另存新檔」對話框，可自行選擇資料夾與檔名。
            </template>
            <template v-else>
              目前瀏覽器不支援選擇資料夾，檔案將存到瀏覽器預設下載資料夾。
            </template>
            <div>
              另會在<b>伺服器輸出目錄</b>產生同名的 <b>.json ＋ .xlsx</b> 兩份（落點見下方「匯出輸出檔案設定」卡；
              已啟用 Google Drive 同步時一併上傳）。該區間查無快照時只下載、不落檔。
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">匯出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Bell, Download, Plus, Promotion, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { useAuthStore } from '@/stores/authStore'
import { useRoute, useRouter } from 'vue-router'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsFlag from '@/components/UsFlag.vue'
import { isClosePending } from '@/utils/displayQuote'
import { applyTradingRadarSsePriceUpdate } from '@/utils/tradingRadarSsePriceUpdate'
import { projectValuationEvidence } from '@/utils/valuationEvidence'
import {
  HOLDING_PERIOD_OPTIONS,
  HOLDING_PERIOD_PROMPT,
  HOLDING_PERIOD_STATES,
  completedCandleDisclosure,
  presentAllHorizonDecisions,
  presentHorizonDecision
} from '@/utils/tradingRadarDecisionPresentation'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const refreshing = ref(false)
const exporting = ref(false)
const exportDialog = reactive({ visible: false, range: [] })
const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window
// 排程自動匯出到伺服器目錄（Requirement 48 追加 / Task 231）
const exportTimes = ref([])
const exportSetting = reactive({
  outputSubpath: '', resolvedDir: '', filenamePattern: '', lastRunAt: null, lastRunStatus: null,
  // Drive 同步（Task 244）；gdriveRemote 是後端給的顯示值，不入庫。
  // 本頁一天可能上傳多次，故 gdriveLastRunAt／Status 是「最後一次」語意。
  gdriveEnabled: false, gdriveSubpath: '', gdriveRemote: '',
  gdriveLastRunAt: null, gdriveLastStatus: ''
})
const savingTimes = ref(false)
const savingDir = ref(false)
const runningNow = ref(false)
// mode：'local'＝本機家目錄樹、'gdrive'＝Drive remote 樹（回傳形狀相同，共用同一棵 el-tree）
const dirPicker = reactive({
  visible: false, mode: 'local', baseDir: '', picked: '', newSub: '', treeKey: 0, error: ''
})
const dirTreeProps = { label: 'name', isLeaf: 'leaf' }
const auth = useAuthStore()

// 匯出到 blog（Requirement 102 / Task 366、374）：發布到 myrader.blogspot.com，僅主要管理者可見可用
const publishingBlog = ref(false)
const connectingBlog = ref(false)
const disconnectingBlog = ref(false)
const savingBlogEnabled = ref(false)
const blogStatus = reactive({
  connected: false,
  accountLabel: '',
  blogUrl: 'https://myrader.blogspot.com/',
  blogEnabled: false,
  lastRunAt: null,
  lastStatus: null,
  lastPostUrl: null
})

const dirPickerTitle = computed(() =>
  dirPicker.mode === 'gdrive' ? '選擇 Google Drive 資料夾' : '選擇輸出資料夾')

// 兩種 mode 的分隔符不同：Drive 基底是 `remote:`（已含冒號，後面直接接子路徑），
// 本機基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財`——
// 多一個斜線、不是 rclone 的路徑格式，會誤導使用者。
const dirPickerPreview = computed(() => {
  const isGdrive = dirPicker.mode === 'gdrive'
  const base = dirPicker.baseDir
    || (isGdrive ? (exportSetting.gdriveRemote || 'GDriveOutput') + ':' : '/home/steven')
  const parts = [dirPicker.picked, (dirPicker.newSub || '').trim()].filter(Boolean)
  const joined = parts.join('/')
  if (!joined) return base
  return isGdrive ? base + joined : base + '/' + joined
})
const radar = ref({ market: {}, usMarket: {}, stocks: [], publicInformation: [], skippedNonTwStocks: 0, ruleVersion: 'TW_RULES_V18' })
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
const analysisVisible = ref(false)
const analysisStock = ref(null)
const RECONNECT_DELAY_MS = 5000

let priceStream = null
let reconnectTimer = null
let disposed = false
let listGeneration = 0
const expandedDetailKeys = new Set()
const detailStates = reactive({})

// Requirement 148 SSE mapping table: this is the complete allow-list for a price-update.
// Identity is selection-only; no payload field is spread or implicitly merged into a row.
const PRICE_UPDATE_FIELD_MAPPING = Object.freeze([
  Object.freeze({ payload: 'market + stockCode', rowFields: Object.freeze([]), rule: 'exact identity only' }),
  Object.freeze({ payload: 'tradingDate + quoteStatus', rowFields: Object.freeze(['quoteStatus']), rule: 'mergeSseQuote gate' }),
  Object.freeze({ payload: 'price + changePercent|changePct', rowFields: Object.freeze(['price', 'changePercent']), rule: 'accepted atomic tuple' }),
  Object.freeze({ payload: 'updatedAt', rowFields: Object.freeze(['priceUpdatedAt']), rule: 'only with accepted tuple' })
])

const market = computed(() => radar.value.market || {})
// 大盤卡片的台股／美股分頁（Requirement 76 / Task 335）。與下方個股表格的 marketTab 是
// 兩個獨立狀態：使用者可以在看台股大盤的同時看美股個股清單，不得合併成同一個 ref。
const marketCardTab = ref('台股')
const usMarket = computed(() => radar.value.usMarket || {})
const currentMarket = computed(() => marketCardTab.value === '美股' ? usMarket.value : market.value)
// 335.10：分頁標籤上的 stale 標記必須各自反映該組狀態，故刻意不走 currentMarket。
const twStale = computed(() => !!market.value.stale)
const usStale = computed(() => !!usMarket.value.stale)
const stocks = computed(() => radar.value.stocks || [])
// 我的台股決策改為台股／美股兩個分頁（Requirement 64 / Task 295），比照 WatchStockView.vue 的 marketTab 模式
const marketTab = ref('台股')
// 此選擇只影響畫面聚焦；不寫入 localStorage、query、store、後端或快照，也不改動三軌資料。
const selectedHorizon = ref(HOLDING_PERIOD_STATES.NONE)
const selectedHorizonPresentation = computed(() => presentHorizonDecision({}, selectedHorizon.value, selectedHorizon.value))
const twStocks = computed(() => stocks.value.filter(s => s.market === '台股'))
const usStocks = computed(() => stocks.value.filter(s => s.market === '美股'))
const currentStocks = computed(() => marketTab.value === '美股' ? usStocks.value : twStocks.value)
const informationGroups = computed(() => [
  { region: 'TW', label: '台灣', items: (radar.value.publicInformation || []).filter(item => item.region === 'TW') },
  { region: 'US', label: '美國', items: (radar.value.publicInformation || []).filter(item => item.region === 'US') }
])
const marketClass = computed(() => `regime-${String(currentMarket.value.regime || 'DATA_INCOMPLETE').toLowerCase().replace('_', '-')}`)

// First screen and explicit manual refresh read only this page's compact list contract.
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const next = await bffApi.tradingRadar.list()
    if (!disposed) replaceList(next)
  } finally {
    if (!silent) loading.value = false
  }
}

function replaceList(next) {
  listGeneration += 1
  expandedDetailKeys.clear()
  for (const key of Object.keys(detailStates)) delete detailStates[key]
  radar.value = next
}

// Task 249：outcome → 提示文案。不得宣稱做了沒做的事。
const REFRESH_MESSAGES = {
  FETCHED: { type: 'success', text: '已重新抓取即時報價並重算' },
  CLOSED_SYNCED: { type: 'success', text: '台股目前休市，已同步至最新收盤價並重算' },
  SKIPPED_PENDING_CLOSE: { type: 'info', text: '今日官方收盤尚未完成，畫面會維持等待狀態' },
  COOLDOWN: { type: 'info', text: '30 秒內剛更新過，已直接重算' },
  BUSY: { type: 'info', text: '行情更新進行中，已以現有報價重算' },
  TIMEOUT: { type: 'warning', text: '行情抓取未完成，已以現有報價重算' },
  FAILED: { type: 'warning', text: '行情抓取未完成，已以現有報價重算' }
}

/**
 * 手動「重新整理」只取得行情回補 outcome，再明確替換 compact list。SSE 絕不走此路。
 */
async function manualRefresh() {
  refreshing.value = true
  try {
    const resp = await bffApi.tradingRadar.refresh()
    if (disposed) return
    const hint = REFRESH_MESSAGES[resp?.priceRefresh?.outcome] || REFRESH_MESSAGES.TIMEOUT
    ElMessage({ type: hint.type, message: hint.text })
    await load(true)
  } finally {
    refreshing.value = false
  }
}

function applyPriceUpdate(payload) {
  applyTradingRadarSsePriceUpdate(radar.value.stocks || [], payload)
}

function detailKey(row) {
  return `${row.market}\u0000${row.stockCode}`
}

function isDetailLoading(row) {
  return detailStates[detailKey(row)]?.loading === true
}

function detailError(row) {
  return detailStates[detailKey(row)]?.error || ''
}

function hasDetail(row) {
  return detailStates[detailKey(row)]?.loaded === true
}

function onRowExpand(row, expandedRows) {
  const key = detailKey(row)
  const expanded = Array.isArray(expandedRows) && expandedRows.some(candidate => detailKey(candidate) === key)
  if (!expanded) {
    expandedDetailKeys.delete(key)
    return
  }
  expandedDetailKeys.add(key)
  if (hasDetail(row) || isDetailLoading(row)) return
  void loadStockDetail(row, key, listGeneration)
}

async function loadStockDetail(row, key, generation) {
  const state = detailStates[key] = { loading: true, loaded: false, error: '', generation }
  try {
    const response = await bffApi.tradingRadar.stock(row.market, row.stockCode)
    const stillCurrent = !disposed && generation === listGeneration && expandedDetailKeys.has(key)
      && detailStates[key] === state && (radar.value.stocks || []).includes(row)
    if (!stillCurrent) return
    if (!response?.stock) throw new Error('未取得股票明細')
    Object.assign(row, response.stock)
    state.loaded = true
  } catch (error) {
    if (!disposed && generation === listGeneration && expandedDetailKeys.has(key) && detailStates[key] === state) {
      state.error = error?.message || '載入股票明細失敗，請稍後再試。'
    }
  } finally {
    if (detailStates[key] === state) state.loading = false
  }
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

function onStockDblClick(row) {
  analysisStock.value = {
    stockCode: row.stockCode,
    stockName: row.stockName,
    market: row.market
  }
  analysisVisible.value = true
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

/**
 * 匯率分位的標籤配色：越貴越警示。
 * 這檔標的以台幣交易但持有外幣資產，台幣報價 ≈ 底層外幣價 × 匯率，
 * 故「站上均線」有相當部分量到的是匯率而非標的本身（Requirement 47）。
 */
function fxTagType(pct) {
  if (pct == null) return 'info'
  if (pct >= 90) return 'danger'
  if (pct >= 65) return 'warning'
  if (pct <= 35) return 'success'
  return 'info'
}

// 門檻一律由後端 kdHeat 決定，前端不得自行比較 K/D 大小（Task 232）。
function kdHeatClass(kdHeat) {
  if (kdHeat === 'OVERHEATED') return 'kd-overheated'
  if (kdHeat === 'ELEVATED') return 'kd-elevated'
  return ''
}

function fxTooltip(row) {
  const pct = Math.round(row.fxPercentile)
  const cur = row.underlyingCurrency
  let verdict = '換匯成本處於中性區間'
  if (pct >= 90) verdict = '換匯過貴，已否決買進建議'
  else if (pct >= 65) verdict = '換匯偏貴，已反映於分數'
  else if (pct <= 35) verdict = '換匯相對划算'
  return `${cur} 兌台幣位於五年期第 ${pct} 百分位——${verdict}。`
    + `本檔以台幣交易但持有 ${cur} 資產，台幣報價同時受標的與匯率驅動。`
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

function fmtRatio(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${Number(value).toFixed(2)} 倍`
}

// 日K 棒／週K 的實體方向：bodyDirection ∈ {-1,0,1}，缺值不得顯示 0（Task 356.5b／356.6d）。
function candleDirectionLabel(value) {
  if (value == null) return '—'
  const n = Number(value)
  if (Number.isNaN(n)) return '—'
  if (n > 0) return '陽線'
  if (n < 0) return '陰線'
  return '平盤'
}

function valuationComponents(row) {
  return projectValuationEvidence(row?.fundamental, row?.evidence)
}

function valuationValueLabel(component) {
  if (component?.value == null) return '—'
  return component.format === 'PERCENT'
    ? fmtPct(component.value)
    : `${fmtNumber(component.value, 2)} 倍`
}

function sourceLine(provider, asOf) {
  if (!provider && !asOf) return '資料累積中'
  return [provider || '來源未標示', asOf ? formatTime(asOf) : null].filter(Boolean).join(' · ')
}

function boolLabel(value) {
  return value == null ? '—' : (value ? '是' : '否')
}

// Task408：設定頁等價分類與 strict AssetProfile 是兩份不同的後端投影；畫面只翻譯
// 固定 enum，不得依股票名稱、代碼或技術資料自行補推或互相替代。
const RADAR_ASSET_CLASS_LABELS = Object.freeze({
  STOCK: '股票',
  BOND: '債券',
  CASH: '現金'
})

const RADAR_STOCK_STYLE_LABELS = Object.freeze({
  VALUE: '價值',
  GROWTH: '成長',
  INCOME: '收益',
  DIVIDEND: '股利',
  BROAD_MARKET: '大盤',
  SECTOR: '產業',
  REIT: '不動產投資信託'
})

const RADAR_BOND_TERM_LABELS = Object.freeze({
  ULTRA_SHORT: '超短期',
  SHORT: '短期',
  MID: '中期',
  INTERMEDIATE: '中期',
  LONG: '長期',
  ULTRA_LONG: '超長期'
})

const CLASSIFICATION_SOURCE_LABELS = Object.freeze({
  OVERRIDE: '使用者指定覆寫',
  RULE: '規則',
  CODE_RULE: '代號規則',
  NAME_RULE: '名稱規則',
  PUBLIC_VALUATION: '公開估值',
  MARKET_DEFAULT: '市場預設',
  UNKNOWN: '資料不足'
})

function radarAssetClassLabel(profile) {
  if (!profile?.assetClass) return '—'
  return RADAR_ASSET_CLASS_LABELS[profile.assetClass] || profile.assetClass
}

function radarStockStyleLabel(value) {
  if (!value) return '不適用／資料不足'
  return RADAR_STOCK_STYLE_LABELS[value] || value
}

function radarBondTermLabel(value) {
  if (!value) return '不適用／資料不足'
  return RADAR_BOND_TERM_LABELS[value] || value
}

function radarAssetSubdivisionLabel(profile) {
  if (!profile) return '—'
  if (profile.stockStyle) return `股票風格：${radarStockStyleLabel(profile.stockStyle)}`
  if (profile.bondTerm) return `債券期別：${radarBondTermLabel(profile.bondTerm)}`
  return '細分不適用／資料不足'
}

function isClassifiableRadarSecurity(row) {
  return !(row?.market === '台股' && row?.stockCode === '0000')
}

function settingsAssetClassLabel(classification) {
  if (!classification?.effectiveAssetClass) return '—'
  return RADAR_ASSET_CLASS_LABELS[classification.effectiveAssetClass] || classification.effectiveAssetClass
}

function settingsSubdivisionLabel(classification) {
  if (!classification) return '—'
  if (classification.effectiveStockStyle) {
    return `股票風格：${radarStockStyleLabel(classification.effectiveStockStyle)}`
  }
  if (classification.effectiveBondTerm) {
    return `債券期別：${radarBondTermLabel(classification.effectiveBondTerm)}`
  }
  return '細分不適用'
}

function classificationSourceLabel(source) {
  if (!source) return '資料不足'
  return CLASSIFICATION_SOURCE_LABELS[source] || source
}

function assetProfileOverrideSummary(profile) {
  if (!profile) return null
  const overridden = []
  if (profile.assetClassSource === 'OVERRIDE') overridden.push(`資產類別=${radarAssetClassLabel(profile)}`)
  if (profile.stockStyleSource === 'OVERRIDE') overridden.push(`股票風格=${radarStockStyleLabel(profile.stockStyle)}`)
  if (profile.bondTermSource === 'OVERRIDE') overridden.push(`債券期別=${radarBondTermLabel(profile.bondTerm)}`)
  return overridden.length ? overridden.join('；') : null
}

function settingsClassificationOverrideSummary(classification) {
  if (!classification) return null
  const overridden = []
  if (classification.assetClassSource === 'OVERRIDE') {
    overridden.push(`資產類別=${settingsAssetClassLabel(classification)}`)
  }
  if (classification.stockStyleSource === 'OVERRIDE') {
    overridden.push(`股票風格=${radarStockStyleLabel(classification.effectiveStockStyle)}`)
  }
  if (classification.bondTermSource === 'OVERRIDE') {
    overridden.push(`債券期別=${radarBondTermLabel(classification.effectiveBondTerm)}`)
  }
  return overridden.length ? overridden.join('；') : null
}

// Task408：D/W 只用 profile ID 的 immutable timeframe segment 分組；不推導或改寫 profile 值。
function technicalProfileGroups(resolution) {
  if (!Array.isArray(resolution?.profiles)) return []
  const groups = { daily: [], weekly: [], other: [] }
  for (const profile of resolution.profiles) {
    const id = String(profile?.profileId || '').toLowerCase()
    if (id.includes('_d_')) groups.daily.push(profile)
    else if (id.includes('_w_')) groups.weekly.push(profile)
    else groups.other.push(profile)
  }
  return [
    { key: 'daily', label: '日線（D）', profiles: groups.daily },
    { key: 'weekly', label: '週線（W）', profiles: groups.weekly },
    { key: 'other', label: '未識別時間框架', profiles: groups.other }
  ].filter(group => group.profiles.length)
}

function technicalResolutionLabel(resolution) {
  if (!resolution) return 'LEGACY_LOCAL_V0'
  return technicalSourceLabel(resolution.source)
}

function technicalResolutionType(resolution) {
  if (!resolution) return 'info'
  if (resolution.source === 'FUBON_SDK') return 'success'
  if (resolution.source === 'LOCAL_CALCULATED') return 'warning'
  return 'info'
}

function technicalSourceLabel(source) {
  return ({
    FUBON_SDK: '富邦 API',
    LOCAL_CALCULATED: '本地計算'
  })[source] || (source || '—')
}

function technicalBindingLabel(binding) {
  return ({
    BOUND_CONTEXT: '綁定本次決策 context',
    UNBOUND_FUBON_SOURCE: '未綁定 context（不可採用）'
  })[binding] || (binding || '—')
}

function technicalProfileType(status) {
  if (status === 'AVAILABLE') return 'success'
  if (status === 'NO_DATA' || status === 'UNAVAILABLE') return 'warning'
  if (status === 'SCHEMA_INVALID') return 'danger'
  return 'info'
}

function technicalEligibilityLabel(eligibility) {
  return ({
    APPLIED: '已納入 V18',
    AVAILABLE_NOT_APPLIED: '可用但未納入 V18',
    DETAIL_ONLY: '僅 detail 呈現',
    LOCAL: '本地計算'
  })[eligibility] || (eligibility || '—')
}

function technicalOriginLabel(origin) {
  return ({
    FUBON_SDK: '富邦 API 直接 overlay',
    LOCAL: '本地計算',
    DERIVED_FROM_FUBON: '由富邦值衍生',
    DETAIL_ONLY: '僅 detail 呈現',
    AVAILABLE_NOT_APPLIED: '可用但未納入 V18'
  })[origin] || (origin || '—')
}

function technicalAgeLabel(ageSeconds) {
  if (ageSeconds == null || Number.isNaN(Number(ageSeconds))) return '—'
  return `${Number(ageSeconds)} 秒`
}

function formatTechnicalMap(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '—'
  const entries = Object.entries(value)
  if (!entries.length) return '—'
  return entries
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => `${key}=${technicalValueLabel(item)}`)
    .join(' · ')
}

function technicalValueLabel(value) {
  if (value == null) return 'null'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value)
  } catch {
    return '—'
  }
}

function roeBasisLabel(fallback) {
  if (fallback === true) return '期初權益缺漏，採最新期末權益作分母；此 fallback 的證據權重已下修。'
  if (fallback === false) return '採同一來源的期初與期末權益平均作分母。'
  return '舊快照未揭露近似 ROE 的權益分母基礎。'
}

function treasurySourceEntries(context) {
  if (!context?.sourceManifest || typeof context.sourceManifest !== 'object') return []
  return Object.entries(context.sourceManifest)
    .filter(([, url]) => typeof url === 'string' && url.length > 0)
    .map(([tenor, url]) => ({ tenor, url }))
}

// 只查找後端 evidenceGroups 已解析的 component；不在畫面推導利率狀態或門檻。
function bondRateEvidence(row) {
  return row?.evidence?.evidenceGroups?.ASSET_SPECIFIC?.components?.find(c => c.name === 'bond_rate') || null
}

function evidenceGroups(row) {
  const groups = row?.evidence?.evidenceGroups
  if (!groups || typeof groups !== 'object') return []
  return Object.values(groups).filter(Boolean).sort((a, b) => String(a.group || '').localeCompare(String(b.group || '')))
}

function marketFeatureEntries(row) {
  const features = row?.evidence?.marketFeatures
  if (!features || typeof features !== 'object') return []
  return Object.entries(features).map(([key, value]) => ({ key, ...(value || {}) }))
    .sort((a, b) => a.key.localeCompare(b.key))
}

function fmtTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

// Task 357／357.6b：nextDistributionKnownAt 是 ISO-8601 date-time（API／匯出維持不變，
// 精確時點的可稽核性見 Requirement 86）；僅此處前端呈現截成 yyyy-MM-dd。取字串前 10 碼
// 而非 new Date() 轉换再格式化，避免瀏覽器本地時區把日界線位移一天。
function fmtDateOnly(value) {
  if (!value) return '—'
  const s = String(value).substring(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(s) ? s : '—'
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

function timingType(state) {
  if (state === 'EXTREME_OVERBOUGHT' || state === 'EXTREME_OVERSOLD') return 'danger'
  if (state === 'OVERBOUGHT' || state === 'OVERSOLD') return 'warning'
  return 'info'
}
function timingEffect(state) {
  return state === 'EXTREME_OVERBOUGHT' || state === 'EXTREME_OVERSOLD' ? 'dark' : 'plain'
}
// 文案只陳述「當前位置與狀態」，不得出現預測隔日漲跌的語句（Task 264）。
function timingHint(row) {
  switch (row.timingState) {
    case 'EXTREME_OVERBOUGHT':
      return 'KD 已達過熱且明顯偏離季線；只有 KD 死叉、MACD 轉弱、下跌爆量三類證據至少兩項成立，才確認獲利了結。'
    case 'OVERBOUGHT':
      return '短線偏貴。其中 KD 過熱與 ETF 溢價過高會關閉買進閘門；單純的季線乖離偏高只反映在分數，不關閉閘門。'
    case 'OVERSOLD':
      return '短線偏便宜（KD 偏低或明顯低於季線）。'
    case 'EXTREME_OVERSOLD':
      return '已深度超跌，此位置不建議追殺出場；即使長期結構偏弱，V11 仍保留低檔保護並另列風險。'
    default:
      return ''
  }
}
const trialBuyHint = '長線結構明確向上（年線之上且乖離足夠）、短線深度超賣並剛出現低檔黃金交叉、'
  + '且最近完成日已止跌。本質是接刀——僅適合小額分批、非全額進場；'
  + '長線判斷失準時虧損可能持續擴大。'

function actionType(action) {
  // 分批試單刻意不與順勢買進共用配色：前者是接刀、後者是順勢，風險結構不同。
  if (action === 'TRIAL_BUY') return 'warning'
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

// ===== 匯出 Excel（Requirement 48）=====
function openExport() {
  exportDialog.range = [
    dayjs().startOf('day').format('YYYY-MM-DDTHH:mm:ss'),
    dayjs().format('YYYY-MM-DDTHH:mm:ss')
  ]
  exportDialog.visible = true
}

async function onExport() {
  const [start, end] = exportDialog.range ?? []
  if (!start || !end) {
    ElMessage.warning('請選擇匯出時間區間')
    return
  }
  exporting.value = true
  try {
    // Task 283：改回完整 response——除了下載用的 blob，還要讀 X-Dir-Export 判斷落檔結果
    const res = await bffApi.tradingRadar.exportExcel(start, end)
    const filename = `交易雷達_${start.replace(/[^0-9]/g, '').slice(0, 12)}_${end.replace(/[^0-9]/g, '').slice(0, 12)}.xlsx`
    const saved = await saveBlob(res.data, filename)
    if (saved) {
      // 落檔失敗不得顯示成功——UI 說謊正是 Task 282 修掉的那個病
      const outcome = res.headers?.['x-dir-export']   // axios 的 header key 一律小寫
      if (outcome === 'ok') {
        ElMessage.success('匯出完成，並已同時寫入伺服器輸出目錄（JSON ＋ Excel）')
      } else if (outcome === 'failed') {
        ElMessage.warning('已下載 Excel；寫入伺服器輸出目錄失敗，請查後端 log')
      } else {
        ElMessage.success('匯出完成（該區間查無快照，未落檔）')
      }
      exportDialog.visible = false
    }
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    exporting.value = false
  }
}

/**
 * 優先開啟系統「另存新檔」對話框讓使用者選目錄；不支援時退回一般下載。
 * 回傳 false 代表使用者主動取消（不顯示成功訊息）。
 */
async function saveBlob(blob, filename) {
  if (canPickDirectory) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{
          description: 'Excel 活頁簿',
          accept: { 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'] }
        }]
      })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return true
    } catch (e) {
      if (e?.name === 'AbortError') return false   // 使用者按取消
    }
  }
  downloadBlob(blob, filename)
  return true
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ===== 排程自動匯出到伺服器目錄（Requirement 48 追加 / Task 231）=====

const pad2 = (n) => String(n ?? 0).padStart(2, '0')
const toRows = (times) => (times || []).map(t => ({
  time: `${pad2(t.runHour)}:${pad2(t.runMinute)}`,
  enabled: t.enabled !== false
}))

async function loadExportSchedule() {
  // 兩個面板並行取得，單一失敗不影響另一個
  const [timesRes, settingRes] = await Promise.allSettled([
    bffApi.tradingRadar.getExportTimes(),
    bffApi.tradingRadar.getExportSetting()
  ])
  if (timesRes.status === 'fulfilled') exportTimes.value = toRows(timesRes.value)
  if (settingRes.status === 'fulfilled' && settingRes.value) Object.assign(exportSetting, settingRes.value)
}

function addExportTime() {
  exportTimes.value.push({ time: '08:30', enabled: true })
}

function removeExportTime(idx) {
  exportTimes.value.splice(idx, 1)
}

async function saveExportTimes() {
  const times = []
  const seen = new Set()
  for (const row of exportTimes.value) {
    if (!row.time) {
      ElMessage.warning('請填寫所有時間點')
      return
    }
    if (seen.has(row.time)) {
      ElMessage.warning(`執行時間重複：${row.time}`)
      return
    }
    seen.add(row.time)
    const [h, m] = row.time.split(':').map(Number)
    times.push({ runHour: h, runMinute: m, enabled: row.enabled !== false })
  }
  savingTimes.value = true
  try {
    exportTimes.value = toRows(await bffApi.tradingRadar.saveExportTimes(times))
    ElMessage.success(times.length ? '已儲存執行時間設定' : '已清空時間點，不再自動匯出')
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    savingTimes.value = false
  }
}

async function saveExportSetting() {
  // 前後端都擋：開了同步卻沒選資料夾，後端也會回 400
  if (exportSetting.gdriveEnabled && !(exportSetting.gdriveSubpath || '').trim()) {
    ElMessage.warning('已開啟 Google Drive 同步時，必須選擇 Drive 目標資料夾')
    return
  }
  savingDir.value = true
  try {
    // 送整包而非裸字串：漏送 gdrive* 會讓 Drive 設定存不進去（後端把 null 視為「不變更」）
    // 本頁是整包 Object.assign 回狀態，故先把 gdriveSelfCheckWarning 拆出來：它是當次自檢結果、
    // 不是設定值，併進 exportSetting 會一直黏在頁面狀態上，重新讀設定也蓋不掉（讀取時後端不回這欄）
    // `?? {}`：原本是 Object.assign(exportSetting, 回應)，空回應時只是 no-op；改成解構後
    // null 回應會直接擲 TypeError，補上預設值維持原行為
    const { gdriveSelfCheckWarning, ...saved } = await bffApi.tradingRadar.saveExportSetting({
      outputSubpath: exportSetting.outputSubpath,
      gdriveEnabled: exportSetting.gdriveEnabled,
      gdriveSubpath: (exportSetting.gdriveSubpath || '').trim()
    }) ?? {}
    Object.assign(exportSetting, saved)
    ElMessage.success('已儲存輸出資料夾')
    // 剛把 Drive 同步打開時後端會附一則自檢警告；正常時為 null，不顯示（Task 247.3.5）
    showGdriveSelfCheckWarning(gdriveSelfCheckWarning)
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    savingDir.value = false
  }
}

async function runExportNow() {
  runningNow.value = true
  try {
    const res = await bffApi.tradingRadar.runExportNow()
    // 「當日尚無快照」是業務語意上的「沒東西可匯出」，與產檔失敗不同，故仍單獨處理。
    // 判準必須比對訊息內容而非「path 為空」：兩份 render 都失敗時後端一樣回 message="匯出完成"，
    // 用 path 判會把「產檔壞了」講成「今天本來就沒東西」，使用者不會來報修。
    // 用 includes 而非 ===：另有 NO_SNAPSHOT_STATUS + "（重算失敗且當日無既有快照）" 的降級版本。
    if (res?.message?.includes('當日尚無快照')) {
      ElMessage.warning(res.message)
    } else {
      // path 依契約一律指 xlsx、jsonPath 指 json（Requirement 55 / Task 282）。
      // 不再顯示 KB：兩份大小差很多，只印一個會誤導；落點才是 run-now 要驗證的東西。
      showDualExportResult({ jsonPath: res?.jsonPath, xlsxPath: res?.path, gdriveStatus: res?.gdriveStatus })
    }
    loadExportSchedule().catch(() => {})   // 刷新上次執行資訊
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    runningNow.value = false
  }
}

function openDirPicker(mode = 'local') {
  dirPicker.mode = mode
  dirPicker.picked = (mode === 'gdrive' ? exportSetting.gdriveSubpath : exportSetting.outputSubpath) || ''
  dirPicker.newSub = ''
  dirPicker.baseDir = ''         // 兩種 mode 的基底不同，重開時一律重新取
  dirPicker.error = ''
  dirPicker.treeKey++            // 強制 el-tree 重新懶載入 root
  dirPicker.visible = true
}

// el-tree 懶載入：level 0 以家目錄為單一 root；其餘列該節點子目錄
async function loadDirNode(node, resolve) {
  const browse = dirPicker.mode === 'gdrive'
    ? bffApi.tradingRadar.browseGdriveExportDir
    : bffApi.tradingRadar.browseExportDir
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
  if (dirPicker.mode === 'gdrive') exportSetting.gdriveSubpath = p
  else exportSetting.outputSubpath = p
  dirPicker.visible = false
}

// ===== 匯出到 blog（Requirement 102 / Task 366）=====

async function loadBlogStatus() {
  if (!auth.isConfiguredAdmin) return
  try {
    const res = await bffApi.tradingRadar.getBlogStatus()
    if (res) Object.assign(blogStatus, res)
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  }
}

// 頁首〔匯出到 blog〕＝立即發布/更新一次，屬「發布公開內容」，每次手動觸發都要先跳確認對話框
async function onPublishBlog() {
  let status
  try {
    status = await bffApi.tradingRadar.getBlogStatus()
  } catch {
    return
  }
  if (!status?.connected) {
    ElMessage.warning('尚未連接 Blogger 帳號，請先於下方設定卡完成連接')
    document.getElementById('blog-publish-card')?.scrollIntoView({ behavior: 'smooth' })
    return
  }
  try {
    await ElMessageBox.confirm(
      '即將把交易雷達目前結果公開發布/更新到 https://myrader.blogspot.com/，任何人皆可瀏覽，內容含個股代號、三軌分數與加減碼建議，確定要發布嗎？',
      '公開發布確認',
      { confirmButtonText: '確定發布', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return   // 使用者取消，不送出任何請求、不顯示錯誤訊息
  }
  publishingBlog.value = true
  try {
    const res = await bffApi.tradingRadar.publishBlog()
    ElMessage({
      type: 'success',
      dangerouslyUseHTMLString: true,
      message: res?.postUrl
        ? `發布成功：<a href="${res.postUrl}" target="_blank" rel="noopener">${res.postUrl}</a>`
        : '發布成功'
    })
    loadBlogStatus().catch(() => {})
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    publishingBlog.value = false
  }
}

async function onConnectBlog() {
  connectingBlog.value = true
  try {
    const res = await bffApi.tradingRadar.getBlogAuthorizeUrl()
    if (res?.url) window.location.href = res.url
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    connectingBlog.value = false
  }
}

async function onDisconnectBlog() {
  try {
    await ElMessageBox.confirm(
      '確定要中斷 Blogger 帳號連接嗎？中斷後「同步發布到 blog」會需要重新連接才能再次發布。',
      '確認中斷連接',
      { type: 'warning' }
    )
  } catch {
    return
  }
  disconnectingBlog.value = true
  try {
    await bffApi.tradingRadar.disconnectBlog()
    ElMessage.success('已中斷 Blogger 帳號連接')
    await loadBlogStatus()
  } catch {
    // 錯誤訊息由 axios 攔截器統一處理
  } finally {
    disconnectingBlog.value = false
  }
}

async function onToggleBlogEnabled(enabled) {
  savingBlogEnabled.value = true
  try {
    const res = await bffApi.tradingRadar.setBlogEnabled(enabled)
    if (res) Object.assign(blogStatus, res)
  } catch {
    blogStatus.blogEnabled = !enabled   // 失敗要復原開關，不能讓畫面顯示與後端實際狀態不一致
  } finally {
    savingBlogEnabled.value = false
  }
}

// 連接／發布完成後由後端 302 導回本頁並帶 blogOauth／reason query；顯示一次訊息後清掉，
// 避免使用者重新整理頁面時重複跳出
function handleBlogOauthRedirect() {
  const { blogOauth, reason } = route.query
  if (!blogOauth) return
  if (blogOauth === 'connected') {
    ElMessage.success('已成功連接 Blogger 帳號')
  } else if (blogOauth === 'error') {
    ElMessage.error('連接 Blogger 帳號失敗：' + (reason || '未知原因'))
  }
  router.replace({ query: { ...route.query, blogOauth: undefined, reason: undefined } })
}

onMounted(() => {
  load().catch(() => {}).finally(openPriceStream)
  loadExportSchedule().catch(() => {})
  loadBlogStatus().catch(() => {})
  handleBlogOauthRedirect()
})

onUnmounted(() => {
  disposed = true
  if (priceStream) {
    priceStream.close()
    priceStream = null
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
.header-actions { display: flex; gap: 8px; }
.dialog-note { color: #64748b; font-size: 13px; line-height: 1.6; }
.local-rule-alert { margin-bottom: 16px; }
.market-card { border-top: 4px solid #f59e0b; }
.market-card.regime-risk-on { border-top-color: #dc2626; }
.market-card.regime-risk-off { border-top-color: #16a34a; }
.market-card.regime-neutral { border-top-color: #64748b; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }

/* 排程自動匯出設定卡（Requirement 48 追加 / Task 231） */
.sched-card { margin-top: 16px; }
.sched-card .card-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.sched-card .card-sub { font-size: 13px; color: #94a3b8; font-weight: 400; }
.sched-note { margin-bottom: 14px; }
.sched-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.sched-actions { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.dir-label { font-size: 13px; color: #475569; white-space: nowrap; }
.dir-hint { margin-top: 10px; }
.dir-hint code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-picker-path { font-size: 13px; color: #475569; margin-bottom: 10px; }
.dir-picker-path code { background: #f1f5f9; color: #0f172a; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
.dir-tree { max-height: 340px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px; }
.dir-new-sub { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.dir-new-sub .dns-label { font-size: 13px; color: #475569; white-space: nowrap; }
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
.public-info-card { margin-top: 16px; }
.info-list { display: flex; flex-direction: column; gap: 10px; }
.info-item { display: flex; flex-direction: column; gap: 3px; padding-bottom: 9px; border-bottom: 1px solid #e2e8f0; }
.info-item a { color: #1d4ed8; font-size: 13px; line-height: 1.5; text-decoration: none; }
.info-item a:hover { text-decoration: underline; }
.info-item small { color: #64748b; }
.stocks-card { margin-top: 16px; }
.holding-period-focus { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: -2px 0 14px; padding: 12px 14px; border: 1px solid #bfdbfe; border-radius: 9px; background: #eff6ff; }
.holding-period-title { color: #1e3a8a; font-size: 13px; font-weight: 700; }
.holding-period-note { margin-top: 3px; color: #475569; font-size: 12px; }
.us-market-note { margin-bottom: 14px; }
.stock-code { font-weight: 750; color: #0f172a; }
.stock-name { margin-top: 2px; color: #64748b; font-size: 12px; }
.stock-meta { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.industry-inline { margin-top: 5px; color: #64748b; font-size: 11px; line-height: 1.45; }
.industry-inline span { display: block; color: #0f766e; font-weight: 700; }
.price-value { font-weight: 700; color: #0f172a; }
.score-inline { margin-top: 5px; font-size: 12px; font-weight: 700; }
.ma-summary { display: inline-flex; align-items: center; white-space: nowrap; }
.slash { color: #cbd5e1; padding: 0 2px; }
/* 過熱＝動作已降級（紅），偏熱＝僅提醒、動作未受影響（橘）；兩者必須可區分（Task 232）。 */
.kd-overheated { color: #f56c6c; font-weight: 600; }
.kd-elevated { color: #e6a23c; }
.kd-heat-tag { margin-left: 5px; }
.expand-panel { padding: 8px 28px 18px 56px; background: #f8fafc; }
.confirm-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; }
.confirm-item { border: 1px solid #e2e8f0; border-radius: 8px; background: white; padding: 12px; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.confirm-item span, .confirm-item small { color: #64748b; font-size: 12px; }
.action-decision-card { border-left: 4px solid #cbd5e1; }
.action-decision-card-focused { border-color: #60a5fa; border-left-color: #2563eb; background: #eff6ff; box-shadow: 0 0 0 1px #93c5fd; }
.completed-candle-disclosure { margin-top: 10px; color: #475569; font-size: 12px; line-height: 1.6; }
.action-gate-panel { margin-top: 18px; padding: 14px 16px; border: 1px solid #f59e0b; border-radius: 9px; background: #fffbeb; }
.action-gate-title { color: #92400e; font-size: 14px; font-weight: 700; }
.action-gate-note { margin-top: 4px; color: #78350f; font-size: 12px; line-height: 1.55; }
.action-gate-panel .reason-list { margin: 8px 0 0; }
/* Task 357／357.7b：下一配息四個日期（除息／除權／發放股息／發放股權）各自獨立標籤，
   不得擠成一行——2 欄 × 2 列，小螢幕（見下方 @media）再降為單欄，避免標籤與日期黏在一起難以分辨是哪個。 */
.dividend-confirm-item { width: 100%; grid-column: 1 / -1; }
.dividend-date-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 12px; width: 100%; margin: 2px 0; }
.dividend-date-cell { display: flex; flex-direction: column; gap: 2px; padding: 6px 8px; border-radius: 6px; background: #f8fafc; border: 1px solid #e2e8f0; }
.dividend-date-cell small { color: #64748b; font-size: 11px; }
.dividend-date-cell strong { color: #0f172a; font-size: 13px; }
.fundamental-panel { margin-top: 18px; border: 1px solid #cbd5e1; border-radius: 9px; background: #fff; padding: 14px 16px; }
.technical-resolution-panel { border-color: #c7d2fe; background: #f8fafc; }
.technical-resolution-meta { display: grid; grid-template-columns: repeat(3, minmax(180px, 1fr)); gap: 9px; }
.technical-resolution-meta-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; border: 1px solid #dbeafe; border-radius: 7px; background: #fff; padding: 9px 10px; }
.technical-resolution-meta-item > span, .technical-resolution-meta-item small { color: #64748b; font-size: 11px; line-height: 1.45; }
.technical-resolution-meta-item strong { color: #0f172a; font-size: 12px; overflow-wrap: anywhere; }
.technical-profile-groups { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; }
.technical-profile-group { border-top: 1px solid #dbeafe; padding-top: 11px; }
.technical-profile-group-title { color: #334155; font-size: 12px; font-weight: 700; }
.technical-profile-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 9px; margin-top: 8px; }
.technical-profile-card { display: flex; flex-direction: column; gap: 4px; min-width: 0; border: 1px solid #dbeafe; border-radius: 7px; background: #fff; padding: 9px 10px; }
.technical-profile-card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.technical-profile-card-head strong { color: #0f172a; font-size: 12px; overflow-wrap: anywhere; }
.technical-profile-card small { color: #64748b; font-size: 11px; line-height: 1.45; overflow-wrap: anywhere; }
.technical-provenance-panel { margin-top: 14px; border-top: 1px solid #dbeafe; padding-top: 11px; }
.technical-provenance-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 8px; margin: 8px 0 0; padding: 0; list-style: none; }
.technical-provenance-list li { display: flex; flex-direction: column; gap: 3px; border: 1px solid #dbeafe; border-radius: 7px; background: #fff; padding: 8px 9px; }
.technical-provenance-list strong { color: #0f172a; font-size: 12px; overflow-wrap: anywhere; }
.technical-provenance-list span, .technical-provenance-list small { color: #64748b; font-size: 11px; line-height: 1.45; overflow-wrap: anywhere; }
.evidence-detail-panel { margin-top: 18px; border: 1px solid #cbd5e1; border-radius: 9px; background: #fff; padding: 14px 16px; }
.evidence-groups-panel { margin-top: 16px; border-top: 1px solid #e2e8f0; padding-top: 12px; }
.evidence-group-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 10px; margin-top: 9px; }
.evidence-group-item { border: 1px solid #e2e8f0; border-radius: 7px; padding: 9px 10px; background: #f8fafc; }
.evidence-group-head { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.evidence-group-head span { color: #475569; white-space: nowrap; }
.evidence-group-item > small { display: block; color: #64748b; margin-top: 4px; }
.evidence-component-list { margin: 7px 0 0; padding-left: 16px; color: #334155; font-size: 12px; }
.evidence-component-list li { margin: 4px 0; }
.evidence-component-list li span, .evidence-component-list li small { display: block; }
.evidence-component-list li small { color: #64748b; line-height: 1.45; }
.market-feature-panel { background: #f1f5f9; }
.fundamental-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.fundamental-title { color: #0f172a; font-size: 14px; font-weight: 750; }
.fundamental-grid { display: grid; grid-template-columns: repeat(5, minmax(145px, 1fr)); gap: 10px; }
.valuation-component-grid { display: grid; grid-template-columns: repeat(3, minmax(180px, 1fr)); gap: 10px; margin-top: 10px; }
.fundamental-item { border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; padding: 11px; display: flex; flex-direction: column; gap: 5px; }
.fundamental-item > span, .fundamental-item small { color: #64748b; font-size: 11px; line-height: 1.45; }
.fundamental-item strong { color: #0f172a; font-size: 14px; }
.valuation-component-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #64748b; font-size: 11px; }
.source-links { display: flex; flex-wrap: wrap; gap: 8px; }
.source-links a { color: #1d4ed8; font-size: 11px; text-decoration: none; }
.source-links a:hover { text-decoration: underline; }
.public-evidence-row { margin-top: 14px; }
.info-list.compact { max-height: 180px; overflow: auto; }
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
  .fundamental-grid, .valuation-component-grid, .technical-resolution-meta { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .header-row, .card-head { align-items: flex-start; flex-direction: column; }
  .market-metrics, .confirm-grid, .fundamental-grid, .valuation-component-grid, .technical-resolution-meta { grid-template-columns: 1fr; }
  .expand-panel { padding-left: 16px; padding-right: 16px; }
  .state-options { grid-template-columns: 1fr; }
}
</style>
