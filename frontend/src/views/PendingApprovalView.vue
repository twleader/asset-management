<template>
  <div class="pending-wrap">
    <el-card class="pending-card">
      <el-result icon="warning" title="帳號等待核准" sub-title="您的帳號已建立，需由管理者核准後才能使用系統。">
        <template #extra>
          <p v-if="auth.me" class="pending-email">登入帳號：{{ auth.me.email }}</p>
          <div class="pending-actions">
            <el-button type="primary" :loading="checking" @click="recheck">重新檢查</el-button>
            <el-button @click="auth.logout()">登出</el-button>
          </div>
          <p class="pending-hint">核准後此頁會自動進入系統（每 15 秒自動檢查一次）。</p>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup>
import { useAuthStore } from '@/stores/authStore'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()
const checking = ref(false)
let timer = null

async function recheck() {
  checking.value = true
  try {
    await auth.fetchMe()
    if (!auth.isLoggedIn) {
      auth.login()
      return
    }
    if (auth.isActive) {
      router.replace('/dashboard')
    }
  } finally {
    checking.value = false
  }
}

onMounted(() => {
  timer = setInterval(recheck, 15000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.pending-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
}
.pending-card {
  max-width: 520px;
  width: 100%;
}
.pending-email {
  color: #475569;
  margin-bottom: 16px;
}
.pending-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
.pending-hint {
  margin-top: 16px;
  color: #94a3b8;
  font-size: 13px;
}
</style>
