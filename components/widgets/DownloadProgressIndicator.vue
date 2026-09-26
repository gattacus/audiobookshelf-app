<template>
  <div v-if="downloadItemPartsRemaining.length || hasFailedDownloads" @click="clickedIt">
    <widgets-circle-progress v-if="downloadItemPartsRemaining.length" :value="progress" :count="downloadItemPartsRemaining.length" />
    <span v-else class="material-symbols text-error p-2" :aria-label="$strings.LabelDownloadFailed">error</span>
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'
import { storageFailureMessage } from '@/utils/downloadStatus'

export default {
  data() {
    return {
      downloadItemListener: null,
      completeListener: null,
      itemPartUpdateListener: null,
      queueChangedListener: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    },
    downloadItemPartsRemaining() {
      return this.downloadItemParts.filter((dip) => !dip.completed && !dip.failed)
    },
    hasFailedDownloads() {
      return this.downloadItemParts.some((dip) => dip.failed)
    },
    progress() {
      let totalBytes = 0
      let totalBytesDownloaded = 0
      this.downloadItemParts.filter((dip) => !dip.failed).forEach((dip) => {
        totalBytes += dip.fileSize
        totalBytesDownloaded += dip.bytesDownloaded
      })

      if (!totalBytes) return 0
      return Math.min(1, totalBytesDownloaded / totalBytes)
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item download complete payload')
        return
      }

      if (!data.localLibraryItem) {
        this.$toast.error(this.$strings.MessageItemDownloadCompleteFailedToCreate)
      } else {
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      }

      if (data.localMediaProgress) {
        console.log('onItemDownloadComplete updating local media progress', data.localMediaProgress.id)
        this.$store.commit('globals/updateLocalMediaProgress', data.localMediaProgress)
      }

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
    },
    onDownloadItem(downloadItem) {
      console.log('DownloadProgressIndicator onDownloadItem', JSON.stringify(downloadItem))

      downloadItem.itemProgress = 0
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      const item = this.downloadItems.find((di) => di.id === itemPart.downloadItemId)
      const alreadyFailedStorage = item?.downloadItemParts.some((dip) => dip.failed && dip.failureCode === 'INSUFFICIENT_STORAGE')
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
      if (itemPart.failed && itemPart.failureCode === 'INSUFFICIENT_STORAGE' && !alreadyFailedStorage) {
        this.$toast.error(storageFailureMessage(itemPart, this.$getString, this.$bytesPretty), { timeout: 10000 })
      }
    },
    onQueueChanged(data) {
      if (!data.hasWork) {
        this.$store.commit(this.$platform === 'android' ? 'globals/retainFailedDownloads' : 'globals/clearItemDownloads')
      }
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.queueChangedListener = await AbsDownloader.addListener('onQueueChanged', (data) => this.onQueueChanged(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
    this.queueChangedListener?.remove()
  }
}
</script>
