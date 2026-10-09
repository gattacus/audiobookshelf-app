<template>
  <div v-if="$platform === 'android'" class="mt-3">
    <button type="button" role="switch" :aria-checked="enabled ? 'true' : 'false'" aria-label="Continuous playback" :disabled="loading || saving || loadFailed || isCasting" class="continuous-podcast-toggle w-full flex items-center justify-between gap-4 text-left py-2" @click="toggle">
      <span>
        <span class="block text-sm">Continuous playback</span>
        <span class="block text-xs text-fg-muted mt-1">{{ isCasting ? 'Continuous playback is available on this device' : 'Automatically play the next unfinished episode' }}</span>
      </span>
      <span aria-hidden="true" class="continuous-podcast-track border rounded-full border-gray-400 flex items-center w-10 flex-shrink-0" :class="enabled ? 'bg-success' : 'bg-primary'">
        <span class="rounded-full w-5 h-5 bg-white border border-gray-100 shadow transform transition-transform duration-100" :class="enabled ? 'translate-x-5' : ''" />
      </span>
    </button>
    <button v-if="loadFailed" type="button" class="text-sm underline py-2" @click="load">Retry loading playback setting</button>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  props: { libraryItem: Object, serverConnectionConfigId: String },
  data() {
    return { enabled: false, loading: true, saving: false, loadFailed: false, requestId: 0 }
  },
  computed: {
    isCasting() { return !!this.$store?.state?.isCasting },
    target() {
      return { libraryItemId: this.libraryItem?.id, serverConnectionConfigId: this.serverConnectionConfigId, podcastType: this.libraryItem?.media?.metadata?.type }
    }
  },
  watch: {
    target: { immediate: true, handler() { this.load() } }
  },
  beforeDestroy() { this.requestId++ },
  methods: {
    async load() {
      if (this.$platform !== 'android' || !this.target.libraryItemId) return
      const request = ++this.requestId
      this.loading = true
      this.loadFailed = false
      this.$emit('ready', false)
      try {
        const result = await AbsAudioPlayer.getPodcastContinuousPlayback(this.target)
        if (request !== this.requestId) return
        this.enabled = result.enabled === true
        this.$emit('change', this.enabled)
        this.$emit('ready', true)
      } catch (error) {
        if (request !== this.requestId) return
        this.loadFailed = true
        this.$toast.error('Could not load continuous playback setting')
      } finally {
        if (request === this.requestId) this.loading = false
      }
    },
    async toggle() {
      if (this.loading || this.saving || this.loadFailed || this.isCasting) return
      const request = this.requestId
      this.saving = true
      try {
        const result = await AbsAudioPlayer.setPodcastContinuousPlayback({ ...this.target, enabled: !this.enabled })
        if (request !== this.requestId) return
        this.enabled = result.enabled === true
        this.$emit('change', this.enabled)
      } catch (error) {
        if (request === this.requestId) this.$toast.error('Could not save continuous playback setting')
      } finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.continuous-podcast-toggle { min-height: 56px; }
.continuous-podcast-toggle:disabled { opacity: 0.6; cursor: not-allowed; }
.continuous-podcast-toggle:focus-visible { outline: 2px solid currentColor; outline-offset: 3px; border-radius: 4px; }
</style>
