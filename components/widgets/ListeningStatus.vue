<template>
  <div class="pointer-events-auto text-xs" @click.stop>
    <button type="button" class="underline py-1" @click="showDetails">{{ label }}</button>
    <nuxt-link class="ml-3 underline" to="/listening" @click.native="$emit('open-management')">Listening &amp; downloads</nuxt-link>
  </div>
</template>
<script>
import { AbsListening } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
export default {
  data: () => ({ status: null, listener: null, request: 0 }),
  computed: {
    session() { return this.$store.state.currentPlaybackSession },
    label() {
      const s = this.status
      if (!s) return 'Progress not yet verified'
      return { saving: 'Saving…', waiting: 'Saved on this phone · Waiting to sync', syncing: 'Syncing…',
        local_only: 'Saved on this phone · Local only', unverified: 'Progress not yet verified',
        error: s.error || 'Progress save/sync error', synced: `Synced at ${this.time(s.acknowledgedAt)}` }[s.state]
    }
  },
  watch: { session: { handler() { this.load() }, immediate: true } },
  methods: {
    time(value) { return value ? new Date(value).toLocaleTimeString() : 'Not yet verified' },
    async load() {
      const request = ++this.request
      try { const { status } = await AbsListening.getProgressStatus(); if (request === this.request) this.status = status }
      catch (_) { if (request === this.request) this.status = null }
    },
    async showDetails() {
      await this.load()
      const s = this.status
      if (!s) return
      const detail = `Position: ${this.$secondsToTimestampFull(Math.floor(s.position))}\nSaved on phone: ${this.time(s.savedAt)}\nServer confirmed: ${this.time(s.acknowledgedAt)}\nServer position: ${s.serverPosition == null ? 'Unknown' : this.$secondsToTimestampFull(Math.floor(s.serverPosition))}\nPending sessions: ${s.pendingSessions}${s.error ? '\n' + s.error : ''}`
      if (s.state === 'local_only') return Dialog.alert({ title: 'Progress on this phone', message: detail })
      const { value } = await Dialog.confirm({ title: 'Progress sync', message: detail, okButtonTitle: 'Retry sync', cancelButtonTitle: 'Close' })
      if (value) { try { await AbsListening.retrySync({ identity: s.identity }) } catch (e) { this.$toast.error(e.message) }; this.load() }
    }
  },
  async mounted() { this.listener = await AbsListening.addListener('onListeningStateChanged', this.load); this.load() },
  beforeDestroy() { this.request++; this.listener?.remove() }
}
</script>
