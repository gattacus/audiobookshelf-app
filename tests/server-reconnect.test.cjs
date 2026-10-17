const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')
const Vue = require('vue')

const source = compiler.parseComponent(fs.readFileSync('layouts/default.vue', 'utf8'))
assert.deepEqual(compiler.compile(source.template.content).errors, [])
function context(platform = 'android') {
  const timers = new Map(), requests = [], connections = []
  let nextTimer = 0
  const module = { exports: {} }
  vm.runInNewContext(source.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, warn() {}, error() {} }, Date,
    AbsLogger: { info() {}, error() {} }, document: { visibilityState: 'visible', removeEventListener() {} },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id },
    clearTimeout(id) { timers.delete(id) }
  })
  const layout = module.exports
  const config = { id: 'saved', name: 'Test server', address: 'https://example.invalid', token: 'test-token' }
  const ctx = {
    ...layout.data(), $platform: platform, hasMounted: true, networkConnected: true, user: null, attemptingConnection: false,
    $db: { getDeviceData: async () => ({ lastServerConnectionConfigId: config.id, serverConnectionConfigs: [config] }), setServerConnectionConfig: async (value) => value },
    $store: { state: { connectionAttemptIsBackground: false }, getters: {}, commit(type, value) { if (type === 'user/setUser') ctx.user = value; if (type === 'setConnectionAttemptIsBackground') ctx.$store.state.connectionAttemptIsBackground = value }, dispatch: async () => {} },
    $nativeHttp: { post: async (url, data, options) => { requests.push(options); throw new Error('Network not ready') } },
    $localStore: { getLastLibraryId: async () => null }, $isValidVersion: () => false,
    $socket: { connect(...args) { connections.push(args) }, off() {}, stopRecovery() {}, recover() {} }, $eventBus: { $off() {}, $emit() {} },
    initLibraries: async () => {}
  }
  for (const [name, fn] of Object.entries(layout.methods)) ctx[name] = fn.bind(ctx)
  ctx.initLibraries = async () => {}
  const success = () => ({ user: { id: 'user', librariesAccessible: [] }, serverSettings: { version: '2.25.0' }, userDefaultLibraryId: 'library' })
  return { ctx, layout, timers, requests, connections, success, async tick() {
    assert.equal(timers.size, 1)
    const [id, timer] = timers.entries().next().value; timers.delete(id)
    await timer.fn()
    return timer.delay
  } }
}

test('Android recovers after an early failed authorize while networkConnected stays true', async () => {
  const app = context(); const { ctx } = app
  let calls = 0
  ctx.$nativeHttp.post = async (url, data, options) => { app.requests.push(options); if (++calls === 1) throw new Error('DNS not ready'); return app.success() }
  await ctx.attemptConnection()
  assert.equal(ctx.attemptingConnection, false)
  assert.equal(ctx.user, null)
  assert.equal(app.connections.length, 0)
  assert.equal(await app.tick(), 2000)
  assert.equal(calls, 2)
  assert.equal(ctx.user.id, 'user')
  assert.equal(app.connections.length, 1)
  assert.equal(app.timers.size, 0)
  assert.equal(ctx.connectionRetryAttempt, 0)
  assert.equal(app.requests[0].readTimeout, 6000)
})

test('real Vue network watcher survives an unvalidated-to-validated Wi-Fi update with no boolean change', async () => {
  const app = context(); let pending
  const state = new Vue({ data: { networkConnected: false }, watch: { networkConnected(value, old) {
    app.ctx.networkConnected = value
    app.layout.watch.networkConnected.handler.call(app.ctx, value, old)
  } } })
  const attempt = app.ctx.attemptConnection
  app.ctx.attemptConnection = () => (pending = attempt())
  // Existing network mutation treats both unvalidated and validated Wi-Fi as true.
  state.networkConnected = true
  await Vue.nextTick(); await pending
  state.networkConnected = true
  await Vue.nextTick()
  assert.equal(app.requests.length, 1)
  app.ctx.$nativeHttp.post = async () => app.success()
  await app.tick()
  assert.equal(app.connections.length, 1)
  state.$destroy()
})

test('transient failures back off to 15 seconds, without overlapping requests', async () => {
  const app = context()
  await app.ctx.attemptConnection()
  for (const delay of [2000, 4000, 8000, 15000, 15000]) assert.equal(await app.tick(), delay)
  assert.equal(app.timers.size, 1)
  let resolve
  app.ctx.$nativeHttp.post = () => new Promise(r => { resolve = r })
  const pending = app.ctx.attemptConnection()
  await Promise.resolve(); await Promise.resolve()
  assert.equal(app.timers.size, 0)
  await app.ctx.attemptConnection()
  resolve(app.success()); await pending
  assert.equal(app.connections.length, 1)
})

test('offline transition cancels retries even before mount; network restoration retries afresh', async () => {
  const app = context(); await app.ctx.attemptConnection()
  app.ctx.networkConnected = false; app.ctx.hasMounted = false
  app.layout.watch.networkConnected.handler.call(app.ctx, false, true)
  assert.equal(app.timers.size, 0)
  assert.equal(app.ctx.connectionRetryAttempt, 0)
  app.ctx.hasMounted = true; app.ctx.networkConnected = true
  let pending
  const attempt = app.ctx.attemptConnection
  app.ctx.attemptConnection = () => (pending = attempt())
  app.layout.watch.networkConnected.handler.call(app.ctx, true, false)
  await pending
  assert.equal(app.requests.length, 2)
  assert.equal(app.timers.values().next().value.delay, 2000)
})

