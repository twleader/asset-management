<template>
  <div v-loading="loading" :element-loading-text="generating ? '配置建議產生中…' : '載入中…'">
    <!-- 頂列：標題 + （管理者）成本設定 -->
    <div class="header-row">
      <div>
        <span class="page-heading">資產配置建議</span>
        <span class="page-sub">填好你的理財條件，AI 會結合你目前持有的資產給出個人化的資產配置建議</span>
      </div>
      <div v-if="auth.isAdmin" class="header-actions">
        <span class="model-label">分析引擎</span>
        <el-select v-model="selectedEngine" size="default" style="width: 190px" :disabled="busy"
          title="切換分析引擎（下次產生生效）" @change="onEngineChange">
          <el-option v-for="en in availableEngines" :key="en.id" :label="en.label" :value="en.id" />
        </el-select>
        <span class="model-label">分析模型</span>
        <el-select v-model="selectedModel" size="default" style="width: 200px" :disabled="modelEffortDisabled"
          title="切換分析模型（下次產生生效）；本機引擎時停用（仍為 hybrid／llm 模式的有效設定）" @change="onModelChange">
          <el-option v-for="m in availableModels" :key="m.id" :label="m.label" :value="m.id" />
        </el-select>
        <span class="model-label">思考深度</span>
        <el-select v-model="selectedEffort" size="default" style="width: 160px" :disabled="modelEffortDisabled"
          title="切換思考深度（越低越省，下次產生生效）；本機引擎時停用（仍為 hybrid／llm 模式的有效設定）" @change="onEffortChange">
          <el-option v-for="e in availableEfforts" :key="e.id" :label="e.label" :value="e.id" />
        </el-select>
        <span class="model-label">市場搜尋</span>
        <el-select v-model="webSearchDisplay" size="default" style="width: 200px" :disabled="webSearchDisabled"
          title="切換 web 搜尋次數（0＝僅依個人資產與條件，下次產生生效）；本機引擎時停用，混合引擎強制不搜尋並顯示為 0" @change="onWebSearchChange">
          <el-option v-for="w in availableWebSearches" :key="w.value" :label="w.label" :value="w.value" />
        </el-select>
      </div>
    </div>

    <!-- 常駐說明（Task 339）：本機配置模板的定性，不論當前引擎為何皆顯示 -->
    <el-alert
      type="info" show-icon :closable="false" style="margin-bottom:16px"
      title="關於「本機配置模板」"
      description="本機配置模板（local／hybrid 檔位使用）依可忍受風險與距退休年數套用常見經驗法則比例，未經回測或個人情境驗證，不構成個人化投資建議；如需 AI 結合你的資產與市場脈絡產生完整建議，請切換為 llm 引擎。"
    />

    <!-- 條件設定表單 -->
    <el-card shadow="never" class="section-card">
      <template #header><span class="section-title">① 我的理財條件</span></template>
      <el-form label-width="120px" label-position="right" :disabled="busy">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="6">
            <el-form-item label="生日">
              <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD"
                format="YYYY年MM月DD日" placeholder="出生年月日" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="退休前年薪">
              <el-input-number v-model="form.preRetirementAnnualSalary" :min="0" :step="50000" controls-position="right"
                style="width: 100%" placeholder="今日幣值／年" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="退休前年支出">
              <el-input-number v-model="form.preRetirementAnnualExpense" :min="0" :step="50000" controls-position="right"
                style="width: 100%" placeholder="今日幣值／年（生活費）" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="退休日期">
              <el-date-picker v-model="form.retirementDate" type="date" value-format="YYYY-MM-DD"
                format="YYYY年MM月DD日" placeholder="預計退休日" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="24">
            <div class="field-note" style="margin-left: 0">退休前每年淨投入＝年薪 − 年支出（皆今日幣值、依通膨逐年膨脹），退休後薪水停止歸零。與退休後（收入勞保勞退、支出生活費）對稱。</div>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="24">
            <div v-if="ageHint" class="retire-derived">{{ ageHint }}</div>
            <div v-if="retirementHint" class="retire-derived" :class="{ 'is-warn': retirementHint.warn }">{{ retirementHint.text }}</div>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="理財目標">
              <el-select v-model="form.goals" multiple collapse-tags collapse-tags-tooltip
                placeholder="可複選" style="width: 100%">
                <el-option v-for="o in goalOptions" :key="o.id" :label="o.label" :value="o.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="獲利預期">
              <el-select v-model="form.expectedAnnualReturn" placeholder="年化報酬期望" style="width: 100%" clearable>
                <el-option v-for="o in returnOptions" :key="o.id" :label="o.label" :value="o.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="可忍受風險">
          <el-radio-group v-model="form.riskTolerance">
            <el-radio v-for="o in riskOptions" :key="o.id" :value="o.id" border>{{ o.label }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 退休後現金流（選填）：勞保年金月領、勞退一次領 -->
        <el-divider content-position="left"><span class="sub-divider">退休後現金流（選填）</span></el-divider>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="6">
            <el-form-item label="勞保月領">
              <el-input-number v-model="form.laborInsuranceMonthly" :min="0" :step="1000" controls-position="right"
                style="width: 100%" placeholder="每月金額" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="勞保起領日">
              <el-date-picker v-model="form.laborInsuranceStartDate" type="date" value-format="YYYY-MM-DD"
                format="YYYY年MM月DD日" placeholder="開始領取日" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="勞退一次領">
              <el-input-number v-model="form.laborPensionLumpSum" :min="0" :step="10000" controls-position="right"
                style="width: 100%" placeholder="一次領金額" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="6">
            <el-form-item label="勞退領取日">
              <el-date-picker v-model="form.laborPensionClaimDate" type="date" value-format="YYYY-MM-DD"
                format="YYYY年MM月DD日" placeholder="領取日" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="field-note">勞保、勞退請填「未來實際可領」金額（照勞保局試算填入即可，系統不再乘通膨）。</div>

        <!-- 退休現金流試算假設（選填）：長照前年生活費為必填才會試算；退休後分長照前/長照後兩階段；兩階段報酬率可自訂 -->
        <el-divider content-position="left"><span class="sub-divider">退休現金流試算假設</span></el-divider>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="長照前年生活費">
              <el-input-number v-model="form.retirementAnnualExpense" :min="0" :step="50000" controls-position="right"
                style="width: 100%" placeholder="今日幣值／年" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="長照後年生活費">
              <el-input-number v-model="form.longTermCareAnnualExpense" :min="0" :step="50000" controls-position="right"
                style="width: 100%" placeholder="今日幣值／年（通常較高）" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="長照起始年齡">
              <el-input-number v-model="form.longTermCareStartAge" :min="50" :max="100" :step="1" controls-position="right"
                style="width: 100%" placeholder="預設 80 歲" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8">
            <el-form-item label="累積期年報酬率">
              <el-input-number v-model="form.accumulationAnnualReturnRate" :min="0" :max="30" :step="0.5" :precision="1"
                controls-position="right" style="width: 100%" :placeholder="accumReturnPlaceholder" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="退休後年報酬率">
              <el-input-number v-model="form.retirementAnnualReturnRate" :min="0" :max="30" :step="0.5" :precision="1"
                controls-position="right" style="width: 100%" :placeholder="retireReturnPlaceholder" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="field-note">
          退休後分兩階段：<strong>長照前</strong>（一般退休生活）與<strong>長照後</strong>（照護期，年支出通常較高）。年生活費填「今日幣值／年」，系統依通膨逐年膨脹。長照後年生活費留空＝不分長照階段；長照起始年齡留空預設 80 歲。兩階段報酬率為<strong>試算假設</strong>（非預測）：留空則依「獲利預期」帶入（退休後預設較保守）。此區用於下方「退休現金流試算」。
        </div>

        <!-- 特定日期大筆花費（選填）：今日幣值，系統依通膨換算 -->
        <el-divider content-position="left"><span class="sub-divider">特定日期大筆花費（選填）</span></el-divider>
        <el-row :gutter="16" style="margin-bottom: 4px">
          <el-col :xs="24" :sm="8">
            <el-form-item label="假設年通膨率">
              <el-input-number v-model="form.assumedAnnualInflationRate" :min="0" :max="20" :step="0.5" :precision="1"
                controls-position="right" style="width: 100%" placeholder="%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="16">
            <div class="field-note" style="margin-top: 8px">
              大筆花費請填「今日幣值」，系統會依此通膨率換算成花費當日的未來金額再交給 AI。
            </div>
          </el-col>
        </el-row>
        <div class="expense-list">
          <div v-for="(ex, idx) in form.plannedExpenses" :key="idx" class="expense-row">
            <el-date-picker v-model="ex.expenseDate" type="date" value-format="YYYY-MM-DD" format="YYYY年MM月DD日"
              placeholder="花費日期" style="width: 160px" />
            <el-input v-model="ex.name" placeholder="用途（如：買車）" style="width: 170px" maxlength="100" />
            <el-input-number v-model="ex.amount" :min="0" :step="10000" controls-position="right"
              style="width: 160px" placeholder="今日幣值" />
            <span class="expense-hint">{{ expenseFutureHint(ex) }}</span>
            <el-button link type="danger" :icon="Delete" @click="removeExpense(idx)">刪除</el-button>
          </div>
          <el-button text :icon="Plus" @click="addExpense">新增一筆大筆花費</el-button>
        </div>

        <el-form-item style="margin-top: 14px">
          <el-button :loading="savingProfile" @click="saveProfile">儲存條件</el-button>
          <el-button type="primary" :icon="MagicStick" :loading="generating || isProcessing" @click="generate">
            {{ isProcessing ? '產生中…' : '產生建議' }}
          </el-button>
          <span class="form-hint">條件會被記住，下次進來免重填；「產生建議」會一併儲存目前條件。</span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 目前資產配置 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">② 我目前的資產配置</span>
          <span v-if="allocation.snapshotDate" class="head-meta">
            快照日 {{ allocation.snapshotDate }}　資產總額 {{ money(allocation.totalAssets) }} 元
          </span>
        </div>
      </template>
      <div v-if="allocationItems.length && Number(allocation.totalAssets) > 0" class="alloc-list">
        <div v-for="it in allocationItems" :key="it.assetClass" class="alloc-block">
          <div class="alloc-row" :class="{ clickable: hasSubItems(it) }"
            @click="hasSubItems(it) && toggleCurrentSub(it.assetClass)">
            <span class="alloc-name">
              <span v-if="hasSubItems(it)" class="expand-caret">{{ currentSubExpanded[it.assetClass] ? '▾' : '▸' }}</span>
              {{ it.assetClass }}
            </span>
            <div class="bar-wrap">
              <div class="bar cur" :style="{ width: barWidth(it.pct) }"></div>
            </div>
            <span class="alloc-val">{{ fmtPct(it.pct) }}<span class="alloc-amt">（{{ money(it.value) }} 元）</span></span>
          </div>
          <template v-if="hasSubItems(it) && currentSubExpanded[it.assetClass]">
            <div v-for="sub in it.subItems" :key="sub.subClass" class="alloc-row sub">
              <span class="alloc-name">{{ sub.subClass }}</span>
              <div class="bar-wrap">
                <div class="bar cur" :style="{ width: barWidth(sub.pct) }"></div>
              </div>
              <span class="alloc-val">{{ fmtPct(sub.pct) }}<span class="alloc-amt">（{{ money(sub.value) }} 元）</span></span>
            </div>
          </template>
        </div>
      </div>
      <el-empty v-else :image-size="70" description="尚無資產快照——請先於「總覽儀表板／管理資產」建立快照，建議會更貼合你的實際持有" />
    </el-card>

    <!-- 退休現金流試算（決定性逐年試算，非預測） -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">③ 退休現金流試算</span>
          <span class="head-meta">依你填的假設，把現有資產＋投入＋勞保勞退＋生活費逐年推到 100 歲</span>
        </div>
      </template>

      <el-alert v-if="projection && !projection.available" type="info" show-icon :closable="false"
        title="尚無法試算" :description="projection.unavailableReason || '請補齊生日、資產快照與退休後每月生活費。'" />
      <div v-else-if="projection && projection.available">
        <div class="proj-summary" :class="{ 'is-warn': !projection.lastsToEndAge }">
          <template v-if="projection.lastsToEndAge">
            ✅ 依此假設，資產可支應到 <strong>{{ projection.endAge }} 歲</strong>，屆時約剩
            <strong>{{ money(projection.endBalance) }} 元</strong>（未見缺口）。
          </template>
          <template v-else>
            ⚠️ 依此假設，資產預計在約 <strong>{{ projection.depletionAge }} 歲（{{ projection.depletionYear }} 年）</strong>
            出現資金缺口，退休提領需更保守。
          </template>
        </div>
        <div class="proj-assume">
          假設：累積期年報酬 {{ pctText(projection.assumptions.accumulationReturnPct) }}{{ projection.assumptions.accumReturnFromBand ? '（帶入）' : '（自訂）' }}、
          退休後年報酬 {{ pctText(projection.assumptions.retirementReturnPct) }}{{ projection.assumptions.retireReturnFromBand ? '（帶入）' : '（自訂）' }}、
          年通膨 {{ pctText(projection.assumptions.inflationPct) }}
          <template v-if="projection.assumptions.retirementAnnualExpense">
            、長照前年生活費（今日幣值）{{ money(projection.assumptions.retirementAnnualExpense) }} 元
          </template>
          <template v-if="projection.assumptions.longTermCareAnnualExpense">
            、長照後年生活費 {{ money(projection.assumptions.longTermCareAnnualExpense) }} 元（自 {{ projection.assumptions.longTermCareStartAge }} 歲起）
          </template>
          <span v-if="projection.retirementAge != null">
            ；預計退休約 {{ projection.retirementAge }} 歲<template v-if="projection.retirementStartBalance">、退休首年結餘約 {{ money(projection.retirementStartBalance) }} 元</template>
          </span>
        </div>
        <v-chart :option="projectionChartOption" style="height: 340px" autoresize />
        <div class="proj-note">此為依你自訂假設所做的決定性試算，非投資報酬預測；改上方假設並重新載入即會更新。</div>
      </div>
      <el-empty v-else :image-size="70" description="填好生日、退休日期與退休後每月生活費後即可試算" />
    </el-card>

    <!-- 建議結果 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">④ AI 資產配置建議</span>
          <span v-if="isOk" class="head-meta">
            由 {{ latest.model || 'Claude' }} 產生於 {{ formatTime(latest.createdAt) }}
          </span>
        </div>
      </template>

      <el-alert v-if="latest && latest.status === 'NOT_CONFIGURED'" type="warning" show-icon :closable="false"
        title="尚未設定 Anthropic API 金鑰"
        description="請於部署環境設定 ANTHROPIC_API_KEY 後再產生建議。" />
      <el-alert v-else-if="latest && latest.status === 'FAILED'" type="error" show-icon :closable="false"
        title="建議產生失敗" :description="latest.errorMessage || '請稍後再按一次「產生建議」。'" />
      <el-alert v-else-if="isProcessing" type="info" show-icon :closable="false"
        title="AI 產生配置建議中…"
        description="已送出，完成後畫面會自動更新（通常數十秒；含當前市場搜尋時可能久一點）。" />
      <el-empty v-else-if="!isOk" :image-size="80"
        description="尚無建議——填好上方條件後，按「產生建議」" />

      <div v-else>
        <div class="disclaimer-top">
          ⚠️ 本建議由 AI 依你提供的條件與資產產生，僅供參考，不構成投資建議；投資有風險，請自行評估。
        </div>

        <div v-if="latest.summary" class="summary">{{ latest.summary }}</div>
        <div v-if="latest.riskAssessment" class="risk-assess">
          <div class="block-title">現況與風險評估</div>
          <div class="context-text">{{ latest.riskAssessment }}</div>
        </div>

        <div class="block-title">建議目標配置</div>
        <div v-if="latest.targetAllocation && latest.targetAllocation.length" class="alloc-list target">
          <div v-for="(t, i) in latest.targetAllocation" :key="i" class="alloc-block">
            <div class="alloc-row" :class="{ clickable: visibleSubAllocations(t).length }"
              @click="visibleSubAllocations(t).length && toggleTargetSub(t.assetClass)">
              <span class="alloc-name">
                <span v-if="visibleSubAllocations(t).length" class="expand-caret">{{ targetSubExpanded[t.assetClass] ? '▾' : '▸' }}</span>
                {{ t.assetClass }}
              </span>
              <div class="bar-wrap">
                <div class="bar tgt" :style="{ width: barWidth(t.targetPct) }"></div>
              </div>
              <span class="alloc-val">{{ fmtPct(t.targetPct) }}</span>
            </div>
            <div v-if="t.targetAmount != null" class="alloc-amounts">
              目前約 {{ money(t.currentValue) }} 元 → 目標約 {{ money(t.targetAmount) }} 元
              <span v-if="t.deltaAmount != null" class="delta" :class="deltaClass(t.deltaAmount)">（{{ deltaText(t.deltaAmount) }}）</span>
            </div>
            <div v-if="t.rationale" class="alloc-rationale">{{ t.rationale }}</div>

            <template v-if="visibleSubAllocations(t).length && targetSubExpanded[t.assetClass]">
              <div v-for="sub in visibleSubAllocations(t)" :key="sub.subClass" class="alloc-sub-block">
                <div class="alloc-row sub">
                  <span class="alloc-name">{{ sub.subClass }}</span>
                  <div class="bar-wrap">
                    <div class="bar tgt" :style="{ width: barWidth(sub.targetPct) }"></div>
                  </div>
                  <span class="alloc-val">{{ fmtPct(sub.targetPct) }}</span>
                </div>
                <div v-if="sub.targetAmount != null" class="alloc-amounts sub">
                  目前約 {{ money(sub.currentValue) }} 元 → 目標約 {{ money(sub.targetAmount) }} 元
                  <span v-if="sub.deltaAmount != null" class="delta" :class="deltaClass(sub.deltaAmount)">（{{ deltaText(sub.deltaAmount) }}）</span>
                </div>
                <div v-if="sub.rationale" class="alloc-rationale sub">{{ sub.rationale }}</div>
              </div>
            </template>
          </div>
        </div>
        <div v-else class="muted">—</div>

        <template v-if="latest.rebalancePlan && latest.rebalancePlan.length">
          <div class="block-title">再平衡操作明細（估計新台幣金額）</div>
          <ul class="action-list rebalance-list">
            <!-- Task 344.23(2)：分組（assetClass）／分段（subClass）／排序全部由 BFF 的
                 latest.rebalanceGroups 預先算好，前端只負責 render（CLAUDE.md BFF 規範第 1 節）。 -->
            <template v-if="latest.rebalanceGroups && latest.rebalanceGroups.length">
              <li v-for="(g, gi) in latest.rebalanceGroups" :key="gi" class="reb-group">
                <!-- 組標題＝BFF 判定出的類別層級那一筆（判定用的字面值只存在於 BFF） -->
                <div v-if="g.header" class="reb-group-header">
                  <el-tag :type="rebalanceType(g.header.action)" size="small" effect="dark" class="prio-tag">{{ rebalanceLabel(g.header.action) }}</el-tag>
                  <span class="action-title">{{ g.header.holding || g.header.assetClass }}</span>
                  <span v-if="g.header.holding && g.header.assetClass" class="reb-class">（{{ g.header.assetClass }}）</span>
                  <span v-if="g.header.estimatedAmount != null" class="reb-amount" :class="rebalanceAmountClass(g.header.action)">約 {{ money(g.header.estimatedAmount) }} 元</span>
                  <div v-if="g.header.rationale" class="action-detail">{{ g.header.rationale }}</div>
                </div>
                <!-- 無小標段落（BFF 給 subClass: null，如存款群組明細）不印標題，照順序列出 -->
                <div v-for="(sec, si) in g.sections" :key="si" class="reb-sub-section">
                  <div v-if="sec.subClass" class="reb-sub-title">{{ sec.subClass }}</div>
                  <ul class="reb-detail-list">
                    <li v-for="(r, ri) in sec.rows" :key="ri" class="reb-detail-item">
                      <el-tag :type="rebalanceType(r.action)" size="small" effect="dark" class="prio-tag">{{ rebalanceLabel(r.action) }}</el-tag>
                      <span class="action-title">{{ r.holding || r.assetClass }}</span>
                      <span v-if="r.estimatedAmount != null" class="reb-amount" :class="rebalanceAmountClass(r.action)">約 {{ money(r.estimatedAmount) }} 元</span>
                      <div v-if="r.rationale" class="action-detail">{{ r.rationale }}</div>
                    </li>
                  </ul>
                </div>
              </li>
            </template>
            <!-- rebalanceGroups 缺漏或為空：llm 檔位、本任務落地前的舊建議，或 BFF 尚未升版。
                 退回既有扁平渲染，逐字維持現況、不重排 LLM 的輸出順序（344.23(1)(2) 向後相容） -->
            <template v-else>
              <li v-for="(r, i) in latest.rebalancePlan" :key="i">
                <el-tag :type="rebalanceType(r.action)" size="small" effect="dark" class="prio-tag">{{ rebalanceLabel(r.action) }}</el-tag>
                <span class="action-title">{{ r.holding || r.assetClass }}</span>
                <span v-if="r.holding && r.assetClass" class="reb-class">（{{ r.assetClass }}）</span>
                <span v-if="r.estimatedAmount != null" class="reb-amount" :class="rebalanceAmountClass(r.action)">約 {{ money(r.estimatedAmount) }} 元</span>
                <div v-if="r.rationale" class="action-detail">{{ r.rationale }}</div>
              </li>
            </template>
          </ul>
        </template>

        <div class="block-title">具體調整動作</div>
        <ul v-if="latest.actions && latest.actions.length" class="action-list">
          <li v-for="(a, i) in latest.actions" :key="i">
            <el-tag :type="priorityType(a.priority)" size="small" effect="dark" class="prio-tag">
              {{ priorityLabel(a.priority) }}
            </el-tag>
            <span class="action-title">{{ a.title }}</span>
            <div v-if="a.detail" class="action-detail">{{ a.detail }}</div>
          </li>
        </ul>
        <div v-else class="muted">—</div>

        <el-row :gutter="16" style="margin-top: 6px">
          <el-col :xs="24" :md="12" v-if="latest.warnings && latest.warnings.length">
            <div class="block-title">風險提醒</div>
            <ul class="factor-list">
              <li v-for="(w, i) in latest.warnings" :key="i">{{ w }}</li>
            </ul>
          </el-col>
          <el-col :xs="24" :md="12" v-if="latest.references && latest.references.length">
            <div class="block-title">參考來源</div>
            <ul class="news-list">
              <li v-for="(r, i) in latest.references" :key="i">
                <a v-if="safeUrl(r.url)" :href="safeUrl(r.url)" target="_blank" rel="noopener noreferrer">{{ r.title || r.url }}</a>
                <span v-else>{{ r.title }}</span>
              </li>
            </ul>
          </el-col>
        </el-row>
      </div>
    </el-card>

    <!-- 歷次建議 -->
    <el-card shadow="never" class="section-card" v-if="history.length">
      <template #header><span class="section-title">歷次建議</span></template>
      <el-table :data="history" size="small" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="hist-expand">
              <div class="hist-cond">
                條件：{{ row.age != null ? row.age + ' 歲' : '—' }}<span v-if="row.investmentHorizonYears != null">／投資年限 {{ row.investmentHorizonYears }} 年</span>／
                風險 {{ labelOf(riskOptions, row.riskTolerance) }}／獲利預期 {{ labelOf(returnOptions, row.expectedAnnualReturn) }}
                <span v-if="row.goals && row.goals.length">／目標 {{ row.goals.map(g => labelOf(goalOptions, g)).join('、') }}</span>
              </div>
              <div v-if="row.summary" class="hist-summary">{{ row.summary }}</div>
              <div v-if="row.targetAllocation && row.targetAllocation.length" class="hist-target">
                建議：{{ row.targetAllocation.map(t => `${t.assetClass} ${fmtPct(t.targetPct)}`).join('、') }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="產生時間" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="狀態" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OK' ? 'success' : 'info'" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="重點" show-overflow-tooltip>
          <template #default="{ row }">{{ row.status === 'OK' ? row.summary : (row.errorMessage || statusLabel(row.status)) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { MagicStick, Plus, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'
import { useAuthStore } from '@/stores/authStore'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent, MarkPointComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, MarkLineComponent, MarkPointComponent])

