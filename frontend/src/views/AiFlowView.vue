<template>
  <div class="ai-flow-view">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <div>
            <h2>AI 判斷流程</h2>
            <p>依台北日期、執行軌與時段檢視已記錄的觀測歷程。</p>
          </div>
          <el-button :loading="state.listLoading" @click="reloadRuns">重新讀取紀錄</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="流程紀錄保留 90 天。這裡呈現實際觀測、自報結果、執行狀態與紀錄完整度；沒有資料只表示尚無已記錄執行。"
      />
      <div class="filters" aria-label="AI 流程查詢條件">
        <label class="filter-field">
          <span>報告日期（台北）</span>
          <el-date-picker
            v-model="reportDate"
            type="date"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            :clearable="false"
            :disabled-date="dateDisabled"
            aria-label="報告日期（台北）"
          />
        </label>
        <label class="filter-field">
          <span>執行軌</span>
          <el-select v-model="consumer" aria-label="執行軌" :clearable="false">
            <el-option label="Codex" value="Codex" />
            <el-option label="Claude" value="Claude" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>時段</span>
          <el-select v-model="period" aria-label="時段" :clearable="false">
            <el-option label="全部時段" value="ALL" />
            <el-option label="台股早盤" value="MORNING" />
            <el-option label="台股午盤" value="NOON" />
            <el-option label="美股" value="US" />
          </el-select>
        </label>
      </div>
      <p class="date-window">
        可查日期：{{ earliestDate }} 至 {{ today }}
        <span v-if="state.earliestDate">（伺服器回傳的最早日期為準）</span>
        <span v-else>（初次載入以日期計算暫定，查詢範圍由伺服器確認）</span>
      </p>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <h3>執行紀錄</h3>
            <p>每次執行各自列出；重試與後續嘗試不會合併。</p>
          </div>
          <el-tag v-if="!state.listLoading && !state.listError" effect="plain">{{ state.runs.length }} 筆</el-tag>
        </div>
      </template>

      <el-skeleton v-if="state.listLoading" :rows="4" animated />
      <el-alert v-else-if="state.listError" type="error" :closable="false" show-icon :title="state.listError" />
      <el-empty v-else-if="state.runs.length === 0" description="尚無已記錄執行" />
      <div v-else class="run-list">
        <article v-for="run in state.runs" :key="run.runId" class="run-item">
          <div class="run-heading">
            <div>
              <h4>{{ displayUnknown(run.slotLabel) }}</h4>
              <code class="run-id">{{ run.runId }}</code>
            </div>
            <el-button
              type="primary"
              plain
              :loading="state.detailLoading && state.selectedRunId === run.runId"
              @click="openRun(run)"
            >{{ state.selectedRunId === run.runId ? '重新載入流程' : '查看流程' }}</el-button>
          </div>
          <div class="run-badges">
            <el-tag :type="executionTagType(run.executionState)" effect="plain">執行狀態：{{ displayUnknown(run.executionStateLabel) }}（{{ displayUnknown(run.executionState) }}）</el-tag>
            <el-tag :type="coverageTagType(run.traceCoverage)" effect="plain">紀錄完整度：{{ displayUnknown(run.coverageLabel) }}（{{ displayUnknown(run.traceCoverage) }}）</el-tag>
            <el-tag type="info" effect="plain">自報結果：{{ displayUnknown(run.reportedOutcome) }}</el-tag>
            <el-tag type="warning" effect="plain">核實結果：{{ displayUnknown(run.verifiedReportOutcome) }}</el-tag>
          </div>
          <dl class="run-facts">
            <div><dt>時段代碼</dt><dd>{{ displayUnknown(run.reportSlot) }}</dd></div>
            <div><dt>執行軌</dt><dd>{{ displayUnknown(run.consumer) }}</dd></div>
            <div><dt>自報結果標籤</dt><dd>{{ displayUnknown(run.outcomeLabel) }}</dd></div>
            <div><dt>事件數</dt><dd>{{ displayUnknown(run.eventCount) }}</dd></div>
            <div><dt>開始時間</dt><dd>{{ displayUnknown(run.startedAt) }}</dd></div>
            <div><dt>結束時間</dt><dd>{{ displayUnknown(run.endedAt) }}</dd></div>
            <div><dt>整輪耗時</dt><dd>{{ displayDurationMillis(run.durationMillis) }}</dd></div>
            <div><dt>原因代碼</dt><dd>{{ displayUnknown(run.reasonCode) }}</dd></div>
            <div><dt>來源類型</dt><dd>{{ displayUnknown(run.sourceKind) }}</dd></div>
            <div><dt>來源識別</dt><dd class="wrap-value">{{ displayUnknown(run.sourceRunId) }}</dd></div>
          </dl>
          <p v-if="run.traceCoverage === 'RAW_TRACE_UNAVAILABLE' || run.traceCoverage === 'UNLINKED'" class="coverage-note">
            目前沒有可用的原始流程鏈或精確連結；此紀錄不代表完整流程。
          </p>
          <p v-else-if="run.traceCoverage === 'PARTIAL_OBSERVED'" class="coverage-note">
            這筆流程只包含目前可觀測到的部分紀錄。
          </p>
        </article>
      </div>
    </el-card>

    <el-card v-if="state.selectedRunId" shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <h3>流程明細</h3>
            <p>{{ displayUnknown(state.detail?.run?.slotLabel) }} · {{ state.selectedRunId }}</p>
          </div>
          <el-button text @click="closeDetail">關閉流程</el-button>
        </div>
      </template>
      <el-skeleton v-if="state.detailLoading" :rows="5" animated />
      <el-alert v-else-if="state.detailError" type="error" :closable="false" show-icon :title="state.detailError" />
      <template v-else-if="state.detail">
        <div class="detail-status">
          <el-tag :type="executionTagType(state.detail.run.executionState)" effect="plain">執行狀態：{{ displayUnknown(state.detail.run.executionStateLabel) }}（{{ displayUnknown(state.detail.run.executionState) }}）</el-tag>
          <el-tag :type="coverageTagType(state.detail.run.traceCoverage)" effect="plain">紀錄完整度：{{ displayUnknown(state.detail.run.coverageLabel) }}（{{ displayUnknown(state.detail.run.traceCoverage) }}）</el-tag>
          <el-tag type="info" effect="plain">自報結果：{{ displayUnknown(state.detail.run.reportedOutcome) }}</el-tag>
          <el-tag type="warning" effect="plain">核實結果：{{ displayUnknown(state.detail.run.verifiedReportOutcome) }}</el-tag>
          <strong>整輪耗時：{{ displayDurationMillis(state.detail.run.durationMillis) }}</strong>
        </div>
        <el-alert
          v-if="state.detail.run.traceCoverage !== 'FULL_OBSERVED'"
          class="partial-notice"
          type="warning"
          :closable="false"
          show-icon
          :title="state.detail.run.traceCoverage === 'SELF_REPORTED'
            ? '這筆資料來自自報內容，尚未由完整原始流程鏈驗證。'
            : '這筆資料未達完整觀測；缺少的來源不會由畫面補推。'"
        />
        <p class="graph-note">同階判斷與候選水平並排；箭頭沿用已記錄的關係，「接續紀錄」只表示觀測順序。</p>
        <div v-if="graphData.nodes.length" class="graph-toolbar">
          <span>上下或左右捲動查看長流程；拖曳移動，按鈕縮放。</span>
          <div>
            <el-button size="small" :disabled="graphZoom <= 0.25" aria-label="縮小流程圖" @click="graphZoom = Math.max(0.25, graphZoom - 0.25)">−</el-button>
            <el-button size="small" aria-label="重設流程圖比例" @click="graphZoom = 1">{{ Math.round(graphZoom * 100) }}%</el-button>
            <el-button size="small" :disabled="graphZoom >= 2" aria-label="放大流程圖" @click="graphZoom = Math.min(2, graphZoom + 0.25)">＋</el-button>
          </div>
        </div>
        <div v-if="graphData.nodes.length" :key="state.selectedRunId" class="graph-panel" tabindex="0" aria-label="長流程圖捲動區">
          <VChart
            class="flow-chart"
            :style="{ width: graphWidth + 'px', height: graphHeight + 'px' }"
            :init-options="{ renderer: 'svg' }"
            :option="graphOption"
            :autoresize="true"
            aria-label="AI 流程圖，可縮放與拖曳"
            @click="onChartClick"
          />
        </div>
        <el-empty v-else description="此執行尚無可顯示節點" />

        <div class="detail-columns">
          <section class="timeline-panel">
            <h4>事件時間軸</h4>
            <el-empty v-if="state.detail.nodes.length === 0" description="尚無事件紀錄" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="node in state.detail.nodes"
                :key="node.eventId"
                :timestamp="displayUnknown(node.timeLabel)"
                :type="timelineType(node.status)"
                placement="top"
                :hollow="state.selectedNodeId !== node.eventId"
              >
                <button class="timeline-entry" type="button" @click="selectNode(node.eventId)">
                  <span class="timeline-title">{{ displayUnknown(node.label) }}</span>
                  <span class="timeline-status">{{ displayUnknown(node.statusLabel) }}</span>
                  <span class="timeline-category">{{ displayUnknown(node.categoryLabel) }}</span>
                </button>
                <p class="timeline-summary">{{ displayUnknown(node.summary) }}</p>
                <template v-if="isToolNode(node)">
                  <el-button v-if="toolStartId(node.eventId)" text type="primary" @click="showToolCall(node.eventId)">查看工具與參數</el-button>
                  <p v-else class="tool-call-note">未記錄工具開始事件</p>
                </template>
                <el-button
                  v-if="node.publicDecisionSummary"
                  text
                  type="primary"
                  @click.stop="showPublicDecision(node.eventId)"
                >查看公開決策摘要</el-button>
              </el-timeline-item>
            </el-timeline>
          </section>

          <section class="slow-steps-panel">
            <h4>已配對的慢速步驟</h4>
            <el-table v-if="state.detail.slowSteps.length" :data="state.detail.slowSteps" size="small" stripe>
              <el-table-column prop="title" label="步驟" min-width="130" />
              <el-table-column label="狀態" min-width="100">
                <template #default="{ row }">{{ displayUnknown(row.statusLabel) }}</template>
              </el-table-column>
              <el-table-column label="耗時" min-width="210">
                <template #default="{ row }">{{ displayDurationMillis(row.durationMillis) }}</template>
              </el-table-column>
              <el-table-column label="操作" min-width="160">
                <template #default="{ row }"><code>{{ displayUnknown(row.operation?.method) }} {{ displayUnknown(row.operation?.path) }}</code></template>
              </el-table-column>
              <el-table-column label="工具紀錄" min-width="170">
                <template #default="{ row }">
                  <el-button v-if="toolStartId(row.nodeId)" text type="primary" @click="showToolCall(row.nodeId)">查看工具與參數</el-button>
                  <span v-else>未記錄工具開始事件</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="沒有已配對且耗時可確認的步驟" />
          </section>
        </div>

        <section v-if="selectedNode" ref="nodeDetail" class="node-detail" tabindex="-1" aria-label="節點明細">
          <div class="section-header">
            <div>
              <h4>節點明細</h4>
              <p>{{ displayUnknown(selectedNode.label) }}</p>
            </div>
            <el-button text @click="clearSelectedNode">清除選取</el-button>
          </div>
          <dl class="node-facts">
            <div><dt>事件狀態</dt><dd><el-tag :type="eventTagType(selectedNode.status)" effect="plain">{{ displayUnknown(selectedNode.statusLabel) }}</el-tag></dd></div>
            <div><dt>類別</dt><dd>{{ displayUnknown(selectedNode.categoryLabel) }}</dd></div>
            <div><dt>時間</dt><dd>{{ displayUnknown(selectedNode.timeLabel) }}</dd></div>
            <div><dt>節點耗時</dt><dd>{{ displayDurationMillis(selectedNode.durationMillis) }}</dd></div>
            <div><dt>公開摘要</dt><dd>{{ displayUnknown(selectedNode.summary) }}</dd></div>
          </dl>
          <p v-if="state.selectedToolCallLoading" class="tool-call-state" role="status">正在讀取所選節點的工具參數…</p>
          <el-alert
            v-else-if="state.selectedToolCallError"
            class="tool-call-state"
            type="error"
            :closable="false"
            show-icon
            :title="state.selectedToolCallError"
          />
          <template v-else-if="state.selectedToolCall">
            <dl class="node-facts tool-call-facts">
              <div>
                <dt>工具</dt>
                <dd>
                  {{ state.selectedToolCall.name }}
                  <template v-if="state.selectedToolCall.innerNames.length">（內含 {{ state.selectedToolCall.innerNames.join('、') }}）</template>
                </dd>
              </div>
              <div>
                <dt>公開原始輸入</dt>
                <dd><pre class="tool-call-parameters">{{ state.selectedToolCall.parameters === '' ? '（無參數）' : state.selectedToolCall.parameters }}</pre></dd>
              </div>
            </dl>
            <p class="tool-call-note">已處理範圍的輸入長度（遮蔽、清理後）：{{ state.selectedToolCall.parametersChars }} 字元</p>
            <p v-if="isCompositeToolCall" class="tool-call-note">
              上方程式碼是包裝工具的公開原始輸入。內含工具名稱僅從文字擷取，不保證逐項已執行；動態參數與逐項耗時未獨立記錄。
            </p>
            <p v-if="state.selectedToolCall.truncated" class="tool-call-note">
              已截斷：已處理前綴的遮蔽後長度 {{ state.selectedToolCall.parametersChars }} 字元，僅保留前 16384 字元
            </p>
            <p v-if="state.selectedToolCall.sourceTruncated === true" class="tool-call-note">
              原始輸入超出 1,048,576 字元預掃上限；下列內容與長度只反映已處理前綴
            </p>
            <p v-if="state.selectedToolCall.redactedCount > 0" class="tool-call-note">
              已處理範圍的憑證遮蔽數：
              <template v-if="state.selectedToolCall.redactedCount === 100000">至少 100000 處</template>
              <template v-else>{{ state.selectedToolCall.redactedCount }} 處</template>
            </p>
          </template>
          <p v-else class="tool-call-state">未記錄工具參數</p>
          <template v-if="selectedNode.eventKind === 'START'">
            <p v-if="state.selectedChoiceSetLoading" class="choice-set-state" role="status">正在讀取所選節點的公開候選…</p>
            <el-alert
              v-else-if="state.selectedChoiceSetError"
              class="choice-set-state"
              type="error"
              :closable="false"
              show-icon
              :title="state.selectedChoiceSetError"
            />
            <section v-else-if="state.selectedChoiceSet" class="choice-set-detail" aria-label="公開候選">
              <h5>公開候選</h5>
              <p class="choice-set-title">{{ state.selectedChoiceSet.title }}</p>
              <article v-for="option in selectedChoiceSetOptions" :key="option.optionId" class="choice-set-option">
                <div class="choice-set-option-heading">
                  <strong>{{ option.optionId === state.selectedChoiceSet.selectedOptionId ? '採用' : '未選擇' }}</strong>
                  <span>{{ option.label }}</span>
                </div>
                <dl class="node-facts choice-set-facts">
                  <div><dt>工具</dt><dd>{{ option.toolName }}</dd></div>
                  <div><dt>清理後字元數</dt><dd>{{ option.parametersChars }}</dd></div>
                  <div><dt>參數</dt><dd><pre class="choice-set-parameters">{{ option.parameters === '' ? '（無參數）' : option.parameters }}</pre></dd></div>
                </dl>
                <p v-if="option.truncated" class="choice-set-note">
                  已截斷：已處理前綴的遮蔽後長度 {{ option.parametersChars }} 字元，僅保留前 16384 字元
                </p>
                <p v-if="option.sourceTruncated === true" class="choice-set-note">
                  原始輸入超出 1,048,576 字元預掃上限；下列內容與長度只反映已處理前綴
                </p>
                <p v-if="option.redactedCount > 0" class="choice-set-note">
                  已處理範圍的憑證遮蔽數：
                  <template v-if="option.redactedCount === 100000">至少 100000 處</template>
                  <template v-else>{{ option.redactedCount }} 處</template>
                </p>
              </article>
            </section>
            <p v-else-if="state.selectedToolCall" class="choice-set-state">未記錄公開候選</p>
          </template>
          <section
            v-if="selectedNode.publicDecisionSummary || state.selectedPublicDecisionLoading || state.selectedPublicDecisionLoaded || state.selectedPublicDecisionError"
            class="public-decision-detail"
            aria-label="公開決策摘要明細"
          >
            <h5>公開決策摘要</h5>
            <p class="public-decision-disclaimer">公開自述；不保證涵蓋所有內部考量，亦不證明工具已執行。</p>
            <p v-if="state.selectedPublicDecisionLoading" class="choice-set-state" role="status">正在讀取公開決策摘要…</p>
            <el-alert
              v-else-if="state.selectedPublicDecisionError"
              class="choice-set-state"
              type="error"
              :closable="false"
              show-icon
              :title="state.selectedPublicDecisionError"
            />
            <template v-else-if="state.selectedPublicDecisionLoaded && state.selectedPublicDecision">
              <dl class="node-facts public-decision-facts">
                <div><dt>來源版本</dt><dd>{{ state.selectedPublicDecision.source }}</dd></div>
                <div><dt>決策識別</dt><dd>{{ state.selectedPublicDecision.decisionId }}</dd></div>
                <div><dt>主題</dt><dd>{{ state.selectedPublicDecision.topic }}</dd></div>
              </dl>
              <article
                v-for="option in selectedPublicDecisionOptions"
                :key="option.id"
                class="choice-set-option public-decision-option"
              >
                <div class="choice-set-option-heading">
                  <strong>{{ option.id === state.selectedPublicDecision.selectedOptionId ? '已選擇（自述）' : '未選擇' }}</strong>
                  <span>{{ option.label }}</span>
                </div>
                <dl class="node-facts public-decision-option-facts">
                  <div><dt>公開動作</dt><dd>{{ option.action }}</dd></div>
                  <div><dt>公開理由</dt><dd><pre class="public-decision-text">{{ option.reason }}</pre></dd></div>
                  <div v-if="option.evidenceRefs.length">
                    <dt>公開依據</dt>
                    <dd>
                      <ul class="public-decision-evidence-list">
                        <li v-for="evidence in option.evidenceRefs" :key="evidence.role + ':' + evidence.id">
                          {{ evidence.role }} · {{ evidence.id }} · {{ displayUnknown(evidence.hash) }}
                        </li>
                      </ul>
                    </dd>
                  </div>
                  <div v-else><dt>公開依據</dt><dd>未列公開證據參照</dd></div>
                </dl>
                <template v-if="option.toolCall">
                  <dl class="node-facts public-decision-option-facts">
                    <div><dt>擬議工具</dt><dd>{{ option.toolCall.name }}<template v-if="option.toolCall.innerNames.length">（內含 {{ option.toolCall.innerNames.join('、') }}）</template></dd></div>
                    <div><dt>擬議輸入</dt><dd><pre class="choice-set-parameters">{{ option.toolCall.parameters === '' ? '（無參數）' : option.toolCall.parameters }}</pre></dd></div>
                    <div><dt>遮蔽後字元數</dt><dd>{{ option.toolCall.parametersChars }}</dd></div>
                  </dl>
                  <p class="choice-set-note">這是擬議工具輸入，不代表工具已呼叫或執行。</p>
                  <p v-if="option.toolCall.truncated" class="choice-set-note">
                    已截斷：已處理前綴的遮蔽後長度 {{ option.toolCall.parametersChars }} 字元，僅保留前 16384 字元
                  </p>
                  <p v-if="option.toolCall.sourceTruncated === true" class="choice-set-note">
                    原始輸入超出 1,048,576 字元預掃上限；下列內容與長度只反映已處理前綴
                  </p>
                  <p v-if="option.toolCall.redactedCount > 0" class="choice-set-note">
                    已處理範圍的憑證遮蔽數：
                    <template v-if="option.toolCall.redactedCount === 100000">至少 100000 處</template>
                    <template v-else>{{ option.toolCall.redactedCount }} 處</template>
                  </p>
                </template>
                <p v-else class="choice-set-note">未記錄擬議工具輸入</p>
              </article>
            </template>
            <p v-else-if="state.selectedPublicDecisionLoaded" class="choice-set-state">未記錄公開決策摘要</p>
            <div v-else class="choice-set-state">
              <p>完整公開決策明細會在你查閱時載入。</p>
              <el-button text type="primary" @click="showPublicDecision(selectedNode.eventId)">查看公開決策摘要</el-button>
            </div>
          </section>
          <div class="safe-text-actions">
            <el-button
              link
              type="primary"
              @click="showSafeText('操作、狀態與原因', [
                ['操作方法', selectedNode.operation?.method],
                ['操作路徑', selectedNode.operation?.path],
                ['事件狀態代碼', selectedNode.status],
                ['原因代碼', selectedNode.reasonCode]
              ])"
            >查看操作與狀態文字</el-button>
            <el-button
              v-for="evidence in selectedNode.evidenceRefs || []"
              :key="evidence.role + ':' + evidence.id"
              link
              type="primary"
              @click="showSafeText('安全證據參照', [
                ['證據角色', evidence.role],
                ['證據識別', evidence.id],
                ['SHA-256', evidence.hash]
              ])"
            >查看 {{ displayUnknown(evidence.role) }} 證據</el-button>
          </div>
        </section>
      </template>
    </el-card>

    <el-dialog v-model="safeTextVisible" :title="safeTextTitle" width="min(640px, 92vw)">
      <dl class="safe-text-list">
        <div v-for="item in safeTextItems" :key="item.label">
          <dt>{{ item.label }}</dt>
          <dd><pre>{{ item.text }}</pre></dd>
        </div>
      </dl>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { apiErrorMessage, bffApi } from '@/api'
