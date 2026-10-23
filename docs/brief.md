# Build brief — Phone Companion

Build this project: a self-hosted backend plus a native Android app that
together provide home-screen widgets and custom push notifications.
Boring, maintainable stack choices are yours (Kotlin for Android; backend
whatever you would run for years with a small footprint). Repo layout:
`android/` app, `backend/` with a Dockerfile (plus compose example), docs,
and whatever else the project needs to be complete and runnable.

## Pairing

Backend serves a small WebUI behind a login. It shows a pairing QR; scanning
it enrolls the phone with per-device credentials. Multiple phones must be
pairable, individually revocable, and after pairing everything works with no
further manual setup.

## Notifications

Addressable to all devices or to specific ones. Design the notification
system to be extensible: multiple notification kinds and configurations
(priority, channels, actions, images/attachments where sensible), easy to
extend without app updates wherever you can manage it.

## Widgets

The app polls the backend: manual refresh when opened, plus roughly hourly
in the background. Screen-on aware, battery-conscious, no exact alarms.
The backend exposes one definition per widget from a mounted directory of
plain-text files (one file per widget or one folder per widget — your
call). Polling plumbing is shared and generic, but each widget type gets
its own implementation — not one fully-generic renderer. Some widget
payloads will carry secrets: encrypt those appropriately in transit per
device (TLS at minimum; per-device payload encryption where the content is
sensitive — your design, document the threat model briefly).

## Principles

- Adaptable for other people: configuration-driven, no hardcoded hosts or
  users, documented setup from zero to paired phone.
- Secure by default: login-gated UI, per-device tokens with revocation,
  least privilege throughout, no secrets in the repo, logs, or chat output.
- Docker-first backend; the app must work against a backend reached over
  the internet (assume reverse proxy + TLS in front).

## Done when

`android/` builds, `backend/` builds and runs from its Dockerfile, README
covers setup and pairing, and you have verified: two devices paired with
targeted delivery to one of them, plus a secret-bearing widget round-trip
staying encrypted.
