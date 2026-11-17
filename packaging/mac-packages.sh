#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
source "${script_dir}/lib/common.sh"
source "${script_dir}/lib/macos-formats.sh"

usage() {
    cat <<'EOF'
Usage: mac-packages.sh [options] <version> <sh-artifact> <icon.png> <output-dir> [arch]

Builds HDSL.app and the macOS packages. The launcher needs a Java 21+ runtime.

Formats:
  dmg        HDSL-<version>-<arch>.dmg
  zip        HDSL-<version>-<arch>.zip
  all        both (default)

Options:
  --formats LIST   Comma-separated dmg,zip, or all.
  --no-sums        Do not write .sha256 files; existing ones are left alone.
  -h, --help       Print this help.

Options must come before the positional arguments. arch defaults to uname -m;
aarch64/arm64 and x64/x86_64/amd64 are accepted. The .app bundle is always
built and is not published separately.
EOF
}

formats=all
with_sums=1
stage=""

while [ $# -gt 0 ]; do
    case "$1" in
        --formats)
            [ $# -ge 2 ] || usage_error "--formats needs a value"
            formats="$2"
            shift 2
            ;;
        --formats=*) formats="${1#*=}"; shift ;;
        --no-sums) with_sums=0; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; break ;;
        -*) usage_error "unknown option: $1" ;;
        *) break ;;
    esac
done

[ $# -eq 4 ] || [ $# -eq 5 ] || usage_error "expected 4 or 5 positional arguments, got $#; put options before them"

version="$1"
sh_artifact="$2"
icon="$3"
out_dir="$4"
arch_arg="${5:-}"

formats="${formats//[[:space:]]/}"
[ -n "$formats" ] || usage_error "--formats needs a value"
if [ "$formats" = all ]; then
    formats="dmg,zip"
fi
case ",$formats," in
    *,all,*) usage_error "'all' cannot be combined with other formats" ;;
esac
validate_formats "$formats" dmg zip

if [ -n "$arch_arg" ]; then
    case "$arch_arg" in
        aarch64|arm64) arch="aarch64" ;;
        x64|x86_64|amd64) arch="x64" ;;
        *) usage_error "unknown arch '$arch_arg' (want aarch64 or x64)" ;;
    esac
else
    case "$(uname -m)" in
        arm64|aarch64) arch="aarch64" ;;
        x86_64|amd64) arch="x64" ;;
        *) die "unknown host arch $(uname -m)" ;;
    esac
fi

require_file "$sh_artifact"
require_file "$icon"
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

name="hdsl-${version}"
bundle_id="run.hdsl.HDSL"
dmg_artifact="${out_dir}/HDSL-${version}-${arch}.dmg"
zip_artifact="${out_dir}/HDSL-${version}-${arch}.zip"

stage_init

if [ "$with_sums" -eq 0 ]; then
    echo "warning: --no-sums leaves existing .sha256 files alone; they may be stale after a rebuild." >&2
fi

build_bundle
if want dmg; then build_dmg; fi
if want zip; then build_zip; fi

if want dmg; then finish_artifact "$with_sums" "$dmg_artifact"; fi
if want zip; then finish_artifact "$with_sums" "$zip_artifact"; fi

if want dmg && [ -f "$dmg_artifact" ]; then ls -l "$dmg_artifact"; fi
if want zip && [ -f "$zip_artifact" ]; then ls -l "$zip_artifact"; fi