import {
  createAiFlowViewLoader,
  createAiFlowViewState,
  displayDurationMillis,
  displayUnknown,
  fallbackAiFlowEarliestDate,
  isDateOutsideWindow,
  isIsoDate,
  mapAiFlowGraphData,
  resolveAiFlowGraphNodeSelection,
  resolveAiFlowToolStartId,
  todayTaipeiISO
} from '@/utils/aiFlowView'

use([SVGRenderer, GraphChart, TooltipComponent])

const today = ref(todayTaipeiISO())
const reportDate = ref(today.value)
const consumer = ref('Codex')
const period = ref('ALL')
const state = reactive(createAiFlowViewState({
  date: reportDate.value,
  consumer: consumer.value,
  period: period.value
}))
const safeTextVisible = ref(false)
const safeTextTitle = ref('')
const safeTextItems = ref([])
const graphZoom = ref(1)
const nodeDetail = ref(null)
const isCompositeToolCall = computed(() => ['exec', 'run_code'].includes(state.selectedToolCall?.name?.split('.').at(-1)))

const loader = createAiFlowViewLoader({
  listRuns: ({ date, consumer: selectedConsumer, period: selectedPeriod, signal }) =>
    bffApi.aiFlow.runs({ date, consumer: selectedConsumer, period: selectedPeriod, signal }),
  getRun: (runId, { date, consumer: selectedConsumer, signal }) =>
    bffApi.aiFlow.detail(runId, { date, consumer: selectedConsumer, signal }),
  getToolCall: (runId, eventId, { date, consumer: selectedConsumer, signal }) =>
    bffApi.aiFlow.toolCall(runId, eventId, { date, consumer: selectedConsumer, signal }),
  getChoiceSet: (runId, eventId, { date, consumer: selectedConsumer, signal }) =>
    bffApi.aiFlow.choiceSet(runId, eventId, { date, consumer: selectedConsumer, signal }),
  getPublicDecision: (runId, eventId, { date, consumer: selectedConsumer, signal }) =>
    bffApi.aiFlow.publicDecision(runId, eventId, { date, consumer: selectedConsumer, signal }),
  errorMessage: error => apiErrorMessage(error, '目前無法讀取 AI 流程紀錄，請稍後再試。')
})

