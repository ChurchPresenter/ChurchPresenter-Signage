#!/bin/sh
# Starts the signage player installed under APP (the data folder's app/): the version `current`
# links to. A version just updated to counts its starts in `pending` until the agent finds it healthy
# and deletes the file; after three starts that never got there, it goes back to `previous` and
# leaves the version it gave up on in `rolled-back` for the agent to report. The agent's AppFolder
# uses the same names.
#
#   launch.sh APP [agent arguments…]
set -eu
app="$1"
shift

if [ -f "$app/pending" ]; then
  starts="$(cat "$app/pending" 2>/dev/null || echo 0)"
  if [ "$starts" -ge 3 ] && [ -L "$app/previous" ]; then
    readlink "$app/current" > "$app/rolled-back"
    ln -sfn "$(readlink "$app/previous")" "$app/current.new"
    mv -T "$app/current.new" "$app/current"
    rm -f "$app/pending" "$app/previous"
    echo "launch.sh: the new version did not start; went back to $(readlink "$app/current")" >&2
  else
    echo $((starts + 1)) > "$app/pending"
  fi
fi

JAVA_HOME="$app/current/runtime"
export JAVA_HOME
exec "$app/current/bin/churchpresenter-signage" "$@"
