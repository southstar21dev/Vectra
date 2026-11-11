#!/usr/bin/env bash
# Waits until no Release run (release.yml) on master is queued or running, for at most $1 seconds
# (default 0: check once). Exits 0 when master is free to move, 3 when a release is still running
# (any other failure, such as the API call, exits with its own non-zero code).
#
# Used by refresh-catalog.yml before it pushes to master: a push while a release run is in flight
# makes that run's semantic-release see master moved and publish nothing, and since a GITHUB_TOKEN
# push triggers no workflow, the release then waits for the next human push. Sharing the release
# concurrency group instead would let a queued catalog run cancel a pending release.
#
# Needs GH_TOKEN (actions: read) and GH_REPO.
set -euo pipefail

MAX_WAIT="${1:-0}"
INTERVAL="${WAIT_INTERVAL:-60}"
START=$SECONDS

while :; do
  # Every status but "completed" (queued, in_progress, waiting, requested, pending) holds master.
  ACTIVE="$(gh run list --workflow release.yml --branch master --limit 20 --json status \
    --jq '[.[] | select(.status != "completed")] | length')"
  if [ "$ACTIVE" = 0 ]; then
    exit 0
  fi
  if [ $((SECONDS - START + INTERVAL)) -gt "$MAX_WAIT" ]; then
    echo "A Release run on master is still active ($ACTIVE run(s))."
    exit 3
  fi
  echo "A Release run on master is active ($ACTIVE run(s)); checking again in ${INTERVAL}s."
  sleep "$INTERVAL"
done
