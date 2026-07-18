import { defineStore } from 'pinia'
import { snapshotApi } from '@/api'

export const useAssetStore = defineStore('asset', {
  state: () => ({
    snapshots: [],
    currentSnapshot: null,
    history: [],
    loading: false
  }),

  getters: {
    latestSnapshot: (state) => state.snapshots[0] ?? null
  },

  actions: {
    async fetchSnapshots() {
      this.loading = true
      try {
        this.snapshots = await snapshotApi.getAll()
      } finally {
        this.loading = false
      }
    },

    async fetchHistory() {
      this.history = await snapshotApi.getHistory()
    },

    async createSnapshot(data) {
      const result = await snapshotApi.create(data)
      await this.fetchSnapshots()
      return result
    },

    async updateSnapshot(id, data) {
      const result = await snapshotApi.update(id, data)
      await Promise.all([this.fetchSnapshots(), this.fetchHistory()])
      return result
    },

  }
})