const auth = useAuthStore()
const loading = ref(false)
const generating = ref(false)
const savingProfile = ref(false)
const savingEngine = ref(false)
const savingModel = ref(false)
const savingEffort = ref(false)
const savingWebSearch = ref(false)

const DEFAULT_INFLATION_RATE = 2

const form = ref({
  birthDate: null,
  preRetirementAnnualSalary: null,
  preRetirementAnnualExpense: null,
  retirementDate: null,
  laborInsuranceMonthly: null,
  laborInsuranceStartDate: null,
  laborPensionLumpSum: null,
  laborPensionClaimDate: null,
  assumedAnnualInflationRate: DEFAULT_INFLATION_RATE,
  retirementAnnualExpense: null,
  longTermCareAnnualExpense: null,
  longTermCareStartAge: null,
  accumulationAnnualReturnRate: null,
  retirementAnnualReturnRate: null,
  goals: [],
  riskTolerance: '',
  expectedAnnualReturn: '',
  plannedExpenses: []
})
const goalOptions = ref([])
const riskOptions = ref([])
const returnOptions = ref([])

const latest = ref(null)
const history = ref([])
const allocation = ref({ snapshotId: null, snapshotDate: null, totalAssets: null, items: [] })
const projection = ref(null)
const settings = ref({ engine: '', model: '', effort: '', webSearchMaxUses: null, availableModels: [], availableEfforts: [], availableWebSearches: [], availableEngines: [] })
const selectedEngine = ref('')
const selectedModel = ref('')
const selectedEffort = ref('')
const selectedWebSearch = ref(null)

