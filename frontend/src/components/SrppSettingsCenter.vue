<template>
  <div class="srpp-center" v-loading="loadingRoot" element-loading-text="讀取 SRPP 版本與設定…">
    <div class="center-heading">
      <div>
        <h2>SRPP 設定模擬中心</h2>
        <p>修改退休規劃、資產配置與規則條件；先看情境結果，再由管理者建立發布請求。</p>
      </div>
      <el-button :icon="Refresh" :loading="loadingRoot" @click="loadRoot">重新整理</el-button>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="center-notice"
      title="模擬結果不會直接改變正式規則"
      description="選擇「不呼叫 AI」只產生情境數值；選擇「呼叫 AI 解讀」會為相同數值補充文字說明。建立發布請求仍不代表正式採用，請以系統核對後顯示的正式版本為準。"
    />

    <div v-if="rootData" class="version-cards">
      <el-card shadow="never" class="version-card">
        <div class="version-label">目前正式版本</div>
        <div class="version-value">{{ currentVersionLabel }}</div>
        <div class="version-detail">設定草稿與模擬會以此版本為基準</div>
      </el-card>
      <el-card shadow="never" class="version-card">
        <div class="version-label">目前設定目錄</div>
        <div class="version-value">{{ fields.length ? '已載入' : '尚未載入' }}</div>
        <div class="version-detail">共 {{ fields.length }} 個設定欄位</div>
      </el-card>
      <el-card shadow="never" class="version-card">
        <div class="version-label">AI 文字解讀</div>
        <div class="version-value" :class="rootData.aiAvailable ? 'available' : 'unavailable'">
          {{ rootData.aiAvailable ? '可使用' : '目前未配置' }}
        </div>
        <div class="version-detail">AI 不會更改模擬數值或發布規則</div>
      </el-card>
    </div>

    <el-collapse v-if="rootData" class="identity-details">
      <el-collapse-item name="identity">
        <template #title>進階版本識別資訊</template>
        <dl class="identity-grid">
          <dt>正式政策版本</dt><dd>{{ rootData.current?.policyVersion || '—' }}</dd>
          <dt>規則識別</dt><dd>{{ rootData.current?.decision || '—' }}</dd>
          <dt>設定目錄版本</dt><dd>{{ rootData.catalog?.catalogVersion || '—' }}</dd>
          <dt>正式 bundle SHA-256</dt><dd class="mono">{{ rootData.current?.baseBundleSha256 || '—' }}</dd>
          <dt>模型輸入 SHA-256</dt><dd class="mono">{{ rootData.current?.modelInputsSha256 || '—' }}</dd>
          <dt>目錄 SHA-256</dt><dd class="mono">{{ rootData.catalog?.catalogSha256 || '—' }}</dd>
          <dt>草稿基準 bundle SHA-256</dt><dd class="mono">{{ rootData.catalog?.baseBundleSha256 || '—' }}</dd>
        </dl>
      </el-collapse-item>
    </el-collapse>

    <el-alert v-if="rootError" type="error" show-icon :closable="false" class="operation-alert"
      title="無法載入 SRPP 設定中心" :description="rootError" />

    <template v-if="rootData">
      <el-card shadow="never" class="center-section">
        <template #header>
          <div class="section-heading">
            <div>
              <strong>一、設定草稿</strong>
              <span class="section-subtitle">草稿另存或更新後，才可執行情境模擬。</span>
            </div>
            <el-tag v-if="activeDraft" :type="draftDirty ? 'warning' : 'success'" effect="plain">
              {{ draftDirty ? '有未儲存修改' : `版本 ${activeDraft.revision} 已儲存` }}
            </el-tag>
          </div>
        </template>

        <div class="draft-toolbar">
          <div class="draft-picker">
            <label class="control-label" for="srpp-existing-draft">載入已儲存草稿</label>
            <el-select id="srpp-existing-draft" v-model="selectedDraftOption" placeholder="選擇草稿"
              clearable filterable :disabled="draftDirty || savingDraft || simulating" @change="selectDraft">
              <el-option v-for="item in drafts" :key="item.id" :label="draftOptionLabel(item)" :value="item.id" />
            </el-select>
          </div>
          <div class="draft-name">
            <label class="control-label" for="srpp-draft-name">草稿名稱</label>
            <el-input id="srpp-draft-name" v-model="draftName" maxlength="100" show-word-limit
              placeholder="例如：退休規劃調整" :disabled="savingDraft || draftBaseChanged" />
          </div>
          <div class="draft-actions">
            <el-button :icon="Plus" :loading="savingDraft" :disabled="!canCreateDraft || simulating" @click="createDraft">
              另存新草稿
            </el-button>
            <el-button type="primary" :icon="DocumentChecked" :loading="savingDraft"
              :disabled="!activeDraft || !draftDirty || simulating || draftBaseChanged || moneyInputErrorKeys.length || shortStrategyErrors.length || pairedOverrideErrors.length" @click="saveDraft">
              儲存修改
            </el-button>
            <el-button :icon="RefreshLeft" :disabled="!draftDirty || savingDraft || simulating || draftBaseChanged" @click="resetDraft">
              還原已儲存版本
            </el-button>
          </div>
        </div>

        <el-alert v-if="casConflict" type="warning" show-icon :closable="false" class="operation-alert"
          title="草稿已由其他更新推進版本；目前修改未被覆蓋"
          description="請載入最新草稿版本後再繼續；目前修改不會覆蓋其他更新。">
          <template #default>
            <el-button size="small" type="warning" @click="reloadConflictedDraft">載入最新草稿版本</el-button>
          </template>
        </el-alert>

        <el-alert v-if="draftBaseChanged" type="warning" show-icon :closable="false" class="operation-alert"
          title="這份草稿來自較早的正式版本"
          description="此草稿僅供檢視。請以目前正式版本另存新草稿，再修改與模擬。">
          <template #default>
            <el-button size="small" type="warning" @click="startCurrentBaseDraft">以目前正式版本開新草稿</el-button>
          </template>
        </el-alert>

        <div class="editor-meta">
          <span v-if="activeDraft">草稿版本 {{ activeDraft.revision }}</span>
          <span v-if="activeDraft?.baseBundleSha256">建立於{{ activeDraft.baseBundleSha256 === catalog?.baseBundleSha256 ? '目前版本' : '舊版本' }}</span>
          <span v-else>目前顯示尚未另存的設定</span>
        </div>
        <el-alert v-if="moneyInputErrorKeys.length" type="warning" show-icon :closable="false" class="operation-alert"
          title="有金額欄位尚未完成輸入" description="請修正金額格式或完成小數輸入後，再建立或儲存草稿；暫時輸入不會被當成零。" />
        <el-alert v-if="pairedOverrideErrors.length" type="warning" show-icon :closable="false" class="operation-alert"
          title="勞保／勞退指定值需要成對輸入" :description="pairedOverrideErrors.join(' ')" />

        <el-alert v-if="!fields.length" type="warning" show-icon :closable="false"
          title="目前沒有可編輯欄位" description="目錄資料未提供欄位清單；請重新整理，設定不會以本機預設值代替。" />

        <el-tabs v-else-if="visibleFieldGroups.length" v-model="activeFieldGroup" class="field-groups">
          <el-tab-pane v-for="group in visibleFieldGroups" :key="group.name" :label="group.name" :name="group.name">
            <div class="field-grid">
              <article v-for="field in group.fields" :key="field.id" class="field-card">
                <div class="field-heading">
                  <label :for="fieldInputId(field)">{{ field.label }}</label>
                  <div class="field-badges">
                    <el-tag v-if="field.formalSupported" type="success" size="small" effect="plain">正式欄位</el-tag>
                    <el-tag v-else type="warning" size="small" effect="plain">僅供情境模擬</el-tag>
                  </div>
                </div>
                <p v-if="field.help" class="field-help">{{ field.help }}</p>
                <p v-if="field.reason" class="field-reason">{{ fieldReason(field) }}</p>
                <p v-else-if="field.retirementImpact === 'NOT_MODELED'" class="field-reason">
                  此欄位不納入退休現金流情境模型。
                </p>
                <p v-else-if="field.formalSupported === false" class="field-reason">
                  此欄位只供情境模擬，不能改變正式規則。
                </p>

                <el-date-picker v-if="field.type === 'date'" :id="fieldInputId(field)"
                  :model-value="values[field.id] ?? null" type="date" value-format="YYYY-MM-DD"
                  format="YYYY年MM月DD日" placeholder="選擇日期" :disabled="fieldDisabled(field)"
                  class="field-control" @update:model-value="setFieldValue(field.id, $event)" />

                <div v-else-if="field.type === 'weights'" class="weights-grid">
                  <template v-if="field.assetKeys?.length">
                    <div v-for="assetKey in field.assetKeys" :key="assetKey" class="weight-field">
                      <label :for="weightInputId(field, assetKey)">{{ field.assetLabels?.[assetKey] || assetKey }}</label>
                      <el-input :id="weightInputId(field, assetKey)" type="number" inputmode="decimal" step="any"
                        min="0" max="100" :model-value="weightInputDisplayValue(values[field.id]?.[assetKey])"
                        :disabled="fieldDisabled(field)"
                        @update:model-value="setWeightValue(field.id, assetKey, $event)">
                        <template #append>%</template>
                      </el-input>
                    </div>
                  </template>
                  <div v-else class="catalog-warning">目錄未提供配置標的清單，避免自行增刪標的，暫不開放編輯。</div>
                  <div v-if="field.assetKeys?.length" class="weight-total" :class="`weight-total-${weightTotal(field).status}`" aria-live="polite">
                    <strong>合計：{{ weightTotalLabel(field) }}</strong>
                    <span>{{ weightTotalMessage(field) }}</span>
                  </div>
                </div>

                <div v-else-if="field.type === 'boolean'" class="boolean-editor">
                  <el-switch :id="fieldInputId(field)" :model-value="values[field.id] === true"
                    :disabled="fieldDisabled(field)" active-text="啟用" inactive-text="關閉"
                    @update:model-value="setBooleanValue(field.id, $event)" />
                </div>

                <div v-else-if="field.type === 'events'" class="typed-list-editor">
                  <div v-if="!arrayFieldValue(field).length" class="typed-list-empty">目前沒有大額支出事件。</div>
                  <div v-for="(row, index) in arrayFieldValue(field)" :key="`${field.id}-${index}`" class="typed-editor-row event-editor-row">
                    <el-date-picker :model-value="row.date || null" type="date" value-format="YYYY-MM-DD"
                      format="YYYY年MM月DD日" placeholder="支出日期" :disabled="fieldDisabled(field)"
                      @update:model-value="updateArrayField(field.id, index, 'date', $event || '')" />
                    <el-input :model-value="row.name ?? ''" placeholder="用途（如：買車）" maxlength="100"
                      :disabled="fieldDisabled(field)" @update:model-value="updateArrayField(field.id, index, 'name', $event)" />
                    <el-input :model-value="arrayMoneyValue(field.id, index, 'amount', row.amount)" type="text" inputmode="decimal"
                      placeholder="實質新臺幣元" :disabled="fieldDisabled(field)"
                      @focus="focusArrayMoney(field.id, index, 'amount', row.amount)"
                      @update:model-value="updateArrayMoney(field.id, index, 'amount', $event)"
                      @blur="commitArrayMoney(field.id, index, 'amount')" />
                    <el-button link type="danger" :icon="Delete" :disabled="fieldDisabled(field)" @click="removeArrayRow(field.id, index)">刪除</el-button>
                  </div>
                  <el-button text :icon="Plus" :disabled="fieldDisabled(field) || arrayFieldValue(field).length >= 100"
                    @click="addArrayRow(field)">新增大額支出</el-button>
                  <p class="typed-list-note">金額以 2026 年 8 月實質新臺幣計；同日多筆會由系統合併計算。原購車日期不會隨生日或給付日期移動。</p>
                </div>

                <div v-else-if="field.type === 'datedBonuses'" class="typed-list-editor">
                  <div class="typed-list-note bonus-priority">指定日期表優先；未列同名該年度時才使用每年規則。月數代表發放日月薪倍數，並非固定金額或保證給付。</div>
                  <div v-if="!arrayFieldValue(field).length" class="typed-list-empty">目前沒有指定日期獎金。</div>
                  <div v-for="(row, index) in arrayFieldValue(field)" :key="`${field.id}-${index}`" class="typed-editor-row dated-bonus-row">
                    <el-input :model-value="row.name ?? ''" placeholder="年終／績效／節慶獎金名稱" maxlength="100"
                      :disabled="fieldDisabled(field)" @update:model-value="updateArrayField(field.id, index, 'name', $event)" />
                    <el-date-picker :model-value="row.pay_date || null" type="date" value-format="YYYY-MM-DD"
                      format="YYYY年MM月DD日" placeholder="發放日期" :disabled="fieldDisabled(field)"
                      @update:model-value="updateArrayField(field.id, index, 'pay_date', $event || '')" />
                    <el-input-number :model-value="row.months ?? null" :min="0" :max="24" :step="0.1" :precision="4"
                      controls-position="right" placeholder="月薪倍數" :disabled="fieldDisabled(field)"
                      @update:model-value="updateArrayField(field.id, index, 'months', $event)" />
                    <el-date-picker :model-value="row.festival_date || null" type="date" value-format="YYYY-MM-DD"
                      format="YYYY年MM月DD日" placeholder="節日日期（選填）" clearable :disabled="fieldDisabled(field)"
                      @update:model-value="updateOptionalArrayField(field.id, index, 'festival_date', $event)" />
                    <el-button link type="danger" :icon="Delete" :disabled="fieldDisabled(field)" @click="removeArrayRow(field.id, index)">刪除</el-button>
                  </div>
                  <el-button text :icon="Plus" :disabled="fieldDisabled(field) || arrayFieldValue(field).length >= 100"
                    @click="addArrayRow(field)">新增指定日期獎金</el-button>
                </div>

                <div v-else-if="field.type === 'recurringBonuses'" class="typed-list-editor">
                  <div class="typed-list-note bonus-priority">指定日期表優先；未列同名該年度時才使用每年規則。月份例外只影響指定年份，既有指定日期不會被改動。</div>
                  <div v-if="!arrayFieldValue(field).length" class="typed-list-empty">目前沒有每年獎金規則。</div>
                  <div v-for="(row, index) in arrayFieldValue(field)" :key="`${field.id}-${index}`" class="recurring-bonus-card">
                    <div class="typed-editor-row recurring-bonus-row">
                      <el-input :model-value="row.name ?? ''" placeholder="年終／績效／節慶獎金名稱" maxlength="100"
                        :disabled="fieldDisabled(field)" @update:model-value="updateArrayField(field.id, index, 'name', $event)" />
                      <el-input-number :model-value="row.months ?? null" :min="0" :max="24" :step="0.1" :precision="4"
                        controls-position="right" placeholder="月薪倍數" :disabled="fieldDisabled(field)"
                        @update:model-value="updateArrayField(field.id, index, 'months', $event)" />
                      <el-select :model-value="row.month ?? null" placeholder="每年月份" :disabled="fieldDisabled(field)"
                        @update:model-value="updateArrayField(field.id, index, 'month', $event)">
                        <el-option v-for="month in 12" :key="month" :label="`${month} 月`" :value="month" />
                      </el-select>
                      <el-button link type="danger" :icon="Delete" :disabled="fieldDisabled(field)" @click="removeArrayRow(field.id, index)">刪除</el-button>
                    </div>
                    <details class="month-exceptions">
                      <summary>指定年份月份例外（{{ Object.keys(row.month_by_year || {}).length }}）</summary>
                      <div v-if="Object.keys(row.month_by_year || {}).length" class="exception-list">
                        <div v-for="year in sortedExceptionYears(row)" :key="year" class="month-exception-row">
                          <span>{{ year }} 年</span>
                          <el-select :model-value="row.month_by_year[year]" :disabled="fieldDisabled(field)"
                            @update:model-value="setMonthException(field.id, index, year, $event)">
                            <el-option v-for="month in 12" :key="month" :label="`${month} 月`" :value="month" />
                          </el-select>
                          <el-button link type="danger" :disabled="fieldDisabled(field)"
                            @click="removeMonthException(field.id, index, year)">移除例外</el-button>
                        </div>
                      </div>
                      <div class="month-exception-add">
                        <el-input-number v-model="exceptionYearDrafts[`${field.id}:${index}`]" :min="1900" :max="2200" :step="1"
                          controls-position="right" placeholder="年份" :disabled="fieldDisabled(field)" />
                        <el-select v-model="exceptionMonthDrafts[`${field.id}:${index}`]" :disabled="fieldDisabled(field)">
                          <el-option v-for="month in 12" :key="month" :label="`${month} 月`" :value="month" />
                        </el-select>
                        <el-button size="small" :disabled="fieldDisabled(field) || !canAddMonthException(field, index)"
                          @click="addMonthException(field.id, index)">新增月份例外</el-button>
                      </div>
                    </details>
                  </div>
                  <el-button text :icon="Plus" :disabled="fieldDisabled(field) || arrayFieldValue(field).length >= 32"
                    @click="addArrayRow(field)">新增每年獎金規則</el-button>
                </div>

                <div v-else-if="field.type === 'bearLadder' || field.type === 'bullLadder'" class="readonly-ladder">
                  <p class="field-reason">來源五批規則唯讀展示；月頻情境不是每日雷達或下單授權。</p>
                  <el-table :data="arrayFieldValue(field)" size="small" stripe empty-text="來源未提供梯級資料">
                    <template v-if="field.type === 'bearLadder'">
                      <el-table-column label="回撤門檻" min-width="110"><template #default="{ row }">{{ formatFraction(row.drawdown) }}</template></el-table-column>
                      <el-table-column label="窗口預算比例" min-width="130"><template #default="{ row }">{{ formatFraction(row.share_of_window_budget) }}</template></el-table-column>
                    </template>
                    <template v-else>
                      <el-table-column label="高於錨點" min-width="110"><template #default="{ row }">{{ formatFraction(row.gain_above_anchor) }}</template></el-table-column>
                      <el-table-column label="高水位拉回" min-width="110"><template #default="{ row }">{{ formatFraction(row.pullback_from_high_watermark) }}</template></el-table-column>
                      <el-table-column label="窗口預算比例" min-width="130"><template #default="{ row }">{{ formatFraction(row.share_of_window_budget) }}</template></el-table-column>
                    </template>
                  </el-table>
                </div>

                <div v-else-if="field.type === 'number' || field.type === 'integer'" class="field-control-wrap">
                  <el-input v-if="isMoneyField(field)" :id="fieldInputId(field)" type="text" inputmode="decimal"
                    :model-value="fieldMoneyValue(field)" :disabled="fieldDisabled(field)"
                    :class="{ 'money-input-invalid': moneyInputInvalid(fieldMoneyKey(field)) }"
                    class="field-control" @focus="focusFieldMoney(field)"
                    @update:model-value="updateFieldMoney(field, $event)" @blur="commitFieldMoney(field)">
                    <template v-if="field.unit" #append>{{ field.unit }}</template>
                  </el-input>
                  <el-input v-else :id="fieldInputId(field)" type="number" inputmode="decimal"
                    :step="field.type === 'integer' ? 1 : 'any'"
                    :min="displayBound(field.min, field)" :max="displayBound(field.max, field)"
                    :model-value="isFractionField(field) ? fractionInputValue(values[field.id]) : values[field.id] ?? null"
                    :disabled="fieldDisabled(field)" class="field-control"
                    @update:model-value="setFieldValue(field.id, $event)">
                    <template v-if="isFractionField(field)" #append>%</template>
                    <template v-else-if="field.unit" #append>{{ field.unit }}</template>
                  </el-input>
                </div>

                <div v-else class="catalog-warning">
                  目錄型別「{{ field.type }}」未提供可安全編輯的表單；原值會保留，請勿以自由格式覆寫。
                </div>

                <div class="field-bounds">
                  <span v-if="field.min != null || field.max != null">
                    可輸入範圍：{{ formatBound(field.min, field) }} 至 {{ formatBound(field.max, field) }}{{ isFractionField(field) ? '%' : field.unit ? ` ${field.unit}` : '' }}
                  </span>
                  <span v-if="field.simulationSupported === false">僅預覽規則差異</span>
                  <span v-if="field.retirementImpact === 'NOT_MODELED'">退休現金流：未建模</span>
                </div>
              </article>
            </div>
          </el-tab-pane>
        </el-tabs>

        <SrppShortTermOpportunities v-if="shortTermFields.length"
          :fields="shortTermFields" :values="values" :disabled="savingDraft || draftBaseChanged"
          :draft-id="activeDraft?.id || null" :draft-revision="activeDraft?.revision ?? null"
          :draft-is-current="!draftBaseChanged" :draft-is-saved="draftIsSaved"
          :owner-key="ownerIdentity" @field-change="updateShortSetting" />

        <div v-if="activeDraft?.changes?.length" class="change-preview">
          <div class="subsection-heading">已儲存草稿的設定差異</div>
          <el-table :data="activeDraft.changes" size="small" stripe>
            <el-table-column prop="label" label="欄位" min-width="150" />
            <el-table-column label="基準值" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ formatFieldChange(row.fieldId, row.before) }}</template>
            </el-table-column>
            <el-table-column label="草稿值" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ formatFieldChange(row.fieldId, row.after) }}</template>
            </el-table-column>
            <el-table-column label="用途" width="160">
              <template #default="{ row }">
                <el-tag v-if="row.formalSupported" type="success" size="small" effect="plain">正式支援</el-tag>
                <el-tag v-else type="warning" size="small" effect="plain">僅供情境模擬</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <p class="field-reason">下列差異由系統提供。</p>
        </div>
      </el-card>

      <div class="lower-grid">
        <el-card shadow="never" class="center-section">
          <template #header>
            <div class="section-heading">
              <div>
                <strong>二、情境模擬</strong>
                <span class="section-subtitle">兩種模式使用相同計算結果；差別只有是否呼叫 AI 產生文字解讀。</span>
              </div>
              <el-tag v-if="selectedRun" :type="runStatusType(selectedRun.status)" effect="plain">
                {{ runStatusLabel(selectedRun.status) }}
              </el-tag>
            </div>
          </template>

          <el-alert v-if="!activeDraft" type="info" show-icon :closable="false"
            title="請先建立或載入草稿" description="模擬依已儲存草稿執行；畫面修改不會自動儲存。" />
          <el-alert v-else-if="draftDirty" type="warning" show-icon :closable="false"
            title="畫面有尚未儲存的修改" description="請先儲存草稿，再執行模擬；舊 run 不會套用到新的草稿內容。" />

          <div class="mode-options">
            <el-radio-group v-model="runMode" :disabled="simulating">
              <el-radio-button value="LOCAL">不呼叫 AI</el-radio-button>
              <el-radio-button value="AI" :disabled="!rootData.aiAvailable">呼叫 AI 解讀</el-radio-button>
            </el-radio-group>
            <p class="mode-description">
              <template v-if="runMode === 'LOCAL'">不呼叫 AI；只產生情境數值與限制說明。</template>
              <template v-else>呼叫 AI 為相同計算結果補充文字解讀，不計算或修改數值。</template>
              <span v-if="runMode === 'AI' && !rootData.aiAvailable">AI 尚未配置，請選擇「不呼叫 AI」。</span>
            </p>
          </div>

          <div class="simulation-controls">
            <div>
              <label class="control-label" for="srpp-seed">隨機種子</label>
              <el-input-number id="srpp-seed" v-model="seed" :min="0" :max="2147483647" :step="1"
                controls-position="right" :disabled="simulating" />
              <span class="control-hint">固定種子可重現相同模擬</span>
            </div>
            <div>
              <label class="control-label" for="srpp-simulations">模擬路徑數</label>
              <el-input-number id="srpp-simulations" v-model="simulationCount" :min="100" :max="100000" :step="100"
                controls-position="right" :disabled="simulating" />
              <span class="control-hint">100–100,000；預設 1,000</span>
            </div>
            <el-button type="primary" :icon="VideoPlay" :loading="simulating"
            :disabled="!activeDraft || draftDirty || draftBaseChanged || moneyInputErrorKeys.length || shortStrategyErrors.length || pairedOverrideErrors.length || !draftName.trim() || runMode === 'AI' && !rootData.aiAvailable || simulationProcessing"
              @click="simulate">
              {{ simulationProcessing ? '模擬執行中…' : '開始模擬' }}
            </el-button>
          </div>

          <el-alert v-if="selectedRun?.status === 'PROCESSING'" type="info" show-icon :closable="false" class="operation-alert"
            title="模擬正在執行" description="完成後畫面會自動更新；請以目前狀態為準。" />
          <el-alert v-if="pollTimedOut && selectedRun?.status === 'PROCESSING'" type="warning" show-icon :closable="false" class="operation-alert"
            title="等待時間已超過三分鐘" description="目前仍顯示執行中；可手動重新查詢，不會將未完成的模擬視為失敗。">
            <template #default>
              <el-button size="small" @click="refreshSelectedRun">重新查詢</el-button>
            </template>
          </el-alert>
          <el-alert v-if="pollError" type="warning" show-icon :closable="false" class="operation-alert"
            title="暫時無法更新模擬狀態" :description="pollError" />
          <el-alert v-if="simulationError" type="error" show-icon :closable="false" class="operation-alert"
            title="模擬請求失敗" :description="simulationError" />
          <el-alert v-if="loadingSelectedRun" type="info" show-icon :closable="false" class="operation-alert"
            title="正在載入單筆完整模擬結果" description="歷史清單只傳送摘要；完整時程會在選取後讀取。" />

          <div v-if="runs.length" class="run-history-picker">
            <label class="control-label" for="srpp-run-picker">最近的模擬</label>
            <el-select id="srpp-run-picker" v-model="selectedRunOption" placeholder="選擇模擬記錄"
              clearable filterable @change="selectRun">
              <el-option v-for="item in runs" :key="item.id" :label="runOptionLabel(item)" :value="item.id" />
            </el-select>
            <el-button v-if="selectedRun" text :icon="Refresh" @click="refreshSelectedRun">重新查詢此模擬</el-button>
          </div>
        </el-card>

        <el-card shadow="never" class="center-section release-card">
          <template #header>
            <div class="section-heading">
              <div>
                <strong>三、正式發布請求</strong>
                <span class="section-subtitle">僅管理者可建立發布請求；正式狀態由系統核對後顯示。</span>
              </div>
              <el-tag v-if="rootData.canPublish" type="success" effect="plain">具備管理權限</el-tag>
              <el-tag v-else type="info" effect="plain">唯讀</el-tag>
            </div>
          </template>

          <el-alert type="warning" show-icon :closable="false"
            title="建立請求不會立即切換正式版本"
            description="下載設定檔案後仍須經核准的發布流程；待發布不代表正式採用。" />

          <div v-if="publishBlocks.length" class="publish-blocks">
            <div class="subsection-heading">目前不能建立發布請求</div>
            <el-alert v-for="(block, index) in publishBlocks" :key="`${block.code || 'block'}-${index}`"
              type="info" :closable="false" class="block-reason">
              <template #title>{{ block.message || block.code || '尚未符合發布條件' }}</template>
            </el-alert>
          </div>
          <div v-else-if="!selectedRun" class="empty-publish">完成目前草稿的模擬後，這裡會顯示發布條件。</div>

          <el-button v-if="auth.isAdmin" type="warning" :icon="Promotion" :loading="publishing"
            :disabled="!canPublishNow" class="publish-button" @click="publish">
            建立待發布請求
          </el-button>
          <div v-else class="admin-note">發布操作僅限管理者；一般使用者仍可建立草稿與執行情境模擬。</div>

          <div v-if="selectedRelease" class="selected-release">
            <div class="subsection-heading">最近建立的發布請求</div>
            <div class="release-summary">
              <el-tag :type="releaseStatusType(selectedRelease.status)" effect="plain">
                {{ releaseStatusLabel(selectedRelease.status) }}
              </el-tag>
              <span>{{ formatTime(selectedRelease.createdAt) }}</span>
              <span>{{ releaseStatusDescription(selectedRelease.status) }}</span>
            </div>
            <el-button size="small" :icon="Download" :loading="downloadingReleaseId === selectedRelease.id"
              @click="downloadPayload(selectedRelease)">下載設定檔案</el-button>
            <el-collapse class="identity-details">
              <el-collapse-item name="release-identity">
                <template #title>查看版本詳細資料</template>
                <dl class="identity-grid">
                  <dt>請求識別</dt><dd class="mono">{{ selectedRelease.requestSha256 || '—' }}</dd>
                  <dt>設定識別</dt><dd class="mono">{{ selectedRelease.configSha256 || '—' }}</dd>
                  <dt>基準版本</dt><dd class="mono">{{ selectedRelease.baseBundleSha256 || '—' }}</dd>
                </dl>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-card>
      </div>

      <el-card v-if="selectedRun" shadow="never" class="center-section result-section">
        <template #header>
          <div class="section-heading">
            <div>
              <strong>模擬結果與限制</strong>
              <span class="section-subtitle">基準、情境與差異均由系統提供。</span>
            </div>
            <el-tag :type="runStatusType(selectedRun.status)" effect="plain">
              {{ runStatusLabel(selectedRun.status) }}
            </el-tag>
          </div>
        </template>

        <el-alert v-if="selectedRun.status === 'FAILED'" type="error" show-icon :closable="false"
          title="這次模擬未成功" description="請重新查詢或稍後再執行一次模擬。" />
        <el-alert v-else-if="selectedRun.status === 'PROCESSING'" type="info" show-icon :closable="false"
          title="等待情境模擬完成" description="模擬完成後即可查看結果。" />
        <el-alert v-else-if="activeDraft && (draftDirty || !runMatchesDraft)" type="warning" show-icon :closable="false" class="operation-alert"
          :title="draftDirty ? '這筆模擬對應已儲存版本' : '這筆模擬不是目前草稿版本的結果'"
          :description="draftDirty ? '目前畫面有未儲存修改；請先儲存，再重新模擬。' : '草稿內容或版本已變更；此結果僅供檢視，不能用來發布目前草稿。'" />
        <template v-else-if="result">
          <el-alert v-if="!resultPresentation" type="warning" show-icon :closable="false"
            title="模擬假設尚未完整載入" description="請重新查詢這次模擬，以取得完整的來源與假設說明。" />
          <div class="result-scope">
            <el-tag type="warning" effect="plain">月頻情境模型，不是正式模型</el-tag>
            <span>以月頻資料推演退休情境</span>
            <span v-if="selectedRun.mode">執行方式：{{ selectedRun.mode === 'AI' ? '呼叫 AI 解讀' : '不呼叫 AI' }}</span>
          </div>
          <p class="field-reason">金額為 2026 年 8 月實質新臺幣元，已排除緊急預備金；不是未來名目金額。</p>

          <div class="ai-result" :class="aiStatusType(selectedRun.aiStatus)">
            <div class="ai-result-heading">
              <strong>AI 文字解讀</strong>
              <el-tag :type="aiStatusTagType(selectedRun.aiStatus)" size="small" effect="plain">
                {{ aiStatusLabel(selectedRun.aiStatus) }}
              </el-tag>
            </div>
            <p v-if="selectedRun.aiInterpretation">{{ selectedRun.aiInterpretation }}</p>
            <p v-else>{{ aiStatusDescription(selectedRun.aiStatus, selectedRun.mode) }}</p>
          </div>

          <div class="subsection-heading">基準與情境比較</div>
          <el-table :data="resultComparisonRows" size="small" stripe class="comparison-table">
            <el-table-column prop="label" label="指標" min-width="190" />
            <el-table-column label="基準" min-width="170">
              <template #default="{ row }">{{ formatResultValue(row.baseline, row.kind) }}</template>
            </el-table-column>
            <el-table-column label="情境" min-width="170">
              <template #default="{ row }">{{ formatResultValue(row.scenario, row.kind) }}</template>
            </el-table-column>
            <el-table-column label="情境－基準" min-width="170">
              <template #default="{ row }">{{ formatDifference(row.difference, row.kind) }}</template>
            </el-table-column>
          </el-table>

          <div class="subsection-heading path-heading">
            <span>結果版本與路徑分位數</span>
            <el-radio-group v-model="pathSide" size="small">
              <el-radio-button value="scenario">情境</el-radio-button>
              <el-radio-button value="baseline">基準</el-radio-button>
            </el-radio-group>
          </div>
          <el-table :data="selectedPaths" size="small" stripe max-height="360" empty-text="服務未提供路徑資料">
            <el-table-column prop="date" label="日期" min-width="120" />
            <el-table-column prop="age" label="年齡" width="90">
              <template #default="{ row }">{{ row.age == null ? '—' : `${row.age} 歲` }}</template>
            </el-table-column>
            <el-table-column label="P10" min-width="140">
              <template #default="{ row }">{{ formatMoney(row.p10) }}</template>
            </el-table-column>
            <el-table-column label="P50" min-width="140">
              <template #default="{ row }">{{ formatMoney(row.p50) }}</template>
            </el-table-column>
            <el-table-column label="P90" min-width="140">
              <template #default="{ row }">{{ formatMoney(row.p90) }}</template>
            </el-table-column>
          </el-table>

          <div v-if="runChanges.length" class="change-preview">
            <div class="subsection-heading">本次模擬涵蓋的設定差異</div>
            <el-table :data="runChanges" size="small" stripe>
              <el-table-column prop="label" label="欄位" min-width="150" />
              <el-table-column label="基準值" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ formatFieldChange(row.fieldId, row.before) }}</template>
              </el-table-column>
              <el-table-column label="情境值" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ formatFieldChange(row.fieldId, row.after) }}</template>
              </el-table-column>
              <el-table-column label="退休影響" width="150">
                <template #default="{ row }">{{ row.retirementImpact === 'NOT_MODELED' ? '未建模' : row.retirementImpact === 'MODELED' ? '已建模' : '—' }}</template>
              </el-table-column>
              <el-table-column label="正式支援" width="130">
                <template #default="{ row }">
                  <el-tag v-if="row.formalSupported" type="success" size="small" effect="plain">是</el-tag>
                  <el-tag v-else type="warning" size="small" effect="plain">僅模擬</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="limitations-grid">
            <section>
              <div class="subsection-heading">已建模欄位</div>
              <p>{{ fieldListLabel(resultPresentation?.modeledFields) }}</p>
            </section>
            <section>
              <div class="subsection-heading">未建模欄位</div>
              <p>{{ fieldListLabel(resultPresentation?.notModeledFields) }}</p>
            </section>
          </div>

          <el-alert v-for="(warning, index) in result.warnings || []" :key="`warning-${index}`"
            type="warning" show-icon :closable="false" class="operation-alert" :title="warning" />

          <div v-if="visibleAssumptions.length" class="assumptions-panel">
            <div class="subsection-heading">情境假設與幣值基準</div>
            <dl class="assumptions-grid">
              <template v-for="assumption in visibleAssumptions" :key="assumption.key">
                <dt>{{ assumption.label }}</dt>
                <dd>{{ formatAssumption(assumption.key, assumption.value, assumption.unit) }}</dd>
              </template>
            </dl>
          </div>
          <section class="typed-result-section" aria-label="退休現金流與策略設定">
            <div class="typed-result-heading">
              <div>
                <div class="subsection-heading">{{ pathSide === 'baseline' ? '基準' : '情境' }}：退休現金流與策略結果</div>
                <p>日期、金額與狀態直接呈現服務回傳的 typed 結果；前端不推算給付或金融差異。</p>
              </div>
            </div>
            <el-alert v-if="!cashflowSchedule" type="info" show-icon :closable="false"
              title="此為舊版歷史結果，未提供新增時程" description="歷史結果可繼續檢視；畫面不補造生日、薪資、給付或支出資料。" />
            <template v-else>
              <el-alert type="warning" show-icon :closable="false" class="operation-alert"
                title="月頻情境預估，不代表已核定的勞保或勞退給付"
                description="年度薪資稅如有提供，僅試算本人薪資與獎金的單身居民規劃範圍，非完整全戶申報或實際核定。最高適用稅率由所得淨額自動計算，非整筆所得套用該率。未公告年度級距採規劃代理；此結果不含逐檔配置容量、交易成本與交割，也不是雷達訊號、正式報告或下單授權。" />

              <div class="subsection-heading">生日、給付與定期支出時程</div>
              <el-table :data="cashflowScheduleRows" size="small" stripe>
                <el-table-column prop="category" label="項目" width="130" />
                <el-table-column prop="label" label="欄位" min-width="180" />
                <el-table-column label="系統結果" min-width="190">
                  <template #default="{ row }">{{ formatScheduleValue(row.value, row.kind) }}</template>
                </el-table-column>
                <el-table-column prop="note" label="說明" min-width="200" show-overflow-tooltip />
              </el-table>

              <template v-if="cashflowSchedule.majorExpenses?.length">
                <div class="subsection-heading">大額事件（2026 年 8 月實質新臺幣）</div>
                <el-table :data="cashflowSchedule.majorExpenses" size="small" stripe>
                  <el-table-column prop="date" label="日期" width="120" />
                  <el-table-column prop="name" label="用途" min-width="150" />
                  <el-table-column label="金額" min-width="150"><template #default="{ row }">{{ formatCurrency(row.amountRealTwd) }}</template></el-table-column>
                  <el-table-column label="結果狀態" min-width="140"><template #default="{ row }">{{ row.included ? '已列入時程' : '未列入時程' }}</template></el-table-column>
                  <el-table-column label="排除原因" min-width="170"><template #default="{ row }">{{ exclusionReasonLabel(row.exclusionReason) }}</template></el-table-column>
                </el-table>
              </template>

              <template v-if="cashflowSchedule.payroll">
                <div class="subsection-heading">退休前薪資設定與收付合計</div>
                <el-table :data="payrollScheduleRows" size="small" stripe>
                  <el-table-column prop="label" label="欄位" min-width="210" />
                  <el-table-column label="系統結果" min-width="210"><template #default="{ row }">{{ formatScheduleValue(row.value, row.kind) }}</template></el-table-column>
                  <el-table-column prop="note" label="說明" min-width="200" show-overflow-tooltip />
                </el-table>

              <div class="subsection-heading">年度薪資所得稅試算</div>
              <template v-if="cashflowSchedule.payroll.annualIncomeTax">
                <div class="typed-result-note">
                  範圍：{{ incomeTaxScopeLabel(cashflowSchedule.payroll.annualIncomeTax.scope) }}；級距規則年度：{{ cashflowSchedule.payroll.annualIncomeTax.ruleYear }}；
                  未公告年度政策：{{ incomeTaxFuturePolicyLabel(cashflowSchedule.payroll.annualIncomeTax.futureYearPolicy) }}。
                  <a :href="cashflowSchedule.payroll.annualIncomeTax.sourceUrl" target="_blank" rel="noopener noreferrer">官方級距來源</a>
                </div>
                <p class="typed-result-note">歷史基礎：{{ incomeTaxHistoricalBasisLabel(cashflowSchedule.payroll.annualIncomeTax.historicalBasis) }}</p>
                <ul v-if="cashflowSchedule.payroll.annualIncomeTax.notes?.length" class="typed-result-note">
                  <li v-for="(note, index) in cashflowSchedule.payroll.annualIncomeTax.notes" :key="`annual-tax-note-${index}`">{{ note }}</li>
                </ul>
                <el-table v-if="cashflowSchedule.payroll.annualIncomeTax.years?.length"
                  :data="cashflowSchedule.payroll.annualIncomeTax.years" size="small" stripe max-height="460" class="typed-wide-table">
                  <el-table-column type="expand" width="48" fixed="left">
                    <template #default="{ row }">
                      <div class="subsection-heading">課稅所得與扣除額</div>
                      <el-descriptions :column="3" border size="small">
                        <el-descriptions-item label="扣自提後薪資所得">{{ formatCurrency(row.taxableSalaryNominalTwd) }}</el-descriptions-item>
                        <el-descriptions-item label="免稅額">{{ formatCurrency(row.personalExemptionNominalTwd) }}</el-descriptions-item>
                        <el-descriptions-item label="單身標準扣除額">{{ formatCurrency(row.standardDeductionNominalTwd) }}</el-descriptions-item>
                        <el-descriptions-item label="薪資所得特別扣除額">{{ formatCurrency(row.salaryDeductionNominalTwd) }}</el-descriptions-item>
                      </el-descriptions>
                      <div class="subsection-heading">各級距稅額切片</div>
                      <el-table :data="row.brackets" size="small" stripe>
                        <el-table-column label="級距下界" min-width="130"><template #default="{ row: bracket }">{{ formatCurrency(bracket.lowerNominalTwd) }}</template></el-table-column>
                        <el-table-column label="級距上界" min-width="130"><template #default="{ row: bracket }">{{ bracket.upperNominalTwd == null ? '無上限' : formatCurrency(bracket.upperNominalTwd) }}</template></el-table-column>
                        <el-table-column label="級距稅率" min-width="110"><template #default="{ row: bracket }">{{ formatFraction(bracket.rate) }}</template></el-table-column>
                        <el-table-column label="本級課稅切片" min-width="150"><template #default="{ row: bracket }">{{ formatCurrency(bracket.taxableSliceNominalTwd) }}</template></el-table-column>
                        <el-table-column label="本級稅額" min-width="130"><template #default="{ row: bracket }">{{ formatCurrency(bracket.taxNominalTwd) }}</template></el-table-column>
                      </el-table>
                    </template>
                  </el-table-column>
                  <el-table-column prop="taxYear" label="所得年度" width="100" fixed="left" />
                  <el-table-column label="全年薪資與獎金" min-width="150"><template #default="{ row }">{{ formatCurrency(row.grossSalaryNominalTwd) }}</template></el-table-column>
                  <el-table-column label="勞退自提" min-width="130"><template #default="{ row }">{{ formatCurrency(row.selfPensionNominalTwd) }}</template></el-table-column>
                  <el-table-column label="所得淨額（課稅所得）" min-width="180"><template #default="{ row }">{{ formatCurrency(row.taxableIncomeNominalTwd) }}</template></el-table-column>
                  <el-table-column label="最高適用稅率" min-width="130"><template #default="{ row }">{{ formatFraction(row.marginalRate) }}</template></el-table-column>
                  <el-table-column label="有效稅率（約）" min-width="140"><template #default="{ row }">{{ formatEffectiveTaxRate(row.effectiveRate) }}</template></el-table-column>
                  <el-table-column label="試算應納稅額" min-width="145"><template #default="{ row }">{{ formatCurrency(row.liabilityNominalTwd) }}</template></el-table-column>
                  <el-table-column label="已預扣" min-width="130"><template #default="{ row }">{{ formatCurrency(row.withheldNominalTwd) }}</template></el-table-column>
                  <el-table-column label="結算補／退（正退負補）" min-width="190"><template #default="{ row }">{{ formatCurrency(row.settlementNominalTwd) }}</template></el-table-column>
                  <el-table-column prop="settlementDate" label="結算日" width="120" />
                  <el-table-column label="年度處理" min-width="145"><template #default="{ row }">{{ incomeTaxTreatmentLabel(row.treatment) }}</template></el-table-column>

                </el-table>
                <el-alert v-else type="info" show-icon :closable="false"
                  title="此結果未提供年度列" description="畫面不補造年度薪資、稅額或結算資料。" />
              </template>
              <el-alert v-else type="info" show-icon :closable="false"
                title="此歷史結果尚無年度累進稅明細" description="舊版結果未提供年度薪資所得稅資料；畫面不以預扣率或零值補造年度應納稅額。" />

                <template v-if="cashflowSchedule.payroll.bonusEvents?.length">
                  <div class="subsection-heading">指定日期年終／績效／節慶獎金</div>
                  <el-table :data="cashflowSchedule.payroll.bonusEvents" size="small" stripe>
                    <el-table-column prop="name" label="獎金名稱" min-width="160" />
                    <el-table-column prop="pay_date" label="發放日期" width="120" />
                    <el-table-column label="月薪倍數" width="120"><template #default="{ row }">{{ formatMonths(row.months) }}</template></el-table-column>
                    <el-table-column prop="festival_date" label="節日日期" width="120"><template #default="{ row }">{{ row.festival_date || '未指定' }}</template></el-table-column>
                  </el-table>
                </template>

                <template v-if="cashflowSchedule.payroll.recurringBonuses?.length">
                  <div class="subsection-heading">每年年終／績效／節慶獎金規則</div>
                  <el-table :data="cashflowSchedule.payroll.recurringBonuses" size="small" stripe>
                    <el-table-column prop="name" label="獎金名稱" min-width="160" />
                    <el-table-column label="月薪倍數" width="120"><template #default="{ row }">{{ formatMonths(row.months) }}</template></el-table-column>
                    <el-table-column label="一般月份" width="120"><template #default="{ row }">{{ row.month }} 月</template></el-table-column>
                    <el-table-column label="年份例外" min-width="220"><template #default="{ row }">{{ formatMonthExceptions(row.month_by_year) }}</template></el-table-column>
                  </el-table>
                </template>

                <template v-if="cashflowSchedule.payroll.ledger?.length">
                  <div class="subsection-heading">薪資與獎金收付明細</div>
                  <el-table :data="cashflowSchedule.payroll.ledger" size="small" stripe max-height="460" class="typed-wide-table">
                    <el-table-column prop="payDate" label="發薪日" width="112" fixed="left" />
                    <el-table-column label="約定月薪" min-width="130"><template #default="{ row }">{{ formatCurrency(row.contractualMonthlySalaryNominalTwd) }}</template></el-table-column>
                    <el-table-column label="一般薪資" min-width="130"><template #default="{ row }">{{ formatCurrency(row.regularSalaryNominalTwd) }}</template></el-table-column>
                    <el-table-column label="獎金" min-width="120"><template #default="{ row }">{{ formatCurrency(row.bonusNominalTwd) }}</template></el-table-column>
                    <el-table-column label="稅前總額" min-width="130"><template #default="{ row }">{{ formatCurrency(row.grossPayNominalTwd) }}</template></el-table-column>
                    <el-table-column label="勞就保" min-width="120"><template #default="{ row }">{{ formatCurrency(row.laborInsuranceNominalTwd) }}</template></el-table-column>
                    <el-table-column label="健保" min-width="120"><template #default="{ row }">{{ formatCurrency(row.healthInsuranceNominalTwd) }}</template></el-table-column>
                    <el-table-column label="勞退自提" min-width="120"><template #default="{ row }">{{ formatCurrency(row.selfPensionNominalTwd) }}</template></el-table-column>
                    <el-table-column label="所得稅扣繳" min-width="130"><template #default="{ row }">{{ formatCurrency(row.withholdingNominalTwd) }}</template></el-table-column>
                    <el-table-column label="補充健保" min-width="120"><template #default="{ row }">{{ formatCurrency(row.supplementalHealthNominalTwd) }}</template></el-table-column>
                    <el-table-column label="實收淨額" min-width="130"><template #default="{ row }">{{ formatCurrency(row.netCashNominalTwd) }}</template></el-table-column>
                    <el-table-column label="退休日後發放" min-width="130"><template #default="{ row }">{{ row.paymentAfterRetirement ? '是' : '否' }}</template></el-table-column>
                    <el-table-column label="納入退休當日資產" min-width="160"><template #default="{ row }">{{ row.includedAtRetirement ? '是' : '否' }}</template></el-table-column>
                    <el-table-column prop="yearRuleStatus" label="年度級距狀態" min-width="300" show-overflow-tooltip />
                    <el-table-column label="獎金明細" min-width="220"><template #default="{ row }">{{ formatLedgerBonuses(row.bonusEvents) }}</template></el-table-column>
                  </el-table>
                </template>
                <p class="typed-result-note">已收／待發放合計只涵蓋本輪 ledger；薪資預扣是預繳，年度應納稅額及補退請參照上方累進稅試算明細。</p>
              </template>

              <template v-if="selectedResult?.assumptions?.reserveStrategy">
                <div class="subsection-heading">準備金與牛熊策略設定</div>
                <el-table :data="reserveStrategyRows" size="small" stripe>
                  <el-table-column prop="label" label="欄位" min-width="230" />
                  <el-table-column label="系統設定" min-width="190"><template #default="{ row }">{{ formatScheduleValue(row.value, row.kind) }}</template></el-table-column>
                  <el-table-column prop="note" label="說明" min-width="220" show-overflow-tooltip />
                </el-table>
                <template v-if="selectedResult.assumptions.reserveStrategy.bearLadder?.length">
                  <div class="subsection-heading">熊市五批來源規則（唯讀）</div>
                  <el-table :data="selectedResult.assumptions.reserveStrategy.bearLadder" size="small" stripe>
                    <el-table-column label="回撤門檻" min-width="120"><template #default="{ row }">{{ formatFraction(row.drawdown) }}</template></el-table-column>
                    <el-table-column label="窗口預算比例" min-width="150"><template #default="{ row }">{{ formatFraction(row.shareOfWindowBudget) }}</template></el-table-column>
                  </el-table>
                </template>
                <template v-if="selectedResult.assumptions.reserveStrategy.bullLadder?.length">
                  <div class="subsection-heading">牛市五批來源規則（唯讀）</div>
                  <el-table :data="selectedResult.assumptions.reserveStrategy.bullLadder" size="small" stripe>
                    <el-table-column label="高於錨點" min-width="120"><template #default="{ row }">{{ formatFraction(row.gainAboveAnchor) }}</template></el-table-column>
                    <el-table-column label="高水位拉回" min-width="130"><template #default="{ row }">{{ formatFraction(row.pullbackFromHighWatermark) }}</template></el-table-column>
                    <el-table-column label="窗口預算比例" min-width="150"><template #default="{ row }">{{ formatFraction(row.shareOfWindowBudget) }}</template></el-table-column>
                  </el-table>
                </template>
              </template>

              <template v-if="selectedResult?.reserveStrategyAudit">
                <div class="subsection-heading">準備金、流動性與戰術策略稽核</div>
                <el-table :data="reserveAuditRows" size="small" stripe>
                  <el-table-column prop="label" label="稽核項目" min-width="250" />
                  <el-table-column label="結果" min-width="280"><template #default="{ row }">{{ row.kind === 'moneyQuantiles' ? formatMoneyQuantiles(row.value) : formatScheduleValue(row.value, row.kind) }}</template></el-table-column>
                </el-table>
                <template v-if="selectedResult.reserveStrategyAudit.monthlyAudit?.length">
                  <div class="subsection-heading">月頻稽核（每完成月最多一列）</div>
                  <p class="typed-result-note">金額欄為路徑分位數；P10、P50、P90 是路徑分布摘要，不是跨路徑加總。</p>
                  <el-table :data="selectedResult.reserveStrategyAudit.monthlyAudit" size="small" stripe max-height="460" class="typed-wide-table">
                    <el-table-column prop="date" label="完成月份" width="120" fixed="left" />
                    <el-table-column prop="bullSignalPathCount" label="牛訊號路徑數" width="130" />
                    <el-table-column prop="bearSignalPathCount" label="熊訊號路徑數" width="130" />
                    <el-table-column prop="bullFillPathCount" label="牛市成交批次路徑數" width="160" />
                    <el-table-column prop="bearFillPathCount" label="熊市成交批次路徑數" width="160" />
                    <el-table-column label="牛市售股金額 P10／P50／P90" min-width="260"><template #default="{ row }">{{ formatMoneyQuantiles(row.bullEquitySoldRealTwd) }}</template></el-table-column>
                    <el-table-column label="熊市現金投入 P10／P50／P90" min-width="260"><template #default="{ row }">{{ formatMoneyQuantiles(row.bearCashDeployedRealTwd) }}</template></el-table-column>
                    <el-table-column label="熊市售債金額 P10／P50／P90" min-width="260"><template #default="{ row }">{{ formatMoneyQuantiles(row.bearBondsSoldRealTwd) }}</template></el-table-column>
                    <el-table-column label="生活費回補 P10／P50／P90" min-width="260"><template #default="{ row }">{{ formatMoneyQuantiles(row.livingRefillRealTwd) }}</template></el-table-column>
                    <el-table-column label="生活費短缺 P10／P50／P90" min-width="260"><template #default="{ row }">{{ formatMoneyQuantiles(row.livingShortfallRealTwd) }}</template></el-table-column>
                  </el-table>
                </template>
              </template>
              <ul v-if="selectedApproximationNotes.length" class="result-limitations">
                <li v-for="(note, index) in selectedApproximationNotes" :key="`approx-${index}`">{{ note }}</li>
              </ul>
            </template>
          </section>
        </template>
        <el-alert v-else-if="selectedRun.status === 'SUCCEEDED'" type="warning" show-icon :closable="false"
          title="單筆回應未提供完整結果" description="此歷史摘要沒有可呈現的數值；請重新查詢單筆結果，畫面不會補造模型資料。" />
      </el-card>

      <el-card v-if="releases.length" shadow="never" class="center-section history-section">
        <template #header>
          <div class="section-heading">
            <div>
              <strong>發布請求與正式版本紀錄</strong>
              <span class="section-subtitle">待發布的請求不代表正式採用。</span>
            </div>
          </div>
        </template>
        <el-table :data="releases" size="small" stripe>
          <el-table-column label="狀態" width="150">
            <template #default="{ row }">
              <el-tag :type="releaseStatusType(row.status)" size="small" effect="plain">
                {{ releaseStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="建立時間" width="190">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="發布資料" width="180">
            <template #default="{ row }">
              <el-button text :icon="Download" :loading="downloadingReleaseId === row.id"
                @click="downloadPayload(row)">下載設定檔案</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { Delete, Download, DocumentChecked, Plus, Promotion, Refresh, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi, apiErrorMessage } from '@/api'
import { useAuthStore } from '@/stores/authStore'
import SrppShortTermOpportunities from '@/components/SrppShortTermOpportunities.vue'
import { canPublishSrppRun, hasSrppDraftChanges, isCurrentSrppRun } from '@/utils/srppSettingsCenterState'
import {
  formatMoneyText,
  formatPreciseDecimal,
  inspectWeightTotal,
  parseMoneyText,
  validatePensionOverridePairs,
  validateMoneyText,
  validateShortStrategyValues
} from '@/utils/srppSettingsCenterFormatting'

const auth = useAuthStore()
const ownerIdentity = computed(() => `${auth.effectiveUserId ?? ''}|${auth.me?.email || ''}`)
const loadingRoot = ref(false)
const rootData = ref(null)
const rootError = ref('')
const activeDraft = ref(null)
const selectedDraftOption = ref('')
const draftName = ref('SRPP 設定草稿')
const savedDraftName = ref('')
const values = ref({})
const savedValues = ref({})
const moneyInputValues = ref({})
const moneyInputMetadata = ref({})
const exceptionYearDrafts = ref({})
const exceptionMonthDrafts = ref({})
const savingDraft = ref(false)
const casConflict = ref(false)
const selectedRun = ref(null)
const selectedRunOption = ref('')
const loadingSelectedRun = ref(false)
const simulating = ref(false)
const simulationError = ref('')
const runMode = ref('LOCAL')
const seed = ref(20261114)
const simulationCount = ref(1000)
const publishing = ref(false)
const downloadingReleaseId = ref('')
const selectedRelease = ref(null)
const pathSide = ref('scenario')
const pollTimedOut = ref(false)
const pollError = ref('')

let pollTimer = null
let pollStartedAt = 0
let runRequestSequence = 0
let rootRequestSequence = 0
let draftRequestSequence = 0
let publishRequestSequence = 0
let downloadRequestSequence = 0
let disposed = false
let initialized = false

const catalog = computed(() => rootData.value?.catalog || null)
const fields = computed(() => Array.isArray(catalog.value?.fields) ? catalog.value.fields : [])
const drafts = computed(() => Array.isArray(rootData.value?.drafts) ? rootData.value.drafts : [])
const runs = computed(() => Array.isArray(rootData.value?.simulations) ? rootData.value.simulations : [])
const releases = computed(() => Array.isArray(rootData.value?.releases) ? rootData.value.releases : [])
const currentVersionLabel = computed(() => rootData.value?.current?.policyVersion || '版本資訊未提供')
const draftDirty = computed(() => hasSrppDraftChanges(values.value, savedValues.value) ||
  draftName.value !== savedDraftName.value || moneyInputErrorKeys.value.length > 0)
const draftBaseChanged = computed(() => {
  if (!activeDraft.value) return false
  if (!catalog.value || !activeDraft.value.values || typeof activeDraft.value.values !== 'object') return true
  return activeDraft.value.baseBundleSha256 !== catalog.value.baseBundleSha256 ||
    activeDraft.value.modelInputsSha256 !== catalog.value.modelInputsSha256 ||
    activeDraft.value.catalogSha256 !== catalog.value.catalogSha256 ||
    activeDraft.value.catalogVersion !== catalog.value.catalogVersion
})
const pairedOverrideErrors = computed(() => validatePensionOverridePairs(values.value))
const canCreateDraft = computed(() => !!catalog.value?.baseBundleSha256 && !!draftName.value.trim() &&
  !savingDraft.value && !simulating.value && !draftBaseChanged.value && !moneyInputErrorKeys.value.length &&
  !shortStrategyErrors.value.length && !pairedOverrideErrors.value.length)
const simulationProcessing = computed(() => selectedRun.value?.status === 'PROCESSING')
const fieldById = computed(() => new Map(fields.value.map(field => [field.id, field])))
const activeFieldGroup = ref('')
const runMatchesDraft = computed(() => isCurrentSrppRun(selectedRun.value, activeDraft.value))
const moneyInputErrorKeys = computed(() => Object.keys(moneyInputValues.value)
  .filter(key => !validateMoneyText(moneyInputValues.value[key], moneyInputMetadata.value[key] || {})))

const fieldGroups = computed(() => rootData.value?.fieldGroups || [])
const shortSettingIds = new Set([
  'shortEquitySymbols', 'shortEquityReboundPct', 'shortEquityNetRewardRisk', 'shortEquitySleeveCap',
  'shortEquityPositionCap', 'shortEquityMaxHoldingSessions', 'shortBondSymbols', 'shortBondReboundPct',
  'shortBondNetRewardRisk', 'shortBondSleeveCap', 'shortBondPositionCap', 'shortBondMaxHoldingSessions',
  'usTacticalNetUpsidePct', 'usTacticalBatchCap', 'usTacticalCompanyCap', 'usTacticalSleeveCap'
])
const shortTermFields = computed(() => fields.value.filter(field => shortSettingIds.has(field.id)))
const shortStrategyErrors = computed(() => validateShortStrategyValues(values.value,
  fields.value.filter(field => shortSettingIds.has(field.id))))
const visibleFieldGroups = computed(() => fieldGroups.value
  .map(group => ({ ...group, fields: (group.fields || []).filter(field => !shortSettingIds.has(field.id)) }))
  .filter(group => group.fields.length))
const draftIsCurrent = computed(() => !!activeDraft.value && !draftBaseChanged.value)
const draftIsSaved = computed(() => draftIsCurrent.value && !draftDirty.value)

const result = computed(() => selectedRun.value?.deterministicResult?.result || null)
const baseline = computed(() => result.value?.baseline || null)
const scenario = computed(() => result.value?.scenario || null)
const selectedResult = computed(() => pathSide.value === 'baseline' ? baseline.value : scenario.value)
const resultPresentation = computed(() => selectedRun.value?.presentation?.[pathSide.value] || null)
const selectedPaths = computed(() => (pathSide.value === 'baseline' ? baseline.value : scenario.value)?.percentilePaths || [])
const cashflowSchedule = computed(() => selectedResult.value?.assumptions?.cashflowSchedule || null)
const cashflowScheduleRows = computed(() => buildCashflowScheduleRows(selectedResult.value))
const payrollScheduleRows = computed(() => buildPayrollScheduleRows(cashflowSchedule.value?.payroll))
const reserveStrategyRows = computed(() => buildReserveStrategyRows(selectedResult.value?.assumptions?.reserveStrategy))
const reserveAuditRows = computed(() => buildReserveAuditRows(selectedResult.value?.reserveStrategyAudit))
const selectedApproximationNotes = computed(() => selectedResult.value?.assumptions?.approximationNotes || [])
const runChanges = computed(() => {
  if (Array.isArray(selectedRun.value?.changes)) return selectedRun.value.changes
  return Array.isArray(result.value?.changes) ? result.value.changes : []
})
const publishBlocks = computed(() => {
  const runReasons = selectedRun.value?.blockedReasons
  const items = Array.isArray(runReasons) && runReasons.length ? runReasons : activeDraft.value?.blockedReasons || []
  return Array.isArray(items) ? items : []
})
const canPublishNow = computed(() => canPublishSrppRun({
  isAdmin: auth.isAdmin,
  canPublish: rootData.value?.canPublish,
  draft: activeDraft.value,
  run: selectedRun.value,
  dirty: draftDirty.value || casConflict.value,
  publishing: publishing.value
}))

const resultComparisonRows = computed(() => {
  if (!baseline.value || !scenario.value) return []
  const differences = result.value?.differences || {}
  const rows = [{
    label: '給定假設下的存續率',
    baseline: baseline.value.successProbability,
    scenario: scenario.value.successProbability,
    difference: differences.successProbability,
    kind: 'probability'
  }, {
    label: '存續率 95% 信賴區間',
    baseline: baseline.value.successProbabilityCi95,
    scenario: scenario.value.successProbabilityCi95,
    difference: null,
    kind: 'interval'
  }]
  for (const quantile of ['p10', 'p50', 'p90']) {
    rows.push({
      label: `退休資產 ${quantile.toUpperCase()}`,
      baseline: baseline.value.retirementAssets?.[quantile],
      scenario: scenario.value.retirementAssets?.[quantile],
      difference: quantile === 'p50' ? differences.retirementAssetsP50 : null,
      kind: 'money'
    })
  }
  for (const quantile of ['p10', 'p50', 'p90']) {
    rows.push({
      label: `規劃終點資產 ${quantile.toUpperCase()}`,
      baseline: baseline.value.terminalAssets?.[quantile],
      scenario: scenario.value.terminalAssets?.[quantile],
      difference: quantile === 'p50' ? differences.terminalAssetsP50 : null,
      kind: 'money'
    })
  }
  for (const quantile of ['p10', 'p50', 'p90']) {
    rows.push({
      label: `資金缺口年齡 ${quantile.toUpperCase()}`,
      baseline: baseline.value.depletionAge === null ? null : baseline.value.depletionAge?.[quantile],
      scenario: scenario.value.depletionAge === null ? null : scenario.value.depletionAge?.[quantile],
      difference: null,
      kind: 'age'
    })
  }
  return rows
})

const visibleAssumptions = computed(() => resultPresentation.value?.assumptions || [])

watch(() => visibleFieldGroups.value.map(group => group.name).join('|'), () => {
  if (!visibleFieldGroups.value.some(group => group.name === activeFieldGroup.value)) {
    activeFieldGroup.value = visibleFieldGroups.value[0]?.name || ''
  }
})
watch(() => selectedRun.value?.status, status => {
  if (status === 'PROCESSING') startPolling()
  else stopPolling()
})
watch(ownerIdentity, (next, previous) => {
  if (previous == null || next === previous) return
  rootRequestSequence += 1
  runRequestSequence += 1
  draftRequestSequence += 1
  publishRequestSequence += 1
  downloadRequestSequence += 1
  stopPolling()
  initialized = false
  rootData.value = null
  rootError.value = ''
  activeDraft.value = null
  selectedDraftOption.value = ''
  draftName.value = 'SRPP 設定草稿'
  savedDraftName.value = draftName.value
  values.value = {}
  savedValues.value = {}
  clearLocalEditors()
  selectedRun.value = null
  selectedRunOption.value = ''
  loadingSelectedRun.value = false
  simulating.value = false
  simulationError.value = ''
  pollError.value = ''
  pollTimedOut.value = false
  selectedRelease.value = null
  savingDraft.value = false
  publishing.value = false
  downloadingReleaseId.value = ''
  casConflict.value = false
  void loadRoot()
})

function copyJson(value) {
  if (value == null) return value
  return JSON.parse(JSON.stringify(value))
}

function setFieldValue(fieldId, rawValue) {
  const field = fieldById.value.get(fieldId)
  if (field?.type === 'date') {
    values.value = { ...values.value, [fieldId]: rawValue || null }
    return
  }
  if (rawValue == null || rawValue === '') {
    values.value = { ...values.value, [fieldId]: null }
    return
  }
  if (typeof rawValue === 'number' && !isFractionField(field)) {
    values.value = { ...values.value, [fieldId]: Number.isFinite(rawValue) ? rawValue : null }
    return
  }
  const displayedValue = Number(rawValue)
  const storedValue = Number.isFinite(displayedValue)
    ? (isFractionField(field) ? displayedValue / 100 : displayedValue)
    : null
  values.value = { ...values.value, [fieldId]: storedValue }
}

function clearLocalEditors() {
  moneyInputValues.value = {}
  moneyInputMetadata.value = {}
  exceptionYearDrafts.value = {}
  exceptionMonthDrafts.value = {}
}

function fieldDisabled(field) {
  return !field?.editable || savingDraft.value || draftBaseChanged.value
}

const MONEY_FIELD_IDS = new Set([
  'livingExpense', 'laterLivingExpense', 'careExpense', 'monthlySalary', 'annualRaiseAmount',
  'preRetirementMonthlyExpense', 'laborPensionReceiptAmount', 'laborInsuranceMonthlyAmount',
  'homeRepairCost', 'applianceCost', 'emergencyDepositReserve', 'emergencyEtfReserve',
  'livingReserveCeiling', 'livingReserveFloor', 'bearLivingDeploymentCap', 'marketSpendingAdjustment'
])

function isMoneyField(field) {
  return field?.displayFormat === 'money' || MONEY_FIELD_IDS.has(field?.id) ||
    /(?:新臺幣|名目元|實質元|TWD|NTD|貨幣)/i.test(String(field?.unit || ''))
}

function fieldMoneyKey(field) {
  return `field:${field.id}`
}

function arrayMoneyKey(fieldId, index, property) {
  return `array:${fieldId}:${index}:${property}`
}

function fieldMoneyValue(field) {
  const key = fieldMoneyKey(field)
  return Object.hasOwn(moneyInputValues.value, key)
    ? moneyInputValues.value[key]
    : formatMoneyText(values.value[field.id])
}

function arrayMoneyValue(fieldId, index, property, value) {
  const key = arrayMoneyKey(fieldId, index, property)
  return Object.hasOwn(moneyInputValues.value, key) ? moneyInputValues.value[key] : formatMoneyText(value)
}

function setMoneyBuffer(key, rawValue, metadata, applyValue) {
  const text = String(rawValue ?? '')
  moneyInputValues.value = { ...moneyInputValues.value, [key]: text }
  moneyInputMetadata.value = { ...moneyInputMetadata.value, [key]: metadata }
  const parsed = parseMoneyText(text)
  if (validateMoneyText(text, metadata) && parsed.complete) applyValue(parsed.value)
}

function rawMoneyText(value) {
  if (value == null || value === '') return ''
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : String(value).replaceAll(',', '')
}

function focusFieldMoney(field) {
  const key = fieldMoneyKey(field)
  const metadata = { min: field.min, max: field.max, integer: field.type === 'integer', nullable: field.nullable === true }
  moneyInputValues.value = { ...moneyInputValues.value, [key]: rawMoneyText(values.value[field.id]) }
  moneyInputMetadata.value = { ...moneyInputMetadata.value, [key]: metadata }
}

function focusArrayMoney(fieldId, index, property, value) {
  const key = arrayMoneyKey(fieldId, index, property)
  moneyInputValues.value = { ...moneyInputValues.value, [key]: rawMoneyText(value) }
  moneyInputMetadata.value = { ...moneyInputMetadata.value, [key]: { min: 0, max: 100000000, nullable: false } }
}

function finishMoneyBuffer(key, metadata, getCurrentValue, applyValue) {
  const rawValue = moneyInputValues.value[key]
  if (rawValue == null) return
  const parsed = parseMoneyText(rawValue)
  if (validateMoneyText(rawValue, metadata) && parsed.complete) {
    applyValue(parsed.value)
    moneyInputValues.value = { ...moneyInputValues.value, [key]: formatMoneyText(parsed.value) }
  } else {
    moneyInputValues.value = { ...moneyInputValues.value, [key]: formatMoneyText(getCurrentValue()) }
    ElMessage.warning('金額格式或範圍不正確，已保留原設定值。')
  }
  const nextMeta = { ...moneyInputMetadata.value }
  delete nextMeta[key]
  moneyInputMetadata.value = nextMeta
}

function moneyInputInvalid(key) {
  return Object.hasOwn(moneyInputValues.value, key) &&
    !validateMoneyText(moneyInputValues.value[key], moneyInputMetadata.value[key] || {})
}

function updateFieldMoney(field, rawValue) {
  const metadata = {
    min: field.min,
    max: field.max,
    integer: field.type === 'integer',
    nullable: field.nullable === true
  }
  setMoneyBuffer(fieldMoneyKey(field), rawValue, metadata, value => {
    values.value = { ...values.value, [field.id]: value }
  })
}

function commitFieldMoney(field) {
  const key = fieldMoneyKey(field)
  finishMoneyBuffer(key, {
    min: field.min,
    max: field.max,
    integer: field.type === 'integer',
    nullable: field.nullable === true
  }, () => values.value[field.id], value => {
    values.value = { ...values.value, [field.id]: value }
  })
}

function updateArrayMoney(fieldId, index, property, rawValue) {
  setMoneyBuffer(arrayMoneyKey(fieldId, index, property), rawValue,
    { min: 0, max: 100000000, nullable: false }, value => updateArrayField(fieldId, index, property, value))
}

function commitArrayMoney(fieldId, index, property) {
  finishMoneyBuffer(arrayMoneyKey(fieldId, index, property), { min: 0, max: 100000000, nullable: false },
    () => arrayFieldValue({ id: fieldId })[index]?.[property],
    value => updateArrayField(fieldId, index, property, value))
}

function setBooleanValue(fieldId, value) {
  if (typeof value !== 'boolean') return
  values.value = { ...values.value, [fieldId]: value }
}

function updateShortSetting(fieldId, value) {
  const field = fieldById.value.get(fieldId)
  if (!field || !shortSettingIds.has(fieldId) || !field.editable || draftBaseChanged.value || savingDraft.value) return
  if (field.type === 'symbols') {
    const allowed = Array.isArray(field.allowedSymbols) ? new Set(field.allowedSymbols) : new Set()
    if (!Array.isArray(value) || value.some(symbol => typeof symbol !== 'string' || !symbol.trim() || !allowed.has(symbol)) ||
      new Set(value).size !== value.length) return
    values.value = { ...values.value, [fieldId]: [...value] }
    return
  }
  if (field.type !== 'number' && field.type !== 'integer') return
  if (value == null) {
    values.value = { ...values.value, [fieldId]: null }
    return
  }
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || (field.type === 'integer' && !Number.isInteger(numeric)) ||
    field.min != null && numeric < field.min || field.max != null && numeric > field.max) return
  values.value = { ...values.value, [fieldId]: numeric }
}

function arrayFieldValue(field) {
  const list = values.value[field?.id]
  return Array.isArray(list) ? list : []
}

function updateArrayField(fieldId, index, property, value) {
  const list = Array.isArray(values.value[fieldId]) ? [...values.value[fieldId]] : []
  if (!list[index] || typeof list[index] !== 'object' || Array.isArray(list[index])) return
  list[index] = { ...list[index], [property]: value }
  values.value = { ...values.value, [fieldId]: list }
}

function updateOptionalArrayField(fieldId, index, property, value) {
  const list = Array.isArray(values.value[fieldId]) ? [...values.value[fieldId]] : []
  if (!list[index] || typeof list[index] !== 'object' || Array.isArray(list[index])) return
  const next = { ...list[index] }
  if (value == null || value === '') delete next[property]
  else next[property] = value
  list[index] = next
  values.value = { ...values.value, [fieldId]: list }
}

function addArrayRow(field) {
  const type = field?.type
  const list = Array.isArray(values.value[field.id]) ? [...values.value[field.id]] : []
  if (type === 'events' && list.length < 100) list.push({ date: '', name: '', amount: null })
  else if (type === 'datedBonuses' && list.length < 100) list.push({ name: '', pay_date: '', months: 0 })
  else if (type === 'recurringBonuses' && list.length < 32) list.push({ name: '', months: 0, month: 1 })
  else return
  values.value = { ...values.value, [field.id]: list }
}

function clearArrayEditorBuffers(fieldId) {
  const prefix = `array:${fieldId}:`
  const nextValues = { ...moneyInputValues.value }
  const nextMetadata = { ...moneyInputMetadata.value }
  for (const key of Object.keys(nextValues)) if (key.startsWith(prefix)) delete nextValues[key]
  for (const key of Object.keys(nextMetadata)) if (key.startsWith(prefix)) delete nextMetadata[key]
  moneyInputValues.value = nextValues
  moneyInputMetadata.value = nextMetadata
}

function removeArrayRow(fieldId, index) {
  const list = Array.isArray(values.value[fieldId]) ? [...values.value[fieldId]] : []
  list.splice(index, 1)
  values.value = { ...values.value, [fieldId]: list }
  clearArrayEditorBuffers(fieldId)
  const exceptionKeyPrefix = `${fieldId}:`
  exceptionYearDrafts.value = Object.fromEntries(Object.entries(exceptionYearDrafts.value).filter(([key]) => !key.startsWith(exceptionKeyPrefix)))
  exceptionMonthDrafts.value = Object.fromEntries(Object.entries(exceptionMonthDrafts.value).filter(([key]) => !key.startsWith(exceptionKeyPrefix)))
}

function sortedExceptionYears(row) {
  return Object.keys(row?.month_by_year || {}).sort((left, right) => Number(left) - Number(right))
}

function setMonthException(fieldId, index, year, month) {
  const row = arrayFieldValue({ id: fieldId })[index]
  if (!row || !Number.isInteger(Number(month)) || Number(month) < 1 || Number(month) > 12) return
  const exceptions = { ...(row.month_by_year || {}), [String(year)]: Number(month) }
  updateArrayField(fieldId, index, 'month_by_year', exceptions)
}

function removeMonthException(fieldId, index, year) {
  const row = arrayFieldValue({ id: fieldId })[index]
  if (!row || !row.month_by_year || !Object.hasOwn(row.month_by_year, String(year))) return
  const exceptions = { ...row.month_by_year }
  delete exceptions[String(year)]
  updateArrayField(fieldId, index, 'month_by_year', exceptions)
}

function exceptionDraftKey(fieldId, index) {
  return `${fieldId}:${index}`
}

function canAddMonthException(field, index) {
  const key = exceptionDraftKey(field.id, index)
  const year = Number(exceptionYearDrafts.value[key])
  const month = Number(exceptionMonthDrafts.value[key] ?? 1)
  const row = arrayFieldValue(field)[index]
  return Number.isInteger(year) && year >= 1900 && year <= 2200 &&
    Number.isInteger(month) && month >= 1 && month <= 12 &&
    !Object.hasOwn(row?.month_by_year || {}, String(year))
}

function addMonthException(fieldId, index) {
  if (!canAddMonthException({ id: fieldId }, index)) return
  const key = exceptionDraftKey(fieldId, index)
  setMonthException(fieldId, index, exceptionYearDrafts.value[key], exceptionMonthDrafts.value[key] ?? 1)
  exceptionYearDrafts.value = { ...exceptionYearDrafts.value, [key]: undefined }
}

function weightTotal(field) {
  return inspectWeightTotal(values.value[field.id], field.assetKeys)
}

function weightTotalLabel(field) {
  const result = weightTotal(field)
  return result.totalPercent == null ? '—' : `${formatPreciseDecimal(result.totalPercent)}%`
}

function weightTotalMessage(field) {
  const result = weightTotal(field)
  if (result.status === 'valid') return '配置合計符合 100%'
  if (result.status === 'incomplete') return '配置尚未填寫完整'
  if (result.status === 'invalid') return '比例不合法'
  const points = formatPreciseDecimal(result.differencePoints)
  return result.status === 'under' ? `尚差${points}個百分點` : `超出${points}個百分點`
}

function setWeightValue(fieldId, assetKey, rawValue) {
  const next = { ...(values.value[fieldId] || {}) }
  if (rawValue == null || rawValue === '') next[assetKey] = null
  else {
    const displayedValue = Number(rawValue)
    next[assetKey] = Number.isFinite(displayedValue) ? displayedValue / 100 : null
  }
  values.value = { ...values.value, [fieldId]: next }
}

function isFractionField(field) {
  const unit = String(field?.unit || '').toLowerCase()
  return unit === 'fraction' || unit === 'ratio' || unit === '比例'
}

function fractionInputValue(value) {
  if (value == null || value === '') return null
  const fraction = Number(value)
  return Number.isFinite(fraction) ? fraction * 100 : null
}

function weightInputDisplayValue(value) {
  const percent = fractionInputValue(value)
  return percent == null ? '' : formatPreciseDecimal(percent)
}

function displayBound(value, field) {
  return value == null ? undefined : isFractionField(field) ? fractionInputValue(value) : value
}

function formatBound(value, field) {
  if (value == null) return '不限'
  if (isFractionField(field)) return formatPreciseDecimal(fractionInputValue(value))
  return isMoneyField(field) ? formatMoneyText(value) : String(value)
}

function fieldReason(field) {
  if (typeof field?.reason !== 'string') return ''
  if (field.reason.includes('正式跨來源編譯')) return '目前僅供情境模擬，尚未列入正式設定。'
  return field.reason
}

function fieldInputId(field) {
  return `srpp-field-${field.id}`
}

function weightInputId(field, assetKey) {
  return `srpp-field-${field.id}-${assetKey}`
}

function draftOptionLabel(draft) {
  const updated = draft.updatedAt ? ` · ${formatTime(draft.updatedAt)}` : ''
  return `${draft.name || '未命名草稿'} · 版本 ${draft.revision}${updated}`
}

function runOptionLabel(runItem) {
  const mode = runItem.mode === 'AI' ? '呼叫 AI 解讀' : '不呼叫 AI'
  const status = runStatusLabel(runItem.status)
  const at = runItem.createdAt ? ` · ${formatTime(runItem.createdAt)}` : ''
  return `${mode} · ${status}${at}`
}

function setActiveDraft(draft) {
  const previousDraftId = activeDraft.value?.id
  if (previousDraftId !== draft?.id) {
    runRequestSequence += 1
    stopPolling()
  }
  activeDraft.value = copyJson(draft)
  clearLocalEditors()
  selectedDraftOption.value = draft?.id || ''
  draftName.value = draft?.name || 'SRPP 設定草稿'
  savedDraftName.value = draftName.value
  values.value = draft?.values && typeof draft.values === 'object' ? copyJson(draft.values) : {}
  savedValues.value = copyJson(values.value)
  casConflict.value = false
  if (selectedRun.value && selectedRun.value.draftId !== draft?.id) {
    selectedRun.value = null
    selectedRunOption.value = ''
  }
}

function addDraftToRoot(draft) {
  if (!rootData.value || !draft?.id) return
  const existing = drafts.value.filter(item => item.id !== draft.id)
  rootData.value = { ...rootData.value, drafts: [draft, ...existing] }
}

function updateRunInRoot(run) {
  if (!rootData.value || !run?.id) return
  const summary = { ...run, deterministicResult: null, presentation: null }
  const existing = runs.value.filter(item => item.id !== run.id)
  rootData.value = { ...rootData.value, simulations: [summary, ...existing].slice(0, 20) }
}

function setActiveRun(run) {
  const previousId = selectedRun.value?.id
  const previousStatus = selectedRun.value?.status
  selectedRun.value = copyJson(run)
  selectedRunOption.value = run?.id || ''
  loadingSelectedRun.value = false
  simulationError.value = ''
  pollError.value = ''
  pollTimedOut.value = false
  pathSide.value = 'scenario'
  if (run?.status === 'PROCESSING') {
    if (run.id !== previousId || previousStatus !== 'PROCESSING' || !pollStartedAt) pollStartedAt = Date.now()
    startPolling()
  }
}

async function loadRoot() {
  const requestSequence = ++rootRequestSequence
  const ownerAtRequest = ownerIdentity.value
  loadingRoot.value = true
  rootError.value = ''
  try {
    const response = await bffApi.portfolioAdvice.srpp.root()
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== rootRequestSequence) return
    if (!response || typeof response !== 'object' || !response.catalog || !Array.isArray(response.catalog.fields) || !Array.isArray(response.fieldGroups) ||
      !response.catalog.currentValues || typeof response.catalog.currentValues !== 'object') {
      throw new Error('SRPP 設定目錄回應格式不完整，已停止載入編輯器。')
    }
    rootData.value = response
    if (!initialized) {
      const currentValues = response.catalog.currentValues
      values.value = copyJson(currentValues && typeof currentValues === 'object' ? currentValues : {})
      savedValues.value = copyJson(values.value)
      clearLocalEditors()
      savedDraftName.value = draftName.value
      activeFieldGroup.value = visibleFieldGroups.value[0]?.name || ''
      initialized = true
      const selected = (response.simulations || []).find(item => item?.status === 'PROCESSING') || response.simulations?.[0]
      if (selected?.id) await selectRun(selected.id)
    } else if (selectedRun.value) {
      const latestSummary = (response.simulations || []).find(item => item?.id === selectedRun.value.id)
      if (latestSummary?.status === 'PROCESSING') startPolling()
    }
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== rootRequestSequence) return
    rootError.value = apiErrorMessage(error, '無法讀取 SRPP 設定目錄。')
  } finally {
    if (ownerAtRequest === ownerIdentity.value && requestSequence === rootRequestSequence) loadingRoot.value = false
  }
}

