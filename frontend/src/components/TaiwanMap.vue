<template>
  <svg :width="size" :height="size * 1.6" viewBox="0 0 100 160" xmlns="http://www.w3.org/2000/svg">
    <path d="M55 5 Q70 15 75 35 Q78 50 76 65 Q74 80 70 95 Q65 110 55 125 Q48 135 42 145 Q38 150 35 148 Q30 142 28 130 Q25 115 26 100 Q27 85 30 70 Q33 55 38 40 Q42 28 48 15 Q52 8 55 5Z"
      :fill="color" stroke="#2d7a3a" stroke-width="2" />
  </svg>
</template>
<script setup>
defineProps({
  size: { type: Number, default: 14 },
  color: { type: String, default: '#22c55e' }
})
</script>