const isOk = computed(() => latest.value && latest.value.status === 'OK')
const isProcessing = computed(() => latest.value && latest.value.status === 'PROCESSING')

// 退休日期衍生（唯讀，只用於提示；真正計算在後端 service，不入庫）——不再需要「投資年限」
// accumulationYears：今天 → 退休日的整年數（無條件捨去，滿一年才算一年）
// retirementAge：生日 → 退休日的整年（退休當下年齡）；retirementYears：退休 → 100 歲的守成年數
const retirementDerived = computed(() => {
  const rd = form.value.retirementDate
  const bd = form.value.birthDate
  if (!rd) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(rd)
  if (!m) return null
  const now = new Date()
  const nowMonths = now.getFullYear() * 12 + now.getMonth()
  const retireMonths = Number(m[1]) * 12 + (Number(m[2]) - 1)
  const monthsToRetire = retireMonths - nowMonths
  const past = monthsToRetire < 0
  const accumulationYears = Math.max(0, Math.floor(monthsToRetire / 12))
  let retirementAge = null
  let retirementYears = null
  const bm = bd ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(bd) : null
  if (bm) {
    const beforeBirthday = (Number(m[2]) < Number(bm[2])) ||
      (Number(m[2]) === Number(bm[2]) && Number(m[3]) < Number(bm[3]))
    retirementAge = Math.max(0, Number(m[1]) - Number(bm[1]) - (beforeBirthday ? 1 : 0))
    retirementYears = Math.max(0, 100 - retirementAge)
  }
  return { accumulationYears, retirementAge, retirementYears, past }
})

