<template>
  <svg :width="width" :height="height" viewBox="0 0 30 18"
    style="flex-shrink:0;vertical-align:middle;border:1px solid #cbd5e1">
    <rect width="30" height="18" fill="#b22234"/>
    <rect y="1.38" width="30" height="1.38" fill="white"/>
    <rect y="4.15" width="30" height="1.38" fill="white"/>
    <rect y="6.92" width="30" height="1.38" fill="white"/>
    <rect y="9.69" width="30" height="1.38" fill="white"/>
    <rect y="12.46" width="30" height="1.38" fill="white"/>
    <rect y="15.23" width="30" height="1.38" fill="white"/>
    <rect width="12" height="9.69" fill="#3c3b6e"/>
  </svg>
</template>
<script setup>
const props = defineProps({
  size: { type: Number, default: 20 }
})
const width = computed(() => props.size)
const height = computed(() => Math.round(props.size * 0.6))
</script>
