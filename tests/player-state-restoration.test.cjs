const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')

function component(bridge) {
  const parsed = compiler.parseComponent(fs.readFileSync('components/app/AudioPlayer.vue', 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, console: { log() {}, error() {} }, AbsAudioPlayer: bridge, jumpLabelMixin: {}
  })
  return module.exports
}

test('Android restores an existing native session only after every listener is registered', async () => {
  const registered = [], events = [], pendingListeners = []
  const session = { id: 'background-session', libraryItemId: 'book' }
  let requested = false
  const player = component({
    addListener(name) {
      registered.push(name)
      return new Promise((resolve) => pendingListeners.push(resolve))
    },
    async getPlaybackState() {
      requested = true
      return { session, isPlaying: true, currentTime: 123, duration: 456, playerState: 'READY' }
    }
  })
  const context = {
    $platform: 'android', async loadPlayerSettings() {},
    onPlaybackSession(value) { events.push(['session', value]) },
    onPlayingUpdate(value) { events.push(['playing', value.value]) },
    onMetadata(value) { events.push(['position', value.currentTime, value.duration, value.playerState]) }
  }
  const initialized = player.methods.init.call(context)
  await new Promise(setImmediate)
  assert.equal(registered.length, 8)
  assert.equal(requested, false)
  pendingListeners.slice(0, -1).forEach((resolve) => resolve())
  await new Promise(setImmediate)
  assert.equal(requested, false)
  pendingListeners.at(-1)()
  await initialized
  assert.deepEqual(events, [['session', session], ['playing', true], ['position', 123, 456, 'READY']])
})

test('an empty or failed Android snapshot leaves listener-based playback usable; iOS does not request a snapshot', async () => {
  for (const scenario of ['empty', 'failure', 'ios']) {
    let requested = false
    const player = component({
      async addListener() {},
      async getPlaybackState() {
        requested = true
        if (scenario === 'failure') throw new Error('Service unavailable')
        return {}
      }
    })
    await player.methods.init.call({
      $platform: scenario === 'ios' ? 'ios' : 'android', async loadPlayerSettings() {},
      onPlaybackSession() { assert.fail('No session to restore') }
    })
    assert.equal(requested, scenario !== 'ios')
  }
})

test('Android resume updates the same session without resetting playback; iOS keeps its existing initialization', () => {
  const player = component({})
  for (const platform of ['android', 'ios']) {
    const commits = [], ticks = []
    const context = {
      $platform: platform, playbackSession: { id: 'session', title: 'old' },
      isEnded: true, isLoading: false, syncStatus: 2,
      $store: { commit(...args) { commits.push(args) } },
      $nextTick(callback) { ticks.push(callback) }
    }
    const refreshed = { id: 'session', title: 'updated' }
    player.methods.onPlaybackSession.call(context, refreshed)
    assert.equal(context.playbackSession, refreshed)
    assert.deepEqual(commits, [['setPlaybackSession', refreshed]])
    assert.equal(ticks.length, platform === 'android' ? 0 : 1)
    assert.equal(context.isLoading, platform === 'ios')
    assert.equal(context.isEnded, platform === 'android')
    assert.equal(context.syncStatus, platform === 'android' ? 2 : 0)
  }
})

test('a different Android session initializes its loading state normally', () => {
  const player = component({})
  let ticks = 0
  const context = {
    $platform: 'android', playbackSession: { id: 'previous' },
    isEnded: true, isLoading: false, syncStatus: 2,
    $store: { commit() {} }, $nextTick() { ticks++ }
  }
  player.methods.onPlaybackSession.call(context, { id: 'new' })
  assert.equal(context.isLoading, true)
  assert.equal(context.isEnded, false)
  assert.equal(context.syncStatus, 0)
  assert.equal(ticks, 1)
})