const retirementHint = computed(() => {
  const d = retirementDerived.value
  if (!d) return null
  if (d.past) {
    return { warn: true, text: '退休日期早於今天，請確認；系統會將累積期視為 0，全期以退休後守成處理。' }
  }
  if (d.retirementYears == null) {
    return { warn: false, text: `距退休約 ${d.accumulationYears} 年（累積期，每年淨投入＝年薪−年支出）；填「生日」後可推算退休後守成年數。` }
  }
  return { warn: false, text: `累積期 ${d.accumulationYears} 年（退休前，每年淨投入＝年薪−年支出）／退休後守成期約 ${d.retirementYears} 年（退休約 ${d.retirementAge} 歲至 100 歲，淨投入視為 0）。` }
})
// 由生日衍生目前年齡（唯讀提示，不入庫；後端另有 deriveAge 為權威）
const ageHint = computed(() => {
  const b = form.value.birthDate
  if (!b) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(b)
  if (!m) return null
  const birth = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  const now = new Date()
  let age = now.getFullYear() - birth.getFullYear()
  const md = now.getMonth() - birth.getMonth() || now.getDate() - birth.getDate()
  if (md < 0) age -= 1
  if (age < 0) return null
  return `目前年齡約 ${age} 歲（由生日推算）。`
})

