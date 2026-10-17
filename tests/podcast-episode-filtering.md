# Episode filtering regression checks

Run from the repository root with Node.js 20:

```sh
node --test tests/podcast-episode-filter.test.cjs tests/podcast-episode-performance.test.cjs
node --test tests/*.test.cjs
```

The filter suite exercises the actual Vue computed properties with reactive settings,
progress and episode arrays. It covers every pair of filter choices, local versus
server episode IDs, disconnected local lists without a user, imported episodes
without server associations, added/removed downloads, progress changes, empty lists,
all five sort keys in both directions, numeric filename ordering and missing file
metadata. Android, iOS and web filter behavior is checked.

Rendering tests use the actual episode-row template and assert that the displayed
count equals the rendered rows, with no Vue render errors. The performance suite
also checks filter transitions through 0, 3, 100, 101 and 1200 matches. Above 100
matches Android should mount only the virtual window while reporting the complete
filtered count; iOS should render every match.

## Confirmed phone reproduction

With the fork disconnected from a server, open a downloaded podcast with three
local episodes. Select Filename ordering and then:

1. Select Downloaded: the old code shows `Episodes (0)` because it checks local IDs
   against a map keyed by server episode IDs.
2. Select Show All: the old code shows `Episodes (3)` with blank rows. Local episodes
   have `audioFile: null`; their filename is in `audioTrack.metadata.filename`.
   Each row throws when the old template reads `audioFile.metadata.filename`.

The fixes recognize a local list as downloaded and use the same safe filename
resolver for sorting and rendering. The shared Vue components serve both Android
and iOS; no native storage, download files or progress records are changed.

For a physical-phone retest, update the existing debug fork in place using
`scripts/deploy_android.sh <adb-serial>`. Do not uninstall or clear app data. Test
all five filters repeatedly with Filename ordering, verify the selected count
against actual visible rows (including a valid empty Complete/In Progress result),
and check WebView logs for rendering exceptions. Repeat after restarting the app.
Unit and rendering fixtures stay in memory; they do not write the phone database.

Verified on the connected Pixel on 2026-10-10, before and after a cold restart:
all 25 filter pairs, all five sort keys in both directions, and no filtering render
errors. Offline counts were All/Incomplete/Downloaded = 3, In Progress = 1,
Complete = 0. The in-place debug update retained the installation timestamp, three
local episodes and nine progress records. Connected-server cases are covered by
synthetic reactive/rendering tests; a live connected-server session was not tested.
The full frontend suite passed 108 tests; the Android unit task succeeded with
43 tests in its up-to-date result, and web generation/sync/debug assembly passed.
