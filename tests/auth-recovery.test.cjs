const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const { EventEmitter } = require('node:events')

function load(path, bindings = {}, exports = null) {
  const module = { exports: {} }
  let code = fs.readFileSync(path, 'utf8').replace(/^import .*$/gm, '').replace('export default ', 'module.exports = ').replace(/export const /g, 'const ')
  if (exports) code += `\nmodule.exports = { ${exports} }`
  vm.runInNewContext(code, { module, URL, console: { log() {}, warn() {}, error() {} }, AbsLogger: { info: async () => {} }, ...bindings })
  return module.exports
}
const userActions = load('store/user.js', {}, 'actions').actions
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = () => new Promise(setImmediate)
const renewed = { status: 200, data: { user: { accessToken: 'new-access', refreshToken: 'new-refresh' } } }

function app() {
  const config = { id: 'server-a', address: 'https://example.invalid', token: 'old-access', customHeaders: { 'x-test': 'synthetic' } }
  const observed = { refresh: [], saves: [], clears: [], logouts: 0, redirects: [] }
  const state = { networkConnected: true, socketConnected: false, libraries: {}, user: { user: { id: 'user-a' }, accessToken: config.token, serverConnectionConfig: config } }
  let refreshToken = 'old-refresh', selected = config.id
  const db = { getRefreshToken: async () => refreshToken, clearRefreshToken: async id => { observed.clears.push(id); refreshToken = null },
    async setServerConnectionConfig(value) { observed.saves.push(value); refreshToken = value.refreshToken || refreshToken; selected = value.id; return { ...value } },
    async logout() { observed.logouts++; selected = null }
  }
  const store = { state, getters: {}, commit(type, value) {
    if (type === 'user/setAccessToken') state.user.accessToken = value
    if (type === 'user/setServerConnectionConfig') state.user.serverConnectionConfig = value
    if (type === 'logout') Object.assign(state.user, { user: null, accessToken: null, serverConnectionConfig: null })
    if (type === 'setSocketConnected') state.socketConnected = value
  } }
  Object.defineProperty(store.getters, 'user/getToken', { get: () => state.user.accessToken })
  const httpBridge = { async request({ headers }) { return headers.Authorization === 'Bearer new-access' ? { status: 200, data: 'ok' } : { status: 401 } },
    async post(options) { observed.refresh.push(options); return renewed } }
  const location = { pathname: '/bookshelf', set href(value) { observed.redirects.push(value) } }
  let http
  load('plugins/nativeHttp.js', { CapacitorHttp: httpBridge, window: { location } })({ store, $db: db }, (_, value) => { http = value })
  const socket = { logout() {} }
  const actionThis = { $db: db, $nativeHttp: http, $socket: socket, $localStore: { removeLastLibraryId() {} } }
  store.dispatch = (type, payload) => userActions[type.split('/')[1]].call(actionThis, { state: state.user, getters: store.getters, commit: store.commit }, payload)
  return { http, store, db, observed, httpBridge, config, get refreshToken() { return refreshToken }, get selected() { return selected } }
}

for (const status of [400, 404, 408, 429, 500, 502, 503]) {
  test(`refresh HTTP ${status} preserves credentials and remains retryable`, async () => {
    const a = app()
    a.httpBridge.post = async () => ({ status, data: 'temporary failure' })
    await assert.rejects(a.http.get('/api/me'), e => e.status === status)
    assert.equal(a.refreshToken, 'old-refresh'); assert.equal(a.selected, a.config.id)
    assert.equal(a.observed.logouts, 0); assert.equal(a.observed.clears.length, 0); assert.equal(a.observed.redirects.length, 0)
    a.httpBridge.post = async () => renewed
    assert.equal(await a.http.get('/api/me'), 'ok')
  })
}
for (const response of ['timeout', { status: 200, data: {} }, { status: 200, data: null }]) {
  test(`transport/malformed refresh preserves credentials: ${JSON.stringify(response)}`, async () => {
    const a = app()
    a.httpBridge.post = async () => { if (response === 'timeout') throw new Error('DNS timeout'); return response }
    await assert.rejects(a.http.get('/api/me'))
    assert.equal(a.refreshToken, 'old-refresh'); assert.equal(a.observed.logouts, 0)
  })
}
for (const status of [401, 403]) {
  test(`explicit refresh rejection ${status} clears only the rejected credential and requires login`, async () => {
    const a = app(); a.httpBridge.post = async () => ({ status })
    await assert.rejects(a.http.get('/api/me'), e => e.status === status)
    assert.deepEqual(a.observed.clears, [a.config.id]); assert.equal(a.observed.logouts, 1)
    assert.equal(a.selected, null); assert.equal(a.store.state.user.user, null)
    assert.equal(a.observed.redirects.length, 1)
  })
}
for (const status of [401, 403, 404, 503]) {
  test(`API retry HTTP ${status} after successful refresh does not erase the renewed session`, async () => {
    const a = app(); a.httpBridge.request = async ({ headers }) => ({ status: headers.Authorization === 'Bearer new-access' ? status : 401 })
    await assert.rejects(a.http.get('/api/me'), e => e.status === status)
    assert.equal(a.refreshToken, 'new-refresh'); assert.equal(a.observed.logouts, 0)
    assert.equal(a.store.state.user.accessToken, 'new-access'); assert.equal(a.observed.refresh.length, 1)
  })
}