const allocationItems = computed(() => allocation.value.items || [])
// 子類別展開狀態（以 assetClass 為 key），現況／目標配置各自獨立展開，不引入 el-collapse
const currentSubExpanded = ref({})
const targetSubExpanded = ref({})
function toggleCurrentSub(assetClass) {
  currentSubExpanded.value[assetClass] = !currentSubExpanded.value[assetClass]
}
function toggleTargetSub(assetClass) {
  targetSubExpanded.value[assetClass] = !targetSubExpanded.value[assetClass]
}
function hasSubItems(it) {
  return !!(it && Array.isArray(it.subItems) && it.subItems.length)
}
// targetPct=0 且 currentValue 亦為 0（或未出現）的子類別不渲染；targetPct=0 但 currentValue>0（建議減碼）須渲染
function visibleSubAllocations(t) {
  const subs = t && Array.isArray(t.subAllocations) ? t.subAllocations : []
  return subs.filter(s => Number(s.targetPct) !== 0 || Number(s.currentValue) > 0)
}

// Task 344.23(2)：「再平衡操作明細」的分組（assetClass）／分段（subClass）／排序全部搬進 BFF
// （RebalanceGrouper → latest.rebalanceGroups），前端只 render，故此處不再有任何分組邏輯，
// 也不再手抄後端的類別層級標記與五個子類別字面值（兩份手抄必然漂移）。
// rebalanceGroups 缺漏或為空時（llm 檔位、本任務落地前的舊建議），template 退回扁平 rebalancePlan 渲染。
const availableModels = computed(() => settings.value.availableModels || [])
const availableEfforts = computed(() => settings.value.availableEfforts || [])
const availableWebSearches = computed(() => settings.value.availableWebSearches || [])
const availableEngines = computed(() => settings.value.availableEngines || [])
const busy = computed(() => generating.value || isProcessing.value || savingProfile.value || savingEngine.value || savingModel.value || savingEffort.value || savingWebSearch.value)
// local 檔位：模型／思考深度／搜尋次數全部停用；hybrid 檔位：搜尋次數另外停用（強制不搜尋）
const modelEffortDisabled = computed(() => busy.value || selectedEngine.value === 'local')
const webSearchDisabled = computed(() => busy.value || selectedEngine.value === 'local' || selectedEngine.value === 'hybrid')
// hybrid 檔位強制不搜尋，顯示層強制為 0；不覆寫底層 selectedWebSearch，切回 llm 時原值仍在
const webSearchDisplay = computed({
  get: () => (selectedEngine.value === 'hybrid' ? 0 : selectedWebSearch.value),
  set: (v) => { selectedWebSearch.value = v }
})

// 報酬率預設（與後端 RetirementProjectionService.returnDefault 對齊）——留空時 placeholder 顯示帶入值
const RETURN_DEFAULTS = {
  LT3: { accum: 2.5, retire: 2.0 },
  R3_6: { accum: 4.5, retire: 3.0 },
  R6_10: { accum: 8.0, retire: 4.5 },
  GT10: { accum: 12.0, retire: 6.0 },
  '': { accum: 5.0, retire: 3.0 }
}
function returnDefault(band) { return RETURN_DEFAULTS[band] || RETURN_DEFAULTS[''] }
const accumReturnPlaceholder = computed(() => `預設 ${returnDefault(form.value.expectedAnnualReturn).accum}%（依獲利預期）`)
const retireReturnPlaceholder = computed(() => `預設 ${returnDefault(form.value.expectedAnnualReturn).retire}%（較保守）`)

const projectionChartOption = computed(() => {
  const p = projection.value
  if (!p || !p.available || !p.points || !p.points.length) return {}
  const data = p.points.map(pt => [pt.age, Number(pt.balance)])
  const marks = []
  if (p.retirementAge != null) {
    marks.push({ xAxis: p.retirementAge, label: { formatter: `退休 ${p.retirementAge}歲`, position: 'insideEndTop' }, lineStyle: { color: '#f59e0b' } })
  }
  const ltcAge = p.assumptions && p.assumptions.longTermCareStartAge
  if (ltcAge != null && p.assumptions.longTermCareAnnualExpense != null) {
    marks.push({ xAxis: ltcAge, label: { formatter: `長照 ${ltcAge}歲`, position: 'insideEndBottom' }, lineStyle: { color: '#a855f7' } })
  }
  const markPoints = []
  if (!p.lastsToEndAge && p.depletionAge != null) {
    markPoints.push({ coord: [p.depletionAge, 0], value: `缺口 ${p.depletionAge}歲`, itemStyle: { color: '#ef4444' } })
  }
  return {
    grid: { left: 66, right: 24, top: 24, bottom: 42 },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => { const a = ps[0]; return `${a.value[0]} 歲<br/>資產約 ${money(a.value[1])} 元` }
    },
    xAxis: { type: 'value', name: '年齡', min: p.currentAge, max: p.endAge, minInterval: 1, axisLabel: { formatter: '{value}' } },
    yAxis: { type: 'value', name: '資產（萬元）', axisLabel: { formatter: (v) => Math.round(v / 10000).toLocaleString('en-US') } },
    series: [{
      type: 'line', smooth: true, showSymbol: false, data,
      areaStyle: { opacity: 0.12 },
      lineStyle: { width: 2 },
      itemStyle: { color: p.lastsToEndAge ? '#059669' : '#ef4444' },
      markLine: { silent: true, symbol: 'none', data: [{ yAxis: 0, lineStyle: { color: '#94a3b8', type: 'dashed' } }, ...marks] },
      markPoint: { data: markPoints, symbolSize: 46, label: { fontSize: 11 } }
    }]
  }
})

