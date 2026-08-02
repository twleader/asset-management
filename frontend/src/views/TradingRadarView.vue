<template>
  <div v-loading="loading" element-loading-text="讀取本地行情與技術指標…">
    <div class="header-row">
      <div>
        <div class="page-heading">今日交易雷達</div>
        <div class="page-sub">依大盤、MA20／60／240、KD 與連續兩日確認產生規則式決策；不呼叫 AI API</div>
      </div>
      <div class="header-actions">
        <el-button :icon="Download" @click="openExport">匯出 Excel</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="manualRefresh">重新整理</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="local-rule-alert"
      title="純本地規則運算"
      description="判斷全由本地規則產生，不會送出 Claude、OpenAI 或其他 AI API 請求。按下「重新整理」會先回補一次台股行情再重算；頁面自動更新與其餘操作只讀取既有 PostgreSQL 與 Redis 資料；排程產檔則會另外觸發一次台股行情回補。每次結果快照會寫入 Redis 供匯出。"
    />

    <el-card shadow="never" class="market-card" :class="marketClass">
      <template #header>
        <div class="card-head">
          <div>
            <span class="section-title">台股大盤風險</span>
            <el-tag size="small" effect="plain" type="info" class="rule-tag">{{ radar.ruleVersion || 'TW_RULES_V9' }}</el-tag>
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
          <div class="metric"><span class="metric-label">週線 MA5</span><strong>{{ fmtNumber(market.weeklyMa, 2) }}</strong></div>
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
        @row-dblclick="onStockDblClick"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-panel">
              <div class="confirm-grid">
                <div class="confirm-item">
                  <span>週線 MA5</span><strong>{{ fmtNumber(row.weeklyMa, 2) }}</strong>
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
                  <span>折溢價</span>
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
                <span v-if="row.distributionAdjusted">　·　技術價基：還原權息／分割</span>
              </div>
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
        <el-table-column label="規則建議" min-width="150" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.action === 'TRIAL_BUY'" placement="top" :content="trialBuyHint">
              <el-tag :type="actionType(row.action)" effect="dark">{{ row.actionLabel }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="actionType(row.action)" effect="dark">{{ row.actionLabel }}</el-tag>
          </template>
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
            單一 Excel 檔，三張工作表：<b>快照索引／大盤總覽／個股決策</b>；
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
import { Bell, Download, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { bffApi } from '@/api'
import { showGdriveSelfCheckWarning } from '@/utils/gdriveSelfCheck'
import { showDualExportResult } from '@/utils/dualExportMessage'
import { useAuthStore } from '@/stores/authStore'
import { useRouter } from 'vue-router'
import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'

const router = useRouter()
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
const radar = ref({ market: {}, stocks: [], skippedNonTwStocks: 0, ruleVersion: 'TW_RULES_V9' })
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

// 純讀重算。初次載入與 SSE 背景重算都走這裡；不觸發任何外部行情抓取。
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

// Task 249：outcome → 提示文案。不得宣稱做了沒做的事。
const REFRESH_MESSAGES = {
  FETCHED: { type: 'success', text: '已重新抓取即時報價並重算' },
  CLOSED_SYNCED: { type: 'success', text: '台股目前休市，已同步至最新收盤價並重算' },
  SKIPPED_PENDING_CLOSE: { type: 'info', text: '今日收盤價尚未落檔，已保留最新成交價並重算' },
  COOLDOWN: { type: 'info', text: '30 秒內剛更新過，已直接重算' },
  BUSY: { type: 'info', text: '行情更新進行中，已以現有報價重算' },
  TIMEOUT: { type: 'warning', text: '行情抓取未完成，已以現有報價重算' },
  FAILED: { type: 'warning', text: '行情抓取未完成，已以現有報價重算' }
}

/**
 * 手動「重新整理」：先同步回補台股行情再重算（Task 249）。
 * 只有按鈕走這支；scheduleRecalculation / recalculateRadar 一律用上面的 load()，
 * 否則抓取 → 寫 Redis → price-update → 再抓取，會變成自我餵食迴圈。
 */
async function manualRefresh() {
  refreshing.value = true
  try {
    const resp = await bffApi.tradingRadar.refresh()
    if (disposed) return
    if (resp?.radar) radar.value = resp.radar
    const hint = REFRESH_MESSAGES[resp?.priceRefresh?.outcome] || REFRESH_MESSAGES.TIMEOUT
    ElMessage({ type: hint.type, message: hint.text })
  } finally {
    refreshing.value = false
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
      return '短線 KD 已達過熱且明顯偏離季線。若同時出現 KD 高檔死亡交叉，本日會轉為減碼候選。'
    case 'OVERBOUGHT':
      return '短線偏貴。其中 KD 過熱與 ETF 溢價過高會關閉買進閘門；單純的季線乖離偏高只反映在分數，不關閉閘門。'
    case 'OVERSOLD':
      return '短線偏便宜（KD 偏低或明顯低於季線）。'
    case 'EXTREME_OVERSOLD':
      return '已深度超跌，此位置不建議追殺出場；但年線兩日跌破且位於 52 週最低段者不套用此保護。'
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

onMounted(() => {
  load(false).catch(() => {}).finally(openPriceStream)
  loadExportSchedule().catch(() => {})
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
.stocks-card { margin-top: 16px; }
.stock-code { font-weight: 750; color: #0f172a; }
.stock-name { margin-top: 2px; color: #64748b; font-size: 12px; }
.stock-meta { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.price-value { font-weight: 700; color: #0f172a; }
.slash { color: #cbd5e1; padding: 0 2px; }
/* 過熱＝動作已降級（紅），偏熱＝僅提醒、動作未受影響（橘）；兩者必須可區分（Task 232）。 */
.kd-overheated { color: #f56c6c; font-weight: 600; }
.kd-elevated { color: #e6a23c; }
.kd-heat-tag { margin-left: 5px; }
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
