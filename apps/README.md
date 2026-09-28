# MTG Collection — Android share target

Receives a decklist or a collection export from another Android app's share
sheet and puts it into the collection behind
`https://mtg-api.mattshoe81.workers.dev`.

It exists because the installed web app could not do this. Chrome matched
every share, launched the app, and then posted a body of exactly 75 bytes —
the multipart closing delimiter and nothing else — because it could not read
another app's `content://` file handle on the web app's behalf. That is a
boundary no web page can reach across. A native activity holds the read
grant that rides on the intent, so it simply reads the file.

## What it does

1. Another app shares a file. This one reads it, whatever the file is
   labelled — ManaBox calls its CSV export `application/octet-stream` some
   days and `text/csv` others, and guessing from the label is what threw
   away real decklists. Whether the bytes decode as text is the only test.
2. It shows what arrived: card count, filename, size, the first lines.
3. Password once, exchanged for a token that does not expire. The password
   is never stored.
4. Whose collection — never preselected.
5. A real dry run against the server, which resolves against Scryfall and
   reports every change it would make.
6. Only then, apply.

Steps 4 and 5 are not skippable. A list landing in the wrong collection, or
landing at all without having been looked at first, is the mistake this
shape exists to prevent.

## Building

No third-party dependencies at all — `HttpURLConnection` and `org.json` from
the framework, framework views, no AndroidX, no Compose. Nothing to resolve
and nothing to rot.

    ./gradlew :app:assembleRelease

Signing comes from `~/.mtg-android.env`, which is outside the repo and holds
the keystore path, its password and the key alias. Without that file the
release build is unsigned rather than broken. **Keep the keystore** —
`~/.mtg-android-release.keystore`. Android refuses to upgrade an installed
app signed with a different key, so losing it means uninstall-and-reinstall
for every future version.

## Installing

    adb install -r app/build/outputs/apk/release/app-release.apk

Or copy the APK to the phone and open it.

## Tests

Instrumented, on a real emulator, 28 of them:

    ./gradlew :app:connectedDebugAndroidTest

- `SharedListTest` — reading a real `content://` URI published to
  MediaStore: CSV, plain decklist, several files at once, Open-with, shared
  text, a mislabelled file, a binary file, an empty share, a 4,000 row
  export.
- `ShareActivityTest` — the manifest really claims a file share for every
  MIME type ManaBox might use; the list reaches the screen; neither owner is
  preselected; no write is reachable without a preview; a binary share says
  what was wrong instead of going quiet.
- `ApiTest` — the API client against a stub served from inside the test. No
  network, no credentials. Request shape, the bearer token, `dry_run`
  honesty, error passthrough, a 4,000 line list and a list full of commas
  and apostrophes surviving the JSON.

### Against the real API

Dry runs only, so they resolve against Scryfall and write nothing. The token
is passed in and never stored:

    ./gradlew :app:installDebug :app:installDebugAndroidTest
    adb shell am instrument -w \
      -e liveToken "$(curl -s -X POST $API/admin -H 'content-type: application/json' \
         -d "{\"password\":\"$MTG_ADMIN_PASSWORD\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')" \
      -e class org.mattshoe.mtg.share.LiveApiTest \
      org.mattshoe.mtg.share.test/org.mattshoe.mtg.share.ArgRunner

### The ManaBox stand-in

`:sender` is a test harness, never shipped. It owns a file in its own
storage and shares it with a genuine `FileProvider` grant, which is the one
thing `adb shell am start` cannot fake — the shell can only grant URIs it
owns, so a share fired straight from adb arrives unreadable and proves
nothing.

    ./gradlew :sender:assembleDebug
    adb install -r sender/build/outputs/apk/debug/sender-debug.apk
    adb shell am start -n org.mattshoe.mtg.sender/.SendActivity \
      --es name manabox.csv --es mime application/octet-stream \
      --es b64 "$(printf 'Name,Quantity\nSol Ring,1\n' | base64)" \
      --es target org.mattshoe.mtg.share

The body goes base64 because an `--es` argument is re-parsed by the device
shell, which eats spaces, newlines and pipes alike.
