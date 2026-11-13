# Android app

Native Kotlin app: QR pairing, widget polling + home-screen widgets, FCM
notifications. Contract: `docs/api-contract.md`.

## Layout

- `ui/` — `MainActivity` (launcher/navigation), `PairActivity` (QR scan
  + manual enroll), `WidgetsActivity` (cached list + manual refresh).
- `data/` — `ApiService`/`ApiClient` (Retrofit/Moshi), `AppDatabase`
  (Room widget cache), `EnrollRepository`, `PairingQr` (QR parse),
  `SecureStorage` (`EncryptedSharedPreferences`: base URL, device_id,
  device_token, device_key, FCM token — credentials only, never logged).
- `notify/` — `CompanionMessagingService` (FCM entry), `NotificationPayload`
  (kinds registry: `note`/`alert`, generic fallback for unknown kinds),
  `NotificationImages` (HTTPS `BigPictureStyle`, fails closed to
  `BigTextStyle`).
- `widgets/` — the single home-screen Glance widget (`CompanionWidget`
  + `SecretCrypto` for per-device decrypt), with per-type render paths
  (`WidgetContent` text/list/status/secret builders — never a
  fully-generic renderer). Per-instance row selection via
  `WidgetBindingResolver` + the `widget_binding` table (pinned in the
  Widgets screen).
- `work/` — `RefreshWorker` (hourly `WorkManager` poll: needs network +
  battery-not-low; screen-off returns retry, no exact alarms),
  `WidgetPoller` (shared fetch plumbing).

`minSdk 26`, `compileSdk/targetSdk 34`. Key deps: Retrofit/OkHttp/Moshi,
Room, WorkManager, Glance, FCM (via BoM), ML Kit code scanner,
`security-crypto` for encrypted storage.

## Build

Needs JDK 17 and the Android SDK (`ANDROID_HOME` or `local.properties`
with `sdk.dir` — never commit `local.properties`).

```sh
cd android
./gradlew assembleDebug            # debug APK
./gradlew installDebug             # install on an attached device/emulator
```

> Not run in this docs PR's environment (no Android SDK here); run it
> before release and report breakage.

## CI

PR checks (`.github/workflows/ci.yml`) build the app, run JVM unit tests,
then run the widget rendering suite on a Gradle-managed API 30 test device.
Same commands locally (needs JDK 17 and the Android SDK, see above):

```sh
cd android
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # includes the Compose compiler ABI guard
./gradlew connectedDebugAndroidTest # use an already running test emulator
# Or provision an isolated managed test device (requires virtualization):
./gradlew pixel2api30DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

The instrumented suite executes the production `provideGlance`, reads real
Room bindings/cache, and inflates the resulting RemoteViews into Android
views. It checks all four payload types at small and large sizes, live
selection/payload changes, key rotation, and unpairing. It uses fixture
credentials and clears the test app's credentials/cache; run it on a test
emulator, not a paired personal device. No Firebase project or live server
is needed. Launcher placement, camera scanning, and FCM delivery still need
their own end-to-end device checks.

Glance requires `buildFeatures.compose = true` even though the activities
use Android Views. The Compose compiler version must match Kotlin (currently
1.5.14 / 1.9.24). Without it, ordinary Kotlin compilation succeeds but
`provideContent` fails with `NoSuchMethodError` on Android. A running widget
composition observes the binding/cache and credentials; a one-time read
before `provideContent` would stay stale during an active Glance session.

## Pairing from the app

1. Open the app → Pair screen.
2. Tap scan: the ML Kit code scanner reads the WebUI `/webui/pair` QR
   (`{"enroll_url","enroll_token"}`) and fills base URL + token; or type
   them manually (plus a device name, prefilled with the model).
3. Tap Enroll: `POST {enroll_url}` with `enroll_token`, `device_name`,
   and the FCM token (best-effort — enroll proceeds without push).
4. On `201`, credentials persist to encrypted storage (re-pair overwrites
   them: the per-device key rotates, old secret ciphertexts stop
   decrypting by design).

Use the public origin (`https://<your-host>`) as base URL in production
— the same origin the QR encodes via `EXTERNAL_BASE_URL`. Failure text:
expired/used token, bad URL, network/server errors — nothing crashes,
nothing logs secrets. (The app also maps a 409 already-enrolled status,
but the server never emits it at v1 — reserved for future dedup; re-pair
returns `201`.) Detail: `docs/pairing.md`.

