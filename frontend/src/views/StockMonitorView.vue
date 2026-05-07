<template>
  <div class="page-container">
    <el-tabs v-model="activeTab" type="card" class="monitor-tabs">
      <el-tab-pane name="watch">
        <template #label>
          <span style="display:inline-flex;align-items:center;gap:6px">
            <span style="font-size:16px;line-height:1">👁️</span>
            <span>觀察清單</span>
          </span>
        </template>
        <WatchStockView ref="watchRef" @request-new-alert="onRequestNewAlert" />
      </el-tab-pane>
      <el-tab-pane name="alert">
        <template #label>
          <span style="display:inline-flex;align-items:center;gap:6px">
            <span style="font-size:16px;line-height:1">🔔</span>
            <span>警示條件</span>
          </span>
        </template>
        <StockAlertView ref="alertRef" @alert-saved="onAlertSaved" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import WatchStockView from './WatchStockView.vue'
import StockAlertView from './StockAlertView.vue'

const route = useRoute()
const router = useRouter()
const activeTab = ref(route.query.tab === 'alert' ? 'alert' : 'watch')

const watchRef = ref(null)
const alertRef = ref(null)

watch(activeTab, (val) => {
  router.replace({ query: { ...route.query, tab: val } })
})

// 觀察清單頁的「新增觀察」按鈕：切到警示條件頁，並彈出新增 dialog
function onRequestNewAlert(initMarket) {
  activeTab.value = 'alert'
  nextTick(() => alertRef.value?.openNewDialog(initMarket))
}

// 警示條件儲存後：通知觀察清單頁重新載入（即使尚未顯示，下次切回時資料是新的）
function onAlertSaved() {
  watchRef.value?.reload()
}
</script>

<style scoped>
.page-container { padding: 4px; }
.monitor-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.monitor-tabs :deep(.el-tabs__item) { font-size: 14px; padding: 0 18px; }
</style>