async function selectDraft(id) {
  if (!id) {
    runRequestSequence += 1
    stopPolling()
    activeDraft.value = null
    selectedDraftOption.value = ''
    draftName.value = 'SRPP 設定草稿'
    savedDraftName.value = draftName.value
    values.value = copyJson(catalog.value?.currentValues || {})
    savedValues.value = copyJson(values.value)
    clearLocalEditors()
    casConflict.value = false
    if (selectedRun.value) {
      selectedRun.value = null
      selectedRunOption.value = ''
    }
    return
  }
  const draft = drafts.value.find(item => item.id === id)
  if (!draft) {
    selectedDraftOption.value = activeDraft.value?.id || ''
    ElMessage.warning('找不到此草稿，請重新整理後再試。')
    return
  }
  setActiveDraft(draft)
}

async function createDraft() {
  if (draftBaseChanged.value) return
  if (pairedOverrideErrors.value.length) {
    ElMessage.warning(pairedOverrideErrors.value[0])
    return
  }
  if (moneyInputErrorKeys.value.length) {
    ElMessage.warning('請先完成或修正所有金額欄位。')
    return
  }
  if (shortStrategyErrors.value.length) {
    ElMessage.warning('請修正短線策略的白名單、欄位範圍或配置上限。')
    return
  }
  const name = draftName.value.trim()
  if (!name) {
    ElMessage.warning('請先填寫草稿名稱。')
    return
  }
  savingDraft.value = true
  casConflict.value = false
  const ownerAtRequest = ownerIdentity.value
  const requestSequence = ++draftRequestSequence
  try {
    const created = await bffApi.portfolioAdvice.srpp.createDraft({
      name,
      baseBundleSha256: catalog.value.baseBundleSha256,
      values: copyJson(values.value)
    })
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== draftRequestSequence) return
    addDraftToRoot(created)
    setActiveDraft(created)
    ElMessage.success('已建立 SRPP 設定草稿。')
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== draftRequestSequence) return
    ElMessage.error(apiErrorMessage(error, '建立草稿失敗。'))
  } finally {
    if (requestSequence === draftRequestSequence) savingDraft.value = false
  }
}

