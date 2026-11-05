<template>
  <div class="w-full py-4 overflow-hidden relative border-b border-white border-opacity-10" @click.stop="goToEpisodePage">
    <div v-if="episode" class="w-full px-1">
      <!-- Help debug for testing -->
      <!-- <template>
        <p class="text-xs mb-1">{{ isLocal ? 'LOCAL' : 'NOT LOCAL' }}</p>
        <p class="text-xs mb-4">Lid:{{ libraryItemId }}<br />Eid:{{ episode.id }}<br />LLid:{{ localLibraryItemId }}<br />LEid:{{ localEpisodeId }}</p>
        <p v-if="itemProgress">Server Media Progress {{ Math.round(itemProgress.progress * 100) }}</p>
        <p v-else>No Server Media Progress</p>
        <p v-if="localMediaProgress">Local Media Progress {{ Math.round(localMediaProgress.progress * 100) }}</p>
        <p v-else>No Local Media Progress</p>
      </template>-->

      <p v-if="publishedAt" class="text-xs text-fg-muted mb-1">{{ $getString('LabelPublishedDate', [$formatDate(publishedAt, 'MMM do, yyyy')]) }}</p>

      <p class="text-sm font-semibold">{{ title }}</p>

      <p class="text-sm text-fg episode-subtitle mt-1.5 mb-0.5" v-html="subtitle" />

      <p v-if="sortKey === 'audioFile.metadata.filename'" class="text-xs text-fg-muted truncate mt-2 mb-0.5">
        <span class="font-semibold">{{ $getString('LabelFilename') }}</span
        >: <span class="font-light">{{ filename }}</span>
      </p>

      <div v-if="episodeNumber || season || episodeType" class="flex py-2 items-center -mx-0.5">
        <div v-if="episodeNumber" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-50 rounded-full text-xs font-light text-fg">Episode #{{ episodeNumber }}</div>
        <div v-if="season" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-50 rounded-full text-xs font-light text-fg">Season #{{ season }}</div>
        <div v-if="episodeType" class="px-2 pt-px pb-0.5 mx-0.5 bg-primary bg-opacity-50 rounded-full text-xs font-light text-fg capitalize">{{ episodeType }}</div>
      </div>

      <div class="flex items-center pt-2">
        <!-- Play/Pause Button -->
        <div class="h-10 px-4 border border-border rounded-full flex items-center justify-center cursor-pointer" :class="userIsFinished ? 'text-white text-opacity-40' : ''" @click.stop="playClick">
          <span v-if="!playerIsStartingForThisMedia" class="material-symbols text-2xl fill leading-none" :class="streamIsPlaying ? '' : 'text-success'">
            {{ streamIsPlaying ? 'pause' : 'play_arrow' }}
          </span>
          <svg v-else class="animate-spin" style="width: 28px; height: 28px" viewBox="0 0 24 24">
            <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
          </svg>
          <p class="pl-2 pr-1 text-sm font-semibold">{{ timeRemaining }}</p>
        </div>

        <!-- Read Status Button -->
        <ui-read-icon-btn :disabled="isProcessingReadUpdate" :is-read="userIsFinished" borderless class="mx-1" @click="toggleFinished" />

        <!-- Add to Playlist Button -->
        <button v-if="!isLocal" class="mx-1.5" @click.stop="addToPlaylist">
          <span class="material-symbols text-2xl leading-none">playlist_add</span>
        </button>

        <!-- Download Section -->
        <div v-if="userCanDownload" class="flex items-center">
          <span v-if="isLocal" class="material-symbols px-2 text-success text-2xl leading-none">audio_file</span>
          <button v-else-if="!localEpisode && downloadItem && $platform === 'android'" type="button" class="mx-1.5 text-warning text-opacity-75" :disabled="cancellingDownload" :aria-label="$strings.ButtonCancelDownload" @click.stop="cancelDownload">
            <span class="material-symbols text-2xl leading-none animate-bounce">downloading</span>
          </button>
          <span v-else-if="!localEpisode" class="material-symbols mx-1.5 text-2xl leading-none" :class="downloadItem || startingDownload ? 'animate-bounce text-warning text-opacity-75' : ''" @click.stop="downloadClick">
            {{ downloadItem || startingDownload ? 'downloading' : 'download' }}
          </span>
          <button v-else-if="$platform === 'android'" type="button" class="px-2 text-success" :disabled="removingDownload" :aria-label="$strings.ButtonRemoveDownload" @click.stop="removeDownload">
            <span class="material-symbols text-2xl leading-none">download_done</span>
          </button>
          <span v-else class="material-symbols px-2 text-success text-2xl leading-none">download_done</span>
        </div>

        <!-- Spacer to push elements left -->
        <div class="flex-grow" />
      </div>
    </div>

    <div v-if="processing || openingEpisode" class="absolute top-0 left-0 w-full h-full bg-black bg-opacity-60 flex items-center justify-center" role="status" aria-live="polite">
      <widgets-loading-spinner size="la-lg" />
      <span v-if="openingEpisode" class="ml-3 text-sm text-fg">{{ $strings.MessageLoading }}</span>
    </div>

    <div v-if="!userIsFinished" class="absolute bottom-0 left-0 h-0.5 bg-warning" :style="{ width: itemProgressPercent * 100 + '%' }" />
  </div>
