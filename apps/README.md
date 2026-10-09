# apps/

The Kotlin side of the collection: `:core` and `:core-net` hold the logic
and the HTTP client, `:webApp` is the website and `:androidApp` is the
phone. `KMP.md` has the history of how it got this shape.

The phone app receives shared files natively. The installed web app could
not: Chrome matched every share, launched the app, and then posted a body
of exactly 75 bytes — the multipart closing delimiter and nothing else —
because it could not read another app's `content://` file handle on the web
app's behalf. A native activity holds the read grant that rides on the
intent, so it simply reads the file.

## The ManaBox stand-in

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
