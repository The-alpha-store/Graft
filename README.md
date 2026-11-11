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
├── git/                             GitEngine (JGit), JGitTuning, console interpreter, models
├── transfer/                        TransferManager, foreground service, notifications, progress
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

## Transfers (background clone / pull / push)

Every clone, pull, fetch and push runs as a *transfer* in `TransferManager`, independent of the screen
that started it. That gives you:

- **Chrome-style progress**: a banner above the bottom bar, a Transfers list (route `transfers`,
  reachable from the banner, Home's toolbar, or the notification), and progress strips right on the
  Home repo cards. Each shows the phase, bytes downloaded, smoothed speed and time left; failed ones
  have *Try again*, running ones *Cancel*.
- **Background execution**: a foreground service (`dataSync`) keeps the process alive and shows an
  ongoing progress notification with *Cancel*; a normal notification reports the result if the app
  isn't on screen. Starting a clone closes the sheet immediately - the repo appears when it's done.
- **Safe scheduling**: at most 2 transfers at once, and never two on the same repo.

Progress is one monotonic bar built from JGit's separate phases (`PhaseProgress`). Bytes/speed come
from sampling growth of `.git/objects/pack` (JGit doesn't expose byte counts); pushes show object
counts instead.

## Speed

What changed, and why it should help (structural reasoning - not benchmarked on a device yet):

- **JGit window cache** raised from desktop-era defaults (8 KiB windows / 10 MiB) to 128 KiB / 48 MiB,
  and a **background warm-up** at launch moves JGit's one-time initialisation off the first
  "create repo" / "commit".
- **`stageAll`** did two full working-tree walks; it now does one, finding deletions by stat-ing the
  index entries. **Unstage-all** is one reset instead of one per file.
- **Changes screen** gathers branch / ahead-behind / upstream / last commit / status **in parallel**,
  reuses its repo handle (it used to re-open and leak one per refresh), and uses a status-only refresh
  after stage/unstage.
- **Home** scans at most 3 repos at a time (it used to start every scan at once) and no longer shows
  a refresh spinner that ends before the work does.
- **Clone** can fetch only the requested branch, and has a *Latest commit only* switch (shallow);
  **background sync** skips tags. Network calls have a 45 s *inactivity* timeout so a stalled
  connection fails instead of hanging.

## Files tab

Everything from the reference's explorer/editor, plus: **Find file by name** (whole repo), **Properties**
(size - recursive for folders - modified time, location, git status), **Copy path**, and **Find &
Replace** with match-case in the editor (one undo step for "Replace all").

## Storage

Repos live in app storage by default - no permission needed. Settings → Storage location can switch
new clones to a public folder (`/storage/emulated/0/.Graft`), which needs Android's "All files
access". Existing repos keep working across a switch.

## Tests & CI

`./gradlew testDebugUnitTest` covers diff alignment, palette contrast, time formatting, the workflow
catalog, transfer progress/format logic, and the transfer manager's scheduling rules (concurrency cap,
per-repo ordering, cancel, retry). `.github/workflows/build.yml` runs tests + a debug build on every push, and builds
a signed release (secrets: `KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_PASSWORD`) on `v*` tags.
