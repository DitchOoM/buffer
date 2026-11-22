#!/usr/bin/env bash
# Checks and runs the linuxArm64 Kotlin/Native test binaries for build-linux.yaml.
#
# The x64 `Test (linux)` job links every module's linuxArm64 test binary and uploads them. The
# `Test (linux-arm64, native)` job downloads them onto a real ubuntu-24.04-arm runner and runs
# them there. Both jobs call this script, so the list of modules lives in one place.
#
#   linux-arm64-tests.sh check <root>   Exit 1 unless <root> holds a test binary for exactly the
#                                       modules in MODULES: a missing one fails, and so does an
#                                       extra one (a new module whose tests nobody would run).
#   linux-arm64-tests.sh run <root>     Run `check`, then run every binary, even after one fails,
#                                       and the release allocation binary.
#                                       Exit 1 if any binary exits non-zero or prints no
#                                       `[  PASSED  ] N tests.` line. Write a per-module table to
#                                       $GITHUB_STEP_SUMMARY when that is set.
#
# Add a module here when it gets linuxArm64 tests. `check` fails in the x64 job until you do.
set -euo pipefail

MODULES="
buffer
buffer-1brc
buffer-codec
buffer-codec-test
buffer-compression
buffer-crypto
buffer-flow
buffer-kotlinx-io
buffer-ktor
buffer-okio
buffer-sqldelight
"

KEXE_SUFFIX="build/bin/linuxArm64/debugTest/test.kexe"

# The allocation tests measure allocation, so they run from a release binary of their own
# (see buffer-codec-test/build.gradle.kts) and are excluded from the debug binaries.
NATIVE_ALLOCATION_TESTS="*NativeAllocation*"
ALLOCATION_KEXE="buffer-codec-test/build/bin/linuxArm64/allocationReleaseTest/allocation.kexe"

usage() {
  echo "usage: $0 check|run <root>" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
mode="$1"
root="$2"
[ -d "$root" ] || { echo "::error::$root is not a directory" >&2; exit 1; }

check() {
  local problems=0 module found expected
  for module in $MODULES; do
    if [ ! -f "$root/$module/$KEXE_SUFFIX" ]; then
      echo "::error::Missing linuxArm64 test binary for $module: $root/$module/$KEXE_SUFFIX"
      problems=$((problems + 1))
    fi
  done
  # Any test binary on disk that is not in MODULES. `find` prints paths relative to root, so the
  # module is everything before /$KEXE_SUFFIX.
  # MODULES flattened to " a b c " so `case` can match " module " exactly.
  expected=" $(printf '%s' "$MODULES" | tr '\n' ' ') "
  while IFS= read -r found; do
    [ -n "$found" ] || continue
    module="${found%/"$KEXE_SUFFIX"}"
    case "$expected" in
      *" $module "*) ;;
      *)
        echo "::error::Unlisted linuxArm64 test binary $found. Add '$module' to MODULES in $0 so it runs on arm64."
        problems=$((problems + 1))
        ;;
    esac
  done <<EOF
$(cd "$root" && find . -path "*/$KEXE_SUFFIX" -type f | sed 's|^\./||' | sort)
EOF
  if [ ! -f "$root/$ALLOCATION_KEXE" ]; then
    echo "::error::Missing linuxArm64 allocation test binary: $root/$ALLOCATION_KEXE"
    problems=$((problems + 1))
  fi
  if [ "$problems" -ne 0 ]; then
    echo "::error::linuxArm64 test binary set is wrong ($problems problem(s))."
    return 1
  fi
  echo "All $(printf '%s' "$MODULES" | wc -w | tr -d ' ') linuxArm64 test binaries present, and no unlisted ones."
}

# run_one <label> <kexe> <ktest_filter>: runs one binary and appends its row to $summary.
run_one() {
  local label="$1" kexe="$2" filter="$3" rc passed
  # upload-artifact does not keep the execute bit.
  chmod +x "$kexe"
  echo "::group::$label"
  set +e
  "$kexe" "--ktest_filter=$filter" 2>&1 | tee "$log"
  rc="${PIPESTATUS[0]}"
  set -e
  echo "::endgroup::"
  passed="$(sed -n 's/^\[  PASSED  \] \([0-9][0-9]*\) tests\{0,1\}\.$/\1/p' "$log" | tail -n 1)"
  if [ "$rc" -ne 0 ]; then
    echo "::error::$label linuxArm64 tests failed (exit $rc)"
    grep -E '^\[  FAILED  \]' "$log" || true
    failed="$failed $label"
    summary="$summary
| $label | FAILED (exit $rc) | ${passed:-?} |"
  elif [ -z "$passed" ]; then
    echo "::error::$label exited 0 but printed no '[  PASSED  ] N tests.' line"
    failed="$failed $label"
    summary="$summary
| $label | NO RESULT LINE | ? |"
  else
    echo "$label: $passed tests passed"
    total=$((total + passed))
    summary="$summary
| $label | passed | $passed |"
  fi
}

run() {
  check
  local module
  failed=""
  total=0
  summary="| Module | Result | Tests passed |
|---|---|---|"
  log="$(mktemp)"
  for module in $MODULES; do
    run_one "$module" "$root/$module/$KEXE_SUFFIX" "*-$NATIVE_ALLOCATION_TESTS"
  done
  run_one "buffer-codec-test (allocation, release)" "$root/$ALLOCATION_KEXE" "$NATIVE_ALLOCATION_TESTS"
  rm -f "$log"
  summary="$summary
| **total** | | **$total** |"
  echo "$summary"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    { echo "### linuxArm64 tests (native, $(uname -m))"; echo; echo "$summary"; } >> "$GITHUB_STEP_SUMMARY"
  fi
  if [ -n "$failed" ]; then
    echo "::error::linuxArm64 test failures in:$failed"
    return 1
  fi
}

case "$mode" in
  check) check ;;
  run) run ;;
  *) usage ;;
esac