function pctText(v) {
  const n = Number(v)
  return isNaN(n) ? '—' : `${n}%`
}
function deltaClass(d) {
  const n = Number(d)
  if (isNaN(n) || n === 0) return 'flat'
  return n > 0 ? 'up' : 'down'
}
function deltaText(d) {
  const n = Number(d)
  if (isNaN(n)) return ''
  if (n === 0) return '維持'
  return n > 0 ? `增碼 ${money(n)} 元` : `減碼 ${money(-n)} 元`
}
function rebalanceType(a) {
  switch ((a || '').toUpperCase()) {
    case 'BUY': return 'success'
    case 'SELL': return 'danger'
    case 'HOLD': return 'info'
    // 344.16：本機引擎判斷「該子類別沒有可分攤的持有標的、無法指名該買哪一檔」時輸出的第四種 action。
    // 用 warning（非 success）避免看起來像可直接執行的綠色「增碼」——那會誤導使用者去下一張根本沒有標的的單。
    case 'UNSPECIFIED': return 'warning'
    default: return 'info'
  }
}
function rebalanceLabel(a) {
  switch ((a || '').toUpperCase()) {
    case 'BUY': return '增碼'
    case 'SELL': return '減碼'
    case 'HOLD': return '維持'
    case 'UNSPECIFIED': return '無法指名'
    default: return a || '—'
  }
}
function rebalanceAmountClass(a) {
  const t = rebalanceType(a)
  return t === 'success' ? 'buy' : (t === 'danger' ? 'sell' : '')
}

function money(v) {
  const n = Number(v)
  return isNaN(n) ? '0' : Math.round(n).toLocaleString('en-US')
}
function addExpense() {
  form.value.plannedExpenses.push({ expenseDate: null, name: '', amount: null })
}
function removeExpense(idx) {
  form.value.plannedExpenses.splice(idx, 1)
}
// 今日幣值 × (1+通膨)^年數 的未來名目值提示（唯讀；後端組 prompt 時以權威值換算）
function expenseFutureHint(ex) {
  if (!ex || !ex.expenseDate || ex.amount == null || ex.amount === '') return ''
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ex.expenseDate)
  const amt = Number(ex.amount)
  if (!m || isNaN(amt) || amt <= 0) return ''
  const rateRaw = Number(form.value.assumedAnnualInflationRate)
  const r = (isNaN(rateRaw) || rateRaw < 0) ? DEFAULT_INFLATION_RATE : rateRaw
  const date = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  const years = (date.getTime() - Date.now()) / (365.25 * 24 * 3600 * 1000)
  const future = amt * Math.pow(1 + r / 100, years)
  return `≈ 未來 ${money(future)} 元`
}
function fmtPct(v) {
  const n = Number(v)
  return isNaN(n) ? '—' : `${n}%`
}
function barWidth(v) {
  const n = Number(v)
  return `${Math.max(0, Math.min(100, isNaN(n) ? 0 : n))}%`
}
function priorityType(p) {
  switch ((p || '').toUpperCase()) {
    case 'HIGH': return 'danger'
    case 'MEDIUM': return 'warning'
    case 'LOW': return 'info'
    default: return 'info'
  }
}
function priorityLabel(p) {
  switch ((p || '').toUpperCase()) {
    case 'HIGH': return '高'
    case 'MEDIUM': return '中'
    case 'LOW': return '低'
    default: return '—'
  }
}
function statusLabel(status) {
  if (status === 'OK') return '完成'
  if (status === 'FAILED') return '失敗'
  if (status === 'NOT_CONFIGURED') return '未設定'
  return status || '—'
}
function labelOf(options, id) {
  if (!id) return '—'
  const o = (options || []).find(x => x.id === id)
  return o ? o.label : id
}
// references 連結來自 web_search（不可信），只允許 http(s)，擋 javascript:/data:（防 XSS）。後端另有一層。
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

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await bffApi.portfolioAdvice.get(20)
    // profile → 表單 + 可選清單
    const p = data.profile || {}
    goalOptions.value = p.goalOptions || []
    riskOptions.value = p.riskOptions || []
    returnOptions.value = p.returnOptions || []
    form.value = {
      birthDate: p.birthDate ?? null,
      preRetirementAnnualSalary: p.preRetirementAnnualSalary ?? null,
      preRetirementAnnualExpense: p.preRetirementAnnualExpense ?? null,
      retirementDate: p.retirementDate ?? null,
      laborInsuranceMonthly: p.laborInsuranceMonthly ?? null,
      laborInsuranceStartDate: p.laborInsuranceStartDate ?? null,
      laborPensionLumpSum: p.laborPensionLumpSum ?? null,
      laborPensionClaimDate: p.laborPensionClaimDate ?? null,
      assumedAnnualInflationRate: p.assumedAnnualInflationRate ?? DEFAULT_INFLATION_RATE,
      retirementAnnualExpense: p.retirementAnnualExpense ?? null,
      longTermCareAnnualExpense: p.longTermCareAnnualExpense ?? null,
      longTermCareStartAge: p.longTermCareStartAge ?? null,
      accumulationAnnualReturnRate: p.accumulationAnnualReturnRate ?? null,
      retirementAnnualReturnRate: p.retirementAnnualReturnRate ?? null,
      goals: p.goals || [],
      riskTolerance: p.riskTolerance || '',
      expectedAnnualReturn: p.expectedAnnualReturn || '',
      plannedExpenses: (p.plannedExpenses || []).map(e => ({
        expenseDate: e.expenseDate ?? null,
        name: e.name ?? '',
        amount: e.amount ?? null
      }))
    }
    // latest / history / allocation
    latest.value = data.latest && data.latest.status && data.latest.status !== 'NONE' ? data.latest : (data.latest || null)
    history.value = (data.history || []).filter(h => h && h.status)
    allocation.value = data.currentAllocation && data.currentAllocation.items
      ? data.currentAllocation
      : { snapshotId: null, snapshotDate: null, totalAssets: null, items: [] }
    projection.value = data.projection && typeof data.projection.available === 'boolean'
      ? data.projection
      : null
    // settings
    settings.value = data.settings && data.settings.availableModels
      ? data.settings
      : { engine: '', model: '', effort: '', webSearchMaxUses: null, availableModels: [], availableEfforts: [], availableWebSearches: [], availableEngines: [] }
    selectedEngine.value = settings.value.engine || ''
    selectedModel.value = settings.value.model || ''
    selectedEffort.value = settings.value.effort || ''
    selectedWebSearch.value = settings.value.webSearchMaxUses ?? null
  } finally {
    loading.value = false
  }
}

