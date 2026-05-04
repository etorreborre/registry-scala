#!/usr/bin/env bash
# Render the docs locally exactly the way gh-pages will see them.
#
#   ./docs/preview.sh           # render + serve at http://127.0.0.1:4000
#   ./docs/preview.sh --build   # render only, no server
#
# Uses Docker (image: ruby:3.3-bookworm) so we don't fight native gem builds
# against a host Ruby. Requires sbt and a running Docker daemon.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docs_src="$repo_root/docs"
docs_out="$repo_root/docs/target/mdoc"

cd "$repo_root"
sbt docs/mdoc

# _config.yml and Gemfile are copied (they only matter at startup / install
# time). _sass, _includes, and favicon.svg are bind-mounted into the
# container below so edits in docs/_sass/ etc. are picked up live without
# re-running this script.
cp "$docs_src/_config.yml" "$docs_out/_config.yml"
cp "$docs_src/Gemfile"     "$docs_out/Gemfile"
rm -rf "$docs_out/_sass" "$docs_out/_includes" "$docs_out/favicon.svg"

mode="${1:-serve}"
case "$mode" in
  --build)
    docker_cmd="bundle install --quiet && bundle exec jekyll build"
    docker_extra=()
    ;;
  serve|"")
    # Override baseurl locally so the site is reachable at http://127.0.0.1:4000/
    # rather than http://127.0.0.1:4000/<baseurl>/.
    # --force_polling: macOS+Docker filesystem events don't reach the
    # container, so Jekyll's default inotify watcher sees nothing. Polling
    # picks up edits to _sass/ and the markdown files within ~1s.
    # --livereload: Jekyll opens a websocket the browser connects to and
    # triggers a reload when files change — no manual Cmd-Shift-R needed.
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

docker run --rm -it \
  "${docker_extra[@]}" \
  -v "$docs_out:/site" \
  -v "$docs_src/_sass:/site/_sass:ro" \
  -v "$docs_src/_includes:/site/_includes:ro" \
  -v "$docs_src/favicon.svg:/site/favicon.svg:ro" \
  -v "$repo_root/docs/target/.bundle-cache:/usr/local/bundle" \
  -w /site \
  ruby:3.3-bookworm \
  bash -c "$docker_cmd"