async function saveDraft() {
  if (!activeDraft.value || !draftDirty.value || draftBaseChanged.value) return
  if (pairedOverrideErrors.value.length) {
    ElMessage.warning(pairedOverrideErrors.value[0])
    return
  }
  if (moneyInputErrorKeys.value.length) {
    ElMessage.warning('請先完成或修正所有金額欄位。')
    return
  }
  if (shortStrategyErrors.value.length) {
    ElMessage.warning('請修正短線策略的白名單、欄位範圍或配置上限。')
    return
  }
  const name = draftName.value.trim()
  if (!name) {
    ElMessage.warning('請先填寫草稿名稱。')
    return
  }
  savingDraft.value = true
  casConflict.value = false
  const ownerAtRequest = ownerIdentity.value
  const requestSequence = ++draftRequestSequence
  try {
    const saved = await bffApi.portfolioAdvice.srpp.updateDraft(activeDraft.value.id, {
      revision: activeDraft.value.revision,
      name,
      values: copyJson(values.value)
    })
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== draftRequestSequence) return
    addDraftToRoot(saved)
    setActiveDraft(saved)
    ElMessage.success(`草稿已儲存為版本 ${saved.revision}。`)
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== draftRequestSequence) return
    if (error?.response?.status === 409) {
      casConflict.value = true
      ElMessage.warning('草稿版本已過期，請重新載入伺服器版本；目前修改未被覆蓋。')
    } else {
      ElMessage.error(apiErrorMessage(error, '儲存草稿失敗。'))
    }
  } finally {
    if (requestSequence === draftRequestSequence) savingDraft.value = false
  }
}