function profilePayload() {
  return {
    birthDate: form.value.birthDate,
    preRetirementAnnualSalary: form.value.preRetirementAnnualSalary,
    preRetirementAnnualExpense: form.value.preRetirementAnnualExpense,
    retirementDate: form.value.retirementDate,
    laborInsuranceMonthly: form.value.laborInsuranceMonthly,
    laborInsuranceStartDate: form.value.laborInsuranceStartDate,
    laborPensionLumpSum: form.value.laborPensionLumpSum,
    laborPensionClaimDate: form.value.laborPensionClaimDate,
    assumedAnnualInflationRate: form.value.assumedAnnualInflationRate,
    retirementAnnualExpense: form.value.retirementAnnualExpense,
    longTermCareAnnualExpense: form.value.longTermCareAnnualExpense,
    longTermCareStartAge: form.value.longTermCareStartAge,
    accumulationAnnualReturnRate: form.value.accumulationAnnualReturnRate,
    retirementAnnualReturnRate: form.value.retirementAnnualReturnRate,
    goals: form.value.goals,
    riskTolerance: form.value.riskTolerance,
    expectedAnnualReturn: form.value.expectedAnnualReturn,
    // 只送完整的花費列（有日期＋金額），過濾未填完的空列
    plannedExpenses: (form.value.plannedExpenses || [])
      .filter(e => e && e.expenseDate && e.amount != null && e.amount !== '')
      .map(e => ({ expenseDate: e.expenseDate, name: e.name || null, amount: e.amount }))
  }
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await bffApi.portfolioAdvice.saveProfile(profilePayload())
    ElMessage.success('已儲存理財條件')
  } catch (e) {
    // 錯誤 toast 由 api 攔截器統一處理
  } finally {
    savingProfile.value = false
  }
}

async function generate() {
  if (!form.value.riskTolerance) {
    ElMessage.warning('請先選擇「可忍受風險」')
    return
  }
  if (retirementDerived.value && retirementDerived.value.past) {
    ElMessage.warning('退休日期須晚於今天，請重新選擇')
    return
  }
  generating.value = true
  try {
    const res = await bffApi.portfolioAdvice.generate(profilePayload())
    if (res && res.status === 'OK') {
      ElMessage.success('已完成本機配置建議')
    } else if (res && res.status === 'PROCESSING') {
      ElMessage.success('已送出，AI 產生中，完成後自動更新')
    } else if (res && res.status === 'NOT_CONFIGURED') {
      ElMessage.warning('尚未設定 Anthropic API 金鑰')
    } else if (res && res.status === 'FAILED') {
      ElMessage.error(res.errorMessage || '建議產生失敗，請再試一次')
    }
    // 重載聚合 → latest 變 PROCESSING → watch 啟動輪詢，完成後自動更新
    await load()
  } catch (e) {
    // 錯誤 toast 由 api 攔截器統一處理；仍重載一次以反映可能已落的 PROCESSING 列
    try { await load() } catch (_) { /* ignore */ }
  } finally {
    generating.value = false
  }
}

// PROCESSING（背景產生中）時每 5 秒自動 reload，直到狀態改變；離開頁面時清除。
let pollTimer = null
function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}
watch(() => latest.value && latest.value.status, (status) => {
  if (status === 'PROCESSING') {
    if (!pollTimer) pollTimer = setInterval(() => load(true), 5000)
  } else {
    stopPoll()
  }
})

// 管理者切換分析引擎（local／hybrid／llm）→ 持久化，下次產生生效。失敗還原。
async function onEngineChange(engine) {
  savingEngine.value = true
  try {
    const res = await bffApi.portfolioAdvice.updateSettings({ engine })
    if (res && res.engine) { settings.value = res; syncSettingSelects(res) }
    ElMessage.success('已切換分析引擎，下次產生生效')
  } catch (e) {
    selectedEngine.value = settings.value.engine || ''
  } finally {
    savingEngine.value = false
  }
}
// 管理者切換模型 / 思考深度 / web 搜尋（成本控管）→ 持久化，下次產生生效。失敗還原。
async function onModelChange(model) {
  savingModel.value = true
  try {
    const res = await bffApi.portfolioAdvice.updateSettings({ model })
    if (res && res.model) { settings.value = res; syncSettingSelects(res) }
    ElMessage.success('已切換分析模型，下次產生生效')
  } catch (e) {
    selectedModel.value = settings.value.model || ''
  } finally {
    savingModel.value = false
  }
}
async function onEffortChange(effort) {
  savingEffort.value = true
  try {
    const res = await bffApi.portfolioAdvice.updateSettings({ effort })
    if (res && res.effort) { settings.value = res; syncSettingSelects(res) }
    ElMessage.success('已切換思考深度，下次產生生效')
  } catch (e) {
    selectedEffort.value = settings.value.effort || ''
  } finally {
    savingEffort.value = false
  }
}
async function onWebSearchChange(webSearchMaxUses) {
  savingWebSearch.value = true
  try {
    const res = await bffApi.portfolioAdvice.updateSettings({ webSearchMaxUses })
    if (res && res.webSearchMaxUses != null) { settings.value = res; syncSettingSelects(res) }
    ElMessage.success('已切換市場搜尋次數，下次產生生效')
  } catch (e) {
    selectedWebSearch.value = settings.value.webSearchMaxUses ?? null
  } finally {
    savingWebSearch.value = false
  }
}
function syncSettingSelects(res) {
  selectedEngine.value = res.engine || ''
  selectedModel.value = res.model || ''
  selectedEffort.value = res.effort || ''
  selectedWebSearch.value = res.webSearchMaxUses ?? null
}

