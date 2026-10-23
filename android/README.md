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
with `sdk.dir` — never commit `local.properties`). The `google-services`
Gradle plugin is intentionally NOT applied yet: the notifications PR wires
sender config (`google-services.json`, never committed) and documents it.

## TODOs (later PRs)

Camera QR scan + `POST /api/v1/enroll`, Bearer-token storage, widget poll +
per-type renderers, notification channels/kinds/actions, `google-services`
wiring. Contract: `docs/api-contract.md`.