</template>

<script>
import { AbsFileSystem, AbsDownloader } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'
import { waitForEpisodePaint } from '@/utils/episodeList'
import { episodeFilename } from '@/utils/podcastEpisode'
import { rememberEpisodePage, clearEpisodePage } from '@/utils/episodeNavigation'

export default {
  props: {
    libraryItemId: String,
    libraryItem: Object,
    episode: {
      type: Object,
      default: () => {}
    },
    localLibraryItemId: String,
    localEpisode: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean,
    sortKey: String
  },
  mixins: [cellularPermissionHelpers],
  data() {
    return {
      isProcessingReadUpdate: false,
      processing: false,
      openingEpisode: false,
      startingDownload: false,
      removingDownload: false,
      cancellingDownload: false
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    mediaType() {
      return 'podcast'
    },
    userCanDownload() {
      return this.$store.getters['user/getUserCanDownload']
    },
    audioFile() {
      return this.episode.audioFile
    },
    filename() {
      return episodeFilename(this.episode)
    },
    title() {
      return this.episode.title || ''
    },
    subtitle() {
      return this.episode.subtitle || this.episode.description || ''
    },
    episodeNumber() {
      return this.episode.episode
    },
    season() {
      return this.episode.season
    },
    episodeType() {
      if (this.episode.episodeType === 'full') return null // only show Trailer/Bonus
      return this.episode.episodeType
    },
    duration() {
      return this.$secondsToTimestamp(this.episode.duration)
    },
    isStreaming() {
      return this.$store.getters['getIsMediaStreaming'](this.libraryItemId, this.episode.id)
    },
    streamIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isStreaming
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      if (!mediaId) return false

      return mediaId === this.episode?.id
    },
    itemProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, this.episode.id)
    },
    localMediaProgress() {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, this.episode.id)
      else if (this.localLibraryItemId && this.localEpisode) {
        return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId, this.localEpisode.id)
      } else {
        return null
      }
    },
    itemProgressPercent() {
      return this.itemProgress?.progress || 0
    },
    userIsFinished() {
      return !!this.itemProgress?.isFinished
    },
    timeRemaining() {
      if (this.streamIsPlaying) return 'Playing'
      if (!this.itemProgressPercent) return this.$elapsedPretty(this.episode.duration)
      if (this.userIsFinished) return 'Finished'
      var remaining = Math.floor(this.itemProgress.duration - this.itemProgress.currentTime)
      return `${this.$elapsedPretty(remaining)} left`
    },
    publishedAt() {
      return this.episode.publishedAt
    },
    downloadItem() {
      return this.$store.getters['globals/getDownloadItem'](this.libraryItemId, this.episode.id)
    },
    localEpisodeId() {
      return this.localEpisode?.id || null
    }
  },
  methods: {
    async cancelDownload() {
      if (this.$platform !== 'android' || this.cancellingDownload || !this.downloadItem?.id || this.localEpisode) return
      const id = this.downloadItem.id
      const itemId = this.libraryItemId
      const episodeId = this.episode.id
      this.cancellingDownload = true
      try {
        await this.$hapticsImpact()
        const { value } = await Dialog.confirm({
          title: this.$strings.HeaderCancelDownload,
          message: this.$getString('MessageConfirmCancelEpisodeDownload', [this.title]),
          okButtonTitle: this.$strings.ButtonCancelDownload,
          cancelButtonTitle: this.$strings.ButtonKeepDownloading
        })
        if (!value || this._isDestroyed || this.libraryItemId !== itemId || this.episode.id !== episodeId || this.downloadItem?.id !== id || this.localEpisode) return
        const result = await AbsDownloader.cancelDownloadItem({ downloadItemId: id })
        if (result?.status === 'CANCELLED' || result?.status === 'NOT_FOUND') {
          this.$store.commit('globals/removeItemDownload', id)
          this.startingDownload = false
        } else {
          this.$toast.error(result?.status === 'FINISHING' ? this.$strings.MessageDownloadFinishing : this.$strings.MessageCancelDownloadFailed)
        }
      } catch (error) {
        console.error('Failed to cancel episode download', error)
        this.$toast.error(this.$strings.MessageCancelDownloadFailed)
      } finally {
        this.cancellingDownload = false
      }
    },
    async removeDownload() {
      if (this.$platform !== 'android' || this.isLocal || this.removingDownload || !this.localLibraryItemId || !this.localEpisode?.id) return
      this.removingDownload = true
      const itemId = this.libraryItemId
      const episodeId = this.episode.id
      const localItemId = this.localLibraryItemId
      const localEpisodeId = this.localEpisode.id
      // Resolve from current storage, not a possibly stale row or legacy track reference.
      const targetFor = (item) => {
        if (item?.id !== localItemId || item.libraryItemId !== itemId) return null
        const episode = item.media?.episodes?.find((ep) => ep.id === localEpisodeId && ep.serverEpisodeId === episodeId)
        const track = episode?.audioTrack
        const file = item.localFiles?.find((file) => file.id && file.id === track?.localFileId)
        if (!track?.contentUrl || !file || file.contentUrl !== track.contentUrl || episode.audioRemoved === true) return null
        return { id: localItemId, trackLocalFileId: file.id, trackContentUrl: track.contentUrl }
      }
      try {
        const target = targetFor(await this.$db.getLocalLibraryItem(localItemId))
        if (!target) {
          this.$toast.error(this.$strings.MessageRemoveDownloadFailed)
          return
        }
        await this.$hapticsImpact()
        const { value } = await Dialog.confirm({
          title: this.$strings.HeaderRemoveDownload,
          message: this.$getString('MessageConfirmRemoveEpisodeDownload', [this.title]),
          okButtonTitle: this.$strings.ButtonRemoveDownload,
          cancelButtonTitle: this.$strings.ButtonCancel
        })
        if (!value || this._isDestroyed || this.libraryItemId !== itemId || this.episode.id !== episodeId || this.localLibraryItemId !== localItemId || this.localEpisode?.id !== localEpisodeId) return
        const currentTarget = targetFor(await this.$db.getLocalLibraryItem(localItemId))
        if (!currentTarget || currentTarget.trackLocalFileId !== target.trackLocalFileId || currentTarget.trackContentUrl !== target.trackContentUrl) {
          this.$toast.error(this.$strings.MessageRemoveDownloadFailed)
          return
        }
        const updatedItem = await AbsFileSystem.deleteTrackFromItem(target)
        if (updatedItem?.id !== localItemId) {
          this.$toast.error(this.$strings.MessageRemoveDownloadFailed)
          return
        }
        // The parent derives every row and the Downloaded filter from this local item.
        this.$set(this.libraryItem, 'localLibraryItem', updatedItem)
        this.$delete(this.episode, 'localEpisode')
      } catch (error) {
        console.error('Failed to remove episode download', error)
        this.$toast.error(this.$strings.MessageRemoveDownloadFailed)
      } finally {
        this.removingDownload = false
      }
    },
    async goToEpisodePage() {
      const path = `/item/${this.libraryItemId}/${this.episode.id}`
      if (this.$platform !== 'android') return this.$router.push(path)
      if (this.openingEpisode) return
      this.openingEpisode = true
      await this.$nextTick()
      await waitForEpisodePaint()
      if (this._isDestroyed) return
      rememberEpisodePage(this.$store, this.libraryItem, this.episode)
      // Nuxt's router wrapper uses callbacks; awaiting push alone does not wait for the page.
      this.$router.push(path, () => { this.openingEpisode = false }, () => {
        clearEpisodePage(this.$store)
        this.openingEpisode = false
      })
    },
    addToPlaylist() {
      this.$emit('addToPlaylist', this.episode)
    },
    async selectFolder() {
      var folderObj = await AbsFileSystem.selectFolder({ mediaType: this.mediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }
      return folderObj
    },
    async downloadClick() {
      if (this.downloadItem || this.startingDownload) return

      const hasPermission = await this.checkCellularPermission('download')
      if (!hasPermission) return

      this.startingDownload = true
      setTimeout(() => {
        this.startingDownload = false
      }, 1000)

      await this.$hapticsImpact()
      if (this.isIos) {
        // no local folders on iOS
        this.startDownload()
      } else {
        this.download()
      }
    },
    async download(selectedLocalFolder = null) {
      let localFolder = selectedLocalFolder
      if (!localFolder) {
        const localFolders = (await this.$db.getLocalFolders()) || []
        console.log('Local folders loaded', localFolders.length)
        const foldersWithMediaType = localFolders.filter((lf) => {
          console.log('Checking local folder', lf.mediaType)
          return lf.mediaType == this.mediaType
        })
        console.log('Folders with media type', this.mediaType, foldersWithMediaType.length)
        const internalStorageFolder = foldersWithMediaType.find((f) => f.id === `internal-${this.mediaType}`)
        if (!foldersWithMediaType.length) {
          localFolder = {
            id: `internal-${this.mediaType}`,
            name: 'Internal App Storage',
            mediaType: this.mediaType
          }
        } else if (foldersWithMediaType.length === 1 && internalStorageFolder) {
          localFolder = internalStorageFolder
        } else {
          this.$store.commit('globals/showSelectLocalFolderModal', {
            mediaType: this.mediaType,
            callback: (folder) => {
              this.download(folder)
            }
          })
          return
        }
      }

      console.log('Local folder', JSON.stringify(localFolder))

      this.startDownload(localFolder)
    },
    async startDownload(localFolder) {
      var payload = {
        libraryItemId: this.libraryItemId,
        episodeId: this.episode.id
      }
      if (localFolder) {
        payload.localFolderId = localFolder.id
      }
      var downloadRes = await AbsDownloader.downloadLibraryItem(payload)
      if (downloadRes && downloadRes.error) {
        var errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.error(errorMsg)
      }
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return

      await this.$hapticsImpact()
      if (this.streamIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.$store.commit('setPlayerIsStartingPlayback', this.episode.id)

        if (this.localEpisode && this.localLibraryItemId) {
          console.log('Play local episode', this.localEpisode.id, this.localLibraryItemId)

          this.$eventBus.$emit('play-item', {
            libraryItemId: this.localLibraryItemId,
            episodeId: this.localEpisode.id,
            serverLibraryItemId: this.libraryItemId,
            serverEpisodeId: this.episode.id
          })
        } else {
          this.$eventBus.$emit('play-item', {
            libraryItemId: this.libraryItemId,
            episodeId: this.episode.id
          })
        }
      }
    },
    async toggleFinished() {
      await this.$hapticsImpact()

      this.isProcessingReadUpdate = true
      if (this.isLocal || this.localEpisode) {
        const isFinished = !this.userIsFinished
        const localLibraryItemId = this.isLocal ? this.libraryItemId : this.localLibraryItemId
        const localEpisodeId = this.isLocal ? this.episode.id : this.localEpisode.id
        const payload = await this.$db.updateLocalMediaProgressFinished({ localLibraryItemId, localEpisodeId, isFinished })
        console.log('toggleFinished payload', JSON.stringify(payload))
        if (payload?.error) {
          this.$toast.error(payload?.error || 'Unknown error')
        } else {
          const localMediaProgress = payload.localMediaProgress
          console.log('toggleFinished localMediaProgress', JSON.stringify(localMediaProgress))
          if (localMediaProgress) {
            this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
          }
        }
        this.isProcessingReadUpdate = false
      } else {
        const updatePayload = {
          isFinished: !this.userIsFinished
        }
        this.$nativeHttp
          .patch(`/api/me/progress/${this.libraryItemId}/${this.episode.id}`, updatePayload)
          .catch((error) => {
            console.error('Failed', error)
            this.$toast.error(`Failed to mark as ${updatePayload.isFinished ? 'Finished' : 'Not Finished'}`)
          })
          .finally(() => {
            this.isProcessingReadUpdate = false
          })
      }
    }
  }
}
</script>
