#!/usr/bin/env bash
# Materialize the recorded source commit without modifying a developer checkout.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
revision=$(tr -d '\r\n' < "$root/ui-builder-revision.txt")
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Expected a full UI Builder commit SHA" >&2; exit 1; }
target="$root/.upstream/compose-ui-builder"
if [[ ! -d "$target/.git" ]]; then
  mkdir -p "$target"
  git -C "$target" init --quiet
  git -C "$target" remote add origin https://github.com/yschimke/compose-ui-builder.git
fi
if [[ -n "$(git -C "$target" status --porcelain)" ]]; then
  echo "Refusing to overwrite changes in $target" >&2
  exit 1
fi
if ! git -C "$target" cat-file -e "$revision^{commit}" 2>/dev/null; then
  git -C "$target" fetch --depth=1 origin "$revision"
fi
git -C "$target" checkout --detach "$revision"
