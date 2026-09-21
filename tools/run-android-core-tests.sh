#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Compiles and runs the *core* source set of the Android app on the JVM.
#
# `android/app/src/core` is wired into the Gradle build as an extra sourceSet,
# so the classes exercised here are the exact classes the APK ships — this is
# not a parallel re-implementation. It needs no Android SDK, which is why it can
# run in CI (and in sandboxes where dl.google.com is unreachable).
#
# Toolchain resolution order:
#   1. $JAVA_HOME / java on PATH
#   2. $TOOLCHAIN_ROOT/jdk-*          (downloaded JDK)
#   3. $TOOLCHAIN_ROOT/kc/package     (kotlinc distribution from npm)
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$HOME/.toolchain}"
BUILD_DIR="${BUILD_DIR:-/tmp/arealite-core-build}"

CORE_SRC="$REPO_ROOT/android/app/src/core/kotlin"
TEST_SRC="$REPO_ROOT/android/app/src/test/kotlin"

# --- locate a JRE/JDK -------------------------------------------------------
JAVA_BIN=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  JDK_DIR="$(find "$TOOLCHAIN_ROOT" -maxdepth 1 -type d -name 'jdk*' 2>/dev/null | head -1 || true)"
  if [[ -n "$JDK_DIR" && -x "$JDK_DIR/bin/java" ]]; then JAVA_BIN="$JDK_DIR/bin/java"; fi
fi

# --- locate kotlinc ---------------------------------------------------------
KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
  KOTLINC="$(command -v kotlinc)"
elif [[ -x "$TOOLCHAIN_ROOT/kc/package/bin/kotlinc" ]]; then
  KOTLINC="$TOOLCHAIN_ROOT/kc/package/bin/kotlinc"
fi

if [[ -z "$JAVA_BIN" || -z "$KOTLINC" ]]; then
  cat >&2 <<MSG
Butuh JVM dan kotlinc untuk menjalankan core test.

  JDK     : https://adoptium.net/temurin/releases/?version=17
  kotlinc : npm pack kotlin-compiler  (lalu tar xzf ke \$TOOLCHAIN_ROOT/kc)

Lalu jalankan ulang:  TOOLCHAIN_ROOT=... $0
MSG
  exit 2
fi

KOTLIN_LIB="$(dirname "$(dirname "$KOTLINC")")/lib"
COROUTINES_JAR="$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar"
STDLIB_JAR="$KOTLIN_LIB/kotlin-stdlib.jar"

echo "java     : $JAVA_BIN"
echo "kotlinc  : $KOTLINC"
echo "coroutines: $([[ -f $COROUTINES_JAR ]] && echo ada || echo TIDAK-ADA)"

# JAVA_HOME is required by the kotlinc launcher script
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$JAVA_BIN")")}"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/out"

SOURCES=$(find "$CORE_SRC" "$TEST_SRC" -name '*.kt' | sort)
COUNT=$(echo "$SOURCES" | wc -l | tr -d ' ')
echo "kompilasi $COUNT file Kotlin…"

CP="$STDLIB_JAR"
[[ -f "$COROUTINES_JAR" ]] && CP="$CP:$COROUTINES_JAR"

"$KOTLINC" -nowarn -jvm-target 17 -classpath "$CP" -d "$BUILD_DIR/out" $SOURCES

echo "menjalankan dev.arenalite.core.CoreTests…"
echo "---------------------------------------------------------------"
"$JAVA_BIN" -cp "$BUILD_DIR/out:$CP" dev.arenalite.core.CoreTests
