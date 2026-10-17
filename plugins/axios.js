export default function ({ $axios, store }) {
  $axios.onRequest((config) => {
    if (/^(https?:|capacitor:)/.test(config.url)) return
    const token = store.getters['user/getToken']
    if (!config.headers) config.headers = {}
    if (!config.headers.common) config.headers.common = {}
    if (token) config.headers.common.Authorization = `Bearer ${token}`
    const server = store.state.user.serverConnectionConfig
    config._serverConnectionConfigId = server?.id
    if (server?.address) config.url = `${server.address}${config.url}`
  })

  $axios.onError(async (error) => {
    const request = error.config
    if (error.response?.status !== 401 || !request || request._retry || /\/(login|auth\/refresh)(?:\?|$)/.test(request.url)) throw error
    const config = store.state.user.serverConnectionConfig
    if (!config || (request._serverConnectionConfigId && request._serverConnectionConfigId !== config.id) || !request.url.startsWith(config.address + '/')) throw error
    request._retry = true
    // All callers await the same refresh promise. Failure settles every request,
    // and only explicit refresh rejection clears credentials in nativeHttp.
    const failedToken = request.headers?.Authorization || request.headers?.common?.Authorization
    const currentToken = store.getters['user/getToken']
    const token = failedToken && currentToken && failedToken !== `Bearer ${currentToken}`
      ? currentToken : await store.dispatch('user/refreshToken')
    if (!token) throw error
    request.headers = { ...request.headers, Authorization: `Bearer ${token}` }
    return $axios(request)
  })
}