const earliestDate = computed(() =>
  isIsoDate(state.earliestDate) ? state.earliestDate : fallbackAiFlowEarliestDate(today.value))

function pickerDateIso(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return year + '-' + month + '-' + day
}

function dateDisabled(date) {
  return isDateOutsideWindow(pickerDateIso(date), earliestDate.value, today.value)
}

const traceTypes = {
  FULL_OBSERVED: 'success',
  PARTIAL_OBSERVED: 'warning',
  SELF_REPORTED: 'info',
  RAW_TRACE_UNAVAILABLE: 'danger',
  UNLINKED: 'danger'
}
const executionTypes = { STARTED: 'primary', ENDED: 'success', UNKNOWN: 'info' }
const eventTypes = {
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  STOPPED: 'warning',
  SKIPPED: 'info',
  UNKNOWN: 'info'
}

function coverageTagType(value) { return traceTypes[value] || 'info' }
function executionTagType(value) { return executionTypes[value] || 'info' }
function eventTagType(value) { return eventTypes[value] || 'info' }
function timelineType(value) { return eventTypes[value] || 'primary' }

watch(
  [reportDate, consumer, period],
  ([date, selectedConsumer, selectedPeriod]) => {
    void loader.query(state, { date, consumer: selectedConsumer, period: selectedPeriod })
  },
  { immediate: true, flush: 'sync' }
)