function resetDraft() {
  clearLocalEditors()
  if (activeDraft.value) {
    values.value = copyJson(savedValues.value)
    draftName.value = savedDraftName.value
  } else {
    values.value = copyJson(catalog.value?.currentValues || {})
    draftName.value = 'SRPP 設定草稿'
    savedDraftName.value = draftName.value
    savedValues.value = copyJson(values.value)
  }
  casConflict.value = false
}

function startCurrentBaseDraft() {
  const currentValues = catalog.value?.currentValues
  if (!currentValues || typeof currentValues !== 'object') {
    ElMessage.warning('目前正式版本設定尚未載入，請重新整理後再試。')
    return
  }
  runRequestSequence += 1
  stopPolling()
  activeDraft.value = null
  selectedDraftOption.value = ''
  selectedRun.value = null
  selectedRunOption.value = ''
  simulationError.value = ''
  values.value = copyJson(currentValues)
  savedValues.value = copyJson(currentValues)
  clearLocalEditors()
  draftName.value = 'SRPP 設定草稿'
  savedDraftName.value = draftName.value
  casConflict.value = false
  ElMessage.success('已載入目前正式版本設定；修改後請另存新草稿。')
}

async function reloadConflictedDraft() {
  const id = activeDraft.value?.id
  if (!id) return
  const ownerAtRequest = ownerIdentity.value
  await loadRoot()
  if (ownerAtRequest !== ownerIdentity.value) return
  if (rootError.value) {
    ElMessage.warning('目前無法載入最新草稿，請重新整理後再試。')
    return
  }
  const latest = drafts.value.find(item => item.id === id)
  if (!latest) {
    ElMessage.warning('目前已無此草稿；畫面修改仍保留，請另存新草稿。')
    return
  }
  setActiveDraft(latest)
  ElMessage.success(`已載入最新草稿版本 ${latest.revision}。`)
}

