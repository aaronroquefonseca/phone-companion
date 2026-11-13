---
name: phone
description: "Use when the task touches any Android device managed through Phone Companion: notify me, ping or alert the device, push or install an APK, get a build onto a device, look at the screen, take a screenshot, pull the last photo, read logs, test or drive an app with taps and text, or work out why something did not arrive. Also load it when a job holds a connection open or needs the screen usable, even if no device is named, and for anything else related to controlling the managed Android devices."
license: MIT
compatibility: Needs adb access to the device, or the Phone Companion app link (operator token) with Shizuku granted for shell and silent-install work.
---

# Phone Companion

One backend serves every job: notifications, app pushes, and two shell
channels that both run as UID 2000 — exactly `adb shell`. The same secrets
discipline applies everywhere: reference credentials only as
`"$(cat <path>)"`, never print, log, quote, or commit their values.

## 0. Configure once

```sh
PC=https://phone.example.com                  # placeholder: another host
TOKEN=<repo>/secrets/operator-token           # operator credential file
ADMIN=<repo>/secrets/admin-password.txt       # WebUI password file
```

If the backend runs on this machine, ask it instead of searching the disk:

```sh
# the shipped image is distroless: no shell, no printenv, so read one variable
PC=$(docker inspect -f '{{range .Config.Env}}{{if eq (index (split . "=") 0) "EXTERNAL_BASE_URL"}}{{index (split . "=") 1}}{{end}}{{end}}' phone-companion-backend)
# on an image with a shell, this is the same read:
# PC=$(docker exec phone-companion-backend printenv EXTERNAL_BASE_URL)

REPO=$(docker inspect -f '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' phone-companion-backend |
  sed -n 's#/backend/widgets$##p')                                     # repo path
TOKEN=$REPO/secrets/operator-token
ADMIN=$REPO/secrets/admin-password.txt
```

Never inspect the container's full env: it prints `OPERATOR_API_TOKEN`,
`ADMIN_PASSWORD` and `SESSION_SECRET` in plaintext. One variable at a time;
never print a secret's value.

Every command below uses `$PC` and `$(cat "$TOKEN")`. The backend container is
`phone-companion-backend` by default, with the database at
`/data/phone-companion.db`. `GET /api/v1/devices` is admin-session gated
(`backend/pair_wire.go:54`), so the login + cookie step in §1 is required, not
a convenience.

## 1. Resolve the target (never hardcode device ids)

Device ids rotate on every re-pair. Resolve names → ids live:

```sh
JAR=$(mktemp)
curl -sk -c "$JAR" -d "username=admin&password=$(cat "$ADMIN")" \
  -o /dev/null "$PC/login"
COOKIE=$(grep pc_session "$JAR" | awk '{print $NF}')
curl -sk -H "Cookie: pc_session=$COOKIE" "$PC/api/v1/devices"
rm -f "$JAR"
```

Skip `revoked` rows. "Everything" is `{"all":true}` in a target.

The list carries pair timestamps only — `updated_at` moves on a rename, never on
contact — so with several devices paired, "my phone" is a guess. The backend
should expose `last_seen_at` or `online`; until it does, ask which device.

## 2. Wake strategy — before anything on the screen

Identical on charger and battery; nothing depends on being plugged in.

Session start, once, for a job that holds the screen or a connection open
(transfer, install, long poll):

```sh
settings get system screen_off_timeout            # save this
settings put system screen_off_timeout 1800000    # 30 min inactivity
input keyevent KEYCODE_WAKEUP
wm dismiss-keyguard                               # non-secure locks only
```

A read-only glance measured in seconds needs no settings change: no
`screen_off_timeout` write, no restore obligation. It still wakes (below).

Before every action, including every read:

```sh
input keyevent KEYCODE_WAKEUP
```

A no-op when already awake. This is what survives the phone's own power
management, and why `svc power stayon` is not the plan — that only applies
while charging, so it is an extra, never the strategy.

**Re-arm the timeout before any long job** (transfer, install, long poll),
not just at session start: a short timeout cuts it in half and the failure
looks like a bug in the app.

Session end for a job that changed it, including on failure: restore the saved
timeout.

### Awake is not the same as unlocked

- **Shell work** (logcat, files, settings, package queries): screen state is
  irrelevant; success here says nothing about the screen.
