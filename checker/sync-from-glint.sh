#!/usr/bin/env bash
# Re-copies the checker's sources from a Glint checkout: the :glint-recipes module's main Kotlin
# sources (schema, engine, scrubber, checker, shape learner). The catalog's own checks live in
# src/catalog/ and are left alone. Writes VERSION with the Glint commit the copy came from.
#   checker/sync-from-glint.sh <path-to-glint-checkout>
set -euo pipefail
glint="${1:?usage: sync-from-glint.sh <path-to-glint-checkout>}"
here="$(cd "$(dirname "$0")" && pwd)"
src="$glint/glint-recipes/src/main/kotlin"
[ -d "$src/com/thesiegs/glint/recipes" ] || { echo "no glint-recipes sources under $glint" >&2; exit 1; }
rm -rf "$here/src/main/kotlin"
mkdir -p "$here/src/main"
cp -R "$src" "$here/src/main/kotlin"
commit="$(git -C "$glint" rev-parse HEAD)"
branch="$(git -C "$glint" rev-parse --abbrev-ref HEAD)"
dirty=""
git -C "$glint" diff --quiet HEAD -- glint-recipes/src/main || dirty=" (with uncommitted changes)"
cat > "$here/VERSION" <<V
Vendored from Glint's :glint-recipes module (glint-recipes/src/main/kotlin).
Glint commit: $commit$dirty
Glint branch: $branch
Synced: $(date -u +%Y-%m-%d)
Update with: checker/sync-from-glint.sh <path-to-glint-checkout>
V
echo "Synced checker sources from $commit$dirty"