const graphData = computed(() => mapAiFlowGraphData(state.detail))
// Fixed pixel geometry keeps each graph coordinate unit at one screen pixel.
// SVG avoids a tall raster canvas size limit.
const graphWidth = computed(() => graphData.value.graphWidth)
const graphHeight = computed(() => graphData.value.graphHeight)
watch(() => state.selectedRunId, () => { graphZoom.value = 1 })
const graphOption = computed(() => ({
  animation: false,
  tooltip: {
    trigger: 'item',
    renderMode: 'richText',
    formatter: params => String(params?.data?.tooltipText ?? '')
  },
  series: [{
    type: 'graph',
    layout: 'none',
    roam: 'move',
    zoom: graphZoom.value,
    nodeScaleRatio: 0,
    left: graphData.value.plotBounds.left,
    top: graphData.value.plotBounds.top,
    width: graphData.value.plotBounds.width,
    height: graphData.value.plotBounds.height,
    draggable: true,
    data: graphData.value.nodes.map(node => ({
      ...node,
      symbol: 'roundRect',
      symbolSize: [150, 48],
      label: { show: true, position: 'inside', color: '#0f172a', width: 136, overflow: 'truncate', formatter: node.graphLabel }
    })),
    links: graphData.value.edges.map(edge => ({
      ...edge,
      lineStyle: { curveness: edge.curveness },
      edgeLabel: { show: true, formatter: edge.label }
    })),
    edgeSymbol: ['none', 'arrow'],
    edgeSymbolSize: [0, 9],
    label: { show: true, position: 'inside', color: '#0f172a' },
    edgeLabel: { show: true, color: '#475569', fontSize: 12, formatter: params => params?.data?.label ?? '' },
    lineStyle: { color: '#94a3b8', width: 1.5, opacity: 0.9 },
    emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
  }]
}))