## Push (FCM)

Place the Firebase console's `google-services.json` at exactly
`android/app/google-services.json` — it is gitignored and never
committed. Steps: Firebase console → Project settings → General → Your
apps → Add app → Android, package name `com.phonecompanion.app`
→ download `google-services.json` → copy it to
`android/app/google-services.json` in your checkout → rebuild
(`./gradlew assembleDebug`). No keys ship in the repo: the file lives
only in your local checkout (and your release CI secrets, if you build
there), and unit tests use fake tokens only.

The `google-services` Gradle plugin is intentionally NOT applied, so
debug builds work without the file; at build time `app/build.gradle`
parses the file when present and bakes its four sender fields into
`BuildConfig`, otherwise `TODO-OPERATOR` placeholders keep the build
green. `notify/FirebaseInit.ensure()` gates both `FirebaseMessaging` entry
points — the pairing enroll (`PairActivity.fetchFcmToken`) and the push
receiver (`CompanionMessagingService.onCreate`, since the
manifest-registered `MESSAGING_EVENT` path can run before any activity)
— guarded by `FirebaseApp.getApps()` so it is idempotent, and fails
closed without config: enroll and polling still work, just without push. Init failure
and Play-services-unreachable log distinctly in logcat, with no secret
material in either line. Token lifecycle in the app:

- Pairing (`PairActivity`) fetches the token best-effort and sends it in
  the enroll body (`fcm_token`, null when push is not configured).
- `CompanionMessagingService.onNewToken` persists every rotation to
  encrypted storage and uploads it immediately when paired; if the upload
  fails (or the service dies first), the next successful widgets poll
  backfills it via `POST /api/v1/device/fcm-token`
  (`FcmTokenBackfill.maybeBackfill`, called from `WidgetPoller.refresh`).
  The endpoint is idempotent — rows that paired before push was
  configured heal without re-pairing.

Backend side: `FCM_PROJECT_ID` + service-account JSON at
`FCM_CREDENTIALS_FILE` (see `backend/README.md`).

### Remote APK install (`app_install` commands: poll delivery, FCM wake)

Delivery is the existing poll cycle (`RefreshWorker` →
`WidgetPoller.refresh`, hourly + manual refresh), which also GETs
`/api/v1/outbox` with the device Bearer token — plus instant delivery
when the app has an FCM token: the server sends a best-effort silent
`app_push_wake` data message on every app-push, and the client polls
immediately on receipt instead of waiting for the next cycle (poll
otherwise; no `google-services.json`, no new dependencies, no
Play-services-gated paths in this flow).
Each `app_install` command entry carries string fields
`type=app_install`, `command_id` (per-device id used for dedupe and
ack), `push_id`, `apk_url` (https only), `sha256` (64
lowercase hex), `package`, `version` (optional), `label` (required).
Unknown command kinds are acked-and-ignored and malformed entries are
skipped with shape-only logging (same lenient/strict discipline as the
notification parser).

For every fresh command id the app immediately starts the download
(progress notification first — never a "tap to download" prompt),
persists the id in a plain-prefs posted set (pruned to
the newest 200), then acks via `POST /api/v1/commands/ack`
(`{"ids": [...]}` — idempotent, so re-acks are harmless no-ops).
If the app dies before acking, the next poll re-offers and the
persisted set suppresses a duplicate download; if the server
already marked the id, the ack is a no-op. The download runs detached
from the poll worker; on verified bytes the installer session commits
at once, so the system's own Install confirmation is the user's single
press. While the app is foregrounded, a fresh command also shows one
in-app dialog naming the label (Details opens the pre-install screen,
Dismiss only dismisses — the download proceeds regardless, one dialog
per command id, never from background context).
The FCM token upload paths stay as-is for the notification feature.

- Permission: `REQUEST_INSTALL_PACKAGES` (manifest). The pre-install
  screen is optional detail (package, version or `unknown version`,
  short SHA-256 first 12 chars + `…`, source host) — it never gates the
  transfer. If `packageManager.canRequestPackageInstalls()` is false,
  Install stays disabled behind a short explanation plus a deep-link to
  this app's `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` page; the
  grant is re-checked on return, and install is never attempted
  without it.
