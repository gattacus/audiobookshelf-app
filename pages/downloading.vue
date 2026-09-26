<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>

    <div v-if="!downloadItemParts.length" class="py-6 text-center text-lg">No download item parts</div>
    <template v-for="(itemPart, num) in downloadItemParts">
      <div :key="`${itemPart.downloadItemId}-${itemPart.id}`" class="w-full">
        <div class="flex">
          <div class="w-14">
            <span v-if="itemPart.failed" class="material-symbols text-error" :aria-label="$strings.LabelDownloadFailed">error</span>
            <span v-else-if="itemPart.completed" class="material-symbols text-success">check_circle</span>
            <span v-else class="font-semibold text-fg">{{ Math.round(itemPart.progress) }}%</span>
          </div>
          <div class="flex-grow px-2">
            <p class="break-all">{{ itemPart.filename }}</p>
            <p v-if="itemPart.failed" class="text-sm text-error mt-1" role="alert">{{ failureMessage(itemPart) }}</p>
            <p v-else-if="itemPart.waitingForSpace" class="text-sm text-warning mt-1">{{ $strings.MessageWaitingForAvailableStorage }}</p>
            <button v-if="$platform === 'android' && canRetry(itemPart)" class="border border-border rounded px-3 py-2 mt-2" :disabled="retrying[itemPart.downloadItemId]" @click="retry(itemPart)">{{ $strings.ButtonRetryDownload }}</button>
          </div>
        </div>

        <div v-if="num + 1 < downloadItemParts.length" class="flex border-t border-border my-3" />
      </div>
    </template>
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'
import { storageFailureMessage } from '@/utils/downloadStatus'

export default {
  data() {
    return { retrying: {} }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    }
  },
  methods: {
    failureMessage(part) {
      if (part.failureCode === 'INSUFFICIENT_STORAGE') return storageFailureMessage(part, this.$getString, this.$bytesPretty)
      return this.$strings.LabelDownloadFailed
    },
    canRetry(part) {
      if (!part.failed) return false
      const item = this.downloadItems.find((di) => di.id === part.downloadItemId)
      return item?.downloadItemParts.every((dip) => dip.failed || dip.moved)
    },
    async retry(part) {
      this.$set(this.retrying, part.downloadItemId, true)
      try {
        await AbsDownloader.retryDownloadItem({ downloadItemId: part.downloadItemId })
      } catch (error) {
        this.$toast.error(error.message || this.$strings.LabelDownloadFailed)
      } finally {
        this.$set(this.retrying, part.downloadItemId, false)
      }
    }
  }
}
</script>
