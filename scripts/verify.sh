#!/usr/bin/env bash
set -euo pipefail

include_windows=0
include_android=0
skip_install=0

for arg in "$@"; do
  case "$arg" in
    --include-windows) include_windows=1 ;;
    --include-android) include_android=1 ;;
    --skip-install) skip_install=1 ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_step() {
  local name="$1"
  shift
  printf '\n==> %s\n' "$name"
  "$@"
}

run_step "Server tests" bash -lc "cd '$root/server' && python -m pytest"

if [[ "$include_windows" -eq 1 ]]; then
  if [[ "$skip_install" -eq 1 ]]; then
    run_step "Windows build" bash -lc "cd '$root/windows' && npm run build"
  else
    run_step "Windows build" bash -lc "cd '$root/windows' && npm ci && npm run build"
  fi
fi

if [[ "$include_android" -eq 1 ]]; then
  run_step "Android unit test and debug APK" bash -lc "cd '$root/android' && ./gradlew :app:testDebugUnitTest :app:assembleDebug"
fi