const selectedNode = computed(() =>
  state.detail?.nodes?.find(node => node.eventId === state.selectedNodeId) || null)
const selectedChoiceSetOptions = computed(() =>
  Array.isArray(state.selectedChoiceSet?.options) ? state.selectedChoiceSet.options : [])
const selectedPublicDecisionOptions = computed(() =>
  Array.isArray(state.selectedPublicDecision?.options) ? state.selectedPublicDecision.options : [])

function reloadRuns() {
  void loader.query(state, { date: reportDate.value, consumer: consumer.value, period: period.value })
}

function openRun(run) {
  void loader.loadDetail(state, run, state.filters)
}

function closeDetail() {
  loader.clearDetail(state)
}

function selectNode(eventId, { forceSelect = false } = {}) {
  void loader.selectNode(state, eventId, state.filters, { forceSelect })
}

function toolStartId(eventId) {
  return resolveAiFlowToolStartId(state.detail, eventId)
}

function isToolNode(node) {
  return node?.operation?.method === 'TOOL' || node?.stepId?.startsWith('call-')
}

async function showToolCall(eventId) {
  const startId = toolStartId(eventId)
  if (!startId) return
  const selection = loader.selectNode(state, startId, state.filters, { forceSelect: true })
  await nextTick()
  nodeDetail.value?.focus({ preventScroll: true })
  nodeDetail.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  await selection
}

