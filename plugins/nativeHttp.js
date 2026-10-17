import { CapacitorHttp } from '@capacitor/core'
import { AbsLogger } from '@/plugins/capacitor/AbsLogger'

export default function ({ store, $db }, inject) {
  // Axios, socket recovery and native HTTP share one refresh per current session.
  const refreshes = new Map()
  const saves = new Set()
  let revision = 0
  const snapshot = () => JSON.stringify([revision, store.state.user.serverConnectionConfig?.id, store.state.user.user?.id, store.state.user.accessToken])
  const httpError = response => Object.assign(new Error(typeof response.data === 'string' ? response.data : `HTTP ${response.status}`), { status: response.status })
  const sessionChanged = () => Object.assign(new Error('Session changed during token refresh'), { sessionChanged: true })

  const nativeHttp = {
    async request(method, _url, data, options = {}) {
      const { serverConnectionConfig = store.state.user.serverConnectionConfig, headers: extraHeaders, ...requestOptions } = options
      let url = _url
      let headers = {}
      if (!url.startsWith('http') && !url.startsWith('capacitor')) {
        const token = store.getters['user/getToken']
        if (token) headers.Authorization = `Bearer ${token}`
        if (serverConnectionConfig?.address) url = `${serverConnectionConfig.address}${url}`
      }
      if (data) headers['Content-Type'] = 'application/json'
      headers = { ...headers, ...extraHeaders }
      const response = await CapacitorHttp.request({ method, url, data, headers, ...requestOptions })
      // Login/refresh rejection must not recursively refresh or erase another session.
      if (response.status === 401 && serverConnectionConfig?.address && url.startsWith(serverConnectionConfig.address + '/') && !/\/(login|auth\/refresh)(?:\?|$)/.test(url)) {
        return this.handleTokenRefresh(method, url, data, headers, requestOptions, serverConnectionConfig)
      }
      if (response.status >= 400) throw httpError(response)
      return response.data
    },

    async handleTokenRefresh(method, url, data, headers, options, config) {
      const current = store.state.user.serverConnectionConfig
      // A late 401 may belong to the token that another request has already renewed.
      const currentToken = current?.id === config?.id && store.getters['user/getToken']
      const token = currentToken && headers.Authorization !== `Bearer ${currentToken}`
        ? currentToken : await this.refreshSession(config)
      const response = await CapacitorHttp.request({ method, url, data,
        headers: { ...headers, Authorization: `Bearer ${token}` }, ...options })
      // An API retry failure is not rejection of the refresh credential.
      if (response.status >= 400) throw httpError(response)
      return response.data
    },

    refreshSession(config = store.state.user.serverConnectionConfig) {
      const before = snapshot()
      const key = JSON.stringify([config?.id, before])
      if (refreshes.has(key)) return refreshes.get(key)
      const pending = (async () => {
        if (!config?.id || !config.address) throw new Error('No server connection available')
        if (store.state.user.serverConnectionConfig && store.state.user.serverConnectionConfig.id !== config.id) throw sessionChanged()
        const refreshToken = await $db.getRefreshToken(config.id)
        if (before !== snapshot()) throw sessionChanged()
        if (!refreshToken) {
          const error = Object.assign(new Error('No refresh credential available; sign in again'), { status: 401 })
          await this.handleRefreshFailure(config.id, before, false)
          throw error
        }
        let tokens
        try {
          tokens = await this.refreshAccessToken(refreshToken, config.address, config.customHeaders)
        } catch (error) {
          // A native refresh, manual login, logout or account switch may have superseded this request.
          if (before !== snapshot() || refreshToken !== await $db.getRefreshToken(config.id)) throw sessionChanged()
          AbsLogger.info({ tag: 'auth', message: `Refresh failed (${error.status || 'transport'}); ${error.refreshRejected ? 'credential rejected' : 'credentials preserved'}` }).catch(() => {})
          if (error.refreshRejected) await this.handleRefreshFailure(config.id, before, true)
          throw error
        }
        if (before !== snapshot() || refreshToken !== await $db.getRefreshToken(config.id)) throw sessionChanged()
        tokens.refreshToken = tokens.refreshToken || refreshToken
        await this.updateTokens(tokens, config, before)
        return tokens.accessToken
      })().finally(() => { if (refreshes.get(key) === pending) refreshes.delete(key) })
      refreshes.set(key, pending)
      return pending
    },

    async refreshAccessToken(refreshToken, serverAddress, customHeaders = {}) {
      const response = await CapacitorHttp.post({ url: `${serverAddress}/auth/refresh`,
        headers: { ...customHeaders, 'Content-Type': 'application/json', 'x-refresh-token': refreshToken },
        data: {}, connectTimeout: 6000, readTimeout: 6000 })
      if (response.status !== 200) {
        const error = httpError(response)
        error.refreshRejected = response.status === 401 || response.status === 403
        throw error
      }
      if (!response.data?.user?.accessToken) throw new Error('Refresh response has no access token')
      return { accessToken: response.data.user.accessToken, refreshToken: response.data.user.refreshToken }
    },

    async updateTokens(tokens, config, before = snapshot()) {
      if (before !== snapshot()) throw sessionChanged()
      const save = $db.setServerConnectionConfig({ ...config, token: tokens.accessToken, refreshToken: tokens.refreshToken })
      saves.add(save)
      let savedConfig
      try { savedConfig = await save } finally { saves.delete(save) }
      if (before !== snapshot()) throw sessionChanged()
      if (!savedConfig) throw new Error('Could not save refreshed session')
      store.commit('user/setAccessToken', tokens.accessToken)
      store.commit('user/setServerConnectionConfig', savedConfig)
    },

    async cancelRefresh() {
      revision++
      // Let a storage write already in progress finish before logout clears the selected server.
      await Promise.allSettled([...saves])
    },

    async handleRefreshFailure(configId, before, clearToken) {
      if (before !== snapshot()) return
      if (clearToken) await $db.clearRefreshToken(configId)
      if (before !== snapshot()) return
      await store.dispatch('user/logout')
      if (store.state.user.serverConnectionConfig || store.state.user.user) return
      if (window.location.pathname !== '/connect') {
        window.location.href = '/connect?error=refreshTokenFailed&serverConnectionConfigId=' + encodeURIComponent(configId)
      }
    },

    get(url, options = {}) { return this.request('GET', url, undefined, options) },
    post(url, data, options = {}) { return this.request('POST', url, data, options) },
    patch(url, data, options = {}) { return this.request('PATCH', url, data, options) },
    delete(url, options = {}) { return this.request('DELETE', url, undefined, options) }
  }
  inject('nativeHttp', nativeHttp)
}