test('concurrent native HTTP, Axios/Vuex and socket refresh callers share a single renewal', async () => {
  const a = app(), d = deferred()
  a.httpBridge.post = async options => { a.observed.refresh.push(options); return d.promise }
  const requests = [a.http.get('/api/me'), a.http.get('/api/libraries'), a.store.dispatch('user/refreshToken'), a.store.dispatch('user/refreshToken')]
  await flush(); assert.equal(a.observed.refresh.length, 1)
  d.resolve(renewed)
  const results = await Promise.all(requests)
  assert.deepEqual(results, ['ok', 'ok', 'new-access', 'new-access'])
  assert.equal(a.observed.saves.length, 1)
  assert.equal(a.observed.refresh[0].connectTimeout, 6000); assert.equal(a.observed.refresh[0].readTimeout, 6000)
  assert.equal(a.observed.refresh[0].headers['x-test'], 'synthetic')
})

test('late API 401 for the previous access token reuses an already refreshed token', async () => {
  const a = app(), response = deferred()
  a.httpBridge.request = async ({ headers }) => headers.Authorization === 'Bearer old-access' ? response.promise : { status: 200, data: 'ok' }
  const request = a.http.get('/api/me')
  await a.http.refreshSession(); response.resolve({ status: 401 })
  assert.equal(await request, 'ok'); assert.equal(a.observed.refresh.length, 1)
})

test('refresh without token rotation preserves the existing refresh credential', async () => {
  const a = app(); a.httpBridge.post = async () => ({ status: 200, data: { user: { accessToken: 'new-access' } } })
  assert.equal(await a.http.get('/api/me'), 'ok'); assert.equal(a.refreshToken, 'old-refresh')
})

test('storage failure preserves the existing credential and propagates to all callers', async () => {
  const a = app(); a.db.setServerConnectionConfig = async () => { throw new Error('storage unavailable') }
  await assert.rejects(a.http.refreshSession(), /storage unavailable/)
  assert.equal(a.observed.logouts, 0); assert.equal(a.refreshToken, 'old-refresh')
})

for (const rejected of [false, true]) {
  test(`an obsolete ${rejected ? 'rejected' : 'successful'} refresh cannot affect a switched account`, async () => {
    const a = app(), d = deferred(); a.httpBridge.post = () => d.promise
    const pending = a.http.refreshSession(); await flush()
    a.store.state.user.serverConnectionConfig = { id: 'server-b', address: 'https://other.invalid', token: 'other' }
    a.store.state.user.user = { id: 'user-b' }; a.store.state.user.accessToken = 'other'
    d.resolve(rejected ? { status: 401 } : renewed)
    await assert.rejects(pending, e => e.sessionChanged)
    assert.equal(a.observed.logouts, 0); assert.equal(a.observed.clears.length, 0); assert.equal(a.observed.saves.length, 0)
    assert.equal(a.store.state.user.accessToken, 'other')
  })
}

