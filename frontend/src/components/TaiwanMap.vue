<template>
  <svg :width="size" :height="size" viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg"
    style="flex-shrink:0;vertical-align:middle">
    <circle cx="50" cy="50" r="46" fill="none" :stroke="color" :stroke-width="strokeWidth" />
    <path
      d="M55 5 Q70 15 75 35 Q78 50 76 65 Q74 80 70 95 Q65 110 55 125 Q48 135 42 145 Q38 150 35 148 Q30 142 28 130 Q25 115 26 100 Q27 85 30 70 Q33 55 38 40 Q42 28 48 15 Q52 8 55 5Z"
      :fill="color"
      transform="translate(29 18) scale(0.41)" />
  </svg>
</template>
<script setup>
defineProps({
  size: { type: Number, default: 20 },
  color: { type: String, default: '#16a34a' },
  strokeWidth: { type: Number, default: 4 }
})
</script>