async function showPublicDecision(eventId) {
  const selection = loader.selectNode(state, eventId, state.filters, { forceSelect: true })
  await selection
  if (state.selectedNodeId !== eventId || !state.selectedRunId) return
  const request = loader.loadPublicDecision(state, eventId, state.filters)
  await nextTick()
  nodeDetail.value?.focus({ preventScroll: true })
  nodeDetail.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  await request
}

function clearSelectedNode() {
  loader.clearSelectedNode(state)
}

function onChartClick(params) {
  if (params?.dataType !== 'node') return
  const { eventId, fromSynthetic } = resolveAiFlowGraphNodeSelection(graphData.value, params?.data?.id)
  if (eventId) selectNode(eventId, { forceSelect: fromSynthetic })
}

function showSafeText(title, entries) {
  safeTextTitle.value = title
  safeTextItems.value = entries.map(([label, value]) => ({ label, text: displayUnknown(value) }))
  safeTextVisible.value = true
}

onBeforeUnmount(() => loader.clear(state))
</script>

<style scoped>
.ai-flow-view { display: grid; gap: 16px; min-width: 0; max-width: 1440px; margin: 0 auto; }
.ai-flow-view :deep(.el-card), .ai-flow-view :deep(.el-card__body) { min-width: 0; }
.page-header, .section-header, .run-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header h2, .section-header h3 { color: #1e293b; font-size: 20px; }
.page-header p, .section-header p { margin-top: 4px; color: #64748b; font-size: 14px; }
.filters { display: grid; grid-template-columns: repeat(3, minmax(170px, 1fr)); gap: 14px; margin-top: 20px; }
.filter-field { display: grid; gap: 6px; color: #475569; font-size: 13px; }
.filter-field :deep(.el-date-editor), .filter-field :deep(.el-select) { width: 100%; }
.date-window { margin-top: 10px; color: #64748b; font-size: 12px; }
.run-list { display: grid; gap: 14px; }
.run-item { padding: 18px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.run-heading h4, .timeline-panel h4, .slow-steps-panel h4, .node-detail h4 { color: #1e293b; font-size: 16px; }
.run-id { display: inline-block; margin-top: 5px; color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.run-badges, .detail-status { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 14px; }
.run-facts, .node-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px 18px; margin-top: 16px; }
.run-facts > div, .node-facts > div { min-width: 0; }
.run-facts dt, .node-facts dt, .safe-text-list dt { color: #64748b; font-size: 12px; }
.run-facts dd, .node-facts dd, .safe-text-list dd { margin-top: 4px; color: #1e293b; font-size: 13px; overflow-wrap: anywhere; }
.wrap-value { word-break: break-word; }
.coverage-note { margin-top: 12px; color: #9a6700; font-size: 13px; }
.partial-notice { margin-top: 14px; }
.graph-note { margin: 14px 0 8px; color: #64748b; font-size: 13px; }
.graph-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #64748b; font-size: 13px; }
.graph-panel { width: 100%; max-width: 100%; min-width: 0; height: min(68vh, 700px); min-height: 460px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.flow-chart { display: block; }
.detail-columns { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr); gap: 16px; margin-top: 18px; }
.timeline-panel, .slow-steps-panel, .node-detail { min-width: 0; padding: 18px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.timeline-panel h4, .slow-steps-panel h4 { margin-bottom: 16px; }
.timeline-entry { display: flex; flex-wrap: wrap; gap: 8px 12px; padding: 0; border: 0; color: #1e293b; background: transparent; text-align: left; cursor: pointer; font: inherit; }
.timeline-entry:hover .timeline-title { text-decoration: underline; }
.timeline-title { font-weight: 650; }
.timeline-status, .timeline-category { color: #475569; }
.timeline-summary { margin-top: 6px; color: #64748b; font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.slow-steps-panel :deep(.el-table) { width: 100%; }
.slow-steps-panel code { white-space: normal; overflow-wrap: anywhere; }
.node-detail { margin-top: 16px; }
.node-detail .section-header { margin-bottom: 12px; }
.node-facts { grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 10px; }
.tool-call-facts { margin-top: 16px; }
.tool-call-state, .tool-call-note, .choice-set-state, .choice-set-note { margin-top: 12px; }
.tool-call-note, .choice-set-note { color: #64748b; font-size: 13px; line-height: 1.5; }
.tool-call-parameters { max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all; margin: 0; font: inherit; }
.choice-set-detail { display: grid; gap: 10px; margin-top: 16px; }
.choice-set-detail h5 { color: #1e293b; font-size: 14px; }
.choice-set-title { color: #475569; font-size: 13px; }
.choice-set-option { padding: 14px; border: 1px solid #e2e8f0; border-radius: 6px; background: #f8fafc; }
.choice-set-option-heading { display: flex; flex-wrap: wrap; gap: 8px; color: #1e293b; }
.choice-set-option-heading strong { color: #166534; }
.public-decision-detail { margin-top: 20px; padding-top: 16px; border-top: 1px solid #e2e8f0; }
.public-decision-detail h5 { color: #1e293b; font-size: 14px; }
.public-decision-disclaimer { margin-top: 8px; color: #64748b; font-size: 13px; line-height: 1.5; }
.public-decision-option { margin-top: 12px; background: #fff; }
.public-decision-option .choice-set-option-heading strong { color: #1d4ed8; }
.public-decision-facts, .public-decision-option-facts { grid-template-columns: 1fr; }
.public-decision-text { max-height: 240px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; font: inherit; }
.public-decision-evidence-list { margin: 0; padding-left: 18px; }
.public-decision-evidence-list li { overflow-wrap: anywhere; }
.choice-set-facts { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.choice-set-parameters { max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all; margin: 0; font: inherit; }
.safe-text-actions { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 14px; }
.safe-text-list { display: grid; gap: 14px; }
.safe-text-list pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
@media (max-width: 900px) {
  .run-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .detail-columns { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .page-header, .run-heading, .section-header { align-items: flex-start; flex-direction: column; }
  .filters { grid-template-columns: 1fr; }
  .run-heading > .el-button { width: 100%; }
  .run-facts, .node-facts { grid-template-columns: 1fr 1fr; gap: 12px; }
  .graph-panel { height: 64vh; min-height: 360px; }
  .run-badges { align-items: flex-start; }
}
</style>
