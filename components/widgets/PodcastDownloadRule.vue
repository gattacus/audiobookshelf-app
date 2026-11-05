<template>
  <div class="border border-border rounded-lg p-3 my-3 text-sm">
    <label class="flex items-center gap-3"><input type="checkbox" v-model="rule.enabled" @change="save" /> Keep next episodes downloaded</label>
    <div v-if="rule.enabled" class="mt-3 space-y-3">
      <label class="block">Episode count <input class="bg-primary border border-border rounded p-2 w-20 ml-2" type="number" min="1" max="100" v-model.number="rule.count" @change="save" /></label>
      <label class="block"><input type="checkbox" v-model="rule.wifiOnly" @change="save" /> Wi-Fi only</label>
      <label class="block">Storage <select class="bg-primary border border-border rounded p-2 max-w-full" v-model="rule.folderId" @change="save"><option value="internal-podcast">Internal app storage</option><option v-for="folder in sharedFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option></select></label>
      <p class="text-fg-muted">{{ rule.status }} · Follows continuous-playback order.</p>
      <nuxt-link class="underline" to="/listening">Download limits and cleanup</nuxt-link>
    </div>
  </div>
</template>
<script>
import { AbsListening } from '@/plugins/capacitor'
export default {
  props: { libraryItem: { type: Object, required: true } },
  data: () => ({ rule: { enabled: false, count: 3, wifiOnly: true, folderId: 'internal-podcast' }, folders: [], listener: null, saving: false }),
  computed: { sharedFolders() { return this.folders.filter(f => !f.id.startsWith('internal-')) } },
  methods: {
    async load() { if (this.saving) return; const result = await AbsListening.getDownloadRule({ libraryItemId: this.libraryItem.id }); this.rule = result.rule; this.folders = result.folders },
    async save() { this.saving = true; try { await AbsListening.saveDownloadRule({ ...this.rule, libraryItemId: this.libraryItem.id, title: this.libraryItem.media?.metadata?.title }) } catch (e) { this.$toast.error(e.message) } finally { this.saving = false; await this.load() } }
  },
  async mounted() { await this.load(); this.listener = await AbsListening.addListener('onListeningStateChanged', async () => { const { rule } = await AbsListening.getDownloadRule({ libraryItemId: this.libraryItem.id }); this.rule.status = rule.status }) },
  beforeDestroy() { this.listener?.remove() }
}
</script>
