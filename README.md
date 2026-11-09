# Graft

Graft is a full Git client for Android, built with Jetpack Compose and JGit. Clone or create repositories,
stage and commit, branch, tag, stash, resolve conflicts, review diffs side-by-side, edit files with
syntax highlighting, watch GitHub Actions runs (and install the APKs they build), and keep repos in
sync in the background.

This project is the merge of two earlier apps: the original AI Studio *repo-master* and the more
architecturally mature *RepoMaster-main*, with the design system and navigation rebuilt on top.
See [MERGE_NOTES.md](MERGE_NOTES.md) for exactly what came from where and why.

## Run it

1. Open the folder in a recent Android Studio (one that supports AGP 9.1) and let it sync - it regenerates the Gradle
   wrapper (9.3.1) from `gradle/wrapper/gradle-wrapper.properties`. JDK 17+.
2. Run the `app` configuration. `minSdk 24`, `targetSdk 36`.

AI Studio's signing configs in `app/build.gradle.kts` are intentionally untouched. The debug config
expects `debug.keystore` in the project root (git-ignored); AI Studio supplies it, and the CI workflow
generates one. For a local build without it, create one with:

```
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
```

## Architecture

```
dev.graft.app
├── App.kt, MainActivity.kt          application + service locator; edge-to-edge host
├── data/
│   ├── db/                          Room (repos, credentials, sync log) + v1→v2 migration
│   ├── repository/                  Repo / Credential / SyncLog repositories, TokenCrypto
│   ├── github/                      dependency-free GitHub REST client, workflow templates
│   ├── AppearancePrefs, RepoStorage, RepoStatusStore, …
├── git/                             GitEngine (JGit), console interpreter, models
├── sync/                            WorkManager sync worker, scheduler, notifier
└── ui/
    ├── theme/                       palette → ColorScheme solver, GitColors, type, shapes
    ├── components/                  AppCard, state views, chips, top bar, dialogs, refresh box
    ├── navigation/                  Routes, shell with two bottom bars, nested repo graph
    └── screens/<feature>/           one package per feature: Screen + ViewModel
```

**Navigation has two levels.** Home scope (Repos · Discover · Settings) and Repo scope (Overview ·
Changes · History · Files · Tools). The repo id lives in the route, each repo is a nested graph, and
the five tab ViewModels are scoped to that graph so a half-typed commit message survives tab
switches but never leaks into another repo.

**Colors** are solved, not hand-picked. Each accent palette is three seed colors; the theme derives
per-theme lightness and on-colors to clear WCAG AA (verified by `ThemeColorsTest` for every preset in
both themes). Git status colors (added / modified / deleted) are a separate semantic layer that does
not change with the accent.

## Storage

Repos live in app storage by default - no permission needed. Settings → Storage location can switch
new clones to a public folder (`/storage/emulated/0/.Graft`), which needs Android's "All files
access". Existing repos keep working across a switch.

## Tests & CI

`./gradlew testDebugUnitTest` covers diff alignment, palette contrast, time formatting and the
workflow catalog. `.github/workflows/build.yml` runs tests + a debug build on every push, and builds
a signed release (secrets: `KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_PASSWORD`) on `v*` tags.
