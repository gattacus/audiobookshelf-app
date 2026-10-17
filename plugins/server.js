import { io } from 'socket.io-client'
import EventEmitter from 'events'
import { Capacitor } from '@capacitor/core'

class ServerSocket extends EventEmitter {
  constructor(store, android = false) {
    super()

    this.$store = store
    this.socket = null
    this.connected = false
    this.serverAddress = null
    this.isAuthenticated = false

    this.android = android
    this.recoveryTimer = null
    this.recoveryAttempt = 0
    this.recoveryPending = null
    this.needsRefresh = false
    this.stopped = false
    this.generation = 0
    this.lastReconnectAttemptTime = 0
  }

  $on(evt, callback) {
    if (this.socket) this.socket.on(evt, callback)
    else console.error('$on Socket not initialized')
  }

  $off(evt, callback) {
    if (this.socket) this.socket.off(evt, callback)
    else console.error('$off Socket not initialized')
  }

  connect(serverAddress, token) {
    this.stopRecovery()
    this.removeListeners()
    if (this.socket) this.socket.disconnect()
    this.generation++
    this.recoveryPending = null
    this.stopped = false
    this.connected = false
    this.isAuthenticated = false
    this.needsRefresh = false
    this.serverAddress = serverAddress
    this.serverConfigId = this.$store.state.user.serverConnectionConfig?.id
    if (this.android) this.$store.commit('setSocketConnected', false)

    const serverUrl = new URL(serverAddress)
    const serverHost = `${serverUrl.protocol}//${serverUrl.host}`
    const serverPath = serverUrl.pathname === '/' ? '' : serverUrl.pathname

    console.log(`[SOCKET] Connecting to ${serverHost} with path ${serverPath}/socket.io`)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      path: `${serverPath}/socket.io`,
      reconnectionDelayMax: 15000
    }
    this.socket = io(serverHost, socketOptions)
    this.setSocketListeners()
  }

  logout() {
    this.stopped = true
    this.generation++
    this.recoveryPending = null
    this.stopRecovery()
    this.removeListeners()
    if (this.socket) this.socket.disconnect()
    this.socket = null
    this.connected = false
    this.isAuthenticated = false
    this.needsRefresh = false
    this.$store.commit('setSocketConnected', false)
  }

  setSocketListeners() {
    const socket = this.socket
    const listen = (emitter, event, method) => emitter.on(event, (...args) => {
      if (this.socket === socket) this[method](...args)
    })
    for (const [event, method] of Object.entries({ connect: 'onConnect', disconnect: 'onDisconnect', init: 'onInit', auth_failed: 'onAuthFailed',
      user_updated: 'onUserUpdated', user_item_progress_updated: 'onUserItemProgressUpdated', playlist_added: 'onPlaylistAdded', connect_error: 'onReconnectError' })) listen(socket, event, method)
    for (const [event, method] of Object.entries({ reconnect_attempt: 'onReconnectAttempt', reconnect_error: 'onReconnectError', reconnect_failed: 'onReconnectFailed' })) listen(socket.io, event, method)
  }

  canRecover() {
    return this.android && !this.stopped && this.$store.state.networkConnected && !!this.$store.state.user.user && !!this.$store.state.user.serverConnectionConfig
  }

  stopRecovery() {
    if (this.recoveryTimer !== null) clearTimeout(this.recoveryTimer)
    this.recoveryTimer = null
    this.recoveryAttempt = 0
  }

  scheduleRecovery(refresh = false, delay = null) {
    this.needsRefresh = this.needsRefresh || refresh
    if (!this.canRecover() || this.recoveryTimer !== null) return
    const wait = delay ?? Math.min(2000 * 2 ** this.recoveryAttempt, 15000)
    this.recoveryAttempt = Math.min(this.recoveryAttempt + 1, 3)
    this.recoveryTimer = setTimeout(() => {
      this.recoveryTimer = null
      return this.recover()
    }, wait)
  }

  recover() {
    if (!this.canRecover()) return Promise.resolve()
    if (this.recoveryPending) return this.recoveryPending
    if (this.recoveryTimer !== null) clearTimeout(this.recoveryTimer)
    this.recoveryTimer = null
    const generation = this.generation
    const config = this.$store.state.user.serverConnectionConfig
    if (config.id !== this.serverConfigId || config.address !== this.serverAddress) {
      this.connect(config.address)
      return Promise.resolve()
    }
    if (this.isAuthenticated && this.socket?.connected) return Promise.resolve()
    const pending = (async () => {
      try {
        if (this.needsRefresh) {
          await this.$store.dispatch('user/refreshToken')
          if (generation !== this.generation || !this.canRecover() || this.$store.state.user.serverConnectionConfig.id !== config.id) return
          this.needsRefresh = false
        }
        if (!this.socket) {
          this.connect(config.address)
        } else if (this.socket.connected) {
          this.sendAuthenticate()
        } else if (!this.socket.active) {
          // Socket.IO will not retry a server-forced disconnect on its own.
          this.socket.connect()
        }
      } catch (error) {
        // Transient refresh failures keep the session and retry quietly.
        console.warn('[SOCKET] Recovery deferred', error.status || 'transport')
      }
    })().finally(() => {
      if (this.recoveryPending === pending) this.recoveryPending = null
      if (generation === this.generation && !this.isAuthenticated) this.scheduleRecovery()
    })
    this.recoveryPending = pending
    return pending
  }

  sendAuthenticate() {
    // Required to connect a socket to a user
    if (!this.socket?.connected) return
    this.socket.emit('auth', this.$store.getters['user/getToken'])
    // A missing init/auth_failed response must not leave an unauthenticated socket stuck.
    if (this.android && !this.isAuthenticated) this.scheduleRecovery(true, 6000)
  }

  removeListeners() {
    if (!this.socket) return
    this.socket.removeAllListeners()
    if (this.socket.io && this.socket.io.removeAllListeners) {
      this.socket.io.removeAllListeners()
    }
  }

  onConnect() {
    console.log('[SOCKET] Socket Connected ' + this.socket.id)
    this.connected = true
    this.isAuthenticated = false
    if (!this.android) {
      this.$store.commit('setSocketConnected', true)
      this.emit('connection-update', true)
    }
    this.sendAuthenticate()
  }

  onReconnectAttempt(attemptNumber) {
    const timeSinceLastReconnectAttempt = this.lastReconnectAttemptTime ? Date.now() - this.lastReconnectAttemptTime : 0
    this.lastReconnectAttemptTime = Date.now()
    console.log(`[SOCKET] Reconnect attempt ${attemptNumber} ${timeSinceLastReconnectAttempt > 0 ? `after ${timeSinceLastReconnectAttempt}ms` : ''}`)
  }

  onReconnectError(error) {
    console.log('[SOCKET] Reconnect error')
    this.scheduleRecovery()
  }

  onReconnectFailed(error) {
    console.log('[SOCKET] Reconnect failed')
    this.scheduleRecovery()
  }

  onDisconnect(reason) {
    console.log('[SOCKET] Socket Disconnected: ' + reason)
    this.connected = false
    this.isAuthenticated = false
    this.$store.commit('setSocketConnected', false)
    this.emit('connection-update', false)
    this.scheduleRecovery(reason === 'io server disconnect')
  }

  onInit(data) {
    console.log('[SOCKET] Initial socket data received', data)
    this.isAuthenticated = true
    this.needsRefresh = false
    this.stopRecovery()
    if (this.android) {
      this.$store.commit('setSocketConnected', true)
      this.emit('connection-update', true)
    }
    this.emit('initialized', true)
  }

  onAuthFailed(data) {
    console.log('[SOCKET] Auth failed')
    this.isAuthenticated = false
    if (this.android) this.$store.commit('setSocketConnected', false)
    this.scheduleRecovery(true)
  }

  onUserUpdated(data) {
    console.log('[SOCKET] User updated', data)
    this.emit('user_updated', data)
  }

  onUserItemProgressUpdated(payload) {
    console.log('[SOCKET] User Item Progress Updated', JSON.stringify(payload))
    this.$store.commit('user/updateUserMediaProgress', payload.data)
    this.emit('user_media_progress_updated', payload)
  }

  onPlaylistAdded() {
    // Currently numUserPlaylists is only used for showing the playlist tab or not. Precise number is not necessary
    if (!this.$store.state.libraries.numUserPlaylists) {
      this.$store.commit('libraries/setNumUserPlaylists', 1)
    }
  }
}

export default ({ app, store }, inject) => {
  inject('socket', new ServerSocket(store, Capacitor.getPlatform() === 'android'))
}
