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

PR checks (`.github/workflows/ci.yml`) run unit tests only — no emulator.
Same commands locally (needs JDK 17 and the Android SDK, see above):

```sh
cd android
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # unit tests (androidTest/ NOT run in CI)
```

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
