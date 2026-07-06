#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-$(git rev-parse --show-toplevel)}"
git -C "$repo_root" rev-parse --git-dir >/dev/null

mapfile -d '' tracked_files < <(git -C "$repo_root" ls-files -z)
violations=()
for file in "${tracked_files[@]}"; do
    case "$file" in
        local.properties|*/local.properties|*.jks)
            violations+=("$file")
            ;;
    esac
done

if ((${#violations[@]} > 0)); then
    echo "Refusing tracked Android secrets or local configuration:" >&2
    printf '  %s\n' "${violations[@]}" >&2
    exit 1
fi
