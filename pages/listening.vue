<template>
  <div class="p-4 pb-28 overflow-y-auto h-full text-fg">
    <h1 class="text-xl font-semibold mb-4">Listening &amp; downloads</h1>
    <template v-if="$platform === 'android' && settings">
      <h2 class="text-lg mt-6 mb-2">Progress waiting to sync ({{ pending.length }})</h2>
      <p v-if="!pending.length" class="text-fg-muted">No pending items.</p>
      <div v-for="item in pending" :key="key(item.identity)" class="border-b border-border py-3">
        <p>{{ item.title || 'Listening item' }} <span v-if="item.identity.episodeId" class="text-xs">· Episode</span></p>
        <p class="text-xs text-fg-muted">{{ item.state }} · {{ item.pendingSessions }} pending sessions · Last saved {{ time(item.savedAt) }}</p>
        <p v-if="item.error" class="text-error text-xs">{{ item.error }}</p>
        <button class="underline py-2" :disabled="busy" @click="retry(item)">Retry sync</button>
      </div>
      <h2 class="text-lg mt-6 mb-2">Automatic downloads</h2>
      <label>Automatic audio storage limit <select class="bg-primary border border-border rounded p-2 my-2" v-model.number="settings.automaticLimitBytes" @change="saveSettings"><option v-for="size in [1,2,5,10,0]" :key="size" :value="size * 1073741824">{{ size ? size + ' GiB' : 'Unlimited' }}</option></select></label>
      <p class="text-sm text-fg-muted">Enable downloads on each podcast page. Unfinished downloads are kept when the limit is reached.</p>
      <div v-for="rule in rules" :key="key(rule.identity)" class="py-2 border-b border-border text-sm"><nuxt-link class="underline" :to="`/item/${rule.identity.itemId}`">{{ rule.title || 'Podcast' }}</nuxt-link>: {{ rule.enabled ? rule.status : 'Disabled' }}</div>
      <h2 class="text-lg mt-6 mb-2">Automatic cleanup</h2>
      <div v-for="kind in cleanupKinds" :key="kind.enabled" class="py-2">
        <label><input type="checkbox" v-model="settings[kind.enabled]" @change="saveSettings" /> {{ kind.label }}</label>
        <select v-model.number="settings[kind.delay]" class="bg-primary border border-border rounded p-2 ml-2" @change="saveSettings"><option :value="0">Immediately</option><option :value="24">After 24 hours</option><option :value="168">After 7 days</option></select>
      </div>
      <p class="text-sm text-fg-muted">Automatic cleanup waits for confirmed server sync. Ebooks, progress, bookmarks, and server files stay.</p>
      <h2 class="text-lg mt-6 mb-2">Clean up finished downloads</h2>
      <label>Show <select v-model="filter" class="bg-primary border border-border rounded p-2"><option value="all">Books and episodes</option><option value="books">Books</option><option value="episodes">Episodes</option></select></label>
      <p v-if="!visibleCandidates.length" class="py-4 text-fg-muted">No finished audio downloads.</p>
      <div v-for="item in visibleCandidates" :key="key(item.identity)" class="border-b border-border py-3">
        <label class="flex items-center gap-3"><input type="checkbox" :disabled="!item.eligible" :value="key(item.identity)" v-model="selected" /> {{ item.title }} · {{ $bytesPretty(item.bytes) }}</label>
        <p v-if="item.reason" class="text-xs text-fg-muted mt-1">{{ item.reason }}</p>
        <label class="block text-xs mt-2"><input type="checkbox" :checked="item.keepDownloaded" @change="protect(item, $event.target.checked)" /> Keep downloaded</label>
      </div>
      <button class="border border-border rounded p-3 mt-4" :disabled="busy || !selectedItems.length" @click="cleanup">Delete selected audio · {{ $bytesPretty(selectedItems.reduce((n, item) => n + item.bytes, 0)) }}</button>
      <p v-if="error" class="text-error mt-3" role="alert">{{ error }}</p>
    </template>
  </div>
