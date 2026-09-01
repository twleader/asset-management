import { defineStore } from 'pinia'
import { bffApi } from '@/api'

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
        this.snapshots = await bffApi.snapshotList.getAll()
      } finally {
        this.loading = false
      }
    },

    async fetchHistory() {
      this.history = await bffApi.assetHistory.getHistory()
    },

  }
})
