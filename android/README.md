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
- `widgets/` — one Glance implementation per type (`HelloWidget`,
  `TextWidget`, `ListWidget`, `StatusWidget`, `SecretWidget` + `SecretCrypto`
  for per-device decrypt), never fully-generic.
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

Place the Firebase console's `google-services.json` at
`android/app/google-services.json` — it is gitignored and never
committed. The `google-services` Gradle plugin is intentionally NOT
applied yet, so debug builds work without the file; without it, FCM token
fetch is best-effort and pairing/notification polling still work. Backend
side: `FCM_PROJECT_ID` + service-account JSON at `FCM_CREDENTIALS_FILE`
(see `backend/README.md`).

## Adding widgets

- Phone screen: open Widgets (refreshes from cache on open; "Refresh
  now" button; hourly background poll when the screen is on).
- Home screen: long-press → Widgets → Phone Companion → place per-type
  widget. Secret widgets render `•••••• needs refresh` whenever decrypt
  fails (wrong key after re-pair, corrupt payload) — never plaintext,
  never a crash; the next poll after a re-pair fetches a freshly
  encrypted envelope.

## F-Droid compatibility notes

No proprietary files are committed: `google-services.json` is gitignored,
no keystores ship, and the build works without them. Proprietary SDKs
(Google Play services ML Kit scanner, FCM) arrive only as Gradle
dependencies — full F-Droid inclusion would need review/replacement of
those deps, which is out of scope at v1. Reproducible build pointer:
tagged source + the pinned Gradle wrapper (`gradle-8.9`) + `compileSdk 34`
+ JDK 17; `./gradlew assembleDebug` with no local files beyond the SDK.
