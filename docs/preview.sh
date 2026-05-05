#!/usr/bin/env bash
# Render the docs locally exactly the way gh-pages will see them.
#
#   ./docs/preview.sh           # render + serve at http://127.0.0.1:4000
#   ./docs/preview.sh --build   # render only, no server
#
# Runs two long-lived processes side by side:
#   1. `sbt ~docs/mdoc` in the background — re-runs mdoc on every edit
#      under docs/mdoc/ so the rendered markdown stays fresh.
#   2. Docker container (image: ruby:3.3-bookworm) running Jekyll in the
#      foreground — serves the site at http://127.0.0.1:4000.
# Ctrl-C stops the foreground container, then the EXIT trap stops sbt.
#
# Requires sbt and a running Docker daemon.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docs_src="$repo_root/docs"
docs_out="$repo_root/docs/target/mdoc"

# ── Background sbt watcher ────────────────────────────────────────────────
# Tracked here so the cleanup trap can kill it even if the script is
# interrupted before the watcher is started.
sbt_pid=

cleanup() {
  if [[ -n "$sbt_pid" ]] && kill -0 "$sbt_pid" 2>/dev/null; then
    echo
    echo "Stopping sbt watcher (pid $sbt_pid)…"
    # sbt forks a java subprocess; kill children first, then the wrapper.
    pkill -P "$sbt_pid" 2>/dev/null || true
    kill "$sbt_pid"     2>/dev/null || true
    wait "$sbt_pid"     2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# ── 1. Initial mdoc run (synchronous) ─────────────────────────────────────
# We need docs/target/mdoc/ populated before Jekyll starts; doing the first
# pass in the foreground is the simplest way to guarantee that.
cd "$repo_root"
sbt docs/mdoc

# ── 2. Layer in Jekyll config ────────────────────────────────────────────
# _config.yml and Gemfile are copied (they only matter at startup / install
# time). _sass, _includes, favicon.svg, and assets/ are bind-mounted into
# the container so edits in those dirs are picked up live.
cp "$docs_src/_config.yml" "$docs_out/_config.yml"
cp "$docs_src/Gemfile"     "$docs_out/Gemfile"
rm -rf "$docs_out/_sass" "$docs_out/_includes" "$docs_out/assets" "$docs_out/favicon.svg"
mkdir -p "$docs_src/assets"

mode="${1:-serve}"
case "$mode" in
  --build)
    docker_cmd="bundle install --quiet && bundle exec jekyll build"
    docker_extra=()
    ;;
  serve|"")
    # --baseurl '': site reachable at http://127.0.0.1:4000/ (production
    # uses /registry-scala via _config.yml's baseurl).
    # --force_polling: macOS+Docker filesystem events don't reach the
    # container; polling picks up edits within ~1 s.
    # --livereload: Jekyll pushes a refresh to the browser when files change.
    docker_cmd="bundle install --quiet && bundle exec jekyll serve --host 0.0.0.0 --baseurl '' --force_polling --livereload"
    docker_extra=(-p 4000:4000 -p 35729:35729)
    ;;
  *)
    echo "unknown mode: $mode (expected: serve, --build)" >&2
    exit 2
    ;;
esac

# Persist gems across runs so we don't reinstall on every invocation.
mkdir -p "$repo_root/docs/target/.bundle-cache"

# ── 3. Start the sbt watcher in the background ───────────────────────────
# Skip for --build mode (one-shot) — only useful when serving.
if [[ "$mode" == "serve" || "$mode" == "" ]]; then
  echo
  echo "Starting sbt watcher (sbt ~docs/mdoc) in the background…"
  sbt "~docs/mdoc" &
  sbt_pid=$!
fi

# ── 4. Run Jekyll in the foreground ──────────────────────────────────────
docker run --rm -it \
  "${docker_extra[@]}" \
  -v "$docs_out:/site" \
  -v "$docs_src/_sass:/site/_sass:ro" \
  -v "$docs_src/_includes:/site/_includes:ro" \
  -v "$docs_src/favicon.svg:/site/favicon.svg:ro" \
  -v "$docs_src/assets:/site/assets:ro" \
  -v "$repo_root/docs/target/.bundle-cache:/usr/local/bundle" \
  -w /site \
  ruby:3.3-bookworm \
  bash -c "$docker_cmd"
