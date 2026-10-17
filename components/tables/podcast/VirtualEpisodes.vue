<template>
  <div ref="list" role="list">
    <div aria-hidden="true" :style="{ height: offsets[range.start] + 'px' }" />
    <div v-for="(episode, index) in visibleEpisodes" :key="episode.id" ref="rows" :data-index="range.start + index" role="listitem" :aria-posinset="range.start + index + 1" :aria-setsize="episodes.length">
      <slot :episode="episode" />
    </div>
    <div aria-hidden="true" :style="{ height: (offsets[offsets.length - 1] - offsets[range.end]) + 'px' }" />
  </div>
</template>

<script>
import { episodeIndexAt, episodeWindow, getEpisodeLayout, rememberEpisodeLayout } from '@/utils/episodeList'

export default {
  props: {
    episodes: { type: Array, default: () => [] },
    itemId: String,
    resetKey: String
  },
  data() {
    const layout = this.$store && this.itemId ? getEpisodeLayout(this.$store, this.itemId) : null
    return { rowHeights: layout?.heights || {}, measuredWidth: layout?.width || 0, viewportTop: 0, viewportHeight: 800 }
  },
  computed: {
    offsets() {
      const offsets = [0]
      for (const episode of this.episodes) offsets.push(offsets[offsets.length - 1] + (this.rowHeights[episode.id] || 160))
      return offsets
    },
    range() {
      return episodeWindow(this.offsets, this.viewportTop, this.viewportHeight)
    },
    visibleEpisodes() {
      return this.episodes.slice(this.range.start, this.range.end)
    }
  },
  watch: {
    episodes() {
      this.$nextTick(this.scheduleViewport)
    },
    resetKey() {
      // A new filter/order starts at the list header; normal progress updates keep position.
      if (this.scrollRoot) this.scrollRoot.scrollTop = Math.min(this.scrollRoot.scrollTop, this.listTop())
      this.scheduleViewport()
    }
  },
  methods: {
    listTop() {
      return this.$el.getBoundingClientRect().top - this.scrollRoot.getBoundingClientRect().top + this.scrollRoot.scrollTop
    },
    updateViewport() {
      if (!this.scrollRoot || this._isDestroyed) return
      this.viewportTop = Math.max(0, this.scrollRoot.scrollTop - this.listTop())
      this.viewportHeight = this.scrollRoot.clientHeight
    },
    scheduleViewport() {
      if (this.frame || this._isDestroyed) return
      this.frame = requestAnimationFrame(() => { this.frame = null; this.updateViewport() })
    },
    observeRows() {
      if (!this.resizeObserver) return
      // Only mounted rows are observed, and removed rows must not remain retained.
      this.resizeObserver.disconnect()
      this.resizeObserver.observe(this.scrollRoot)
      for (const row of this.$refs.rows || []) this.resizeObserver.observe(row)
    },
    measureRows(entries) {
      const anchor = episodeIndexAt(this.offsets, this.viewportTop)
      let adjustment = 0
      for (const entry of entries) {
        if (entry.target === this.scrollRoot) {
          if (this.measuredWidth !== this.scrollRoot.clientWidth) {
            this.measuredWidth = this.scrollRoot.clientWidth
            this.rowHeights = {}
          }
          this.scheduleViewport()
          continue
        }
        const index = Number(entry.target.dataset.index)
        const episode = this.episodes[index]
        if (!episode) continue
        const height = entry.target.getBoundingClientRect().height
        const oldHeight = this.rowHeights[episode.id] || 160
        if (height <= 0 || Math.abs(height - oldHeight) < 1) continue
        if (index < anchor) adjustment += height - oldHeight
        this.$set(this.rowHeights, episode.id, height)
      }
      // Keep the visible content anchored when an overscan row changes height.
      if (adjustment) this.scrollRoot.scrollTop += adjustment
      this.scheduleViewport()
    }
  },
  mounted() {
    let parent = this.$el.parentElement
    while (parent && !/auto|scroll/.test(getComputedStyle(parent).overflowY)) parent = parent.parentElement
    this.scrollRoot = parent
    if (!parent) return
    if (this.measuredWidth && this.measuredWidth !== parent.clientWidth) this.rowHeights = {}
    this.measuredWidth = parent.clientWidth
    this.resizeObserver = new ResizeObserver(this.measureRows)
    parent.addEventListener('scroll', this.scheduleViewport, { passive: true })
    window.addEventListener('resize', this.scheduleViewport)
    this.updateViewport()
    this.observeRows()
  },
  updated() {
    this.observeRows()
  },
  beforeDestroy() {
    if (this.$store && this.itemId && this.scrollRoot) rememberEpisodeLayout(this.$store, this.itemId, this.measuredWidth, this.rowHeights)
    if (this.frame) cancelAnimationFrame(this.frame)
    this.resizeObserver?.disconnect()
    this.scrollRoot?.removeEventListener('scroll', this.scheduleViewport)
    window.removeEventListener('resize', this.scheduleViewport)
  }
}
</script>