- On receipt (FCM wake or poll) the app downloads over HTTPS via the
  shared OkHttp stack (redirects followed https-only) to the app cache
  dir with a progress notification + Cancel action (tap opens the
  pre-install detail; when the wake service owns the progress line —
  payload-free by design — its tap opens the app instead, never a dead
  tap), 100 MB hard cap (abort + error
  above it), then verifies SHA-256 BEFORE install (mismatch = hard
  refuse, error notification naming the package, file deleted, no hashes
  in logcat — package + match/mismatch only). The grant is a pre-commit
  check: missing grant keeps the verified APK cached and posts a grant
  notification routing to the one-time onboarding flow, from which the
  Install tap resumes without re-fetching.
- Install goes through `PackageInstaller.Session` (`MODE_FULL_INSTALL` +
  `setAppPackageName`) with a MUTABLE result PendingIntent on API 31+
  (so the system-filled status extras arrive); the installer-result
  receiver deletes the cached APK on success/cancel but keeps it on
  failure, and posts exactly one notification (success / plain
  user-cancelled with NO retry / cause-aware failure: transient offers
  Retry from the cached verified APK, signature/update-incompatible
  offers the target's system App-info screen with NO Retry — raw
  installer message stays secondary detail). A failed download retries
  the same way — its post carries Retry and the pre-install tap target,
  whose Install tap re-runs the transfer (no bytes survived). Every
  terminal notification is tappable: retryable kinds with a payload open
  the pre-install screen, everything else opens the app — a status post
  is never a dead tap. A
  `STATUS_PENDING_USER_ACTION` result launches the system's
  confirmation intent WITHOUT deleting the APK or claiming a terminal
  — the follow-up result owns the terminal. Because some devices
  (observed: Samsung + signing-key mismatch) refuse the install after
  confirmation without ever reporting a status back, the pending path
  also schedules a 2-minute WorkManager watchdog
  (`AppInstallResultWatchdogWorker`): if no terminal is claimed by
  then it posts the transient failure note (Retry, cache kept) without
  taking the claim, so a late real result still replaces it. The
  download-cancel
  receiver and the activity race to post at most one terminal state per
  Install-tap attempt (fresh nonce per tap, carried through the Cancel
  and installer-result intents — never two, never silent, never a
  retry). The transfer lives in a retained ViewModel, so rotation
  neither cancels the download nor spawns a second flow. The
  unknown-sources grant is re-checked after verify, before commit
  (revoked → onboarding gate, fail closed); stale `app_push/` entries
  are purged on download-start/activity entry (24 h retention). When the
  session commit itself throws (installer unavailable on some devices),
  the flow re-verifies the cached APK and opens it in the system's package
  installer via an `ACTION_VIEW` FileProvider intent as a last resort (the
  VIEW returns no result, so an informational notification replaces the
  failure note — complete the install there; the verified APK stays cached
  until the 24 h purge). Signature-mismatch failures never take this path:
  retrying the same bytes is futile, so they still offer App-info.

Manual test checklist (needs a paired device + operator backend; FCM
steps only for the instant-wake path):

1. Pair the app, then open system Settings → Apps → Phone Companion →
   “Install unknown apps” and leave it OFF.
2. Operator enqueues a test command for a small APK you built yourself
   (`type=app_install` + real `push_id`/`apk_url`/`sha256`/`package`/
   `version`/`label`) via the operator command-queue endpoint (curl).
3. Wait at most one poll interval (tap Refresh now for an expedited
   manual refresh, or rely on the FCM wake for instant delivery); a
   `Downloading <label>…` progress notification arrives on the general
   channel and the download starts with no tap. With the app open, one
   in-app dialog names the label (Details opens the pre-install
   screen; Dismiss changes nothing).
4. Pre-install screen shows package, version, short SHA-256, source host;
   Install is disabled behind the unknown-sources explainer → tap Allow,
   grant in Settings, return → Install enables. (If the grant was
   already missing at commit time, a grant notification routes here
   instead and the Install tap resumes from the verified cache.)
5. Download completes on its own (progress notification grows; SHA shown
   matches the file you uploaded — `sha256sum` the APK and compare the
   first 12 chars).
6. System install dialog appears → Accept → success notification, app
   installed; `cacheDir/app_push/` holds no leftover APK
   (`adb shell ls /data/data/com.phonecompanion.app/cache/app_push`).
7. Negative paths: enqueue a command with a wrong `sha256` → blocked
   error naming the package, no install attempt, cache clean. Cancel
   mid-download → single cancelled notification, partial file gone.
   Deny at the system dialog → single plain cancelled note, no retry.
   Revoke “Install unknown apps” mid-download → onboarding gate, fail
   closed, verified APK kept for grant-resume.
