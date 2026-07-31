#!/usr/bin/env bash
# Retries a Gradle invocation, but only when it failed for a reason a retry can
# actually fix.
#
# Maven Central intermittently answers a GitHub runner with 403 Forbidden while
# resolving the Android Gradle Plugin's own transitive dependencies, which fails
# the build in under thirty seconds with nothing of ours involved. Blindly
# retrying every failure would triple the time a genuine compile error takes to
# report, so the log is inspected first and a real failure returns immediately.
#
# Written to be safe under `set -e`: every command that may fail is either
# tested or has its status captured with `|| status=$?`, so sourcing this into
# an errexit shell cannot abort the step early.

# Failure signatures worth another attempt: transport problems and the HTTP
# statuses a repository returns when it is rate limiting or briefly unavailable.
# Deliberately excludes 401 and 404, which mean a wrong credential or a wrong
# coordinate and will never succeed on a retry.
GRADLE_RETRYABLE_PATTERN='Could not (GET|HEAD|resolve|download|get resource)|Received status code (403|408|429|500|502|503|504)|Connection (reset|refused|timed out)|Read timed out|Premature end of Content-Length|Network is unreachable|Temporary failure in name resolution'

# Usage: gradle_with_network_retry <log-file> [gradle args...]
# Returns Gradle's exit status. Never exits the shell itself.
gradle_with_network_retry() {
  local log_file="$1"
  shift

  local max_attempts="${GRADLE_MAX_ATTEMPTS:-3}"
  local attempt
  local status=0
  local backoff

  for (( attempt = 1; attempt <= max_attempts; attempt++ )); do
    status=0
    ./gradlew --no-daemon --console=plain "$@" > "$log_file" 2>&1 || status=$?

    if [ "$status" -eq 0 ]; then
      if [ "$attempt" -gt 1 ]; then
        echo "::notice::Gradle succeeded on attempt ${attempt}."
      fi
      return 0
    fi

    if [ "$attempt" -ge "$max_attempts" ]; then
      break
    fi

    if grep -qE "$GRADLE_RETRYABLE_PATTERN" "$log_file"; then
      backoff=$(( attempt * 20 ))
      echo "::warning::Attempt ${attempt} failed while fetching dependencies, not while building. Retrying in ${backoff}s."
      grep -oE "$GRADLE_RETRYABLE_PATTERN" "$log_file" | sort -u | head -n 5 || true
      sleep "$backoff"
      continue
    fi

    # A real build failure. Report it now rather than repeating it twice more.
    return "$status"
  done

  echo "::error::Gradle failed after ${max_attempts} attempts."
  return "$status"
}
