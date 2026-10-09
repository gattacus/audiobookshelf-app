# Continuous podcast playback (Android fork)

The podcast page has a **Continuous playback** switch below Play. It starts off,
and remembers its choice independently for each podcast and saved server account.
The downloaded and server copies of a linked podcast share that choice. Unlinked
local podcasts have their own choice. The local-media detail page also exposes it.

When enabled, the main button says **Play continuously**. Starting an individual
episode anywhere in the app uses the same choice. At the end of the episode,
Android starts the next unfinished episode in the show's order: oldest first for
serial podcasts, newest first for episodic podcasts. It stops at the end without
wrapping back to earlier episodes. Episodes with missing or equal dates retain
their indexed order, or the source list order when indices are also absent.
Turning the switch off leaves the current
episode alone and stops subsequent advancement.

Continuation runs in the Android playback service rather than the WebView. It
prefers verified downloaded episode files. When streaming is permitted it uses the
server for gaps; offline it advances through available downloads. Background
continuation never opens a cellular-permission dialog: the existing **Always**
cellular-streaming choice is required on cellular networks. Pause, headset
disconnect, closing playback, or starting another item cancels pending advancement.
Streaming failures leave playback stopped. Chromecast does not support this switch;
it applies to playback on the Android device.

No persisted playback queue or media files are rewritten. The next episode is
resolved from the current playback session and current library/download records.
The preference survives app restarts and governs Android Auto episode playback too.

Regression checks:

- `node --test tests/podcast-continuous-playback.test.cjs` (Node 20).
- `./android/gradlew :app:testDebugUnitTest -p android --no-daemon` includes
  `PodcastPlaybackPolicyTest` for order, finished episodes, unavailable downloads,
  account isolation, restored preferences, and stale/duplicate callbacks.
- Generate and synchronize web assets before building an APK.
- Use `scripts/test_android_downloads.sh <adb-serial>` for isolated Paper recovery
  and storage checks on a valuable installation; never use Gradle's connected-test
  uninstall workflow on it.
