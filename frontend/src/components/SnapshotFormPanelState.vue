<template>
  <section :data-panel="name" :data-state="status" class="form-panel">
    <div v-if="enabled && (state.error || !state.data)" class="panel-placeholder" :role="state.error ? 'alert' : 'status'">
      <strong>{{ label }}</strong>
      <span>{{ state.error ? '載入失敗，請重試。' : '載入中…' }}</span>
      <!-- Native button remains usable while the surrounding el-form is disabled. -->
      <button v-if="state.error" type="button" :disabled="state.loading" @click="$emit('retry')">重試此區</button>
    </div>
    <template v-else>
      <div v-if="enabled && warnings.length" class="panel-warning" role="status">{{ warnings.join('；') }}</div>
      <slot />
    </template>
  </section>
</template>

<script setup>
const props = defineProps({ name: String, label: String, enabled: Boolean, state: { type: Object, required: true } })
defineEmits(['retry'])
const status = computed(() => !props.enabled ? 'ready' : props.state.error ? 'error'
  : props.state.loading || !props.state.data ? 'loading' : props.state.empty ? 'empty' : 'ready')
const warningLabels = {
  PRICES_UNAVAILABLE: '即時股價暫時無法取得，目前顯示已保存的價格',
  MARKET_STATUS_UNAVAILABLE: '目前無法確認市場開盤狀態',
  CLOSE_PRICES_UNAVAILABLE: '部分收盤價暫時無法取得，目前保留快照現值',
  TRANSACTION_RATES_UNAVAILABLE: '部分交易日匯率暫時無法取得，相關成本暫以快照匯率計算'
}
const warnings = computed(() => (props.state.data?.warnings ?? []).map(code => warningLabels[code] ?? '部分資料暫時無法更新'))
</script>

<style scoped>
.form-panel { margin-bottom: 16px; }
.panel-placeholder { display: flex; align-items: center; gap: 16px; min-height: 100px; padding: 20px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; color: #64748b; }
.panel-placeholder strong { color: #334155; }
.panel-placeholder button { border: 1px solid #bfdbfe; padding: 6px 12px; border-radius: 4px; color: #2563eb; background: #eff6ff; cursor: pointer; }
.panel-warning { padding: 10px 16px; margin-bottom: 8px; border-radius: 4px; background: #fffbeb; color: #92400e; }
</style>
