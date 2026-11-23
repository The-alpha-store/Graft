package dev.graft.app.data.github

/**
 * A ready-made GitHub Actions workflow that can be dropped into a repo's `.github/workflows/`.
 * [fileName] is the repo-relative path it installs to; [category] groups templates in the picker.
 */
data class WorkflowTemplate(
  val id: String,
  val name: String,
  val fileName: String,
  val description: String,
  val category: String, // "Android Build", "CI/CD", "Release", "Lint & Test"
  val yamlContent: String
)

object WorkflowTemplateCatalog {
  val templates = listOf(
    WorkflowTemplate(
      id = "graft_remote_run",
      name = "Graft remote runner",
      fileName = ".github/workflows/graft-run.yml",
      description = "Lets the Graft console run any command - cargo, npm, gradle, python... - on a GitHub runner and bring the results back. Install it once, then just type commands.",
      category = "Utilities",
      yamlContent = """name: Graft remote run

# Runs ONE command on a clean GitHub-hosted Ubuntu runner, on request from the Graft console.
# ubuntu-latest already has Rust (cargo, rustfmt, clippy), Node, Python, Go, Java and Gradle.
# If "commit" is true, any files the command changed are committed and pushed back to the same branch.
# The command travels through an environment variable, never pasted into the script, so it can't
# break out of its quotes.
on:
  workflow_dispatch:
    inputs:
      command:
        description: "Command to run in the repository root"
        required: true
        type: string
      commit:
        description: "Commit and push any files the command changed"
        required: false
        default: "true"
        type: string

permissions:
  contents: write

jobs:
  run:
    name: run
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Run command
        env:
          GRAFT_COMMAND: ${'$'}{{ inputs.command }}
        run: bash -c "${'$'}GRAFT_COMMAND"

      - name: Commit changes
        if: ${'$'}{{ inputs.commit == 'true' }}
        env:
          GRAFT_COMMAND: ${'$'}{{ inputs.command }}
        run: |
          if [ -n "${'$'}(git status --porcelain)" ]; then
            git config user.name "graft[bot]"
            git config user.email "graft-bot@users.noreply.github.com"
            git add -A
            git commit -m "chore: run '${'$'}GRAFT_COMMAND' (from Graft)"
            git push
          else
            echo "No files were changed."
          fi
"""
    ),
    WorkflowTemplate(
      id = "rust_ci",
      name = "Rust CI (fmt, clippy, test)",
      fileName = ".github/workflows/rust-ci.yml",
      description = "Checks formatting, lints with clippy (warnings are errors) and runs the tests on every push and pull request.",
      category = "Lint & Test",
      yamlContent = """name: Rust CI

on:
  push:
    branches: [ "main", "master" ]
  pull_request:

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: Swatinem/rust-cache@v2
      - name: Format check
        run: cargo fmt --all -- --check
      - name: Clippy
        run: cargo clippy --all-targets -- -D warnings
      - name: Tests
        run: cargo test --all
"""
    ),
    WorkflowTemplate(
      id = "android_ci_build",
      name = "Android CI (Build & Unit Tests)",
      fileName = ".github/workflows/android-ci.yml",
      description = "Builds debug APK, runs unit tests, and uploads test reports & build artifacts on push and PR.",
      category = "Android Build",
      yamlContent = """name: Android CI

on:
  push:
    branches: [ "main", "master", "develop" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

concurrency:
  group: ${'$'}{{ github.workflow }}-${'$'}{{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build & Test
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Grant execute permission for gradlew
        run: |
          if [ -f "./gradlew" ]; then
            chmod +x gradlew
          else
            gradle wrapper --gradle-version 9.3.1
            chmod +x gradlew
          fi

      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest --continue

      - name: Build Debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Upload Debug APK Artifact
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-results
          path: app/build/reports/tests/testDebugUnitTest/
          if-no-files-found: ignore
"""
    ),
    WorkflowTemplate(
      id = "android_release_bundle",
      name = "Android Release AAB & APK Build",
      fileName = ".github/workflows/android-release.yml",
      description = "Builds production signed or unsigned App Bundle (AAB) & APK, generates release checksums.",
      category = "Release",
      yamlContent = """name: Android Release Build

on:
  push:
    tags:
      - 'v*.*.*'
  workflow_dispatch:
    inputs:
      build_type:
        description: 'Build Type (bundle / assemble)'
        required: true
        default: 'bundleRelease'
        type: choice
        options:
          - bundleRelease
          - assembleRelease

jobs:
  release:
    name: Build Production Release
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Setup Gradle
        run: |
          if [ ! -f "./gradlew" ]; then
            gradle wrapper
          fi
          chmod +x gradlew

      - name: Build Release Artifact
        run: ./gradlew ${'$'}{{ inputs.build_type || 'bundleRelease assembleRelease' }}

      - name: Upload Release Bundle (AAB)
        uses: actions/upload-artifact@v4
        with:
          name: app-release-bundle
          path: app/build/outputs/bundle/release/*.aab
          if-no-files-found: ignore

      - name: Upload Release APK
        uses: actions/upload-artifact@v4
        with:
          name: app-release-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: ignore
"""
    ),
    WorkflowTemplate(
      id = "lint_and_static_analysis",
      name = "Android Lint & Code Quality",
      fileName = ".github/workflows/lint-check.yml",
      description = "Runs Android Lint checks and static code verification, outputting HTML reports as artifacts.",
      category = "Lint & Test",
      yamlContent = """name: Android Code Quality

on:
  push:
    branches: [ "main", "develop" ]
  pull_request:

jobs:
  lint:
    name: Android Lint Check
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Make Gradlew Executable
        run: |
          if [ -f "./gradlew" ]; then chmod +x gradlew; fi

      - name: Run Android Lint
        run: ./gradlew lintDebug

      - name: Upload Lint Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-report
          path: app/build/reports/lint-results-debug.html
          if-no-files-found: ignore
"""
    ),
    WorkflowTemplate(
      id = "pr_verify_and_comment",
      name = "PR Verification & Automated Checks",
      fileName = ".github/workflows/pr-verify.yml",
      description = "Fast verification workflow for PRs checking compile safety and running tests.",
      category = "CI/CD",
      yamlContent = """name: PR Verification

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  verify:
    name: Verify PR Compile & Tests
    runs-on: ubuntu-latest

    steps:
      - name: Checkout PR Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Compile & Check
        run: |
          chmod +x ./gradlew || true
          ./gradlew check --no-daemon
"""
    )
  )

  fun byFileName(fileName: String): WorkflowTemplate? = templates.firstOrNull { it.fileName == fileName }
}
