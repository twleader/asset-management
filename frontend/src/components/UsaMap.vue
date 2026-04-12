<template>
  <svg :width="size * 1.7" :height="size" viewBox="0 0 170 100" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <clipPath id="usShape">
        <path d="M5 30 L15 25 L25 22 L30 18 L38 20 L42 18 L48 20 L52 15 L58 18 L65 15 L72 18 L78 12 L82 15 L88 10 L95 12 L102 8 L108 12 L115 10 L122 14 L128 12 L135 16 L140 14 L148 18 L155 15 L160 20 L163 28 L165 35 L163 42 L160 48 L155 55 L150 58 L145 62 L140 65 L135 62 L128 65 L130 72 L128 78 L122 80 L115 78 L108 82 L100 80 L95 82 L90 85 L82 82 L75 85 L68 82 L60 85 L52 80 L45 82 L38 78 L30 80 L22 75 L15 78 L10 72 L8 65 L5 55 L3 45 L5 35Z" />
      </clipPath>
    </defs>
    <!-- Red stripes background -->
    <g clip-path="url(#usShape)">
      <rect x="0" y="0" width="170" height="100" fill="#bf0a30"/>
      <rect x="0" y="8" width="170" height="7" fill="white"/>
      <rect x="0" y="22" width="170" height="7" fill="white"/>
      <rect x="0" y="36" width="170" height="7" fill="white"/>
      <rect x="0" y="50" width="170" height="7" fill="white"/>
      <rect x="0" y="64" width="170" height="7" fill="white"/>
      <rect x="0" y="78" width="170" height="7" fill="white"/>
      <!-- Blue canton -->
      <rect x="0" y="0" width="68" height="43" fill="#002868"/>
    </g>
    <!-- Border -->
    <path d="M5 30 L15 25 L25 22 L30 18 L38 20 L42 18 L48 20 L52 15 L58 18 L65 15 L72 18 L78 12 L82 15 L88 10 L95 12 L102 8 L108 12 L115 10 L122 14 L128 12 L135 16 L140 14 L148 18 L155 15 L160 20 L163 28 L165 35 L163 42 L160 48 L155 55 L150 58 L145 62 L140 65 L135 62 L128 65 L130 72 L128 78 L122 80 L115 78 L108 82 L100 80 L95 82 L90 85 L82 82 L75 85 L68 82 L60 85 L52 80 L45 82 L38 78 L30 80 L22 75 L15 78 L10 72 L8 65 L5 55 L3 45 L5 35Z"
      fill="none" stroke="#666" stroke-width="1" />
  </svg>
</template>
<script setup>
defineProps({
  size: { type: Number, default: 14 }
})
</script>
