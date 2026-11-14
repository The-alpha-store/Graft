# Merge notes

`repo-master` (the AI Studio app) was upgraded using `RepoMaster-main` as the reference, and the
result was renamed **Graft** (package `dev.graft.app`). The
reference is a near-superset in features and data layer, so it supplied the engine; the base supplied
the project shell, a few unique features, and the visual identity of the launcher icon. The design
system, navigation and several screens were rebuilt rather than copied.

## Where things came from

| Area | Source | Notes |
|---|---|---|
| Build shell and signing configs | **base** | AI Studio signing blocks untouched; package and applicationId changed to `dev.graft.app`. Firebase / Retrofit / Moshi / OkHttp / google-services / secrets plugins removed (unused). |
| Git engine, console, stash/tags/remote/conflicts/blame/search, APK explorer, Actions runs, Discover, credentials (encrypted), markdown preview, undo/redo editor, sync worker | **reference** | Package-renamed. |
| Local repo creation (`git init`), clone progress | **base** → ported onto reference engine | New "Add repository" sheet (Home → + → Clone or create repo): Clone / Create new. |
| Workflow template catalog | **base** | Now a *Templates* tab inside Actions, next to the reference's *Runs* tab. Pseudo-`gradlew` generators dropped (can't work without the wrapper jar). |
| Repo dashboard | **base** idea, rebuilt | Now the repo's landing tab (Overview): status, ahead/behind, recent commits, sync activity. |
| Sync history | **base** | `sync_logs` table (Room v2 + real migration), written by the sync worker, shown on Overview and in Settings. |
| Side-by-side diff | **base** idea, rewritten | Block-based alignment (see "Bugs fixed"). Defaults on only for wide screens. |
| Storage modes (app vs public) | **base** + reference | Reference required All-files-access for *any* clone; now opt-in. |
| Launcher icon | **base** art, rebuilt as vector | Adds a monochrome layer for themed icons. |

## Design decisions (and the defects they fix)

- **Colors are solved, not listed.** Palette seeds → full Material 3 scheme with WCAG-AA contrast per
  theme. The reference hard-coded `onPrimary` for one palette and read colors from global
  `mutableStateOf` vars with misleading names (`Amber` was coral, `Ink`, `PlumDeep`…).
- **Git status colors are semantic** and independent of the accent (`MaterialTheme.gitColors`).
- **Dynamic color vs accent is explicit.** The reference defaulted to dynamic color, which silently
  disabled the accent picker. Now off by default; when on, the picker is visibly disabled.
- **Typography:** `bodySmall` is proportional again (the reference forced monospace on all of it,
  including helper prose). Data uses `MaterialTheme.code`.
- **Syntax colors follow the app theme**, not the system's (forcing Dark on a Light system used to
  render dark-on-dark code).
- **Full light scheme.** The reference's light theme left most roles at Material's baseline purple.
- **One card, one top bar, one set of state views** (`AppCard`, `ScreenTopBar`, `EmptyState` …)
  instead of per-screen variants; `GlassCard` (which wasn't glass) is gone. The leading accent bar is
  now measured with an intrinsic height - inside a lazy list `fillMaxHeight()` has no bounded height
  to fill and can collapse the bar to nothing.
- **Insets:** nested Scaffolds double-applied the bottom inset above the tab bar; the shell now
  consumes what it pads.
- **Cold-start flash:** light-only window theme made dark-mode launches flash white; now night-aware.
- **Pull-to-refresh** uses Material 3's `PullToRefreshBox`; the legacy Material 2 artifact is gone.
- **Console / blame / markdown** no longer hard-code dark colors, so they work in light theme.

## Architecture changes

- Repo id is **in the route**; each repo is a nested graph; tab ViewModels are scoped to it. The
  reference kept the id in a `rememberSaveable`, and its `saveState/restoreState` tab switching would
  restore repo A's tabs (with A's arguments) inside repo B.
- Deep links `graft://repo/{id}` now open the repo Overview (reference landed in a bare Explorer
  with nothing beneath it); leaving falls back to Home.
- Appearance prefs consolidated into one observable store; global color mutation removed.
- Shared dialogs moved out of `ChangesScreen` (other screens imported them from there).
- `Changes` tab badge is live via `RepoStatusStore`; Home counts no longer go stale after a commit.

## Bugs fixed on the way

- Side-by-side pairing only matched *adjacent* −/+ lines, so `-A -B +C +D` paired B with C. Covered
  by `SideBySideDiffTest`.