test('logout during a token-storage write finishes with no resurrected session', async () => {
  const a = app(), d = deferred(), save = a.db.setServerConnectionConfig
  a.db.setServerConnectionConfig = async c => { await d.promise; return save(c) }
  const pending = a.http.refreshSession(); await flush()
  const logout = a.store.dispatch('user/logout')
  d.resolve(); await logout
  await assert.rejects(pending, e => e.sessionChanged)
  assert.equal(a.selected, null); assert.equal(a.store.state.user.user, null); assert.equal(a.observed.redirects.length, 0)
})

test('logout during a network refresh ignores its eventual successful response', async () => {
  const a = app(), d = deferred(); a.httpBridge.post = () => d.promise
  const pending = a.http.refreshSession(); await flush(); await a.store.dispatch('user/logout')
  d.resolve(renewed); await assert.rejects(pending, e => e.sessionChanged)
  assert.equal(a.observed.saves.length, 0); assert.equal(a.selected, null)
})

test('a native token rotation supersedes a stale JavaScript refresh rejection', async () => {
  const a = app(), d = deferred(); a.httpBridge.post = () => d.promise
  const pending = a.http.refreshSession(); await flush()
  await a.db.setServerConnectionConfig({ ...a.config, token: 'native-access', refreshToken: 'native-refresh' })
  a.store.commit('user/setAccessToken', 'native-access'); d.resolve({ status: 401 })
  await assert.rejects(pending, e => e.sessionChanged)
  assert.equal(a.refreshToken, 'native-refresh'); assert.equal(a.observed.logouts, 0)
})

test('a changed refresh token without a WebView event is not cleared by stale rejection', async () => {
  const a = app(), d = deferred(); a.httpBridge.post = () => d.promise
  const pending = a.http.refreshSession(); await flush()
  await a.db.setServerConnectionConfig({ ...a.config, refreshToken: 'native-refresh' }); d.resolve({ status: 403 })
  await assert.rejects(pending, e => e.sessionChanged)
  assert.equal(a.refreshToken, 'native-refresh'); assert.equal(a.observed.logouts, 0)
})

for (const url of ['/login', '/auth/refresh', 'https://unrelated.invalid/api/me']) {
  test(`401 from ${url} does not refresh or clear the current session`, async () => {
    const a = app(); await assert.rejects(a.http.get(url), e => e.status === 401)
    assert.equal(a.observed.refresh.length, 0); assert.equal(a.observed.logouts, 0)
  })
}

function axios(a) {
  let onError, onRequest
  const retries = []
  const client = async request => { retries.push(request); return 'axios-ok' }
  client.onError = f => { onError = f }; client.onRequest = f => { onRequest = f }
  load('plugins/axios.js')({ $axios: client, store: a.store })
  const request = () => { const r = { url: '/api/me', headers: {} }; onRequest(r); return r }
  return { fail: r => onError({ config: r, response: { status: 401 } }), request, retries }
}

test('concurrent Axios failures settle together on transient refresh failure, then can recover', async () => {
  const a = app(), x = axios(a), d = deferred()
  a.httpBridge.post = async options => { a.observed.refresh.push(options); return d.promise }
  const pending = [x.fail(x.request()), x.fail(x.request())]
  await flush(); assert.equal(a.observed.refresh.length, 1)
  d.reject(new Error('timeout'))
  assert.deepEqual((await Promise.allSettled(pending)).map(r => r.status), ['rejected', 'rejected'])
  assert.equal(a.observed.logouts, 0); assert.equal(a.refreshToken, 'old-refresh')
  a.httpBridge.post = async () => renewed
  assert.equal(await x.fail(x.request()), 'axios-ok'); assert.equal(x.retries[0].headers.Authorization, 'Bearer new-access')
})

test('Axios does not retry twice or refresh a request from a previously selected server', async () => {
  const a = app(), x = axios(a), r = x.request()
  a.store.state.user.serverConnectionConfig = { id: 'server-b', address: 'https://other.invalid' }
  await assert.rejects(x.fail(r)); assert.equal(a.observed.refresh.length, 0)
  a.store.state.user.serverConnectionConfig = a.config; r._retry = true
  await assert.rejects(x.fail(r)); assert.equal(a.observed.refresh.length, 0)
})

