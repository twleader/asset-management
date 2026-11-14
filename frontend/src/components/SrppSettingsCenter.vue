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
              :disabled="!activeDraft || !draftDirty || simulating || draftBaseChanged" @click="saveDraft">
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

        <el-alert v-if="!fields.length" type="warning" show-icon :closable="false"
          title="目前沒有可編輯欄位" description="目錄資料未提供欄位清單；請重新整理，設定不會以本機預設值代替。" />

        <el-tabs v-else v-model="activeFieldGroup" class="field-groups">
          <el-tab-pane v-for="group in fieldGroups" :key="group.name" :label="group.name" :name="group.name">
            <div class="field-grid">
              <article v-for="field in group.fields" :key="field.id" class="field-card">
                <div class="field-heading">
                  <label :for="fieldInputId(field)">{{ field.label }}</label>
                  <div class="field-badges">
                    <el-tag v-if="field.formalSupported" type="success" size="small" effect="plain">正式欄位</el-tag>
                    <el-tag v-else type="warning" size="small" effect="plain">僅供情境模擬</el-tag>
                  </div>
                </div>
                <p v-if="field.reason" class="field-reason">{{ fieldReason(field) }}</p>
                <p v-else-if="field.retirementImpact === 'NOT_MODELED'" class="field-reason">
                  此欄位不納入退休現金流情境模型。
                </p>
                <p v-else-if="field.formalSupported === false" class="field-reason">
                  此欄位只供情境模擬，不能改變正式規則。
                </p>

                <el-date-picker v-if="field.type === 'date'" :id="fieldInputId(field)"
                  :model-value="values[field.id] ?? null" type="date" value-format="YYYY-MM-DD"
                  format="YYYY年MM月DD日" placeholder="選擇日期" :disabled="!field.editable || savingDraft || draftBaseChanged"
                  class="field-control" @update:model-value="setFieldValue(field.id, $event)" />

                <div v-else-if="field.type === 'weights'" class="weights-grid">
                  <template v-if="field.assetKeys?.length">
                    <div v-for="assetKey in field.assetKeys" :key="assetKey" class="weight-field">
                      <label :for="weightInputId(field, assetKey)">{{ field.assetLabels?.[assetKey] || assetKey }}</label>
                      <el-input :id="weightInputId(field, assetKey)" type="number" inputmode="decimal" step="any"
                        min="0" max="100" :model-value="fractionInputValue(values[field.id]?.[assetKey])"
                        :disabled="!field.editable || savingDraft || draftBaseChanged"
                        @update:model-value="setWeightValue(field.id, assetKey, $event)">
                        <template #append>%</template>
                      </el-input>
                    </div>
                  </template>
                  <div v-else class="catalog-warning">目錄未提供配置標的清單，避免自行增刪標的，暫不開放編輯。</div>
                </div>

                <el-input v-else :id="fieldInputId(field)" type="number" inputmode="decimal"
                  :step="field.type === 'integer' ? 1 : 'any'"
                  :min="displayBound(field.min, field)" :max="displayBound(field.max, field)"
                  :model-value="isFractionField(field) ? fractionInputValue(values[field.id]) : values[field.id] ?? null"
                  :disabled="!field.editable || savingDraft || draftBaseChanged"
                  class="field-control" @update:model-value="setFieldValue(field.id, $event)">
                  <template v-if="isFractionField(field)" #append>%</template>
                  <template v-else-if="field.unit" #append>{{ field.unit }}</template>
                </el-input>

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
              :disabled="!activeDraft || draftDirty || draftBaseChanged || !draftName.trim() || runMode === 'AI' && !rootData.aiAvailable || simulationProcessing"
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
            <span>情境路徑分位數</span>
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
        </template>
        <el-alert v-else-if="selectedRun.status === 'SUCCEEDED'" type="warning" show-icon :closable="false"
          title="模擬已完成，但目前沒有數值結果" description="請重新查詢此模擬，或稍後重新執行。" />
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
import { Download, DocumentChecked, Plus, Promotion, Refresh, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { bffApi, apiErrorMessage } from '@/api'
import { useAuthStore } from '@/stores/authStore'
import { canPublishSrppRun, hasSrppDraftChanges, isCurrentSrppRun } from '@/utils/srppSettingsCenterState'

const auth = useAuthStore()
const loadingRoot = ref(false)
const rootData = ref(null)
const rootError = ref('')
const activeDraft = ref(null)
const selectedDraftOption = ref('')
const draftName = ref('SRPP 設定草稿')
const savedDraftName = ref('')
const values = ref({})
const savedValues = ref({})
const savingDraft = ref(false)
const casConflict = ref(false)
const selectedRun = ref(null)
const selectedRunOption = ref('')
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
let disposed = false
let initialized = false

const catalog = computed(() => rootData.value?.catalog || null)
const fields = computed(() => Array.isArray(catalog.value?.fields) ? catalog.value.fields : [])
const drafts = computed(() => Array.isArray(rootData.value?.drafts) ? rootData.value.drafts : [])
const runs = computed(() => Array.isArray(rootData.value?.simulations) ? rootData.value.simulations : [])
const releases = computed(() => Array.isArray(rootData.value?.releases) ? rootData.value.releases : [])
const currentVersionLabel = computed(() => rootData.value?.current?.policyVersion || '版本資訊未提供')
const draftDirty = computed(() => hasSrppDraftChanges(values.value, savedValues.value) || draftName.value !== savedDraftName.value)
const draftBaseChanged = computed(() => !!activeDraft.value && !!catalog.value?.baseBundleSha256 &&
  activeDraft.value.baseBundleSha256 !== catalog.value.baseBundleSha256)
const canCreateDraft = computed(() => !!catalog.value?.baseBundleSha256 && !!draftName.value.trim() &&
  !savingDraft.value && !simulating.value && !draftBaseChanged.value)
const simulationProcessing = computed(() => selectedRun.value?.status === 'PROCESSING')
const fieldById = computed(() => new Map(fields.value.map(field => [field.id, field])))
const activeFieldGroup = ref('')
const runMatchesDraft = computed(() => isCurrentSrppRun(selectedRun.value, activeDraft.value))

const fieldGroups = computed(() => rootData.value?.fieldGroups || [])

const result = computed(() => selectedRun.value?.deterministicResult?.result || null)
const baseline = computed(() => result.value?.baseline || null)
const scenario = computed(() => result.value?.scenario || null)
const resultPresentation = computed(() => selectedRun.value?.presentation?.scenario || null)
const selectedPaths = computed(() => (pathSide.value === 'baseline' ? baseline.value : scenario.value)?.percentilePaths || [])
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

watch(() => fieldGroups.value.map(group => group.name).join('|'), () => {
  if (!fieldGroups.value.some(group => group.name === activeFieldGroup.value)) {
    activeFieldGroup.value = fieldGroups.value[0]?.name || ''
  }
})
watch(() => selectedRun.value?.status, status => {
  if (status === 'PROCESSING') startPolling()
  else stopPolling()
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

function displayBound(value, field) {
  return value == null ? undefined : isFractionField(field) ? fractionInputValue(value) : value
}

function formatBound(value, field) {
  if (value == null) return '不限'
  return isFractionField(field) ? String(fractionInputValue(value)) : String(value)
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
  selectedDraftOption.value = draft?.id || ''
  draftName.value = draft?.name || 'SRPP 設定草稿'
  savedDraftName.value = draftName.value
  values.value = copyJson(draft?.values || catalog.value?.currentValues || {})
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
  const existing = runs.value.filter(item => item.id !== run.id)
  rootData.value = { ...rootData.value, simulations: [run, ...existing].slice(0, 20) }
}

function setActiveRun(run) {
  const previousId = selectedRun.value?.id
  const previousStatus = selectedRun.value?.status
  selectedRun.value = copyJson(run)
  selectedRunOption.value = run?.id || ''
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
  loadingRoot.value = true
  rootError.value = ''
  try {
    const response = await bffApi.portfolioAdvice.srpp.root()
    if (disposed || requestSequence !== rootRequestSequence) return
    if (!response || typeof response !== 'object' || !response.catalog || !Array.isArray(response.catalog.fields) || !Array.isArray(response.fieldGroups) ||
      !response.catalog.currentValues || typeof response.catalog.currentValues !== 'object') {
      throw new Error('SRPP 設定目錄回應格式不完整，已停止載入編輯器。')
    }
    rootData.value = response
    if (!initialized) {
      const currentValues = response.catalog.currentValues
      values.value = copyJson(currentValues && typeof currentValues === 'object' ? currentValues : {})
      savedValues.value = copyJson(values.value)
      savedDraftName.value = draftName.value
      activeFieldGroup.value = fieldGroups.value[0]?.name || ''
      initialized = true
      const processing = (response.simulations || []).find(item => item?.status === 'PROCESSING')
      if (processing?.id) await selectRun(processing.id)
    } else if (selectedRun.value) {
      const latestSummary = (response.simulations || []).find(item => item?.id === selectedRun.value.id)
      if (latestSummary?.status === 'PROCESSING') startPolling()
    }
  } catch (error) {
    if (disposed || requestSequence !== rootRequestSequence) return
    rootError.value = apiErrorMessage(error, '無法讀取 SRPP 設定目錄。')
  } finally {
    if (requestSequence === rootRequestSequence) loadingRoot.value = false
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
  const name = draftName.value.trim()
  if (!name) {
    ElMessage.warning('請先填寫草稿名稱。')
    return
  }
  savingDraft.value = true
  casConflict.value = false
  try {
    const created = await bffApi.portfolioAdvice.srpp.createDraft({
      name,
      baseBundleSha256: catalog.value.baseBundleSha256,
      values: copyJson(values.value)
    })
    addDraftToRoot(created)
    setActiveDraft(created)
    ElMessage.success('已建立 SRPP 設定草稿。')
  } catch (error) {
    ElMessage.error(apiErrorMessage(error, '建立草稿失敗。'))
  } finally {
    savingDraft.value = false
  }
}

async function saveDraft() {
  if (!activeDraft.value || !draftDirty.value || draftBaseChanged.value) return
  const name = draftName.value.trim()
  if (!name) {
    ElMessage.warning('請先填寫草稿名稱。')
    return
  }
  savingDraft.value = true
  casConflict.value = false
  try {
    const saved = await bffApi.portfolioAdvice.srpp.updateDraft(activeDraft.value.id, {
      revision: activeDraft.value.revision,
      name,
      values: copyJson(values.value)
    })
    addDraftToRoot(saved)
    setActiveDraft(saved)
    ElMessage.success(`草稿已儲存為版本 ${saved.revision}。`)
  } catch (error) {
    if (error?.response?.status === 409) {
      casConflict.value = true
      ElMessage.warning('草稿版本已過期，請重新載入伺服器版本；目前修改未被覆蓋。')
    } else {
      ElMessage.error(apiErrorMessage(error, '儲存草稿失敗。'))
    }
  } finally {
    savingDraft.value = false
  }
}

function resetDraft() {
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
  draftName.value = 'SRPP 設定草稿'
  savedDraftName.value = draftName.value
  casConflict.value = false
  ElMessage.success('已載入目前正式版本設定；修改後請另存新草稿。')
}

async function reloadConflictedDraft() {
  const id = activeDraft.value?.id
  if (!id) return
  await loadRoot()
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
  runRequestSequence += 1
  const requestSequence = runRequestSequence
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
    if (disposed || requestSequence !== runRequestSequence) return
    setActiveRun(run)
    updateRunInRoot(run)
    if (run.status === 'PROCESSING') ElMessage.success('模擬已送出，完成後畫面會自動更新。')
    else if (run.status === 'SUCCEEDED') ElMessage.success('情境模擬已完成。')
    else if (run.status === 'FAILED') ElMessage.warning('情境模擬未完成，請稍後重新執行。')
  } catch (error) {
    if (disposed || requestSequence !== runRequestSequence) return
    if (error?.response?.status === 409) {
      simulationError.value = apiErrorMessage(error, '草稿版本已變更，請重新載入後再模擬。')
    } else {
      simulationError.value = apiErrorMessage(error, '情境模擬請求失敗。')
    }
  } finally {
    simulating.value = false
  }
}

async function selectRun(id) {
  if (!id) {
    runRequestSequence += 1
    stopPolling()
    selectedRun.value = null
    selectedRunOption.value = ''
    return
  }
  runRequestSequence += 1
  const requestSequence = runRequestSequence
  stopPolling()
  selectedRunOption.value = id
  simulationError.value = ''
  pollStartedAt = Date.now()
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(id)
    if (disposed || requestSequence !== runRequestSequence) return
    setActiveRun(run)
    updateRunInRoot(run)
  } catch (error) {
    if (disposed || requestSequence !== runRequestSequence) return
    simulationError.value = apiErrorMessage(error, '無法載入這筆模擬。')
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
  if (Date.now() - pollStartedAt >= 180000) {
    pollTimedOut.value = true
    return
  }
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(runId)
    if (disposed || requestSequence !== runRequestSequence || selectedRun.value?.id !== runId) return
    setActiveRun(run)
    updateRunInRoot(run)
    pollError.value = ''
    if (run.status === 'PROCESSING') startPolling()
    else await loadRoot()
  } catch (error) {
    if (disposed || requestSequence !== runRequestSequence) return
    pollError.value = apiErrorMessage(error, '暫時無法更新模擬狀態。')
    if (!disposed && selectedRun.value?.status === 'PROCESSING') startPolling()
  }
}

async function refreshSelectedRun() {
  if (!selectedRun.value?.id) return
  runRequestSequence += 1
  const requestSequence = runRequestSequence
  pollTimedOut.value = false
  pollError.value = ''
  pollStartedAt = Date.now()
  try {
    const run = await bffApi.portfolioAdvice.srpp.getSimulation(selectedRun.value.id)
    if (disposed || requestSequence !== runRequestSequence) return
    setActiveRun(run)
    updateRunInRoot(run)
  } catch (error) {
    if (disposed || requestSequence !== runRequestSequence) return
    pollError.value = apiErrorMessage(error, '重新查詢模擬失敗。')
  }
}

async function publish() {
  if (!canPublishNow.value || !activeDraft.value || !selectedRun.value) return
  publishing.value = true
  try {
    const created = await bffApi.portfolioAdvice.srpp.publishDraft(activeDraft.value.id, {
      revision: activeDraft.value.revision,
      simulationId: selectedRun.value.id
    })
    selectedRelease.value = created
    rootData.value = {
      ...rootData.value,
      releases: [created, ...releases.value.filter(item => item.id !== created.id)].slice(0, 20)
    }
    ElMessage.success('已建立待發布請求；正式版本尚未切換。')
    await loadRoot()
    const refreshed = releases.value.find(item => item.id === created.id)
    if (refreshed) selectedRelease.value = refreshed
  } catch (error) {
    ElMessage.error(apiErrorMessage(error, '無法建立待發布請求。'))
  } finally {
    publishing.value = false
  }
}

async function downloadPayload(release) {
  if (!release?.id) return
  downloadingReleaseId.value = release.id
  try {
    const payload = await bffApi.portfolioAdvice.srpp.releasePayload(release.id)
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
    ElMessage.error(apiErrorMessage(error, '下載發布設定檔案失敗。'))
  } finally {
    downloadingReleaseId.value = ''
  }
}

function formatMoney(value) {
  if (value == null || value === '') return '—'
  const number = Number(value)
  return Number.isFinite(number) ? `NT$ ${new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 }).format(number)}` : '—'
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
  return formatFieldScalar(value, field)
}

function formatFieldScalar(value, field) {
  if (value == null) return '—'
  if (field?.type === 'date') return String(value)
  if (isFractionField(field)) return `${fractionInputValue(value)}%`
  return `${value}${field?.unit ? ` ${field.unit}` : ''}`
}

function fieldListLabel(ids) {
  if (!Array.isArray(ids) || !ids.length) return '尚無欄位資料。'
  return ids.map(id => fieldById.value.get(id)?.label || id).join('、')
}

function formatAssumption(key, value, unit) {
  if (key === 'baseCurrency') return '2026 年 8 月實質新臺幣'
  if (key === 'targetPriceComplete') return value ? '完整' : '不完整'
  if (key === 'protectedReserveRealTwd') return formatMoney(value)
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
