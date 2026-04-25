<template>
  <div>
    <!-- Market Status -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title" style="display:inline-flex;align-items:center;gap:6px"><TaiwanMap :size="14" /> 台股</span></template>
          <div class="market-info">
            <div class="status-row">
              <span class="status-dot" :class="status.twMarketOpen ? 'open' : 'closed'" />
              <span class="status-text">{{ status.twMarketOpen ? '開盤中' : '休市' }}</span>
            </div>
            <div class="info-item">
              <span class="label">台灣時間</span>
              <span class="value">{{ twTimeDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">交易時間</span>
              <span class="value">週一～五 09:00 ~ 13:30</span>
            </div>
            <div class="info-item">
              <span class="label">交易所</span>
              <span class="value">TWSE 臺灣證券交易所</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title" style="display:inline-flex;align-items:center;gap:6px"><UsaMap :size="14" /> 美股</span></template>
          <div class="market-info">
            <div class="status-row">
              <span class="status-dot" :class="status.usMarketOpen ? 'open' : 'closed'" />
              <span class="status-text">{{ status.usMarketOpen ? '開盤中' : '休市' }}</span>
            </div>
            <div class="info-item">
              <span class="label">美東時間</span>
              <span class="value">{{ usTimeDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">交易時間</span>
              <span class="value">{{ usSessionDisplay }}</span>
            </div>
            <div class="info-item">
              <span class="label">日光節約</span>
              <span class="value">
                <el-tag :type="isDst ? 'success' : 'info'" size="small">{{ isDst ? '夏令時間 (DST)' : '標準時間 (EST)' }}</el-tag>
                　{{ isDst ? 'UTC-4 → 台灣 21:30~04:00' : 'UTC-5 → 台灣 22:30~05:00' }}
              </span>
            </div>
            <div class="info-item">
              <span class="label">交易所</span>
              <span class="value">NYSE / NASDAQ</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Calendar -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">交易日曆 {{ calendarYear }}年{{ String(calendarMonth).padStart(2,'0') }}月
            <el-tag v-if="holidaysLoading" type="info" size="small" style="margin-left:8px">載入假日中…</el-tag>
          </span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button size="small" @click="prevMonth">上月</el-button>
            <el-button size="small" @click="goToday">今天</el-button>
            <el-button size="small" @click="nextMonth">下月</el-button>
          </div>
        </div>
      </template>
      <div class="legend" style="margin-bottom:12px">
        <span class="legend-item"><TaiwanMap :size="16" /> 台股交易日</span>
        <span class="legend-item"><UsFlag :size="20" /> 美股交易日</span>
        <span class="legend-item">
          <span style="display:inline-flex;align-items:center;gap:3px">
            <TaiwanMap :size="16" /><UsFlag :size="20" />
          </span>
          兩市同交易
        </span>
        <span class="legend-item"><span class="legend-dot holiday" /> 假日/休市</span>
      </div>
      <table class="cal-table">
        <thead>
          <tr>
            <th v-for="d in ['日','一','二','三','四','五','六']" :key="d">{{ d }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(week, wi) in calendarWeeks" :key="wi">
            <td v-for="(day, di) in week" :key="di"
              :class="dayClass(day)"
              @click="day.date && (selectedDate = day.date)">
              <div v-if="day.day" class="cal-cell">
                <span class="cal-day" :class="{ today: day.isToday }">{{ day.day }}</span>
                <div class="cal-tags">
                  <TaiwanMap v-if="day.tw" :size="12" />
                  <UsFlag v-if="day.us" :size="14" />
                </div>
                <div v-if="day.twHoliday || day.usHoliday" class="cal-holiday">
                  <small v-if="day.twHoliday" style="color:#ef4444">{{ day.twHoliday }}</small>
                  <small v-if="day.usHoliday" style="color:#3b82f6">{{ day.usHoliday }}</small>
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </el-card>

    <!-- US DST Info -->
    <el-card style="margin-top:20px">
      <template #header><span class="section-title">美國日光節約時間說明</span></template>
      <el-descriptions :column="1" border class="dst-desc">
        <el-descriptions-item label="夏令時間 (DST)">3月第二個週日 02:00 起 ~ 11月第一個週日 02:00 止</el-descriptions-item>
        <el-descriptions-item label="夏令交易時間">美東 09:30~16:00 = 台灣 21:30~04:00 (隔日)</el-descriptions-item>
        <el-descriptions-item label="標準交易時間">美東 09:30~16:00 = 台灣 22:30~05:00 (隔日)</el-descriptions-item>
        <el-descriptions-item :label="`${calendarYear} 夏令期間`">{{ dstRange }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { marketDataApi } from '@/api'
import dayjs from 'dayjs'
import TaiwanMap from '@/components/TaiwanMap.vue'
import UsaMap from '@/components/UsaMap.vue'
import UsFlag from '@/components/UsFlag.vue'

const status = ref({ twMarketOpen: false, usMarketOpen: false, twTime: '', usTime: '' })
const calendarYear = ref(dayjs().year())
const calendarMonth = ref(dayjs().month() + 1)
const selectedDate = ref(null)

// Holiday cache by year: { 2026: { tw: {...}, us: {...} }, ... }
const holidayCache = ref({})
const holidaysLoading = ref(false)

async function loadHolidays(year) {
  if (holidayCache.value[year]) return
  holidaysLoading.value = true
  try {
    const data = await marketDataApi.getHolidays(year)
    holidayCache.value = { ...holidayCache.value, [year]: data }
  } catch (e) {
    // fallback: empty (weekends still blocked)
    holidayCache.value = { ...holidayCache.value, [year]: { tw: {}, us: {} } }
  } finally {
    holidaysLoading.value = false
  }
}

watch(calendarYear, (y) => loadHolidays(y), { immediate: false })

onMounted(async () => {
  try {
    status.value = await marketDataApi.getMarketStatus()
  } catch {}
  setInterval(async () => {
    try { status.value = await marketDataApi.getMarketStatus() } catch {}
  }, 60000)
  loadHolidays(calendarYear.value)
})

const twTimeDisplay = computed(() => {
  if (!status.value.twTime) return '-'
  return dayjs(status.value.twTime).format('YYYY/MM/DD (dd) HH:mm:ss')
})

const usTimeDisplay = computed(() => {
  if (!status.value.usTime) return '-'
  return dayjs(status.value.usTime).format('YYYY/MM/DD (dd) HH:mm:ss')
})

// US DST calculation
function getDstStart(year) {
  // 3月第二個週日
  let d = dayjs(`${year}-03-01`)
  let count = 0
  while (count < 2) {
    if (d.day() === 0) count++
    if (count < 2) d = d.add(1, 'day')
  }
  return d
}

function getDstEnd(year) {
  // 11月第一個週日
  let d = dayjs(`${year}-11-01`)
  while (d.day() !== 0) {
    d = d.add(1, 'day')
  }
  return d
}

const isDst = computed(() => {
  const now = dayjs()
  const start = getDstStart(now.year())
  const end = getDstEnd(now.year())
  return now.isAfter(start) && now.isBefore(end)
})

const usSessionDisplay = computed(() => {
  return isDst.value
    ? '週一～五 09:30~16:00 (美東夏令 UTC-4)'
    : '週一～五 09:30~16:00 (美東標準 UTC-5)'
})

const dstRange = computed(() => {
  const y = calendarYear.value
  const s = getDstStart(y)
  const e = getDstEnd(y)
  return `${s.format('MM/DD')} ~ ${e.format('MM/DD')}`
})

// Calendar generation
const calendarWeeks = computed(() => {
  const y = calendarYear.value
  const m = calendarMonth.value
  const firstDay = dayjs(`${y}-${String(m).padStart(2,'0')}-01`)
  const daysInMonth = firstDay.daysInMonth()
  const startDow = firstDay.day() // 0=Sun

  const cached = holidayCache.value[y] || { tw: {}, us: {} }
  const twHolidays = cached.tw
  const usHolidays = cached.us
  const today = dayjs().format('YYYY-MM-DD')

  const weeks = []
  let week = []

  // Fill leading blanks
  for (let i = 0; i < startDow; i++) {
    week.push({})
  }

  for (let d = 1; d <= daysInMonth; d++) {
    const date = dayjs(`${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`)
    const dateStr = date.format('YYYY-MM-DD')
    const dow = date.day()
    const isWeekend = dow === 0 || dow === 6

    // 台股交易日: 週一～五，非台灣假日
    const tw = !isWeekend && !twHolidays[dateStr]
    // 美股交易日: 週一～五，非美國假日
    const us = !isWeekend && !usHolidays[dateStr]

    week.push({
      day: d,
      date: dateStr,
      dow,
      isToday: dateStr === today,
      tw,
      us,
      isWeekend,
      twHoliday: twHolidays[dateStr] || null,
      usHoliday: usHolidays[dateStr] || null
    })

    if (week.length === 7) {
      weeks.push(week)
      week = []
    }
  }

  // Fill trailing blanks
  if (week.length > 0) {
    while (week.length < 7) week.push({})
    weeks.push(week)
  }

  return weeks
})

function dayClass(day) {
  if (!day.day) return 'empty'
  if (day.isWeekend) return 'weekend'
  if (day.tw && day.us) return 'both'
  if (!day.tw && !day.us) return 'holiday'
  return ''
}

function prevMonth() {
  if (calendarMonth.value === 1) {
    calendarYear.value--
    calendarMonth.value = 12
  } else {
    calendarMonth.value--
  }
}

function nextMonth() {
  if (calendarMonth.value === 12) {
    calendarYear.value++
    calendarMonth.value = 1
  } else {
    calendarMonth.value++
  }
}

function goToday() {
  calendarYear.value = dayjs().year()
  calendarMonth.value = dayjs().month() + 1
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }

.market-info { display: flex; flex-direction: column; gap: 12px; }

.status-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.status-dot { width: 12px; height: 12px; border-radius: 50%; display: inline-block; }
.status-dot.open { background: #16a34a; box-shadow: 0 0 6px #16a34a; }
.status-dot.closed { background: #94a3b8; }
.status-text { font-size: 16px; font-weight: 600; }

.info-item { display: flex; gap: 12px; }
.info-item .label { color: #64748b; min-width: 80px; }
.info-item .value { font-weight: 500; }

/* Legend */
.legend { display: flex; gap: 20px; font-size: 13px; color: #475569; }
.legend-item { display: flex; align-items: center; gap: 4px; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.legend-dot.tw { background: #ef4444; }
.legend-dot.us { background: #3b82f6; }
.legend-dot.both { background: linear-gradient(135deg, #ef4444 50%, #3b82f6 50%); }
.legend-dot.holiday { background: #e2e8f0; }

/* Calendar */
.cal-table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.cal-table th { padding: 8px; text-align: center; color: #64748b; font-weight: 600; font-size: 13px; border-bottom: 2px solid #e2e8f0; }
.cal-table td { padding: 4px; vertical-align: top; min-height: 70px; height: 70px; border: 1px solid #f1f5f9; cursor: pointer; transition: background 0.15s; }
.cal-table td:hover:not(.empty) { background: #f8fafc; }
.cal-table td.empty { background: #fafafa; cursor: default; }
.cal-table td.weekend { background: #fef2f2; }
.cal-table td.holiday { background: #fff7ed; }
.cal-table td.both { background: #f0fdf4; }

.cal-cell { display: flex; flex-direction: column; align-items: center; gap: 2px; min-height: 60px; }
.cal-day { font-size: 14px; font-weight: 600; color: #1e293b; }
.cal-day.today { background: #3b82f6; color: white; border-radius: 50%; width: 26px; height: 26px; display: flex; align-items: center; justify-content: center; }

.cal-tags { display: flex; gap: 3px; }
.cal-tags .dot { width: 8px; height: 8px; border-radius: 50%; }
.cal-tags .dot.tw { background: #ef4444; }
.cal-tags .dot.us { background: #3b82f6; }

.cal-holiday { text-align: center; line-height: 1.2; width: 100%; }
.cal-holiday small { font-size: 10px; display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }

/* DST descriptions */
.dst-desc :deep(.el-descriptions__label) { font-size: 15px; font-weight: 600; width: 200px; }
.dst-desc :deep(.el-descriptions__content) { font-size: 15px; }
</style>
