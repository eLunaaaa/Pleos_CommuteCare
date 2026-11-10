#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ -x "$PROJECT_ROOT/.tools/jdk17/Contents/Home/bin/java" ]; then
    JAVA_HOME="$PROJECT_ROOT/.tools/jdk17/Contents/Home"
    export JAVA_HOME
fi
cd "$PROJECT_ROOT/android"
exec ./gradlew --no-daemon "$@"
