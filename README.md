# Twig

Snappy bullet lists for Android, organised like folders: a bullet with bullets in it is a list,
three levels deep at most. Kotlin + Jetpack Compose, raw SQLite, whole tree in memory.

- Build: `./gradlew assembleRelease` (needs Android SDK 36; set `sdk.dir` in `local.properties`)
- Tests: `./gradlew testDebugUnitTest` (Robolectric; screenshots land in `app/build/shots/`)
- Signing: `keystore.properties` + `twig-release.jks` in the project root.
  Updates must be signed with the same key, or Android makes you uninstall (and lose notes).
  Without them the release build falls back to the debug key.
- Import/export: Markdown bullets, one tab per level (`data/TextFormat.kt`).
- Site-specific link looks (YouTube, GitHub repos): add a rule in `share/Sites.kt`.

Layout: `data/` (Node, Store, Db, TextFormat), `share/` (share sheet, link parsing, site rules),
`ui/` (page, rows + gestures, drag to reorder, search, picker).
