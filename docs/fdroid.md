# F-Droid readiness (NOT enabled)

F-Droid inclusion is a conscious non-goal at v1. Nothing has been
submitted, and no F-Droid-specific files (upstream recipe, fastlane
metadata) are committed — they would have no consumer until submission
day. This note records exactly what remains so enabling later is trivial.

## Dependency audit (honest result: NOT zero proprietary deps)

- Backend: all Go module deps are open source (`go.mod`: `go-qrcode`,
  `x/crypto`, `modernc.org/sqlite` + indirect). Moot for F-Droid anyway —
  the backend ships as server-side Docker, never as an on-device APK.
- Android: mostly OSS (AndroidX, Retrofit/OkHttp/Moshi, Room, Glance,
  `security-crypto` — all Apache 2.0), BUT three proprietary Google Play
  services deps remain in `android/app/build.gradle`:
  - `com.google.firebase:firebase-messaging` (via BoM `33.2.0`)
  - `com.google.android.gms:play-services-mlkit-barcode-scanning`
  - `com.google.android.gms:play-services-code-scanner`

## What remains

1. Drop or gate the three proprietary deps above — e.g. an F-Droid build
   flavor without FCM (poll-only notifications) and with an OSS QR
   scanner replacing the ML Kit code scanner. Full inclusion needs this;
   there is no shortcut.
2. Add `android/fastlane/metadata/android/en-US/` (title, short/full
   description, screenshots, changelog) — skipped now on purpose.
3. Add the upstream `fdroiddata` recipe (repo URL, pinned tag, gradle
   flavor + `assemble<Flavor>Release` step) and get the first F-Droid
   build green.
4. Confirm reproducibility against the F-Droid build (pointers already in
   place: pinned Gradle wrapper `8.9`, `compileSdk/targetSdk 34`,
   JDK 17, no committed `google-services.json`/keystores, and the
   `google-services` plugin intentionally not applied so the build works
   without proprietary files).

Do NOT submit anything to F-Droid until 1–4 are done.
