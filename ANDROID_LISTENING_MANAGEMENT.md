# Android listening and download management

The Android player shows local save and server acknowledgement status for the current book or episode. Tap it for details or retry; Settings and Downloads link to **Listening & downloads**, which lists pending items and download/cleanup controls. A successful connection is not a progress acknowledgement. Existing progress starts unverified; acknowledgements are tied to account, item and saved revision. Paused seek edits use a network-constrained worker with quiet exponential retries, independently of automatic downloads.

Explicit seeks offer a ten-second Undo banner and **Undo last jump** in the player menu. Skip commands within two seconds share one recovery point. Undo preserves playing/paused state; recovery is cleared on item change, close or process restart. Automatic rewinds and natural transitions do not create recovery points. History shows explicit seek sources and previous positions.

Enable **Keep next episodes downloaded** on a server podcast. The default is three episodes, Wi-Fi only, in continuous-playback order (serial oldest first, episodic newest first), including the current unfinished episode. Settings provides a 2 GiB default cap for automatically downloaded audio, including reservations. Reaching the cap stops new transfers without evicting unfinished media. Periodic replenishment is approximate and subject to Android scheduling; transfers preserve validated staging when interrupted.

Finished-download cleanup removes audio only. Books and episodes have independent automatic switches (off by default) and delays (24 hours by default). Enabling includes existing eligible downloads after a preview. **Keep downloaded**, active playback, in-flight transfers, and uncertain file ownership prevent deletion. Automatic cleanup also waits for exact finished-progress acknowledgement and pending sessions to drain. Manual cleanup works offline and retains progress, pending sessions, metadata, companion ebooks, covers, and server content.

New transfers record exact audio-file provenance. Legacy internal downloads can be verified from their server-linked records and private download root. Legacy shared files without a download manifest are listed as skipped: a filename or directory alone is not sufficient ownership evidence. Metadata-only records remain available for progress and re-download reconciliation but are not treated as playable downloads.

## Verification

Use Node 20 for frontend commands:

```
node --test tests/*.test.cjs
npm run generate
npx --no-install cap sync android
./android/gradlew :app:testDebugUnitTest assembleDebug -p android --no-daemon
scripts/test_android_downloads.sh <adb-serial>
```

The instrumentation script updates the debug app with `adb install -r`, runs explicit isolated suites, and removes only the test runner. The new suite uses the existing cache fixture root; its document provider lives in the disposable test APK and exposes only synthetic cache files; a fixture context models granted and revoked access to that test authority. Never use `connectedDebugAndroidTest` against an installation containing valuable data.