8. Idempotence: re-pair (or wait for the next poll before the ack lands)
   → no duplicate download or dialog for an already-posted id; the re-offer is
   acked as a no-op.

#### Silent installs via Shizuku (optional, off until the user enables it)

When the Shizuku manager app is running and this app has been granted
Shizuku permission, verified APKs commit silently — no system install
confirmation, no unknown-sources grant needed (privileged installer
sessions need none, like `adb install`). Shell `pm` is deliberately NOT
used: it is fragile across ROMs (user targeting, output parsing, file
visibility). Instead the app builds a `PackageInstaller` on the Shizuku-
held `package` system-service binder (hidden-API access via the
Apache-2.0 HiddenApiBypass lib, version-pinned — reflection only,
written for this call path), creates a MODE_FULL_INSTALL session
(replace-existing semantics) for the verified staged APK, writes the
APK bytes into the session from our own storage, and commits. The session carries the adb-install trust flag (`INSTALL_FROM_ADB`, resolved reflectively with a `0x20` fallback) so the system treats it like an `adb install` and skips the confirmation dialog — without Shizuku or a granted binder the dialog path is unchanged. The commit
carries a status receiver that is only ever observed — a user-
confirmation intent is never launched on this path, ever. The commit
call alone is never trusted: the installed package's versionCode is read
back afterwards and must equal the staged APK's versionCode (parsed once
before commit and passed through), or the silent attempt fails over.
Everything else stays exactly as above: the progress notification posts
first and grows the same way, SHA-256 is verified BEFORE anything
installs (mismatch = hard refuse, same notification), and terminal
notifications are the same success copy. An unverified silent attempt
(mismatch or absent version after the grace window, binder trouble)
falls back to the existing chain — dialog PackageInstaller path →
ACTION_VIEW fallback → terminal — so a signature mismatch still reaches
its App-info screen with no Retry via the dialog result mapping, and
anything transient still offers Retry from the kept verified cache.
Unknown installer states fail closed to the dialog path; nothing ever
silent-retries in a loop.

User setup (all in the pre-install screen — nothing auto-starts, no new
periodic work, no boot receiver):

1. Install the Shizuku manager app (`https://shizuku.rikka.app/download/`,
   a separate user install, never bundled) and start it — via wireless
   debugging (Android 11+) or ADB from a computer.
2. Open any app push's pre-install screen (Details from the progress
   notification): a “silent install via Shizuku” line appears with an
   Enable button. Tap it once and approve in Shizuku.
3. Later pushes then go silent. Deny once (or “deny and don’t ask again”)
   and the line disappears for good — installs fall back to the system
   confirmation with no nagging.

Behavior matrix:

- Binder alive + granted → silent commit at commit time, foreground or
  background (background never requests permission: a backgrounded
  command without an existing grant takes the dialog path as today, and
  the next foreground dialog routes to the pre-install screen where the
  enable lives).
- Shizuku missing / pre-v11 / binder dead / permission denied →
  current dialog flow, unchanged (grant gate included).
- Binder dies mid-install, staging is impossible, the staged APK is
  unparseable, or the post-commit version read-back mismatches/is
  absent after the grace window → falls back to the dialog flow from
  the cached verified APK; silent trouble never fails the install
  attempt and never loops.
- A cancelled attempt never commits behind the user (checked before
  staging, same as the dialog tail).

Plumbing notes: the verified APK is staged to the app-specific external
dir (`getExternalFilesDir("apk")/<pushId>.apk` — not media-scanned, no extra
permissions) so the privileged session writer reads it from our own
storage; no cross-uid readability is required. The staged copy is deleted
after every silent terminal and covered by the same 24 h stale purge as
the cache. The client library
(`dev.rikka.shizuku:api/provider:13.1.5`, Apache-2.0) plus HiddenApiBypass
(`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`, Apache-2.0,
version-pinned, reflection only) ship with the app; only the Shizuku
manager app is a separate install. The privileged installer targets the
current user only — work-profile installs are future work.

Manual test checklist (needs a paired device + the Shizuku app running;
cannot be CI-verified — no emulator runs Shizuku):

1. Without granting Shizuku: enqueue a test push → progress notification,
   system install dialog, success — exactly as before.
