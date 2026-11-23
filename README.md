# TV Docs

A simple text editor for Android TV, made to be used with the remote.

- **Write** text with the TV's on-screen keyboard (or voice typing, or a Bluetooth keyboard).
- **Copy, cut, paste** using the system clipboard. "Copy" with nothing selected copies the whole document.
- **Select** part of the text with the remote: press *Select*, move with the arrows, press OK.
- **Save** documents inside the app, then reopen, rename or delete them from *Documents*.
- **Import / export** `.txt` files with the system file picker, when the TV has one.
- Undo, text size A− / A+, autosave of your draft, English and French interface.

## Install on the TV

Each push to `main` builds an APK and publishes it on the **latest** release:
`https://github.com/<owner>/<repo>/releases/latest/download/TvDocs.apk`

Easiest ways to install it:

1. **Downloader app** (by AFTVnews, free on the Play Store / Amazon store): open it on the TV,
   type the link above, then allow "Install unknown apps" for Downloader when asked.
2. **adb** from a computer on the same network (enable *Developer options* → *USB/Network debugging* on the TV):
   ```
   adb connect <tv-ip-address>
   adb install -r TvDocs.apk
   ```
3. **Send Files to TV** or a USB stick, then open the APK with a file manager on the TV.

The app appears in the TV's app row as **TV Docs**.

## Remote control tips

| Key | Action |
| --- | --- |
| OK on the text area | open the keyboard and type |
| Back while typing | leave the text area and go back to the buttons |
| Menu | jump to the buttons |
| Select → arrows → OK | select part of the text |

## Build locally

Requires JDK 17 and the Android SDK:

```
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.