function sockets(android = true) {
  const timers = new Map(), instances = [], a = app()
  let timerId = 0, refreshCalls = 0
  const io = () => {
    const s = new EventEmitter(); s.io = new EventEmitter(); s.connected = false; s.active = true; s.connectCalls = 0; s.auth = []
    s.connect = () => { s.connectCalls++; s.active = true }
    s.disconnect = () => { s.connected = false; s.active = false; s.emit('disconnect', 'io client disconnect') }
    s.on('auth', token => s.auth.push(token)); instances.push(s); return s
  }
  let server
  load('plugins/server.js', { EventEmitter, Capacitor: { getPlatform: () => android ? 'android' : 'ios' }, io,
    setTimeout(fn, delay) { const id = ++timerId; timers.set(id, { fn, delay }); return id }, clearTimeout: id => timers.delete(id) })({ store: a.store }, (_, value) => { server = value })
  const dispatch = a.store.dispatch
  a.store.dispatch = (...args) => { if (args[0] === 'user/refreshToken') refreshCalls++; return dispatch(...args) }
  server.connect(a.config.address)
  const transport = () => { const s = instances.at(-1); s.connected = true; s.emit('connect'); return s }
  return { ...a, server, instances, timers, transport, get refreshCalls() { return refreshCalls }, async tick() {
    assert.equal(timers.size, 1); const [id, timer] = timers.entries().next().value; timers.delete(id); await timer.fn(); return timer.delay
  } }
}

test('Android waits for socket authentication before reporting connected', () => {
  const a = sockets(), s = a.transport()
  assert.equal(a.store.state.socketConnected, false); assert.deepEqual(s.auth, ['old-access'])
  s.emit('init', {}); assert.equal(a.store.state.socketConnected, true); assert.equal(a.timers.size, 0)
  s.connected = false; s.emit('disconnect', 'transport close')
  assert.equal(a.server.isAuthenticated, false); assert.equal(a.store.state.socketConnected, false)
})

test('expired socket auth refreshes once and authenticates the existing transport with the new token', async () => {
  const a = sockets(), s = a.transport(); s.emit('auth_failed', {})
  await a.tick(); assert.equal(a.refreshCalls, 1); assert.deepEqual(s.auth, ['old-access', 'new-access'])
  s.emit('init', {}); assert.equal(a.server.isAuthenticated, true); assert.equal(a.timers.size, 0)
})

test('server-forced disconnect reconnects an inactive socket after renewing authentication', async () => {
  const a = sockets(), s = a.transport(); s.emit('init', {})
  s.connected = false; s.active = false; s.emit('disconnect', 'io server disconnect')
  await a.tick(); assert.equal(a.refreshCalls, 1); assert.equal(s.connectCalls, 1)
  a.transport(); s.emit('init', {}); assert.equal(a.store.state.socketConnected, true)
})

test('ordinary transport loss lets Socket.IO retry without redundant refresh or sockets', async () => {
  const a = sockets(), s = a.transport(); s.emit('init', {})
  s.connected = false; s.emit('disconnect', 'transport close')
  await a.tick(); assert.equal(a.refreshCalls, 0); assert.equal(s.connectCalls, 0); assert.equal(a.instances.length, 1)
  a.transport(); s.emit('init', {}); assert.equal(a.timers.size, 0)
})

test('socket refresh timeout preserves credentials and retries until authentication recovers', async () => {
  const a = sockets(), s = a.transport(); s.emit('auth_failed', {})
  a.httpBridge.post = async () => { throw new Error('DNS not ready') }
  await a.tick(); assert.equal(a.refreshToken, 'old-refresh'); assert.equal(a.observed.logouts, 0); assert.equal(a.timers.size, 1)
  a.httpBridge.post = async () => renewed
  await a.tick(); assert.equal(s.auth.at(-1), 'new-access')
  s.emit('init', {}); assert.equal(a.timers.size, 0)
})

test('recovery does not overlap and a logout invalidates an in-flight socket refresh', async () => {
  const a = sockets(), s = a.transport(), d = deferred(); s.emit('auth_failed', {})
  a.httpBridge.post = () => d.promise
  const pending = a.server.recover(); assert.equal(a.server.recover(), pending)
  await flush(); a.server.logout(); await a.store.dispatch('user/logout'); d.resolve(renewed); await pending
  assert.equal(a.refreshCalls, 1); assert.equal(s.auth.length, 1); assert.equal(a.timers.size, 0)
})