- **Anything holding a connection open** (a link session, streamed transfer,
  foreground install): the screen must stay **awake for the whole
  operation**. Screen-off sleeps the radio and lets OEM power management
  freeze background apps, so the "idle" connection dies quietly.
- **Screen work** (`screencap` whose content matters, `uiautomator dump`,
  launching an app): must be **awake**. A black or lock-screen screenshot is
  not a bug — it is a dozing phone.
- **Input** (`input tap`, `input swipe`, `input text`, reading a real app's
  UI): must be **awake and unlocked**. A secure keyguard swallows all of it;
  taps land on the lock screen and the app underneath never sees them.

```sh
dumpsys power | grep -m1 mWakefulness      # Awake | Dozing | Asleep
```

If it is not `Awake` after the wake key, stop and ask the device owner.

## 3. Reaching the device

### The fast link — default for anything interactive

A persistent, device-initiated stream. One round trip in, the payload back, no
base64 and no 64 KB ceiling. It is a **session**: open it when you start
working and close it when you stop, so no socket is held while idle.

```sh
# open a session (30-3600 s). It wakes the app over FCM; "online":true means it was already up.
curl -s -X POST "$PC/api/v1/link/start" \
  -H "Authorization: Bearer $(cat "$TOKEN")" -H 'Content-Type: application/json' \
  -d '{"device_id":"<id>","ttl_s":900}'
# -> {"online":false,"ttl_s":900,"wake_sent":true}   ready in a few s (FCM wake)

# one blocking call per operation; the body IS the result
curl -s -X POST "$PC/api/v1/exec" \
  -H "Authorization: Bearer $(cat "$TOKEN")" -H 'Content-Type: application/json' \
  -d '{"device_id":"<id>","kind":"screenshot","args":{"width":540,"quality":60}}' \
  -o shot.jpg

# close it: {"online":true,"stopping":true} means closed. The next exec may be
# 503 device_offline or 504 device_timeout — both normal; do not retry the stop.
curl -s -X POST "$PC/api/v1/link/stop" \
  -H "Authorization: Bearer $(cat "$TOKEN")" -H 'Content-Type: application/json' \
  -d '{"device_id":"<id>"}'
```

Kinds and args:

| kind | args | returns |
|---|---|---|
| `screenshot` | `{"width":540,"quality":60}`, or `{"full":true}` | image/jpeg (PNG for full) |
| `file` | `{"path":"/sdcard/…","width":1600,"quality":80}` — image downscale is optional | the bytes, original content type unless downscaled |
| `logs` | `{"lines":200,"buffer":"main","filter":"-s TAG"}` | text/plain |
| `shell` | `{"command":"id -u","timeout_ms":30000}` | `{"exit_code","stdout","stderr","truncated"}` |

`width` snaps down to an integer divisor of the panel: 720 asked on a 2316 px
capture returns 579, and text is unreadable. Downscaled captures answer "where
am I"; use `{"full":true}` and crop locally to read anything.

Offline (no session) answers `503 device_offline` immediately, `504
device_timeout` if the device does not answer within `wait_ms` — a cold link's
first read can hit this while the FCM wake lands, so retry that read once.

Measured on a Note 20 Ultra over the public endpoint (~75 ms RTT):

| operation | time |
|---|---|
| session open | ~2-3 s (longer cold, FCM wake) |
| screenshot 540 px | **230-350 ms** |
| shell command | ~280 ms |
| logs, 200 lines | ~550 ms |
| photo 2.5 MB | ~2.1 s (professional phone uplink: ~1.2 MB/s) |
| same photo at 1600 px | ~0.9 s (174 KB, downscaled on device) |
| close session → 503 | <1 s |

**Fast screenshots need screen-capture consent**, one system dialog per app
process: the app's "Screen access" button → "Start now". The agent can tap
both through the link. Without consent (or after the phone sleeps, which
invalidates the projection) screenshots fall back to `screencap` at ~750 ms.
So for a working session: keep the screen awake, grant access once, and the
fast path holds.

Two artifact rules learned the hard way:

- A capture taken mid-transition can composite two screens. Settle ~200 ms
  after an input, or capture twice.
- `input swipe` is ~50% reliable in browsers; `input motionevent DOWN/MOVE…/UP`
  lands far more often, and a board that did not change means "resend", not
  "the app ignores input".

### adb — when a cable is attached