async function simulate() {
  if (!activeDraft.value || draftDirty.value || draftBaseChanged.value || simulationProcessing.value) return
  if (pairedOverrideErrors.value.length) {
    ElMessage.warning(pairedOverrideErrors.value[0])
    return
  }
  if (moneyInputErrorKeys.value.length) {
    ElMessage.warning('請先完成或修正所有金額欄位。')
    return
  }
  if (shortStrategyErrors.value.length) {
    ElMessage.warning('請修正短線策略的白名單、欄位範圍或配置上限。')
    return
  }
  runRequestSequence += 1
  const requestSequence = runRequestSequence
  const ownerAtRequest = ownerIdentity.value
  stopPolling()
  simulationError.value = ''
  pollTimedOut.value = false
  simulating.value = true
  try {
    const run = await bffApi.portfolioAdvice.srpp.simulateDraft(activeDraft.value.id, {
      revision: activeDraft.value.revision,
      mode: runMode.value,
      seed: Number(seed.value),
      simulations: Number(simulationCount.value)
    })
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    setActiveRun(run)
    updateRunInRoot(run)
    if (run.status === 'PROCESSING') ElMessage.success('模擬已送出，完成後畫面會自動更新。')
    else if (run.status === 'SUCCEEDED') ElMessage.success('情境模擬已完成。')
    else if (run.status === 'FAILED') ElMessage.warning('情境模擬未完成，請稍後重新執行。')
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    if (error?.response?.status === 409) {
      simulationError.value = apiErrorMessage(error, '草稿版本已變更，請重新載入後再模擬。')
    } else {
      simulationError.value = apiErrorMessage(error, '情境模擬請求失敗。')
    }
  } finally {
    if (ownerAtRequest === ownerIdentity.value && requestSequence === runRequestSequence) simulating.value = false
  }
}