2. Pre-install screen shows the Shizuku status line + Enable button; tap
   Enable, approve in Shizuku, return → line hides (granted).
3. Enqueue another push → progress notification as usual, NO system
   dialog → success notification, app installed.
4. Deny path: revoke Shizuku permission in the Shizuku manager app,
   enqueue again → system dialog path, no nagging, no stuck state.
5. Negative paths: wrong `sha256` → blocked error, nothing installed
   (verify-before-stage holds); stop the Shizuku server mid-download →
   falls back to the system dialog from the verified cache.
6. Version check: push an APK whose install cannot land (e.g. a build
   that will not install, or kill the Shizuku server mid-install) →
   NO silent success is posted; the dialog path (then ACTION_VIEW, then
   terminal) owns the outcome exactly as without Shizuku.

### Pushing this app's own release APK (self-update)

Each GitHub Release attaches `phone-companion-vX.Y.Z.apk` with a real
`versionName` (the tag) and a monotonic `versionCode` (commit count, so
every release counts as an update over earlier ones). Push it to paired
phones with the operator endpoint (placeholder token — never commit a
real one; `sha256sum` the downloaded asset for the real hash):

```sh
curl -X POST https://YOUR-HOST/api/v1/app-push \
  -H 'Authorization: Bearer OPERATOR_API_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"target":{"all":true},
       "apk_url":"https://github.com/<you>/phone-companion/releases/download/vX.Y.Z/phone-companion-vX.Y.Z.apk",
       "sha256":"<sha256-of-the-asset>",
       "package":"com.phonecompanion.app","version":"X.Y.Z",
       "label":"Phone Companion X.Y.Z"}'
```

Two hard rules (Android-enforced, not our choice): the pushed APK only
updates an installed build signed with the **same key** (release assets
are debug-signed by the CI runner — a phone carrying a locally built
APK must uninstall first, which wipes pairing, then re-pair), and the
pushed `versionCode` must exceed the installed one (guaranteed across
releases by the commit-count scheme above).

## Adding widgets

- Phone screen: open Widgets (refreshes from cache on open; "Refresh
  now" button; hourly background poll when the screen is on). Each
  home-screen instance gets a human-labelled button (pinned row type +
  title, or "Not associated"); tapping it opens the same row chooser as
  placement.
- Home screen: long-press → Widgets → Phone Companion → place the one
  "Widget" entry. Placement opens the configuration: pick any cached row
  (text/secret/list/status — title + id + human updated-at) or leave the
  widget unassociated, then it updates immediately. Cancelling stores
  nothing — the widget stays explicitly unassociated. Secret widgets
  render `•••••• needs refresh` whenever decrypt fails (wrong key after
  re-pair, corrupt payload) — never plaintext, never a crash; the next
  poll after a re-pair fetches a freshly encrypted envelope.

## Per-instance widget selection

Each home-screen widget shows exactly the cached row chosen for it at
placement: tap the widget (when it shows "Not associated") or its button
in the Widgets screen to open the chooser, and pick a row (or
"Not associated" to clear the pick). Two instances may show the same row.
A widget with no pick never shows another row's content. The Widgets
screen and the chooser name rows by title + id + updated-at in human
words — raw home-screen instance ids are never shown.

Where it is stored: the Room `widget_binding` table in the app database
(`phone-companion.db`) maps one home-screen instance — provider class +
Glance key — to one cached widget id (`widgetId` NULL = explicitly
unassociated). There is one provider class now (`CompanionWidget`);
bindings keyed to the removed per-type providers were deleted by the v4
migration. There is no foreign key to the widget cache on purpose.

Gone-widget behavior: when the associated widget disappears from the
server, the instance shows the unassociated state (never a stale render)
and the chooser shows a `was-<old id>` hint naming the gone row so you
can re-point it. Bindings for home-screen widgets you remove are pruned
on the next widget push. The widget picker shows label + description
only (no static previews).

## F-Droid compatibility notes

No proprietary files are committed: `google-services.json` is gitignored,
no keystores ship, and the build works without them. Proprietary SDKs
(Google Play services ML Kit scanner, FCM) arrive only as Gradle
dependencies — full F-Droid inclusion would need review/replacement of
those deps, which is out of scope at v1. Reproducible build pointer:
tagged source + the pinned Gradle wrapper (`gradle-8.9`) + `compileSdk 34`
+ JDK 17; `./gradlew assembleDebug` with no local files beyond the SDK.