```sh
adb devices
adb -s <serial> shell '<command>'
adb -s <serial> exec-out screencap -p > shot.png    # no base64, no slicing
adb -s <serial> pull /sdcard/x.jpg ./x.jpg
adb -s <serial> logcat -d -s <TAG>
adb -s <serial> install -r app.apk
```

### Outbox shell — fallback only

`POST /api/v1/shell` + `GET /api/v1/shell/{id}`: same privilege, but queued
and polled, ~1.5 s per command, **64 KB of text per result** (base64 and slice
binaries at ~48 KB), and `timeout_ms` 1 000-120 000. Use it when the link
cannot start (no FCM), never for routine work.

```sh
base64 file | tr -d '\n' > /sdcard/_pull.b64
wc -c < /sdcard/_pull.b64
tail -c +<offset> /sdcard/_pull.b64 | head -c 48000
```

Two known edges: a `done` with empty stdout and `stderr:
shell_transport_failure` means the Shizuku bind raced and *nothing ran* —
retry once (fixed in app 0.26.0+); and do not `am force-stop` the companion
app while commands are in flight.

### Delivery modes (why "it didn't arrive")

- **FCM wake** — seconds while the screen is on; some OEMs (Samsung among
  them) hold data messages while the screen is off.
- **Instant mode** — the user's always-on toggle; holds the link permanently.
  The agent-driven session above is the battery-friendly equivalent.
- **Poll-only** (no FCM token) — hourly, or manual Refresh in the app.

## 4. Send a notification

```sh
curl -s -X POST "$PC/api/v1/notify" \
  -H "Authorization: Bearer $(cat "$TOKEN")" \
  -H 'Content-Type: application/json' \
  -d '{"target":{"device_ids":["<id>"]},"kind":"note","title":"<title>","body":"<body>"}'
# -> {"notification_id":"...","accepted":N}  202
```

`accepted:0` with no live target is normal, not an error. Fields: `kind`
(`note`, `alert`, extensible — unknown kinds render generically), `priority`,
`channel`, `actions[]` (max 3), `image_url` (https only). `"secret": true`
seals title/body per device; keyless targets fail closed for that device only
— never send secrets without it.

## 5. Push an APK / install

Check in order:

1. Target paired and reachable (§1).
2. `versionCode` must **exceed** the installed one, and the signer must
   **match** it. A mismatch is deterministic: the phone posts an App-info
   note. Fix is uninstall-first, then fresh install + re-pair — say so upfront
   when continuity is in doubt.
3. `apk_url` https ≤2048, `sha256` exactly 64 lowercase hex, `package`
   reverse-DNS. There is **no size cap**: the download budget is the device's
   free space minus a 256 MB reserve.

Getting a URL, ranked:

- **Public** — GitHub release asset, F-Droid, vendor link. Verify out of band
  (`sha256sum`, `apksigner verify`, `aapt dump badging`) before pushing.
- **WebDAV staging** — any temporary https host works; stage under a dated
  directory, keep it ephemeral, and restore the host's original state when
  done. Never store the hosting credentials.
- **GitHub release** — `gh release upload` when it should live permanently.
  Never commit APKs. Use a **fresh tag per build**; `--clobber` on an existing
  tag can serve the previous asset from the CDN and fail the hash check.

There is no backend upload endpoint: app-push is URL-only by design. Do not
invent one.

```sh
curl -s -X POST "$PC/api/v1/app-push" \
  -H "Authorization: Bearer $(cat "$TOKEN")" \
  -H 'Content-Type: application/json' \
  -d '{"target":{"device_ids":["<id>"]},"apk_url":"<https-url>","sha256":"<64-hex>",
       "package":"<pkg>","version":"<ver>","label":"<Label>","require_wake":true}'
# -> {"push_id":"...","accepted":1,"wake_sent":1,"poll_only":0}
```

`require_wake:true` refuses (422 `wake_unavailable`) when instant delivery is
impossible — use it when "install now" matters instead of discovering hours
later that it waited for a poll.

On device: silent privileged install when Shizuku is granted (no dialog),
otherwise progress → verified commit → system Install dialog (one press).
Missing unknown-sources grant routes to onboarding once. Failures post Retry
(transient) or App-info (signature) — never re-push blindly on a deterministic
failure. Downloads live in `cacheDir/app_push/<pushId>.apk`; deleted on
success, cancel, or failure; a verified APK whose install failed is kept for
retry and swept after 24 h.