onMounted(() => load())
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
.section-card { margin-bottom: 16px; }
.section-title { font-weight: 700; color: #1e293b; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.head-meta { font-size: 12px; color: #94a3b8; }
.form-hint { font-size: 12px; color: #94a3b8; margin-left: 10px; }
.retire-derived { font-size: 12px; color: #64748b; margin: -4px 0 8px 120px; }
.retire-derived.is-warn { color: #b45309; }
.sub-divider { font-size: 13px; font-weight: 600; color: #475569; }
.field-note { font-size: 12px; color: #94a3b8; margin: 0 0 10px 120px; }
.expense-list { margin-left: 120px; }
.expense-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.expense-hint { font-size: 12px; color: #059669; min-width: 120px; }
@media (max-width: 768px) {
  .retire-derived, .field-note, .expense-list { margin-left: 0; }
}

.alloc-list { display: flex; flex-direction: column; gap: 12px; }
.alloc-block { display: flex; flex-direction: column; gap: 2px; }
.alloc-row { display: flex; align-items: center; gap: 12px; }
.alloc-name { width: 150px; flex-shrink: 0; font-size: 14px; color: #334155; font-weight: 600; }
.bar-wrap { flex: 1; background: #f1f5f9; border-radius: 6px; height: 18px; overflow: hidden; }
.bar { height: 100%; border-radius: 6px; transition: width 0.4s; }
.bar.cur { background: linear-gradient(90deg, #60a5fa, #2563eb); }
.bar.tgt { background: linear-gradient(90deg, #34d399, #059669); }
.alloc-val { width: 170px; flex-shrink: 0; text-align: right; font-size: 14px; color: #1e293b; font-variant-numeric: tabular-nums; }
.alloc-amt { color: #94a3b8; font-size: 12px; }
.alloc-rationale { margin-left: 162px; font-size: 13px; color: #64748b; line-height: 1.6; }
.alloc-amounts { margin-left: 162px; font-size: 13px; color: #475569; font-variant-numeric: tabular-nums; }
.alloc-amounts .delta { font-weight: 600; }
.alloc-amounts .delta.up { color: #059669; }
.alloc-amounts .delta.down { color: #dc2626; }
.alloc-amounts .delta.flat { color: #94a3b8; }
@media (max-width: 768px) {
  .alloc-rationale, .alloc-amounts { margin-left: 0; }
}

/* 子類別展開列（成長型／收益型（高股息）／短中長期債） */
.alloc-row.clickable { cursor: pointer; }
.alloc-row .expand-caret { display: inline-block; width: 12px; color: #94a3b8; font-size: 11px; }
.alloc-row.sub { padding-left: 24px; }
.alloc-row.sub .alloc-name { width: 126px; font-size: 13px; font-weight: 500; color: #64748b; }
.alloc-row.sub .bar-wrap { height: 14px; }
.alloc-row.sub .alloc-val { font-size: 13px; }
.alloc-sub-block { display: flex; flex-direction: column; gap: 2px; }
.alloc-amounts.sub { margin-left: 186px; font-size: 12px; }
.alloc-rationale.sub { margin-left: 186px; font-size: 12px; }
@media (max-width: 768px) {
  .alloc-amounts.sub, .alloc-rationale.sub { margin-left: 24px; }
}

/* 退休現金流試算 */
.proj-summary {
  font-size: 15px; line-height: 1.8; color: #065f46; background: #ecfdf5;
  border: 1px solid #a7f3d0; border-radius: 8px; padding: 10px 14px; margin-bottom: 10px;
}
.proj-summary.is-warn { color: #92400e; background: #fffbeb; border-color: #fde68a; }
.proj-assume { font-size: 13px; color: #64748b; line-height: 1.7; margin-bottom: 10px; }
.proj-note { font-size: 12px; color: #94a3b8; margin-top: 6px; }

/* 再平衡操作明細 */
.rebalance-list .reb-class { font-size: 12px; color: #94a3b8; margin-left: 4px; }
.rebalance-list .reb-amount { margin-left: 8px; font-weight: 600; font-variant-numeric: tabular-nums; }
.rebalance-list .reb-amount.buy { color: #059669; }
.rebalance-list .reb-amount.sell { color: #dc2626; }

/* Task 344.23：以 assetClass 分組——組標題（.reb-group-header）沿用 .action-list li 原本的
   間距／分隔線，外層 <li class="reb-group"> 本身歸零，讓「只有組標題、無標的層級明細」的舊資料
   （本任務落地前的建議）渲染結果與改動前一致；標的層級明細（.reb-detail-list）縮排並用較淺的
   分隔線呈現次一層級。 */
.rebalance-list li.reb-group { padding: 0; border-bottom: none; }
.rebalance-list .reb-group-header { padding: 8px 0; border-bottom: 1px dashed #f1f5f9; }
.rebalance-list .reb-detail-list { list-style: none; margin: 0; padding-left: 20px; }
.rebalance-list .reb-detail-list .reb-detail-item { padding: 6px 0; border-bottom: 1px dashed #f8fafc; }
.rebalance-list .reb-detail-list .reb-detail-item:last-child { border-bottom: none; }
.rebalance-list .reb-detail-list .action-title { font-weight: 500; }

/* Task 344.23：子類別小標——股票桶內同時有賣（成長型減碼）有買（收益型增碼）時，
   沒有小標會像系統自相矛盾，所以刻意做成有底色的識別標籤，不做成不起眼的灰字。
   無小標段落（.reb-sub-title 不存在，如存款群組）維持原本間距不受影響。 */
.rebalance-list .reb-sub-section + .reb-sub-section { margin-top: 4px; }
.rebalance-list .reb-sub-title {
  display: inline-block;
  margin: 6px 0 4px 20px;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 700;
  color: #1e3a8a;
  background: #e0e7ff;
  border-radius: 4px;
}

.disclaimer-top {
  background: #fffbeb; border: 1px solid #fde68a; color: #92400e;
  font-size: 13px; padding: 8px 12px; border-radius: 8px; margin-bottom: 14px;
}
.summary { font-size: 15px; line-height: 1.9; color: #334155; margin-bottom: 12px; }
.risk-assess { margin-bottom: 8px; }
.block-title {
  font-weight: 700; color: #1e293b; margin: 14px 0 8px;
  border-left: 3px solid #cbd5e1; padding-left: 8px;
}
.context-text { font-size: 14px; line-height: 1.8; color: #475569; }
.action-list { list-style: none; padding-left: 0; margin: 0; }
.action-list li { padding: 8px 0; border-bottom: 1px dashed #f1f5f9; }
.prio-tag { margin-right: 8px; }
.action-title { font-weight: 600; color: #1e293b; }
.action-detail { margin-top: 4px; margin-left: 2px; font-size: 13px; color: #475569; line-height: 1.7; }
.factor-list { margin: 0; padding-left: 18px; }
.factor-list li { line-height: 1.9; color: #334155; }
.news-list { list-style: none; padding-left: 0; margin: 0; }
.news-list li { line-height: 1.7; margin-bottom: 6px; }
.news-list a { color: #2563eb; text-decoration: none; }
.news-list a:hover { text-decoration: underline; }
.muted { color: #94a3b8; }

.hist-expand { padding: 6px 12px; }
.hist-cond { font-size: 13px; color: #64748b; margin-bottom: 6px; }
.hist-summary { font-size: 14px; color: #334155; line-height: 1.7; margin-bottom: 4px; }
.hist-target { font-size: 13px; color: #059669; }
</style>
