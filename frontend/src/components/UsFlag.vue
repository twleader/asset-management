<template>
  <svg :width="size" :height="size * 10 / 19" viewBox="0 0 760 400"
       preserveAspectRatio="xMidYMid meet"
       style="flex-shrink:0;vertical-align:middle;border-radius:1px;border:1px solid rgba(0,0,0,0.1)">
    <!-- 底色：紅 -->
    <rect width="760" height="400" fill="#B22234"/>
    <!-- 6 條白色橫紋（紅白交錯共 13 條，最上是紅） -->
    <rect v-for="i in 6" :key="i"
          :y="(i * 2 - 1) * 400 / 13" width="760" :height="400 / 13" fill="#FFFFFF"/>
    <!-- 藍色 canton（高 = 7 條紋寬，寬 = 0.4 × 旗寬） -->
    <rect width="304" :height="400 * 7 / 13" fill="#3C3B6E"/>
    <!-- 50 顆星（9 排 6/5/6/5/6/5/6/5/6） -->
    <g fill="#FFFFFF">
      <path v-for="(s, i) in stars" :key="i"
            :transform="`translate(${s.x} ${s.y}) scale(11.5)`"
            d="M0,-1 L0.224,-0.309 L0.951,-0.309 L0.363,0.118 L0.588,0.809 L0,0.382 L-0.588,0.809 L-0.363,0.118 L-0.951,-0.309 L-0.224,-0.309 Z" />
    </g>
  </svg>
</template>

<script setup>
defineProps({
  size: { type: Number, default: 20 }   // 旗寬（px），高度自動 = size × 10/19
})

const SIX_X  = [47.88, 95.76, 143.64, 191.52, 239.4, 287.28]
const FIVE_X = [71.82, 119.7, 167.58, 215.46, 263.34]
const stars = []
for (let row = 0; row < 9; row++) {
  const y = 21.6 + row * 21.6
  const xs = row % 2 === 0 ? SIX_X : FIVE_X
  xs.forEach(x => stars.push({ x, y }))
}
</script>