async function selectRun(id) {
  if (!id) {
    runRequestSequence += 1
    stopPolling()
    selectedRun.value = null
    selectedRunOption.value = ''
    loadingSelectedRun.value = false
    return
  }
  runRequestSequence += 1
  const requestSequence = runRequestSequence
  const ownerAtRequest = ownerIdentity.value
  stopPolling()
  selectedRunOption.value = id
  selectedRun.value = null
  loadingSelectedRun.value = true
  simulationError.value = ''
  pollStartedAt = Date.now()
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(id)
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    if (!run || run.id !== id || (run.status === 'SUCCEEDED' && !run.deterministicResult)) {
      throw new Error('單筆模擬回應未包含完整結果。')
    }
    setActiveRun(run)
    updateRunInRoot(run)
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    simulationError.value = apiErrorMessage(error, '無法載入這筆模擬。')
    selectedRun.value = null
    loadingSelectedRun.value = false
  }
}

function startPolling() {
  if (disposed || pollTimer || selectedRun.value?.status !== 'PROCESSING') return
  if (!pollStartedAt) pollStartedAt = Date.now()
  pollTimer = setTimeout(pollSelectedRun, 2000)
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

async function pollSelectedRun() {
  pollTimer = null
  if (disposed || selectedRun.value?.status !== 'PROCESSING') return
  const runId = selectedRun.value.id
  const requestSequence = runRequestSequence
  const ownerAtRequest = ownerIdentity.value
  if (Date.now() - pollStartedAt >= 180000) {
    pollTimedOut.value = true
    return
  }
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(runId)
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence || selectedRun.value?.id !== runId) return
    setActiveRun(run)
    updateRunInRoot(run)
    pollError.value = ''
    if (run.status === 'PROCESSING') startPolling()
    else await loadRoot()
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    pollError.value = apiErrorMessage(error, '暫時無法更新模擬狀態。')
    if (!disposed && selectedRun.value?.status === 'PROCESSING') startPolling()
  }
}

