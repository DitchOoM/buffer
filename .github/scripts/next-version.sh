#!/usr/bin/env bash
# Prints the next version (exactly one line) from `./gradlew -q nextVersion "$@"` and nothing else.
#
# Even with -q, Gradle's configuration phase can print to stdout. AGP 9.5 auto-installs its default NDK
# there and prints "License for package NDK (Side by side) ... accepted.". A raw `$(./gradlew -q nextVersion)`
# then captures two lines, and writing that to $GITHUB_OUTPUT fails with "Invalid format". So this keeps
# exactly one version-shaped line and shows every other line as a ::notice:: on stderr.
# Exit 1 when Gradle fails, or when it prints zero or several version lines.
set -euo pipefail
raw="$(./gradlew -q nextVersion "$@")"
semver='^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'
printf '%s\n' "$raw" | { grep -vE "$semver" || true; } | sed 's/^/::notice::nextVersion stdout (not the version): /' >&2
version="$(printf '%s\n' "$raw" | { grep -E "$semver" || true; })"
if [ -z "$version" ]; then
  echo "::error::nextVersion produced no version line" >&2
  exit 1
fi
if [ "$(printf '%s\n' "$version" | wc -l | tr -d ' ')" != 1 ]; then
  echo "::error::nextVersion printed more than one version line: $(printf '%s' "$version" | tr '\n' ' ')" >&2
  exit 1
fi
printf '%s\n' "$version"
