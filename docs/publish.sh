#!/usr/bin/env bash
# Render the docs and push them to the gh-pages branch.
#
#   ./docs/publish.sh
#
# Mirrors what the CI publish job does (build.sbt's githubWorkflowPublish
# without the Maven Central step). Use this when you want to update the
# rendered site without cutting a new release.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docs_src="$repo_root/docs"
docs_out="$repo_root/docs/target/mdoc"

remote_url="$(git -C "$repo_root" config --get remote.origin.url)"
tmp_clone="$(mktemp -d -t registry-gh-pages-XXXXXX)"

cleanup() { rm -rf "$tmp_clone"; }
trap cleanup EXIT

# 1. Render markdown via mdoc.
cd "$repo_root"
sbt docs/mdoc

# 2. Layer in Jekyll config and theme assets.
cp "$docs_src/_config.yml"  "$docs_out/_config.yml"
cp "$docs_src/Gemfile"      "$docs_out/Gemfile"
cp "$docs_src/favicon.svg"  "$docs_out/favicon.svg"
rm -rf "$docs_out/_sass" "$docs_out/_includes"
cp -R "$docs_src/_sass"     "$docs_out/_sass"
cp -R "$docs_src/_includes" "$docs_out/_includes"

# 3. Clone gh-pages into a throwaway dir, sync the rendered site into it,
#    commit, push.
git clone --branch gh-pages --single-branch "$remote_url" "$tmp_clone"

rsync -a --delete --exclude=.git "$docs_out/" "$tmp_clone/"

cd "$tmp_clone"
if [[ -z "$(git status --porcelain)" ]]; then
  echo "No changes to publish."
  exit 0
fi

git add -A
git -c user.email="$(git -C "$repo_root" config user.email)" \
    -c user.name="$(git  -C "$repo_root" config user.name)" \
    commit -m "docs: publish $(date -u +%Y-%m-%dT%H:%M:%SZ)"
git push origin gh-pages

echo
echo "Done. Live at https://etorreborre.github.io/registry-scala/ in ~30s."
