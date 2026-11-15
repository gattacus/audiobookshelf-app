<template>
  <div class="w-full h-16 bg-primary relative z-20">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full flex items-center px-2">
      <nuxt-link v-show="!showBack" to="/" class="mr-3">
        <img src="/Logo.png" class="h-10 w-10" />
      </nuxt-link>
      <a v-if="showBack" @click="back" aria-label="Back" class="rounded-full h-10 w-10 flex items-center justify-center mr-2 cursor-pointer">
        <span class="material-symbols text-3xl text-fg">arrow_back</span>
      </a>
      <div v-if="user && currentLibrary">
        <button
          type="button"
          aria-label="Show library modal"
          class="pl-1.5 pr-2.5 py-2 bg-bg bg-opacity-30 rounded-md flex items-center"
          :style="$platform === 'android' ? { touchAction: 'none' } : null"
          :aria-busy="$platform === 'android' && $store.state.libraries.switchingLibrary ? 'true' : 'false'"
          @click="clickShowLibraryModal"
          @touchstart="libraryTouchStart"
          @touchmove="libraryTouchMove"
          @touchend="libraryTouchEnd"
          @touchcancel="libraryTouchCancel"
        >
          <ui-library-icon :icon="currentLibraryIcon" :size="4" font-size="base" />
          <p class="text-sm leading-4 ml-2 mt-0.5 max-w-24 truncate">{{ currentLibraryName }}</p>
        </button>
      </div>

      <widgets-connection-indicator />

      <div class="flex-grow" />

      <widgets-download-progress-indicator />

      <!-- Must be connected to a server to cast, only supports media items on server -->
      <button type="button" aria-label="Cast" v-show="isCastAvailable && user" class="mx-2 cursor-pointer flex items-center" @click="castClick">
        <span class="material-symbols text-2xl leading-none">
          {{ isCasting ? 'cast_connected' : 'cast' }}
        </span>
      </button>

      <nuxt-link v-if="user" class="mx-1.5 flex items-center h-10" to="/search" aria-label="Search">
        <span class="material-symbols text-2xl leading-none">search</span>
      </nuxt-link>

      <button type="button" aria-label="Toggle side drawer" class="h-7 mx-1.5" @click="clickShowSideDrawer">
        <span class="material-symbols" style="font-size: 1.75rem">menu</span>
      </button>
    </div>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  data() {
    return {
      onCastAvailableUpdateListener: null,
      libraryTouch: null,
      librarySuppressClickUntil: 0
    }
  },
  computed: {
    isCastAvailable: {
      get() {
        return this.$store.state.isCastAvailable
      },
      set(val) {
        this.$store.commit('setCastAvailable', val)
      }
    },
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryName() {
      return this.currentLibrary?.name || ''
    },
    currentLibraryIcon() {
      return this.currentLibrary?.icon || 'database'
    },
    showBack() {
      if (!this.$route.name) return true
      return this.$route.name !== 'index' && !this.$route.name.startsWith('bookshelf')
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user?.username || 'err'
    },
    isCasting() {
      return this.$store.state.isCasting
    }
  },
  methods: {
    castClick() {
      if (this.$store.getters['getIsCurrentSessionLocal']) {
        this.$eventBus.$emit('cast-local-item')
        return
      }
      AbsAudioPlayer.requestSession()
    },
    clickShowSideDrawer() {
      this.$store.commit('setShowSideDrawer', true)
    },
    libraryTouchStart(event) {
      if (this.$platform !== 'android') return
      this.librarySuppressClickUntil = 0
      if (event.touches.length !== 1) {
        this.libraryTouchCancel()
        return
      }
      const touch = event.touches[0]
      this.libraryTouch = { id: touch.identifier, x: touch.clientX, y: touch.clientY }
      // Also detect a second finger landing outside this button.
      document.addEventListener('touchstart', this.libraryTouchMove, { passive: true })
    },
    libraryTouchMove(event) {
      if (this.$platform !== 'android') return
      if (event.touches.length !== 1) this.libraryTouchCancel()
    },
    libraryTouchCancel() {
      if (this.$platform !== 'android') return
      this.libraryTouch = null
      document.removeEventListener('touchstart', this.libraryTouchMove)
      this.librarySuppressClickUntil = Date.now() + 750
    },
    libraryTouchEnd(event) {
      if (this.$platform !== 'android' || !this.libraryTouch) return
      const start = this.libraryTouch
      this.libraryTouch = null
      document.removeEventListener('touchstart', this.libraryTouchMove)
      if (event.touches.length || event.changedTouches.length !== 1) return
      const end = event.changedTouches[0]
      if (end.identifier !== start.id) return
      const dx = end.clientX - start.x
      const dy = end.clientY - start.y
      if (Math.abs(dy) < 50 || Math.abs(dy) < 1.5 * Math.abs(dx)) return

      // Consume the swipe even when switching is unavailable (e.g. offline).
      // A fresh touch resets this guard, so the next intentional tap still works.
      this.librarySuppressClickUntil = Date.now() + 750
      if (event.cancelable) event.preventDefault()
      if (!this.$store.getters['libraries/getCanSwitchLibrary']) return
      return this.$store.dispatch('libraries/switchLibrary', { direction: dy > 0 ? 1 : -1 })
    },
    clickShowLibraryModal(event) {
      // Keyboard activation has detail=0 and remains available after a gesture.
      if (this.$platform === 'android' && event?.detail !== 0 && Date.now() < this.librarySuppressClickUntil) return
      this.$store.commit('libraries/setShowModal', true)
    },
    back() {
      window.history.back()
    },
    onCastAvailableUpdate(data) {
      this.isCastAvailable = data && data.value
    }
  },
  async mounted() {
    AbsAudioPlayer.getIsCastAvailable().then((data) => {
      this.isCastAvailable = data && data.value
    })
    this.onCastAvailableUpdateListener = await AbsAudioPlayer.addListener('onCastAvailableUpdate', this.onCastAvailableUpdate)
  },
  beforeDestroy() {
    this.libraryTouchCancel()
    this.onCastAvailableUpdateListener?.remove()
  }
}
</script>

<style>
#appbar {
  box-shadow: 0px 5px 5px #11111155;
}
.loader-dots div {
  animation-timing-function: cubic-bezier(0, 1, 1, 0);
}
.loader-dots div:nth-child(1) {
  left: 0px;
  animation: loader-dots1 0.6s infinite;
}
.loader-dots div:nth-child(2) {
  left: 0px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(3) {
  left: 10px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(4) {
  left: 20px;
  animation: loader-dots3 0.6s infinite;
}
@keyframes loader-dots1 {
  0% {
    transform: scale(0);
  }
  100% {
    transform: scale(1);
  }
}
@keyframes loader-dots3 {
  0% {
    transform: scale(1);
  }
  100% {
    transform: scale(0);
  }
}
@keyframes loader-dots2 {
  0% {
    transform: translate(0, 0);
  }
  100% {
    transform: translate(10px, 0);
  }
}
</style>