## 6. Look at the device

One batched read answers "what is this phone doing right now?":

```sh
kind=shell {"command":"input keyevent KEYCODE_WAKEUP; dumpsys power | grep -m1 mWakefulness; dumpsys window | grep -m1 mCurrentFocus; dumpsys media_session | grep -E 'description=|state=PlaybackState' | head -4; wm size"}
```

Then one `{"full":true}` screenshot. Two calls, not a probe per question: batch
reads because a device call is ~300 ms and an agent turn is 10-20 s. An exit 0
with broken-pipe notes on stderr is still a complete read.

Read the screen by what is in the foreground:

| foreground | read it with |
|---|---|
| video player (YouTube, VLC, Twitch) | `media_session` for title, position and declared speed; the player's progress node for duration; screenshot for the rest |
| native app or browser | `uiautomator` dump, then tap |
| remote desktop, DeX, second screen | pixels only; the tell is a `screencap` size that differs from `wm size` |

`media_session` gives position and speed but no duration: a 42-minute video
reported `position=…, speed=2.0`, and the duration came from a progress-bar
`content-desc` ("25 minutes 32 seconds of 42 minutes 19 seconds"). A fullscreen
video of a desktop is indistinguishable from a desktop in pixels — confirm focus
plus an accessibility tree whose only content is "Video player". `speed=2.0` is
a declaration, not proof: sample `position` and `updated` twice; `Δposition /
Δupdated` is the real rate.

```sh
# screenshot (fast link)
…/exec kind=screenshot {"width":540,"quality":60}   # -> image/jpeg

# newest photos, newest first (run through kind=shell)
content query --uri content://media/external/images/media \
  --projection _id:_data:date_added:_size --sort "date_added DESC"
# then kind=file {"path":"<_data>","width":1600} to fetch it small, or no width for the original

# logs
kind=logs {"lines":200}                        # main buffer, ~550 ms
kind=logs {"lines":200,"buffer":"crash"}       # crash buffer
kind=shell {"command":"logcat -d --pid=$(pidof <pkg>) -t 5"}

# UI: read, then act
kind=shell {"command":"uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml"}
kind=shell {"command":"input tap 653 795"}
kind=shell {"command":"input motionevent DOWN 900 1300; input motionevent MOVE 500 1300; input motionevent UP 200 1300"}
```

Element positions only mean something if the layout is stable: re-read after
acting, and treat state that could not have happened (a value going
backwards) as a bad read, not as a rule of the app.

## 7. Verify and report

Server-side, without touching the device:

```sh
docker cp phone-companion-backend:/data/phone-companion.db /tmp/pc-inspect.db
chmod 600 /tmp/pc-inspect.db
python3 -c "
import sqlite3
db = sqlite3.connect('/tmp/pc-inspect.db')
for r in db.execute(\"SELECT device_id, status FROM outbox WHERE push_id='<id>'\"):
    print(r[1][:8], r[0])
"
```

The database runs in WAL mode: copy `-wal` alongside the `.db` or recent rows
are missing. `pending` = waiting for poll, `received` = fetched + acked.
`received` without progress on device means the app side swallowed it — say
so and stop.

Report: target name(s), id (first 8), accepted count, file/version where
relevant, what to expect on device (instant vs next poll). No credentials, no
tokens, no device secrets — ever.

## 8. Rules

- Never print a token, password, or pairing secret; `"$(cat <path>)"` only.
- Never hardcode device ids.
- Never tap a screen that may be asleep or locked: wake, then verify.
- Never run `locksettings set-disabled` or change lock security silently. On a
  secure lock, ask the device owner to unlock once; never disable their PIN.
- Never infer a working screen from a working shell — prove it with a real
  read and a state that changed.
- Never leave a link session open when you stop working: `link/stop`, and
  restore the screen timeout only if you changed it.
- Never re-push blindly after a deterministic failure.

## Installing this skill

An agent only uses a skill it can see, and phone work arrives from any
directory, so install it **system-wide** for serious use — a project-local
install only covers sessions started in that project.

```sh
ln -s "$PWD/skills/phone" ~/.agents/skills/phone            # portable agent dir
ln -s ../../.agents/skills/phone ~/.claude/skills/phone     # Claude Code
```

Keep the directory name (`phone`) as-is: it becomes the skill id. Reload the
agent afterwards; most clients pick skills up on the next session.
