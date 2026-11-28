# AGENTS.md — TV Docs (Android TV text editor)

Instructions for any AI coding agent (Claude, Antigravity, Codex, Copilot…) working in this repo.

## What this project is
A simple text editor for Android TV, made to be used with the remote: write, copy/cut/paste, select with the remote, save documents in the app, import/export `.txt`, undo, text size, autosave, English and French interface.

## Setup
- Android app in Kotlin, Gradle wrapper included (`./gradlew assembleDebug`). Needs JDK 17 + Android SDK.
- **Every push to `main` builds an APK** (`.github/workflows/build.yml`) and publishes it on the latest release as `TvDocs.apk`.
- Install methods (Downloader app, adb, USB) are in `README.md`.

## Project rules
- **Remote first:** everything must work with the TV remote alone (OK, Back, Menu, arrows). Keep the remote tips table in `README.md` up to date.
- Keep both the English and French texts when you add a string.
- Never lose the user's text: keep autosave of the draft working.

## Rules for all of Daniel's projects (the same in every repo)
- **Free only.** Use free plans and free services. Ask the owner before adding anything paid.
- **No secrets in the repo.** API keys, passwords and tokens go in Cloudflare or GitHub secrets.
- **Keep the owner's private email address out** of the code and the website.
- **Don't break links.** Keep existing URLs, page titles and sitemaps working (Google ranking).
- **`main` is live.** In most repos a push to `main` publishes the site or builds a new app release. Only push work that builds and has been checked.
- **Explain changes simply.** Give a short, plain-language summary of what changed and how to check it.
- **Keep this file current** when the setup changes. Claude reads it through `CLAUDE.md`. Antigravity, Codex and Copilot read `AGENTS.md` directly.