test('manual login, removed saved config, or layout destruction stops retries', async () => {
  const app = context(); await app.ctx.attemptConnection()
  app.ctx.user = { id: 'manual' }
  app.layout.watch.user.call(app.ctx, app.ctx.user)
  assert.equal(app.timers.size, 0)
  await app.ctx.attemptConnection(); assert.equal(app.requests.length, 1)
  app.ctx.user = null; await app.ctx.attemptConnection()
  app.ctx.$db.getDeviceData = async () => ({ lastServerConnectionConfigId: null, serverConnectionConfigs: [] })
  await app.tick(); assert.equal(app.timers.size, 0); assert.equal(app.requests.length, 2)
  app.ctx.scheduleConnectionRetry()
  app.layout.beforeDestroy.call(app.ctx)
  assert.equal(app.timers.size, 0)
})

test('iOS does not schedule retries; permanent HTTP errors stop, transient HTTP errors retry', async () => {
  const ios = context('ios'); await ios.ctx.attemptConnection()
  assert.equal(ios.timers.size, 0); assert.equal(ios.requests[0].readTimeout, undefined)
  for (const [status, retry] of [[400, false], [401, false], [403, false], [404, false], [408, true], [429, true], [503, true]]) {
    const app = context(); app.ctx.$nativeHttp.post = async () => { throw Object.assign(new Error('HTTP failure'), { status }) }
    await app.ctx.attemptConnection(); assert.equal(app.timers.size, retry ? 1 : 0, `HTTP ${status}`)
  }
})

test('storage failures release the in-flight guard', async () => {
  const app = context(); app.ctx.$db.getDeviceData = async () => { throw new Error('Storage unavailable') }
  await assert.rejects(app.ctx.attemptConnection(), /Storage unavailable/)
  assert.equal(app.ctx.attemptingConnection, false)
})

test('native HTTP preserves error status for retry classification', async () => {
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync('plugins/nativeHttp.js', 'utf8').replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, error() {} }, CapacitorHttp: { request: async () => ({ status: 403, data: 'Forbidden' }) }
  })
  let http
  module.exports({ store: { state: { user: {} }, getters: {} } }, (name, value) => { http = value })
  await assert.rejects(http.post('https://example.invalid/api/authorize'), error => error.status === 403 && error.message === 'Forbidden')
})

test('a failed request completing after layout destruction cannot restart retries', async () => {
  const app = context(); let reject, entered
  const started = new Promise(resolve => { entered = resolve })
  app.ctx.$nativeHttp.post = () => new Promise((resolve, no) => { reject = no; entered() })
  const pending = app.ctx.attemptConnection()
  await started
  app.layout.beforeDestroy.call(app.ctx)
  reject(new Error('Late network failure'))
  await pending
  assert.equal(app.timers.size, 0)
  assert.equal(app.ctx.attemptingConnection, false)
  await app.ctx.attemptConnection()
  assert.equal(app.timers.size, 0)
})

test('initial authorize is visible; scheduled retries remain in flight without repeating the banner', async () => {
  const app = context(); let reject, entered
  let started = new Promise(resolve => { entered = resolve })
  app.ctx.$nativeHttp.post = () => new Promise((resolve, no) => { reject = no; entered() })
  const first = app.ctx.attemptConnection()
  await started
  assert.equal(app.ctx.attemptingConnection, true)
  assert.equal(app.ctx.$store.state.connectionAttemptIsBackground, false)
  reject(new Error('Network settling')); await first
  started = new Promise(resolve => { entered = resolve })
  const retry = app.tick(); await started
  assert.equal(app.ctx.attemptingConnection, true)
  assert.equal(app.ctx.$store.state.connectionAttemptIsBackground, true)
  reject(new Error('Still offline')); await retry
  assert.equal(app.ctx.attemptingConnection, false)
  assert.equal(app.ctx.$store.state.connectionAttemptIsBackground, false)
  assert.equal(app.timers.size, 1)
})

test('network restoration recovers an existing Android socket while offline cancels its recovery timer', () => {
  const app = context(); app.ctx.user = { id: 'user' }; app.ctx.currentLibraryId = 'library'; app.ctx.disconnectTime = Date.now()
  let recoveries = 0, stops = 0
  app.ctx.$socket.recover = () => { recoveries++ }; app.ctx.$socket.stopRecovery = () => { stops++ }
  app.layout.watch.networkConnected.handler.call(app.ctx, false, true)
  app.layout.watch.networkConnected.handler.call(app.ctx, true, false)
  assert.equal(recoveries, 1); assert.equal(stops, 1); assert.equal(app.requests.length, 0)
})

test('Android foreground recovery checks existing socket authentication even without a network boolean transition', async () => {
  const app = context(); app.ctx.user = { id: 'user' }; app.ctx.timeLostFocus = Date.now()
  let recoveries = 0; app.ctx.$socket.recover = () => { recoveries++ }
  await app.ctx.visibilityChanged()
  assert.equal(recoveries, 1); assert.equal(app.requests.length, 0)
})

test('iOS foreground and network restoration do not enable Android socket recovery', async () => {
  const app = context('ios'); app.ctx.user = { id: 'user' }; app.ctx.currentLibraryId = 'library'; app.ctx.disconnectTime = Date.now(); app.ctx.timeLostFocus = Date.now()
  app.ctx.$socket.recover = () => assert.fail('Android-only recovery')
  app.layout.watch.networkConnected.handler.call(app.ctx, true, false)
  await app.ctx.visibilityChanged()
})