</template>
<script>
import { AbsListening } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
export default {
  data: () => ({ settings: null, savedSettings: null, rules: [], pending: [], candidates: [], selected: [], filter: 'all', listener: null, busy: false, error: '',
    cleanupKinds: [{ enabled: 'cleanBooks', delay: 'bookDelayHours', label: 'Delete finished book audio' }, { enabled: 'cleanEpisodes', delay: 'episodeDelayHours', label: 'Delete finished episode audio' }] }),
  computed: {
    visibleCandidates() { return this.candidates.filter(item => this.filter === 'all' || (this.filter === 'episodes') === !!item.identity.episodeId) },
    selectedItems() { return this.visibleCandidates.filter(item => item.eligible && this.selected.includes(this.key(item.identity))) }
  },
  methods: {
    key(id) { return JSON.stringify(id) },
    time(value) { return value ? new Date(value).toLocaleString() : 'Not yet verified' },
    async load() {
      if (this.busy || this.$platform !== 'android') return
      try {
        const [management, summary, preview] = await Promise.all([AbsListening.getManagement(), AbsListening.getSyncSummary(), AbsListening.getCleanupPreview()])
        this.settings = management.settings; this.savedSettings = { ...this.settings }; this.rules = management.rules; this.pending = summary.items; this.candidates = preview.items
      } catch (e) { this.error = e.message }
    },
    async retry(item) { this.busy = true; try { await AbsListening.retrySync({ identity: item.identity }) } catch (e) { this.error = e.message } finally { this.busy = false; await this.load() } },
    async protect(item, enabled) { this.busy = true; try { await AbsListening.setKeepDownloaded({ identity: item.identity, enabled }) } catch (e) { this.error = e.message } finally { this.busy = false; await this.load() } },
    async saveSettings() {
      this.busy = true
      try {
        if ((this.settings.cleanBooks && !this.savedSettings.cleanBooks) || (this.settings.cleanEpisodes && !this.savedSettings.cleanEpisodes)) {
          const { items } = await AbsListening.getCleanupPreview({ automatic: true, settings: this.settings })
          const applicable = items.filter(item => item.identity.episodeId ? this.settings.cleanEpisodes : this.settings.cleanBooks)
          const names = applicable.map(item => `${item.title}: ${item.reason || 'Eligible now'}`).join('\n')
          const { value } = await Dialog.confirm({ title: 'Enable automatic cleanup', message: `Existing finished downloads are included. Keep downloaded protects individual items.\n\n${names || 'No existing finished downloads.'}`, okButtonTitle: 'Enable', cancelButtonTitle: 'Cancel' })
          if (!value) { this.settings = { ...this.savedSettings }; return }
        }
        await AbsListening.saveManagementSettings({ settings: this.settings }); this.savedSettings = { ...this.settings }
      } catch (e) { this.error = e.message; this.settings = { ...this.savedSettings } } finally { this.busy = false; await this.load() }
    },
    async cleanup() {
      const items = this.selectedItems
      const { value } = await Dialog.confirm({ title: 'Delete downloaded audio', message: `${items.map(item => item.title).join('\n')}\n\nRemove ${this.$bytesPretty(items.reduce((n,item) => n + item.bytes, 0))}? Progress and pending listening sessions stay on this phone, including while offline. Ebooks and server files stay.`, okButtonTitle: 'Delete audio', cancelButtonTitle: 'Cancel' })
      if (!value) return
      this.busy = true
      try { const result = await AbsListening.cleanupFinished({ identities: items.map(item => item.identity) }); this.error = result.failures.join('\n'); this.selected = [] }
      catch (e) { this.error = e.message } finally { this.busy = false; await this.load() }
    }
  },
  async mounted() { if (this.$platform === 'android') { await this.load(); this.listener = await AbsListening.addListener('onListeningStateChanged', this.load) } },
  beforeDestroy() { this.listener?.remove() }
}
</script>