async function refreshSelectedRun() {
  if (!selectedRun.value?.id) return
  runRequestSequence += 1
  const requestSequence = runRequestSequence
  const ownerAtRequest = ownerIdentity.value
  const runId = selectedRun.value.id
  pollTimedOut.value = false
  pollError.value = ''
  pollStartedAt = Date.now()
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(runId)
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence || selectedRun.value?.id !== runId) return
    setActiveRun(run)
    updateRunInRoot(run)
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== runRequestSequence) return
    pollError.value = apiErrorMessage(error, '重新查詢模擬失敗。')
  }
}

async function publish() {
  if (!canPublishNow.value || !activeDraft.value || !selectedRun.value) return
  const ownerAtRequest = ownerIdentity.value
  const requestSequence = ++publishRequestSequence
  publishing.value = true
  try {
    const created = await bffApi.portfolioAdvice.srpp.publishDraft(activeDraft.value.id, {
      revision: activeDraft.value.revision,
      simulationId: selectedRun.value.id
    })
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== publishRequestSequence) return
    selectedRelease.value = created
    rootData.value = {
      ...rootData.value,
      releases: [created, ...releases.value.filter(item => item.id !== created.id)].slice(0, 20)
    }
    ElMessage.success('已建立待發布請求；正式版本尚未切換。')
    await loadRoot()
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== publishRequestSequence) return
    const refreshed = releases.value.find(item => item.id === created.id)
    if (refreshed) selectedRelease.value = refreshed
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== publishRequestSequence) return
    ElMessage.error(apiErrorMessage(error, '無法建立待發布請求。'))
  } finally {
    if (ownerAtRequest === ownerIdentity.value && requestSequence === publishRequestSequence) publishing.value = false
  }
}

async function downloadPayload(release) {
  if (!release?.id) return
  const ownerAtRequest = ownerIdentity.value
  const requestSequence = ++downloadRequestSequence
  downloadingReleaseId.value = release.id
  try {
    const payload = await bffApi.portfolioAdvice.srpp.releasePayload(release.id)
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== downloadRequestSequence) return
    const blob = payload instanceof Blob
      ? payload
      : new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'SRPP-設定發布請求.json'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    if (disposed || ownerAtRequest !== ownerIdentity.value || requestSequence !== downloadRequestSequence) return
    ElMessage.error(apiErrorMessage(error, '下載發布設定檔案失敗。'))
  } finally {
    if (ownerAtRequest === ownerIdentity.value && requestSequence === downloadRequestSequence) downloadingReleaseId.value = ''
  }
}

function formatMoney(value) {
  return formatCurrency(value)
}

function formatCurrency(value) {
  if (value == null || value === '') return '—'
  const text = formatMoneyText(value, { minimumFractionDigits: 2 })
  return text ? `NT$ ${text}` : '—'
}

function formatFraction(value) {
  if (value == null || typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return `${formatPreciseDecimal(value * 100)}%`
}

function formatEffectiveTaxRate(value) {
  if (value == null || typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return `${new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 2 }).format(value * 100)}%`
}

function formatMonths(value) {
  if (value == null || typeof value !== 'number' || !Number.isFinite(value)) return '—'
  return formatPreciseDecimal(value, 4)
}

function formatMonthExceptions(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '無'
  const entries = Object.entries(value).sort(([left], [right]) => Number(left) - Number(right))
  return entries.length ? entries.map(([year, month]) => `${year} 年 ${month} 月`).join('、') : '無'
}

function formatLedgerBonuses(value) {
  if (!Array.isArray(value) || !value.length) return '無'
  return value.map(item => `${item.name} × ${formatMonths(item.months)} 月薪（${formatCurrency(item.amountNominalTwd)}）${item.festivalDate ? `，節日 ${item.festivalDate}` : ''}`).join('；')
}

function formatMoneyQuantiles(value) {
  if (!value || typeof value !== 'object') return '—'
  return ['p10', 'p50', 'p90'].map(key => `${key.toUpperCase()} ${formatCurrency(value[key])}`).join('；')
}

function formatScheduleValue(value, kind = 'text') {
  if (value == null || value === '') return '—'
  if (kind === 'money') return formatCurrency(value)
  if (kind === 'fraction') return formatFraction(value)
  if (kind === 'boolean') return value === true ? '是' : value === false ? '否' : '—'
  if (kind === 'mode') return value === 'MANUAL' ? '手動輸入' : value === 'AUTO' ? '依目前模型自動估算' : String(value)
  if (kind === 'indexation') {
    if (value === 'REFERENCE_INFLATION_HISTORY_PROXY') return '固定基準通膨歷史價格代理；非真實官方歷史 CPI 或核定給付'
    if (value === 'SCENARIO_CPI') return '依本輪情境 CPI'
  }
  if (kind === 'status') return exclusionReasonLabel(value)
  if (kind === 'integer' || kind === 'months') return String(value)
  return String(value)
}

function incomeTaxScopeLabel(value) {
  if (value === 'SALARY_ONLY_SINGLE_RESIDENT_PLANNING') return '本人薪資與獎金的單身居民規劃（非完整全戶申報）'
  return value == null || value === '' ? '—' : String(value)
}

function incomeTaxFuturePolicyLabel(value) {
  if (value === 'CARRY_115_UNTIL_OFFICIAL') return '沿用115年度級距作代理，直到官方公布新年度級距；不代表未來法規已公告'
  return value == null || value === '' ? '—' : String(value)
}

function incomeTaxHistoricalBasisLabel(value) {
  if (value === 'SOURCE_PAYROLL_PROJECTION_NOT_VERIFIED_SLIPS') return '依來源薪資資料回推的規劃值，未核實薪資扣繳憑單'
  return value == null || value === '' ? '—' : String(value)
}

function incomeTaxTreatmentLabel(value) {
  if (value === 'SETTLED_BEFORE_AS_OF') return '估值日前已結算'
  if (value === 'CASH_SETTLEMENT') return '按結算日補退'
  if (value === 'TERMINAL_ACCRUAL') return '期末應補稅負債'
  return value == null || value === '' ? '—' : String(value)
}

function exclusionReasonLabel(value) {
  if (value == null || value === '') return '—'
  return ({
    AT_OR_BEFORE_AS_OF: '估值日當日或以前，視為已反映',
    AFTER_HORIZON: '晚於規劃終點'
  })[value] || String(value)
}

function buildCashflowScheduleRows(resultSide) {
  const schedule = resultSide?.assumptions?.cashflowSchedule
  if (!schedule) return []
  const rows = [{ category: '退休規劃', label: '生日', value: resultSide.assumptions?.birthDate, kind: 'text', note: '生日影響模型時程，不代表給付資格已核准。' }]
  const pension = schedule.laborPension
  if (pension) {
    rows.push({ category: '勞退一次領', label: '來源模式', value: pension.mode, kind: 'mode', note: pension.mode === 'MANUAL' ? '名目實收金額，依回傳時程入帳。' : '依目前模型估算。' })
    rows.push({ category: '勞退一次領', label: '預計實收日期', value: pension.receiptDate, kind: 'text', note: pension.included ? '系統標示已納入本輪時程。' : '系統未標示納入本輪時程。' })
    rows.push({ category: '勞退一次領', label: '預計實收金額', value: pension.amountNominalTwd, kind: 'money', note: '名目新臺幣；非核定給付。' })
  }
  const insurance = schedule.laborInsurance
  if (insurance) {
    rows.push({ category: '勞保月領', label: '來源模式', value: insurance.mode, kind: 'mode', note: '不代表法定資格已核驗。' })
    rows.push({ category: '勞保月領', label: '起領日期', value: insurance.startDate, kind: 'text', note: '依系統回傳的有效日期呈現。' })
    rows.push({ category: '勞保月領', label: '起領時名目月額', value: insurance.monthlyNominalTwd, kind: 'money', note: '起領時名目金額，不是固定實質額。' })
    rows.push({ category: '勞保月領', label: '本輪期初有效名目月額', value: insurance.asOfMonthlyNominalTwd, kind: 'money', note: '系統回傳的估值日有效額。' })
    rows.push({ category: '勞保月領', label: '指數化來源', value: insurance.indexationBasis, kind: 'indexation', note: insurance.indexationBasis === 'REFERENCE_INFLATION_HISTORY_PROXY' ? '缺少真實歷史 CPI，採固定基準通膨代理。' : '依本輪情境 CPI。' })
  }
  for (const [key, label] of [['homeRepair', '房屋修繕'], ['appliance', '家電汰換']]) {
    const item = schedule[key]
    if (!item) continue
    rows.push({ category: label, label: '首次年份', value: item.firstYear, kind: 'integer', note: '依結果來源時程。' })
    rows.push({ category: label, label: '固定支出日', value: item.eventMonthDay, kind: 'text', note: '每次於該年 8 月 31 日支出。' })
    rows.push({ category: label, label: '重複間隔', value: item.intervalYears, kind: 'integer', note: '年。' })
    rows.push({ category: label, label: '每次實質金額', value: item.amountRealTwd, kind: 'money', note: '2026 年 8 月實質新臺幣，不再通膨。' })
  }
  return rows
}

function buildPayrollScheduleRows(payroll) {
  if (!payroll) return []
  return [
    { label: '調薪前基準月薪', value: payroll.monthlySalaryNominalTwd, kind: 'money', note: '名目薪資。' },
    { label: '每月發薪日', value: payroll.payday, kind: 'integer', note: '每月日期。' },
    { label: '每年調薪金額', value: payroll.annualRaiseAmountNominalTwd, kind: 'money', note: '名目新臺幣。' },
    { label: '首次調薪日期', value: payroll.annualRaiseFirstDate, kind: 'text', note: '之後依薪資帳務規約。' },
    { label: '薪資預扣率（預繳，非最終稅率）', value: payroll.withholdingRate, kind: 'fraction', note: '僅影響預繳金額與現金時點，不決定年度應納稅額。' },
    { label: '勞退自提率', value: payroll.selfPensionRate, kind: 'fraction', note: '自提款不列入可支用現金。' },
    { label: '健保眷屬人數', value: payroll.nhiDependents, kind: 'integer', note: '人。' },
    { label: '退休前每月實質生活費', value: payroll.monthlyExpenseRealTwd, kind: 'money', note: '2026 年 8 月實質新臺幣。' },
    { label: '本輪 ledger 已於退休日前收付淨額', value: payroll.receivedByRetirementNominalTwd, kind: 'money', note: '只含本輪 ledger 範圍。' },
    { label: '本輪 ledger 退休日後應收淨額', value: payroll.receivableAfterRetirementNominalTwd, kind: 'money', note: '按實際發薪日列為待收，不提前入帳。' }
  ]
}

function buildReserveStrategyRows(strategy) {
  if (!strategy) return []
  return [
    { label: '緊急備用金：定存', value: strategy.emergencyDepositReserveRealTwd, kind: 'money', note: '來自既有資產，鎖定實質代理。' },
    { label: '緊急備用金：ETF', value: strategy.emergencyEtfReserveRealTwd, kind: 'money', note: '未模擬00865B專屬收益、急難提款與補回。' },
    { label: '鎖定準備金合計', value: strategy.protectedReserveRealTwd, kind: 'money', note: '不另加新錢。' },
    { label: '生活費準備目標', value: strategy.livingReserveCeilingRealTwd, kind: 'money', note: '由既有可用資產切分。' },
    { label: '生活費現金底線', value: strategy.livingReserveFloorRealTwd, kind: 'money', note: '投資與再平衡不得侵蝕。' },
    { label: '熊市生活費動用上限', value: strategy.bearLivingDeploymentCapRealTwd, kind: 'money', note: '資金不足時不創造資產。' },
    { label: '牛市配置戰術', value: strategy.bullStrategyEnabled, kind: 'boolean', note: '獨立於基準牛市補足生活費分支。' },
    { label: '熊市配置戰術', value: strategy.bearStrategyEnabled, kind: 'boolean', note: '獨立於退休後市況生活費分支。' },
    { label: '配置戰術等待月數', value: strategy.tacticalWaitMonths, kind: 'integer', note: '只調整可選戰術的等待。' },
    { label: '熊市全窗口可售債券比例', value: strategy.bearBondSaleMaxShare, kind: 'fraction', note: '比例。' },
    { label: '熊市債券權重底線', value: strategy.minimumBondWeight, kind: 'fraction', note: '比例。' },
    { label: '熊市股票上限', value: strategy.bearEquityCap, kind: 'fraction', note: '比例。' },
    { label: '牛市售股保留下限', value: strategy.bullEquityFloor, kind: 'fraction', note: '比例。' },
    { label: '市況年生活費調整額', value: strategy.marketSpendingAdjustmentRealTwd, kind: 'money', note: '退休後市況分支使用；不是短線報酬。' },
    { label: '市況訊號來源', value: strategy.signalBasis, kind: 'text', note: '月頻合成總報酬代理，非每日雷達。' },
    { label: '基準流動性分支', value: strategy.liquidityBranchesEnabled, kind: 'boolean', note: '與可選配置戰術開關分開。' }
  ]
}

function buildReserveAuditRows(audit) {
  if (!audit) return []
  return [
    { label: '期初現金轉入生活準備金', value: audit.openingCashTransferRealTwd, kind: 'moneyQuantiles' },
    { label: '期初生活準備金短缺', value: audit.openingLivingShortfallRealTwd, kind: 'moneyQuantiles' },
    { label: '完成月更新次數', value: audit.monthUpdateCount, kind: 'integer' },
    { label: '熊市實際成交批次總數', value: audit.bearFillCount, kind: 'integer' },
    { label: '牛市實際成交批次總數', value: audit.bullFillCount, kind: 'integer' },
    { label: '熊市窗口最多批次', value: audit.bearMaximumWindowFills, kind: 'integer' },
    { label: '牛市窗口最多批次', value: audit.bullMaximumWindowFills, kind: 'integer' },
    { label: '熊市首批最大占比', value: audit.bearMaximumFirstBatchShare, kind: 'fraction' },
    { label: '牛市首批最大占比', value: audit.bullMaximumFirstBatchShare, kind: 'fraction' }
  ]
}

function formatProbability(value) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  return Number.isFinite(number) ? `${(number * 100).toFixed(1)}%` : '—'
}

function formatResultValue(value, kind) {
  if (kind === 'probability') return formatProbability(value)
  if (kind === 'money') return formatMoney(value)
  if (kind === 'interval') {
    if (!value || value.low == null || value.high == null) return '—'
    return `${formatProbability(value.low)}–${formatProbability(value.high)}`
  }
  if (kind === 'age') return value === null ? '未出現資金缺口' : value == null ? '—' : `${value} 歲`
  return value == null ? '—' : String(value)
}

function formatDifference(value, kind) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  if (kind === 'probability') {
    const points = number * 100
    return `${points > 0 ? '+' : ''}${points.toFixed(1)} 個百分點`
  }
  if (kind === 'money') return `${number > 0 ? '+' : number < 0 ? '−' : ''}${formatMoney(Math.abs(number))}`
  return String(value)
}