- Missing `ProGuard` keep paths (reference's pointed at nonexistent packages).
- JGit uses `java.time`; core-library desugaring is enabled so API 24–25 devices don't crash.
- CI passed `-PGRAFT_RELEASE_*` properties the signing config never read, and pinned Gradle 8.9
  where AGP 9 needs 9.x. Replaced by one workflow that matches this build.

## Verification - read this

This environment has no Android SDK, Gradle or network, so **the project has not been compiled or
run**. What was done instead:

- A static verifier (bracket balance with strings/comments stripped; every internal import resolves
  to a real declaration; no references to removed APIs; every common Compose symbol is imported) - 92
  files pass, and it was confirmed to catch deliberately planted errors.
- A call-site check of every named argument and callback type in the nav graph against the declared
  screen signatures.
- Every icon and non-trivial library import was cross-checked against the two original codebases
  (which did compile); the few new ones were replaced with proven equivalents or are stable APIs.
- The palette contrast math was ported to Python and checked for all presets in both themes.

**First thing to do:** sync and build. Any residual error is most likely a small import/signature
slip in the files rewritten for this merge (`ui/theme`, `ui/components`, `ui/navigation`,
`screens/overview`, `screens/actions/WorkflowTemplatesTab.kt`, `screens/diff/SideBySideDiff.kt`,
edits to `RepoListScreen`, `SettingsScreen`, `CloneScreen`/`CloneViewModel`).

## Round 2: transfers, speed, Files tab

- **New `transfer/` package**: `TransferManager` (app-scoped, 2-at-a-time, per-repo lock),
  `TransferService` (foreground, `dataSync`), `TransferNotifier`, pure `PhaseProgress`/`RateTracker`.
  Callers rerouted: Clone sheet, Discover, Home cards, Changes (fetch/pull/push/sync and the
  commit-and-push shortcut), Files-tab push, editor "save, commit & push". Console, tag/branch push
  and the sync worker keep their direct calls on purpose.
- **Engine**: progress monitor + 45 s timeout on every remote call; single-pass `stageAll`; batched
  `unstageFiles`; `TagOpt.NO_TAGS` for background fetch (a new tag is not "new commits").
- **Self-contained handles**: each transfer opens and closes its own `Git`, so it can't depend on a
  ViewModel's handle surviving (a push used to be silently abandoned if you left the screen).
- **Files tab**: filename search, properties, copy path, find & replace. The base's tree is *not*
  ported - the reference explorer already had a superset (multi-select, import, compact folders, sort).

### Round 3: errors, editor, console, actions, Files design

- **Sticky errors fixed** (see screenshot: every repo red with `cannot open git-upload-pack` / `connection
  abort`): `GitErrors` classifies + humanises; `RepoRepository.markError` refuses transient failures;
  `clearTransientErrors()` runs at startup; `TransferManager` fails fast when offline.
- **Files tab restyled** to the original: inset card rows, extension badges, action header.
- **Editor**: see README. New pure `EditorText.kt` (unit-tested) + responsive `FileEditorScreen`.
- **Console**: shared top bar, one-tap quick commands (read-only ones run, mutating ones only fill the
  input), selectable output, copy transcript, proper empty state.
- **Actions**: filter chips with counts, status pills, monospace branch/SHA, real empty states.
- **Bug found by CI, fixed**: a constructor-argument `runCatching {}` resolving to `T.runCatching` on an
  uninitialised `this`; `conflate()` on a `StateFlow`; a duplicate `onCleared`; a top-level `val` reading
  `MaterialTheme`. The verifier now also checks icon imports.

### Known limits

- Nothing here has been run or benchmarked; speed claims are from removing redundant work, not stopwatch
  numbers. Measure a clone/commit before and after if it matters to you.
- Cancel is cooperative: JGit checks between objects, so there can be a moment before it stops, and
  during the very first server negotiation it may only stop at the 45 s inactivity timeout.
- Bytes/speed are inferred from pack-file growth, so they're approximate; a push has no byte figure.
- Android 15 limits `dataSync` foreground services to ~6 h per day - irrelevant for git, noted anyway.

## Behavior changes to know about

- The Room database file is `graft.db` with a new `sync_logs` table (v2). Repos you added in
  the old base build live in a different DB; use **Home → + → Scan for local repos** if they're under
  this app's storage folder.
- Default storage is app storage; the public-folder mode is now opt-in.
- Default theme follows the system (the base was dark-only); dynamic color is off.
- Release builds still have R8 off (as in the AI Studio template). `proguard-rules.pro` is ready if
  you flip `isMinifyEnabled` on.
