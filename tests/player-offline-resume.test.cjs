const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')

const bridge = { playPause: async () => ({ playing: true }), playPlayer() {}, seek() {}, seekForward() {}, seekBackward() {} }
function component(path, bindings = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync(path, 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, error() {} }, AbsAudioPlayer: bridge, CellularPermissionHelpers: {}, jumpLabelMixin: {}, ...bindings
  })
  return module.exports
}
const playerComponent = component('components/app/AudioPlayer.vue')
const containerComponent = component('components/app/AudioPlayerContainer.vue')
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function context({ platform = 'android', local = true, episodeId = null } = {}) {
  const requests = [], seeks = [], syncs = []
  const player = { ...playerComponent.data(), $platform: platform, isLocalPlayMethod: local, $hapticsImpact: async () => {}, startPlayInterval() {}, $store: { commit() {} } }
  for (const name of ['setIsCheckingServerProgress', 'playPauseClick', 'play', 'onPlayingUpdate', 'jumpForward', 'jumpBackwards']) player[name] = playerComponent.methods[name].bind(player)
  Object.defineProperty(player, 'showLoadingState', { get: () => playerComponent.computed.showLoadingState.call(player) })
  const session = { id: 'session', libraryItemId: 'book', episodeId, localLibraryItem: { id: 'local-book' }, localEpisodeId: episodeId ? 'local-episode' : null }
  const localProgress = { id: 'local-progress', lastUpdate: 100, currentTime: 10 }
  const ctx = {
    ...containerComponent.data(), $platform: platform, $refs: { audioPlayer: player },
    $store: { state: { networkConnected: true, user: { user: {} }, currentPlaybackSession: session }, getters: { 'globals/getLocalMediaProgressById': () => localProgress }, commit() {} },
    $nativeHttp: { get(url, options) { const request = deferred(); requests.push({ ...request, url, options }); return request.promise } },
    $db: { async syncServerMediaProgressWithLocalMediaProgress(payload) { syncs.push(payload); return {} } }
  }
  for (const [name, fn] of Object.entries(containerComponent.computed)) Object.defineProperty(ctx, name, { get: () => fn.call(ctx) })
  for (const [name, fn] of Object.entries(containerComponent.methods)) ctx[name] = fn.bind(ctx)
  player.seek = (time) => { player.serverProgressRevision++; seeks.push(time) }
  return { ctx, player, requests, seeks, syncs, localProgress, session }
}
const response = (episodeId = null) => ({ libraryItemId: 'book', episodeId, lastUpdate: 200, currentTime: 20 })

test('downloaded book and podcast can resume before the server responds; late progress cannot seek or overwrite storage', async () => {
  for (const episodeId of [null, 'episode']) {
    const { ctx, player, requests, seeks, syncs, localProgress } = context({ episodeId })
    const pending = ctx.checkSyncServerProgressWithLocalProgress(localProgress)
    assert.equal(player.isCheckingServerProgress, true)
    assert.equal(player.showLoadingState, false)
    await player.playPauseClick()
    assert.equal(player.isPlaying, true)
    // Even if paused again, the result from before playback began is obsolete.
    player.isPlaying = false
    requests[0].resolve(response(episodeId))
    await pending
    assert.deepEqual(seeks, [])
    assert.deepEqual(syncs, [])
    assert.equal(player.isCheckingServerProgress, false)
  }
})

test('offline transition releases pending check; old completion cannot unlock or update a newer check', async () => {
  const { ctx, player, requests } = context()
  const old = ctx.getServerMediaProgressForCurrentSession()
  ctx.$store.state.networkConnected = false
  containerComponent.watch.networkConnected.call(ctx, false)
  assert.equal(player.isCheckingServerProgress, false)
  assert.equal(await ctx.getServerMediaProgressForCurrentSession(), null)
  assert.equal(requests.length, 1)
  ctx.$store.state.networkConnected = true
  const current = ctx.getServerMediaProgressForCurrentSession()
  requests[0].resolve(response())
  assert.equal(await old, null)
  assert.equal(player.isCheckingServerProgress, true)
  requests[1].resolve(response())
  assert.equal((await current).currentTime, 20)
  assert.equal(player.isCheckingServerProgress, false)
})

test('newer server position is still applied and saved when downloaded player remains paused', async () => {
  const { ctx, requests, seeks, syncs, localProgress } = context()
  const pending = ctx.checkSyncServerProgressWithLocalProgress(localProgress)
  requests[0].resolve(response())
  await pending
  assert.deepEqual(seeks, [20])
  assert.equal(syncs.length, 1)
  assert.equal(syncs[0].localMediaProgressId, localProgress.id)
})

test('native playback, seeking, session replacement, and refreshed local progress invalidate stale results', async () => {
  for (const action of ['native-play', 'seek', 'replace', 'local-progress']) {
    const { ctx, player, requests, seeks, syncs, localProgress } = context()
    const pending = ctx.checkSyncServerProgressWithLocalProgress(localProgress)
    if (action === 'native-play') { player.onPlayingUpdate({ value: true }); player.isPlaying = false }
    if (action === 'seek') await player.jumpForward()
    if (action === 'replace') { ctx.$store.state.currentPlaybackSession = { id: 'other', libraryItemId: 'other' }; containerComponent.watch.currentPlaybackSession.call(ctx) }
    if (action === 'local-progress') localProgress.lastUpdate = 150
    requests[0].resolve(response())
    await pending
    assert.deepEqual(seeks, [], action)
    assert.deepEqual(syncs, [], action)
  }
})

test('audio buffering still blocks Play; streaming and iOS retain their progress-check lock', () => {
  for (const options of [{ platform: 'ios' }, { local: false }]) {
    const { player } = context(options)
    player.isCheckingServerProgress = true
    assert.equal(player.showLoadingState, true)
  }
  const { player } = context()
  player.isLoading = true
  assert.equal(player.showLoadingState, true)
})

test('duplicate checks are skipped, request failures release state, and wrong podcast episode is ignored', async () => {
  const { ctx, player, requests } = context({ episodeId: 'episode' })
  const pending = ctx.getServerMediaProgressForCurrentSession()
  assert.equal(await ctx.getServerMediaProgressForCurrentSession(), null)
  assert.equal(requests.length, 1)
  requests[0].reject(new Error('offline timeout'))
  assert.equal(await pending, null)
  assert.equal(player.isCheckingServerProgress, false)
  const wrong = ctx.getServerMediaProgressForCurrentSession()
  requests[1].resolve(response('different-episode'))
  assert.equal(await wrong, null)
})
