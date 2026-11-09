# ------------------------------------------------------------------
# Graft ProGuard / R8 rules
# ------------------------------------------------------------------
# These only take effect on release builds (isMinifyEnabled = true).
# Debug builds are never shrunk, so day-to-day development is unaffected.

# --- JGit ---
# JGit does a fair amount of reflection (transport plugin discovery,
# service loading) — keep it whole rather than risk stripping something
# it looks up dynamically at runtime.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn com.jcraft.jsch.**
-dontwarn com.googlecode.javaewah.**

# --- Room ---
# Room's generated code (schema classes, DAOs) is created at compile time
# and referenced via generated implementations; keep entities and DAOs
# intact so those generated classes still line up.
#
-keep class dev.graft.app.data.db.entity.** { *; }
-keep interface dev.graft.app.data.db.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Kotlin coroutines ---
# Coroutines use some reflection-adjacent internals (debug probes, service
# loading for the Main dispatcher) that shrinking can otherwise break.
-dontwarn kotlinx.coroutines.debug.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# --- Kotlin metadata / reflection ---
# Keeps enough metadata around for kotlin-reflect-adjacent library code
# (Room, etc.) that inspects Kotlin classes at runtime.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep class kotlin.Metadata { *; }

# --- Jetpack Compose ---
# Compose Compiler-generated code is already shrink-safe by default via
# the AGP-provided consumer rules; nothing extra needed here, but keeping
# this section as a marker in case a future Compose library needs a rule.

# --- Android Keystore / crypto ---
# TokenCrypto uses javax.crypto + Android Keystore reflectively in some
# OEM implementations.
-dontwarn javax.crypto.**
-keep class dev.graft.app.data.repository.TokenCrypto { *; }

# --- Core library desugaring (java.time, ConcurrentHashMap, etc. on older API levels) ---
# The desugar_jdk_libs artifact ships its own bundled keep rules for its internal j$.util.*
# classes; on the version AGP resolves here, a few of those rules reference fields
# (lockState, sizeCtl, cellsBusy, etc.) that don't exist in the actual compiled classes,
# so R8 logs "Proguard configuration rule does not match anything" for each one. It's
# purely informational — those aren't our rules, nothing is actually being kept
# incorrectly, and it doesn't affect the build (this note appears only during the
# already-successful release/lint pass). Scoped -dontnote to just this package so it's
# quiet without hiding notes anywhere else.
-dontnote j$.util.**

# --- General ---
# Keep line numbers for readable stack traces from crash reports, but
# rename the source file attribute so it doesn't leak the original
# filename structure.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
