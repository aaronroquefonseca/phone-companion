# Android app (skeleton)

Native Kotlin app: QR pairing, widget polling, FCM notifications. P0 BASE
declares the dependency surface and component stubs; no logic.

## Layout

- `ui/` — `MainActivity` (launcher, navigation only), `PairActivity`
  (QR scan stub), `WidgetsActivity` (list stub).
- `data/` — `ApiService`/`ApiClient` (Retrofit stubs), `AppDatabase`
  (Room stub: `devices` table placeholder).
- `notify/` — `CompanionMessagingService` (FCM stub).
- `widgets/` — `HelloWidget` (Glance wiring stub).
- `work/` — `RefreshWorker` (hourly poll stub).

`minSdk 26`, `compileSdk/targetSdk 34`. Key deps: Retrofit/OkHttp/Moshi,
Room, WorkManager, Glance, FCM (via BoM), ML Kit barcode scanning.

## Build

```sh
cd android
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK (`ANDROID_HOME` or `local.properties`
with `sdk.dir` — never commit `local.properties`).

## Notifications (P9)

`notify/` renders FCM data messages per `docs/api-contract.md`
(`POST /api/v1/notify` fan-out fields as the message's data map):

- **Kinds registry** (`NotificationKinds`): `note`/`alert` render with
  kind-specific channels; unknown kinds and unknown payload versions (`v`)
  render generically so new server kinds never need an app update. To add a
  kind, extend `KNOWN_KINDS` (+ `displayName`); branch rendering only if it
  needs more than title/body.
- **Channels**: one channel per kind (`pc_<kind>`), or the backend's
  explicit `channel` when sent (sanitized). Per-kind channels stay
  independently mutable by the user; Android keeps an existing channel's
  settings, so priority changes only affect newly created channels.
- **Actions**: up to 3 buttons from `action_<i>_id`/`action_<i>_title`
  pairs. Tap and action-button taps open `MainActivity` with the
  notification/action ids; action taps also dismiss the notification
  (buttons don't auto-dismiss).
- **Images**: `image_url` (HTTPS-only) downloads and renders
  `BigPictureStyle`; any download/decode failure fails closed to
  `BigTextStyle` — a bad attachment never crashes or blocks the
  notification.

## FCM wiring

Push needs two pieces, both secret-bearing and never committed:

1. **App side**: place the Firebase console's `google-services.json` at
   `android/app/google-services.json` (gitignored). The `google-services`
   Gradle plugin is intentionally NOT applied yet, so debug builds work
   without the file; enabling full sender wiring applies the plugin per the
   Firebase docs.
2. **Backend side**: `FCM_PROJECT_ID` plus the service-account JSON mounted
   at `FCM_CREDENTIALS_FILE` (compose secret, never committed or baked into
   the image). The FCM driver is still a skeleton that fails closed until
   the Wave-2 push implementation; the default `log` driver needs no
   credentials.

## TODOs (later PRs)

Camera QR scan + `POST /api/v1/enroll`, Bearer-token storage, widget poll +
per-type renderers, `google-services` plugin apply once sender wiring lands.
Contract: `docs/api-contract.md`.