test('offline recovery suspends and online/app-resume can recover an inactive transport', async () => {
  const a = sockets(), s = a.transport(); s.emit('init', {})
  a.store.state.networkConnected = false; s.connected = false; s.active = false; s.emit('disconnect', 'transport close')
  await a.server.recover(); assert.equal(a.timers.size, 0); assert.equal(s.connectCalls, 0)
  a.store.state.networkConnected = true; await a.server.recover(); assert.equal(s.connectCalls, 1)
})

test('a missing socket auth response schedules bounded recovery rather than staying stuck', async () => {
  const a = sockets(), s = a.transport()
  assert.equal(await a.tick(), 6000); assert.equal(s.auth.length, 2)
  assert.equal(a.timers.size, 1)
})

test('late events from replaced sockets cannot change the new connection state', () => {
  const a = sockets(), old = a.transport(); old.emit('init', {})
  a.server.connect('https://other.invalid'); const current = a.transport(); current.emit('init', {})
  old.emit('auth_failed', {}); old.emit('disconnect', 'io server disconnect')
  assert.equal(a.server.isAuthenticated, true); assert.equal(a.store.state.socketConnected, true); assert.equal(a.timers.size, 0)
})

test('iOS keeps transport-connected behavior and does not enable Android recovery timers', async () => {
  const a = sockets(false), s = a.transport()
  assert.equal(a.store.state.socketConnected, true); assert.equal(a.timers.size, 0)
  s.emit('auth_failed', {}); await a.server.recover(); assert.equal(a.refreshCalls, 0); assert.equal(a.timers.size, 0)
})

test('a successful refresh cannot overwrite a newer native refresh credential without a WebView event', async () => {
  const a = app(), d = deferred(); a.httpBridge.post = () => d.promise
  const pending = a.http.refreshSession(); await flush()
  await a.db.setServerConnectionConfig({ ...a.config, refreshToken: 'native-refresh' }); d.resolve(renewed)
  await assert.rejects(pending, e => e.sessionChanged)
  assert.equal(a.refreshToken, 'native-refresh'); assert.equal(a.observed.saves.length, 1)
})

test('a transport failure on the original API retry preserves a successfully refreshed session', async () => {
  const a = app(); a.httpBridge.request = async ({ headers }) => { if (headers.Authorization === 'Bearer new-access') throw new Error('connection lost'); return { status: 401 } }
  await assert.rejects(a.http.get('/api/me'), /connection lost/)
  assert.equal(a.refreshToken, 'new-refresh'); assert.equal(a.observed.logouts, 0)
})

test('a late Axios 401 reuses the current token rather than rotating credentials again', async () => {
  const a = app(), x = axios(a), request = x.request()
  await a.http.refreshSession()
  assert.equal(await x.fail(request), 'axios-ok'); assert.equal(a.observed.refresh.length, 1)
})

test('socket recovery connects to the new account/server instead of authenticating the old transport', async () => {
  const a = sockets(), old = a.transport()
  old.emit('init', {})
  a.store.state.user.serverConnectionConfig = { id: 'server-b', address: 'https://other.invalid', token: 'other-access' }
  a.store.state.user.accessToken = 'other-access'
  await a.server.recover()
  assert.equal(a.instances.length, 2); assert.equal(a.server.serverAddress, 'https://other.invalid')
  assert.deepEqual(old.auth, ['old-access']); assert.equal(old.connected, false)
})

test('request options remain intact so failed callers can reuse them', async () => {
  const a = app(), options = { serverConnectionConfig: a.config, headers: { Authorization: 'Bearer old-access' }, connectTimeout: 1000 }
  const before = JSON.stringify(options)
  await a.http.get(a.config.address + '/api/me', options)
  assert.equal(JSON.stringify(options), before)
})

test('missing refresh credentials require login without pretending a transient server failure', async () => {
  const a = app(); a.db.getRefreshToken = async () => null
  await assert.rejects(a.http.refreshSession(), e => e.status === 401)
  assert.equal(a.observed.logouts, 1); assert.equal(a.observed.clears.length, 0); assert.equal(a.observed.redirects.length, 1)
})
