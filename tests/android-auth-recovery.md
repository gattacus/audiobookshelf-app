# Android session and connection recovery

Run the regression suites from the repository root with Node.js 20:

```sh
node --test tests/auth-recovery.test.cjs tests/server-reconnect.test.cjs
node --test tests/*.test.cjs
```

The tests execute the real HTTP plugins, Vuex refresh/logout actions, Socket.IO
wrapper and layout recovery hooks with isolated in-memory transport, storage and
timer fixtures. They never inject credentials or fixtures into the phone database.

## Confirmed causes and intended behavior

Previously, native HTTP and Axios logged out and cleared the saved refresh token
after any refresh exception, including a timeout or HTTP 503. Native HTTP also
erased a newly renewed session when the subsequent API retry failed. Socket auth
failure only changed a flag, and a server-forced disconnect left an inactive
Socket.IO client without a recovery path. A connected VPN can keep the network
boolean true throughout airplane mode, so a network watcher alone is insufficient.

Native HTTP, Axios and socket recovery now share one refresh per current session.
Transport failures, temporary server errors and malformed refresh responses retain
credentials. Only an explicit refresh HTTP 401/403 rejects the credential and
requires login; a missing stored credential also requires login. A failing API
retry propagates its error without invalidating the renewed session. Refreshes
check the current session and stored credential before changing it, and logout
invalidates outstanding work.

Android reconnects inactive sockets, renews rejected socket authentication and
retries temporary failures with bounded backoff. Network restoration and returning
to the foreground both trigger recovery. A missing authentication response also
has a retry watchdog. The connected indicator becomes true after the socket's
authenticated `init` event. The Android socket behavior is platform gated; iOS
keeps its existing transport-connected semantics. The credential-preservation
fix is shared by the HTTP clients.

## Automated coverage

- Refresh timeout, malformed success and HTTP 400/404/408/429/500/502/503;
  explicit rejection 401/403 and missing credentials.
- API retry failure after successful renewal, including 401/403/404/503 and
  transport exceptions; unrelated hosts and login/refresh endpoint isolation.
- Concurrent clients, late 401 responses, rotation omitted by the server,
  failed storage writes and all waiting Axios callers settling after failure.
- Logout during network or storage work, account changes, native token rotations
  and stale success/rejection responses.
- Socket authentication failure, forced disconnect, ordinary transport retry,
  temporary refresh failure, missing auth response, replaced socket events,
  overlapping recovery, offline/online transitions and foreground recovery.
- Android connection-state publication and iOS platform isolation.

The two targeted reproductions (refresh HTTP 503 and socket auth failure) fail
against the pre-fix plugins and pass with the changes. On 2026-10-10 the complete
frontend suite passed 158 tests. Android unit verification succeeded with the
existing 43 tests up to date; web generation, Android sync and debug assembly passed.

## Physical-phone checks

Install the fork in place with `scripts/deploy_android.sh <adb-serial>`; never
uninstall, clear data or use Gradle connected-test cleanup on a valuable phone.
An existing refresh credential is required for a meaningful session test.

Check a normal airplane-mode cycle, then restore the original Wi-Fi and mobile
settings. Confirm that the saved account remains present and the socket becomes
authenticated without entering a password. Repeat while a VPN reports connected
with connection type unknown. Check foreground return and a cold restart.

On the connected Pixel on 2026-10-10, the in-place update preserved the original
installation timestamp and real login. Three simultaneous refresh callers issued
one real server refresh. Invoking the actual Socket.IO forced-disconnect handler
recovered automatically. An in-memory, one-shot HTTP 503 refresh fault preserved
the credential and retried against the real server successfully. With airplane
mode enabled and Wi-Fi disabled, Tailscale continued reporting connected/unknown;
forced socket recovery failed over the real offline transport, retained the
credential and scheduled retries. Restoring connectivity recovered automatically.
An ordinary airplane-mode cycle also recovered after the socket naturally detected
transport loss. Foreground return and a cold process restart retained the login
and authenticated connection. The final library contained seven local items,
nine local episodes and nine progress records, matching the pre-restart counts.
No crash-buffer fatal exception or native fatal signal was recorded for either
tested fork process. Airplane mode, Wi-Fi and mobile-data settings were restored
to their original values.
These controlled runtime faults were temporary method wrappers and socket events,
not database fixtures. No deliberately rejected credential was sent to the live
server; that destructive case is covered by isolated tests.
