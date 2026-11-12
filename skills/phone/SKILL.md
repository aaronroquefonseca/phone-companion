---
name: phone
description: "Use when the task touches any Android device managed through Phone Companion: notify me, ping or alert the device, push or install an APK, get a build onto a device, look at the screen, take a screenshot, pull the last photo, read logs, test or drive an app with taps and text, or work out why something did not arrive. Also load it when a job holds a connection open or needs the screen usable, even if no device is named, and for anything else related to controlling the managed Android devices."
license: MIT
compatibility: Needs adb access to the device, or the Phone Companion app link (operator token) with Shizuku granted for shell and silent-install work.
---

# Phone Companion

One backend serves every job: notifications, app pushes, and a shell channel
that runs as UID 2000 — exactly `adb shell`. The same secrets discipline
applies everywhere: reference credentials only as `"$(cat <path>)"`, never
print, log, quote, or commit their values.

## 0. Configure once

```sh
PC=https://phone.example.com                  # your deployment
TOKEN=<repo>/secrets/operator-token           # operator credential file
ADMIN=<repo>/secrets/admin-password.txt       # WebUI password file
```

Every command below uses `$PC` and `$(cat "$TOKEN")`. The backend container is
`phone-companion-backend` by default, with the database at
`/data/phone-companion.db`.

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

## 2. Wake strategy — before anything on the screen

Identical on charger and battery; nothing depends on being plugged in.

Session start, once:

```sh
settings get system screen_off_timeout            # save this
settings put system screen_off_timeout 1800000    # 30 min inactivity
input keyevent KEYCODE_WAKEUP
wm dismiss-keyguard                               # non-secure locks only
```

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

Session end, always, including on failure: restore the saved timeout.

### Awake is not the same as unlocked

- **Shell work** (logcat, files, settings, package queries): screen state is
  irrelevant; success here says nothing about the screen.
- **Anything holding a connection open** (instant-mode long poll, streamed
  transfer, foreground install): the screen must stay **awake for the whole
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

### adb — first choice: fast and binary-safe

```sh
adb devices
adb -s <serial> shell '<command>'
adb -s <serial> exec-out screencap -p > shot.png    # no base64, no slicing
adb -s <serial> pull /sdcard/x.jpg ./x.jpg
adb -s <serial> logcat -d -s <TAG>
adb -s <serial> install -r app.apk
```

### App-link shell — when there is no cable

```sh
POST /api/v1/shell  {"device_id","command","timeout_ms"}   -> {"command_id"}
GET  /api/v1/shell/{command_id}   -> pending | done {exit_code,stdout,stderr}
```

`timeout_ms` 1 000–120 000 (default 30 000). Commands are at-most-once.

- **64 KB of text per result.** Binaries must be base64'd and sliced (~48 KB
  per slice, 1–3 s each): a 1.5 MB photo is 40+ round trips. Fine for a
  one-off, never for routine work.
- **`done` with empty stdout and `stderr: shell_transport_failure`** means the
  Shizuku bind raced and *nothing ran*: retry once. Fixed in app 0.26.0+.
- Don't `am force-stop` the companion app while commands are in flight; that
  kills the poll and queued results come back empty.

```sh
# chunked pull
base64 file | tr -d '\n' > /sdcard/_pull.b64
wc -c < /sdcard/_pull.b64
tail -c +<offset> /sdcard/_pull.b64 | head -c 48000
```

### Delivery modes (why "it didn't arrive")

- **FCM wake** — seconds while the screen is on; some OEMs (Samsung among
  them) hold data messages while the screen is off.
- **Instant mode** — opt-in in the app, a held long poll: ~1 s regardless of
  screen state, at the cost of one persistent notification. It survives
  reboot and app updates on 0.25.0+.
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
   reverse-DNS, APK under 100 MB.

Getting a URL, ranked:

- **Public** — GitHub release asset, F-Droid, vendor link. Verify out of band
  (`sha256sum`, `apksigner verify`, `aapt dump badging`) before pushing.
- **WebDAV staging** — any temporary https host works; stage under a dated
  directory, keep it ephemeral, and restore the host's original state when
  done. Never store the hosting credentials.
- **GitHub release** — `gh release upload` when it should live permanently.
  Never commit APKs.

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
failure.

## 6. Look at the device

```sh
# screenshot (adb)
adb -s <serial> exec-out screencap -p > shot.png
# screenshot (app link): screencap -p /sdcard/shot.png, then chunk-pull it

# newest photos, newest first
content query --uri content://media/external/images/media \
  --projection _id:_data:date_added:_size --sort "date_added DESC"
# skip your own screenshots; the newest DCIM/Camera row is the real photo

# logs
logcat -d -t 5                                   # recent, system-wide
logcat -d --pid=$(pidof <pkg>) -t 5              # one app
logcat -d -b crash -t 50 | grep -E "FATAL|Process:"

# UI: read, then act
uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml
input tap 653 795
input swipe 950 1150 150 1150 120
input text 'hello'
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

`pending` = waiting for poll, `received` = fetched + acked. `received` without
progress on device means the app side swallowed it — say so and stop.

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