function formatFieldChange(fieldId, value) {
  if (value == null) return '—'
  const field = fieldById.value.get(fieldId)
  if (field?.type === 'weights' && value && typeof value === 'object') {
    const labels = field.assetLabels || {}
    return (field.assetKeys || Object.keys(value)).map(key => `${labels[key] || key} ${formatFieldScalar(value[key], field)}`).join('、')
  }
  if (field?.type === 'symbols' && Array.isArray(value)) return value.join('、') || '未選取'
  if (field?.type === 'events' && Array.isArray(value)) {
    return value.map(item => `${item.date || '未填日期'} ${item.name || '未填用途'} ${formatCurrency(item.amount)}`).join('；') || '無大額支出'
  }
  if (field?.type === 'datedBonuses' && Array.isArray(value)) {
    return value.map(item => `${item.name || '未命名獎金'} ${item.pay_date || '未填發放日'} × ${formatMonths(item.months)} 月薪`).join('；') || '無指定日期獎金'
  }
  if (field?.type === 'recurringBonuses' && Array.isArray(value)) {
    return value.map(item => `${item.name || '未命名獎金'} 每年 ${item.month || '—'} 月 × ${formatMonths(item.months)} 月薪（例外：${formatMonthExceptions(item.month_by_year)}）`).join('；') || '無每年獎金規則'
  }
  if ((field?.type === 'bearLadder' || field?.type === 'bullLadder') && Array.isArray(value)) return `來源唯讀五批（${value.length} 批）`
  return formatFieldScalar(value, field)
}

function formatFieldScalar(value, field) {
  if (value == null) return '—'
  if (field?.type === 'date') return String(value)
  if (isFractionField(field)) return `${formatPreciseDecimal(fractionInputValue(value))}%`
  if (isMoneyField(field)) return formatMoneyText(value)
  return `${value}${field?.unit ? ` ${field.unit}` : ''}`
}

function fieldListLabel(ids) {
  if (!Array.isArray(ids) || !ids.length) return '尚無欄位資料。'
  return ids.map(id => fieldById.value.get(id)?.label || id).join('、')
}

function formatAssumption(key, value, unit) {
  if (key === 'baseCurrency') return '2026 年 8 月實質新臺幣'
  if (key === 'targetPriceComplete') return value ? '完整' : '不完整'
  if (key === 'protectedReserveRealTwd' || /Twd$/.test(key) || /(?:TWD|新臺幣.*元|實質元|名目元)/i.test(String(unit || ''))) return formatCurrency(value)
  if (unit === 'fraction' || ['referenceInflation', 'equityVolatility', 'bondVolatility'].includes(key)) return `${fractionInputValue(value)}%`
  if (key === 'scenarioInflationBounds' && value && value.low != null && value.high != null) {
    return `${fractionInputValue(value.low)}% 至 ${fractionInputValue(value.high)}%`
  }
  if (key === 'reserveExcluded') return value ? '已排除於可支用資產之外' : '未排除'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.join('、')
  if (value && typeof value === 'object') return '—'
  return String(value)
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-TW', { hour12: false })
}

function runStatusLabel(status) {
  return ({ PROCESSING: '執行中', SUCCEEDED: '完成', FAILED: '失敗' })[status] || status || '未知'
}

function runStatusType(status) {
  return ({ PROCESSING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' })[status] || 'info'
}

function aiStatusLabel(status) {
  return ({ NOT_REQUESTED: '未呼叫', PENDING: '處理中', SUCCEEDED: '已完成', FAILED: '未成功', NOT_CONFIGURED: '未配置' })[status] || status || '無狀態'
}

function aiStatusTagType(status) {
  return ({ SUCCEEDED: 'success', FAILED: 'warning', NOT_CONFIGURED: 'warning', PENDING: 'info' })[status] || 'info'
}

function aiStatusType(status) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' || status === 'NOT_CONFIGURED' ? 'warning' : 'neutral'
}

function aiStatusDescription(status, mode) {
  if (mode !== 'AI' || status === 'NOT_REQUESTED') return '本次未呼叫 AI；情境數值仍由系統提供。'
  if (status === 'PENDING') return '文字解讀仍在處理中；數值結果與 AI 狀態分開呈現。'
  if (status === 'NOT_CONFIGURED') return 'AI 尚未配置；數值模擬仍保留，不會被 AI 替代。'
  if (status === 'FAILED') return 'AI 文字解讀未成功；數值結果仍由同一次情境模擬提供。'
  return '目前沒有文字解讀內容；數值結果不受影響。'
}

function releaseStatusLabel(status) {
  return ({ PENDING_RELEASE: '待發布請求', ACTIVE: '正式採用', SUPERSEDED: '已被後續版本取代' })[status] || status || '未知狀態'
}

function releaseStatusType(status) {
  return ({ PENDING_RELEASE: 'warning', ACTIVE: 'success', SUPERSEDED: 'info' })[status] || 'info'
}

function releaseStatusDescription(status) {
  return ({
    PENDING_RELEASE: '尚未正式採用',
    ACTIVE: '系統已核對此版本正式生效',
    SUPERSEDED: '後續正式版本已取代'
  })[status] || '請重新整理查看最新狀態'
}

watch(() => rootData.value?.releases, next => {
  if (!Array.isArray(next) || !next.length) return
  const refreshed = selectedRelease.value ? next.find(item => item.id === selectedRelease.value.id) : null
  selectedRelease.value = refreshed || selectedRelease.value || next[0]
}, { immediate: true })

onMounted(() => loadRoot())
onUnmounted(() => {
  disposed = true
  stopPolling()
})
</script>

<style scoped>
.srpp-center { color: #1e293b; }
.center-heading, .section-heading, .draft-toolbar, .draft-actions, .release-summary, .path-heading, .ai-result-heading {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
}
.center-heading { margin: 2px 0 14px; }
.center-heading h2 { margin: 0; font-size: 21px; font-weight: 750; color: #0f172a; }
.center-heading p { margin: 5px 0 0; color: #64748b; font-size: 13px; line-height: 1.6; }
.center-notice { margin-bottom: 14px; }
.version-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 8px; }
.version-card :deep(.el-card__body) { padding: 14px 16px; }
.version-label { color: #64748b; font-size: 12px; }
.version-value { margin-top: 4px; color: #0f172a; font-size: 17px; font-weight: 700; word-break: break-word; }
.version-value.available { color: #047857; }
.version-value.unavailable { color: #64748b; }
.version-detail { margin-top: 4px; color: #94a3b8; font-size: 12px; line-height: 1.5; }
.identity-details { margin: 2px 0 14px; }
.identity-grid, .assumptions-grid { display: grid; grid-template-columns: minmax(140px, 0.8fr) minmax(0, 2fr); gap: 8px 14px; margin: 0; }
.identity-grid dt, .assumptions-grid dt { color: #64748b; font-size: 12px; }
.identity-grid dd, .assumptions-grid dd { min-width: 0; margin: 0; color: #334155; font-size: 12px; overflow-wrap: anywhere; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px !important; }
.center-section { margin-bottom: 16px; }
.section-heading { width: 100%; }
.section-heading strong { display: block; color: #0f172a; font-size: 15px; }
.section-subtitle { display: block; margin-top: 4px; color: #94a3b8; font-size: 12px; line-height: 1.5; }
.draft-toolbar { align-items: flex-end; margin-bottom: 14px; }
.draft-picker { flex: 1 1 260px; min-width: 220px; }
.draft-name { flex: 1 1 240px; min-width: 200px; }
.draft-actions { justify-content: flex-start; }
.control-label { display: block; margin: 0 0 6px; color: #475569; font-size: 12px; font-weight: 600; }
.draft-picker :deep(.el-select), .draft-name :deep(.el-input), .run-history-picker :deep(.el-select) { width: 100%; }
.operation-alert { margin: 10px 0; }
.editor-meta { display: flex; gap: 12px 20px; flex-wrap: wrap; align-items: center; margin: 4px 0 12px; color: #94a3b8; font-size: 11px; }
.field-groups { margin-top: 8px; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.field-card { min-width: 0; padding: 13px; border: 1px solid #e2e8f0; border-radius: 9px; background: #fff; }
.field-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.field-heading label { color: #334155; font-size: 13px; font-weight: 650; line-height: 1.5; }
.field-badges { flex-shrink: 0; }
.field-reason { margin: 4px 0 8px; color: #94a3b8; font-size: 11px; line-height: 1.5; }
.catalog-warning { grid-column: 1 / -1; color: #b45309; font-size: 11px; line-height: 1.6; }
.field-control { width: 100%; margin-top: 9px; }
.field-bounds { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 7px; color: #94a3b8; font-size: 10px; }
.weights-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.weight-field { min-width: 0; }
.weight-field label { display: block; margin-bottom: 4px; color: #64748b; font-size: 11px; }
.weight-field :deep(.el-input__wrapper) { min-width: 0; }
.weight-field :deep(.el-input-group__append) { padding: 0 6px; color: #64748b; font-size: 10px; }
.weight-total { grid-column: 1 / -1; display: flex; align-items: center; flex-wrap: wrap; gap: 6px 16px; padding: 10px 12px; border: 1px solid #cbd5e1; border-radius: 7px; background: #f8fafc; color: #475569; font-size: 12px; }
.weight-total strong { font-size: 14px; }
.weight-total-valid { border-color: #a7f3d0; background: #ecfdf5; color: #047857; }
.weight-total-under, .weight-total-over, .weight-total-invalid { border-color: #fed7aa; background: #fff7ed; color: #9a3412; }
.change-preview { margin-top: 18px; }
.subsection-heading { margin: 14px 0 8px; color: #334155; font-size: 13px; font-weight: 700; }
.lower-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr); gap: 16px; align-items: start; }
.lower-grid .center-section { min-width: 0; }
.mode-options { margin-top: 14px; }
.mode-description { margin: 8px 0; color: #64748b; font-size: 12px; line-height: 1.6; }
.mode-description span { display: block; margin-top: 3px; color: #b45309; }
.simulation-controls { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 14px; margin: 14px 0 8px; }
.simulation-controls > div { min-width: 154px; }
.simulation-controls :deep(.el-input-number) { width: 160px; }
.control-hint { display: block; margin-top: 4px; color: #94a3b8; font-size: 10px; }
.run-history-picker { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 6px 10px; margin-top: 14px; }
.run-history-picker .control-label { grid-column: 1 / -1; margin-bottom: 0; }
.run-history-picker :deep(.el-select) { min-width: 0; }
.release-card > :deep(.el-card__body) { display: flex; flex-direction: column; gap: 10px; }
.publish-blocks { margin-top: 4px; }
.block-reason { margin-top: 6px; }
.publish-button { margin-top: 8px; }
.empty-publish, .admin-note { padding: 10px 0; color: #94a3b8; font-size: 12px; line-height: 1.6; }
.selected-release { margin-top: 16px; padding-top: 10px; border-top: 1px solid #f1f5f9; }
.release-summary { justify-content: flex-start; margin: 6px 0 10px; color: #64748b; font-size: 11px; }
.selected-release .identity-details { margin: 8px 0 0; }
.result-section { min-width: 0; }
.result-scope { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; color: #64748b; font-size: 11px; }
.ai-result { margin: 0 0 16px; padding: 12px 14px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.ai-result.success { border-color: #a7f3d0; background: #f0fdf4; }
.ai-result.warning { border-color: #fde68a; background: #fffbeb; }
.ai-result p { margin: 8px 0 0; color: #475569; font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.comparison-table { margin-bottom: 12px; }
.path-heading { justify-content: space-between; margin-top: 16px; }
.limitations-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 8px; }
.limitations-grid section { min-width: 0; padding: 8px 12px; border: 1px solid #e2e8f0; border-radius: 8px; }
.limitations-grid .subsection-heading { margin-top: 4px; }
.limitations-grid p { margin: 4px 0; color: #64748b; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.assumptions-panel { margin-top: 14px; padding: 12px; border-radius: 8px; background: #f8fafc; }
.assumptions-panel .subsection-heading { margin-top: 0; }
.assumptions-grid { grid-template-columns: minmax(140px, 0.6fr) minmax(0, 2fr); }
.history-section { margin-top: 4px; }
@media (max-width: 900px) {
  .lower-grid { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .version-cards { grid-template-columns: 1fr; }
  .field-grid, .limitations-grid { grid-template-columns: 1fr; }
  .weights-grid { grid-template-columns: 1fr; }
  .identity-grid, .assumptions-grid { grid-template-columns: 1fr; gap: 2px; }
  .identity-grid dd, .assumptions-grid dd { margin-bottom: 8px; }
  .simulation-controls { align-items: stretch; }
  .simulation-controls > div, .simulation-controls :deep(.el-input-number) { width: 100%; }
  .run-history-picker { grid-template-columns: 1fr; }
  .run-history-picker .control-label { grid-column: auto; }
}
</style>
