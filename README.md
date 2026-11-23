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

## Files tab & editor

The Files tab follows the original app's look: each file is an **inset rounded card** (indented by depth)
with a coloured **extension badge** (`KT`, `YML`, `MD`...), amber folders, size under the name, a quick-edit
button and a ⋮ menu, under a pinned header with **.gitignore**, **CI Actions** and **Find file**. Also:
whole-repo filename search, Properties, Copy path.

The editor (responsive: phone / large phone / tablet layouts so the filename always fits):
- **Find & replace** (match case, one undo step for *Replace all*), **go to line**, select all, copy all
- **Line tools**: duplicate, delete, indent, outdent, **toggle comment** (`//` or `#` by language)
- **Auto-indent** on Enter (extra level after `{ ( [ :`, and `{|}` opens a block)
- Word wrap, line-number toggle, text size, a status bar (Ln/Col, selection, size, language)
- Quick-symbol bar with **◀ ▶ cursor keys**; **side-by-side Markdown** preview on wide screens
- **Unsaved-changes guard** (Save / Discard / Keep editing), robust save, hardware shortcuts
  (Ctrl+S, F, G, D, Z/Y, /)

## Console: one prompt, any command

You never pick a mode. Whatever you type is routed automatically (`ConsoleRouter`):

1. **Git** (with or without `git`): `status diff log show blame branch add restore rm mv commit push pull fetch
   switch checkout merge rebase cherry-pick stash reset tag remote clean reflog ls-files config describe
   rev-parse shortlog gc`, plus aliases `st co br ci lg last unstage`. Runs on the in-app JGit engine.
2. **Anything else** is tried on the device's own shell (`sh -c`) in the repo folder: `ls cat grep find sed head
   tail wc sort cp mv mkdir touch diff...` - whatever Android ships. `!cmd` forces the shell where a git command
   shares the name (`rm`, `mv`). Stop (✕) kills it.
3. **If the device doesn't have the program** (`cargo`, `npm`, `gradle`, `python`... - a stock phone has no
   toolchains, and an app can't borrow Termux's or execute downloaded binaries), the console offers - once - to
   run it on a **GitHub Actions runner** and brings back the output. With *Always*, it stops asking.

Cloud details: install the runner once with `cloud setup` (writes `.github/workflows/graft-run.yml`; add, commit,
push it). Then e.g. `cargo fmt --all` runs on a clean Ubuntu runner (Rust, Node, Python, Go, Java, Gradle are
preinstalled), the log is streamed back with the CI noise stripped, files the command changed are committed and
pushed, and the console pulls them (if your tree is clean). `cloud --no-commit <cmd>` is check-only. Caveats shown
before every run: the runner sees what's **pushed** (not unpushed commits or uncommitted edits), it uses your
Actions minutes, and your token needs the `workflow` scope. Stop also cancels the run on GitHub. The command
reaches the runner through an environment variable, never pasted into a script, so quotes can't break out.

## More features

- **Pin to top** (card menu): pinned repos sort first in every sort mode; stored in prefs, no DB change.
- **Fetch all / Pull all** (Home toolbar): parallel background transfers with one summary at the end.
  *Pull all* skips repos with uncommitted changes and says how many it skipped.
- **Open in browser / Copy clone URL** (card menu): tokens embedded in a URL are stripped, never copied.
- **Pull requests tab** in Actions: open / merged / closed / all with counts; tap to open on GitHub.
- **Overview sync card**: Fetch / Pull / Push right where the ahead/behind numbers are - Pull is
  emphasised when you're behind, Push when you're ahead - with live progress while running.

## Errors

Raw JGit exceptions are translated into plain language (`GitErrors`), classifying by the whole cause chain
(so `cannot open git-upload-pack` caused by an `UnknownHostException` reads as "you're offline").
Transient failures - no connection, dropped connection, timeout, server hiccup - are **never stored** as a
repo's error; they show once, as a message and a retryable row in Transfers. Only actionable failures (bad
credentials, rejected push) stay on a card, with **Dismiss**. Offline is detected up front so a transfer fails
instantly instead of hanging. Stale network errors saved by older builds are cleared at launch.

## Storage

Repos live in app storage by default - no permission needed. Settings → Storage location can switch
new clones to a public folder (`/storage/emulated/0/.Graft`), which needs Android's "All files
access". Existing repos keep working across a switch.

## Tests & CI

`./gradlew testDebugUnitTest` covers diff alignment, palette contrast, time formatting, the workflow
catalog, transfer progress/format logic, and the transfer manager's scheduling rules (concurrency cap,
per-repo ordering, cancel, retry). `.github/workflows/build.yml` runs tests + a debug build on every push, and builds
a signed release (secrets: `KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_PASSWORD`) on `v*` tags.
