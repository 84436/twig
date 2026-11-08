# Twig

Snappy outliner for Android. Kotlin + Jetpack Compose, raw SQLite, whole tree in memory.

- Build: `./gradlew assembleRelease` (needs Android SDK 36; set `sdk.dir` in `local.properties`)
- Tests: `./gradlew testDebugUnitTest` (Robolectric; screenshots land in `app/build/shots/`)
- Signing: `keystore.properties` + `twig-release.jks` in the project root (not in git, back them up yourself).
  Updates must be signed with the same key, or Android makes you uninstall (and lose notes).
  Without them the release build falls back to the debug key.

Layout: `data/` (Node, Store, Db, Backup, Attachments), `share/` (share sheet, link parsing),
`ui/` (outline, rows + gestures, drag & drop, search, picker).
