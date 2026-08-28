<template>
  <div class="open-api-view">
    <el-card shadow="never">
      <template #header>
        <div class="page-header">
          <div>
            <h2>開放 API</h2>
            <p>目前 port 9090 對外契約的唯讀文件檢視。</p>
          </div>
          <el-button :icon="Document" :disabled="!contract" @click="drawerVisible = true">
            查看完整 YAML
          </el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="此頁僅供瀏覽文件，不會呼叫或測試任何 API。實際 API 僅供 Docker loopback 與受授權的 Tailscale 私有網路使用。"
      />
      <el-alert
        class="side-effect-notice"
        type="warning"
        :closable="false"
        show-icon
        title="請留意：契約中的 crawler rescan POST 會觸發外部資料抓取；本頁不提供執行功能。"
      />

      <div v-if="loading" class="loading-state">
        <el-skeleton :rows="6" animated />
      </div>
      <el-alert v-else-if="loadError" type="error" :closable="false" show-icon :title="loadError" />

      <template v-else-if="contract">
        <div class="contract-overview">
          <div>
            <span class="overview-label">文件</span>
            <strong>{{ contract.title }}</strong>
          </div>
          <div>
            <span class="overview-label">版本</span>
            <el-tag effect="plain">{{ contract.version }}</el-tag>
          </div>
          <div>
            <span class="overview-label">OpenAPI</span>
            <el-tag type="info" effect="plain">{{ contract.openapi }}</el-tag>
          </div>
          <div>
            <span class="overview-label">Operations</span>
            <strong>{{ contract.operations.length }}</strong>
          </div>
        </div>

        <div class="servers">
          <span class="overview-label">契約 server</span>
          <code v-for="serverUrl in contract.serverUrls" :key="serverUrl">{{ serverUrl }}</code>
        </div>

        <el-collapse v-model="activeOperation" accordion class="operation-list">
          <el-collapse-item v-for="operation in contract.operations" :key="operation.id" :name="operation.id">
            <template #title>
              <div class="operation-title">
                <el-tag :type="methodTagType(operation.method)" effect="dark" size="small">
                  {{ operation.method.toUpperCase() }}
                </el-tag>
                <code>{{ operation.path }}</code>
                <span>{{ operation.summary }}</span>
              </div>
            </template>
            <div class="operation-meta">
              <span v-if="operation.operationId">operationId: <code>{{ operation.operationId }}</code></span>
              <span v-if="operation.responseCodes.length">回應：{{ operation.responseCodes.join('、') }}</span>
            </div>
            <pre class="swagger-fragment">{{ operation.fragment }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-card>

    <el-drawer v-model="drawerVisible" title="完整 OpenAPI YAML" size="min(860px, 92vw)">
      <pre v-if="contract" class="full-contract">{{ contract.rawYaml }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Document } from '@element-plus/icons-vue'
import { bffApi } from '@/api'
import { OpenApiContractParseError, parseOpenApiContract } from '@/utils/openApiContract'

const loading = ref(true)
const loadError = ref('')
const contract = ref(null)
const activeOperation = ref('')
const drawerVisible = ref(false)

const methodTagTypes = {
  get: 'success',
  post: 'warning',
  put: 'primary',
  patch: 'info',
  delete: 'danger'
}

function methodTagType(method) {
  return methodTagTypes[method] || 'info'
}

async function loadContract() {
  loading.value = true
  loadError.value = ''
  contract.value = null
  activeOperation.value = ''
  try {
    contract.value = parseOpenApiContract(await bffApi.openApi.contract())
  } catch (error) {
    contract.value = null
    loadError.value = error instanceof OpenApiContractParseError
      ? error.message
      : '目前無法載入 API 文件，請稍後再試。'
  } finally {
    loading.value = false
  }
}

onMounted(loadContract)
</script>

<style scoped>
.open-api-view { max-width: 1280px; margin: 0 auto; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header h2 { font-size: 20px; color: #1e293b; }
.page-header p { margin-top: 4px; color: #64748b; font-size: 14px; }
.side-effect-notice { margin-top: 12px; }
.loading-state { padding: 28px 0; }
.contract-overview { display: flex; flex-wrap: wrap; gap: 24px; padding: 20px 0 14px; }
.contract-overview > div { display: flex; align-items: center; gap: 8px; }
.overview-label { color: #64748b; font-size: 13px; }
.servers { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-bottom: 16px; }
.servers code, .operation-title code, .operation-meta code { color: #0f4c81; }
.servers code { background: #f1f5f9; padding: 3px 6px; border-radius: 4px; overflow-wrap: anywhere; }
.operation-list { border-top: 1px solid #e2e8f0; }
.operation-title { display: flex; align-items: center; gap: 10px; min-width: 0; padding-right: 12px; }
.operation-title code { flex: 0 0 auto; font-weight: 600; }
.operation-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475569; }
.operation-meta { display: flex; flex-wrap: wrap; gap: 16px; margin: 2px 0 10px; color: #64748b; font-size: 13px; }
.swagger-fragment, .full-contract { margin: 0; padding: 14px; border-radius: 6px; background: #0f172a; color: #e2e8f0; overflow: auto; white-space: pre; font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace; }
.full-contract { min-height: 100%; }
@media (max-width: 640px) {
  .page-header { align-items: flex-start; flex-direction: column; }
  .contract-overview { gap: 12px 20px; }
  .operation-title { gap: 7px; }
  .operation-title span { display: none; }
  .swagger-fragment { font-size: 11px; }
}
</style>
