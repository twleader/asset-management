<template>
  <div class="pending-wrap">
    <el-card class="pending-card">
      <el-result icon="warning" title="帳號等待核准" sub-title="您的帳號已建立，需由管理者核准後才能使用系統。">
        <template #extra>
          <p v-if="auth.me" class="pending-email">登入帳號：{{ auth.me.email }}</p>
          <div class="pending-actions">
            <el-button type="primary" @click="auth.login()">已獲核准，重新登入</el-button>
            <el-button @click="auth.logout()">登出</el-button>
          </div>
          <p class="pending-hint">請管理者於「使用者管理」核准後，按「已獲核准，重新登入」即可進入系統。</p>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup>
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
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
