# Twig

Snappy bullet lists for Android, organised like folders: a top-level bullet with bullets in it
is a list, and lists hold plain bullets (two levels). Kotlin + Jetpack Compose, raw SQLite, whole tree in memory.

- Versions: every change request is a new version. `versionCode` is the number, `versionName` the
  number spelled out (7 → "seven"), Hyperion Launcher style.
- Build: `./gradlew assembleRelease` (needs Android SDK 37.2; set `sdk.dir` in `local.properties`)
- Compose stays on 1.12 (BOM 2026.09.00): 1.13 hides and re-shows the keyboard every time the cursor
  moves to another bullet. Try a newer BOM only by typing on a phone: tap between bullets, Enter, Backspace.
- Tests: `./gradlew testDebugUnitTest` (Robolectric; screenshots land in `app/build/shots/`)
- Signing: `keystore.properties` + `twig-release.jks` in the project root.
  Updates must be signed with the same key, or Android makes you uninstall (and lose notes).
  Without them the release build falls back to the debug key.
- Import/export: Markdown bullets, one tab per level (`data/TextFormat.kt`). Bold is `**…**` in the text itself.
- Sync (`data/Gist.kt`): that same Markdown in a secret gist, `twig.md`, via a fine-grained token
  with Gists read and write (Settings). Runs on open/close, after edits and every 15 minutes in the
  background; when both sides changed, this phone wins and Undo takes the gist's copy.
- Site-specific link looks (GitHub repos, YouTube videos/channels/playlists, osu! beatmaps): add a rule in `share/Sites.kt`.
- Settings (swipes, text size, font, share target, read-only, sync token) live in the `meta` table.

Layout: `data/` (Node, Store, Db, TextFormat, Markup, Gist), `share/` (share sheet, link parsing, site rules),
`ui/` (main screen + keyboard bar + settings, page, rows + gestures, drag to reorder, search, picker).
