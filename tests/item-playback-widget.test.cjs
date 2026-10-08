const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { test } = require('node:test')
const compiler = require('vue-template-compiler')

function component(bridge = {}) {
  const parsed = compiler.parseComponent(fs.readFileSync('components/modals/ItemMoreMenuModal.vue', 'utf8'))
  assert.deepEqual(compiler.compile(parsed.template.content).errors, [])
  const module = { exports: {} }
  vm.runInNewContext(parsed.script.content.replace(/^import .*$/gm, '').replace('export default ', 'module.exports = '), {
    module, AbsAudioPlayer: bridge
  })
  return module.exports
}

test('widget action is available for Android books and podcast shows, excluding episodes and iOS', () => {
  const menu = component()
  for (const [platform, mediaType, episode, visible] of [
    ['android', 'book', null, true], ['android', 'podcast', null, true],
    ['android', 'podcast', { id: 'episode' }, false], ['ios', 'book', null, false],
    ['ios', 'podcast', null, false], ['android', 'video', null, false]
  ]) {
    const items = menu.computed.moreMenuItems.call({
      $platform: platform, mediaType, episode, isPodcast: mediaType === 'podcast', $strings: {},
      ereaderDeviceItems: [], progressPercent: 0
    })
    assert.equal(items.some((item) => item.value === 'playbackWidget'), visible)
  }
})

test('a downloaded podcast widget retains its server identity and only saves dates for downloaded episodes', async () => {
  let payload
  const menu = component({ async pinItemPlaybackWidget(value) { payload = value } })
  await menu.methods.pinPlaybackWidget.call({
    title: 'Podcast', mediaType: 'podcast', mediaMetadata: { type: 'serial' }, media: {},
    episodes: [{ id: 'downloaded', publishedAt: 123 }, { id: 'unavailable', publishedAt: 456 }],
    localLibraryItem: { id: 'local-podcast', serverConnectionConfigId: 'saved-server', coverContentUrl: 'local-cover', media: { episodes: [{ id: 'local-episode', serverEpisodeId: 'downloaded' }] } },
    localLibraryItemId: 'local-podcast', serverLibraryItemId: 'server-podcast',
    $store: { getters: { 'user/getServerConnectionConfigId': 'different-server' } },
    $toast: { error(message) { assert.fail(message) } }
  })
  assert.equal(payload.serverConfigId, 'saved-server')
  assert.equal(payload.serverItemId, 'server-podcast')
  assert.equal(payload.localItemId, 'local-podcast')
  assert.equal(payload.podcastType, 'serial')
  assert.equal(payload.hasCover, true)
  assert.deepEqual(JSON.parse(payload.episodeDates), { downloaded: 123 })
})

test('unsupported launcher reports its error without requesting playback', async () => {
  const errors = []
  const menu = component({ async pinItemPlaybackWidget() { throw new Error('Launcher unsupported') } })
  await menu.methods.pinPlaybackWidget.call({
    title: 'Book', mediaType: 'book', mediaMetadata: {}, media: {}, episodes: [],
    serverLibraryItemId: 'book', $store: { getters: { 'user/getServerConnectionConfigId': 'server' } },
    $toast: { error(message) { errors.push(message) } }
  })
  assert.deepEqual(errors, ['Launcher unsupported'])
})
