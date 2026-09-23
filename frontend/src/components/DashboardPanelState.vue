<template>
  <div :data-dashboard-panel="panel" :aria-busy="state.loading">
    <div v-if="state.data && !state.empty">
      <div v-for="warning in warnings" :key="warning" class="panel-notice">{{ warning }}</div>
      <div v-if="state.refreshError" class="panel-notice" role="status">
        {{ label }}更新失敗，保留目前資料。
        <el-button size="small" link type="primary" @click="$emit('retry')">重試</el-button>
      </div>
      <slot />
    </div>
    <div v-else class="panel-placeholder" :style="{ minHeight: height + 'px' }" role="status">
      <template v-if="state.error">
        <span>{{ label }}載入失敗，請重試。</span>
        <el-button size="small" type="primary" plain @click="$emit('retry')">重試</el-button>
      </template>
      <span v-else-if="state.loading">{{ label }}載入中…</span>
      <span v-else-if="state.empty">{{ label }}目前沒有資料</span>
      <span v-else>等待載入{{ label }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  state: { type: Object, required: true },
  panel: { type: String, required: true },
  label: { type: String, required: true },
  height: { type: Number, default: 280 }
})
defineEmits(['retry'])
const warnings = computed(() => (props.state.data?.warnings ?? []).map(warning => ({
  LIVE_ASSETS_UNAVAILABLE: '即時估值暫無資料，已顯示快照凍結值。',
  MARKET_STATUS_UNAVAILABLE: '市場狀態暫無資料。'
}[warning] ?? warning)))
</script>

<style scoped>
.panel-placeholder { display: flex; align-items: center; justify-content: center; gap: 12px; color: #64748b; font-size: 14px; }
.panel-notice { padding: 6px 10px; margin-bottom: 8px; color: #9a6700; background: #fffbeb; border-radius: 6px; font-size: 12px; }
</style>
