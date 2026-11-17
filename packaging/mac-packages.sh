#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
source "${script_dir}/lib/common.sh"
source "${script_dir}/lib/macos-formats.sh"

usage() {
    cat <<'EOF'
Usage: mac-packages.sh [options] <version>

Builds HDSL.app and the macOS packages. The launcher needs a Java 21+ runtime.

Formats:
  dmg        HDSL-<version>-<arch>.dmg
  zip        HDSL-<version>-<arch>.zip
  all        both (default)

Options:
  --sh-artifact PATH   Self-executing launcher; default build/libs/hdsl-<version>.sh.
  --icon PATH          Bundle icon; default src/main/resources/assets/img/icon@8x.png.
  --output-dir DIR     Artifact directory; default dist.
  --formats LIST       Comma-separated dmg,zip, or all.
  --arch ARCH          aarch64 or x64; default host (uname -m).
  --no-sums            Do not write .sha256 files; existing ones are left alone.
  -h, --help           Print this help.
EOF
}

formats=all
with_sums=1
sh_artifact=""
icon=""
out_dir=""
arch=""
stage=""
positional=""
positional_count=0

while [ $# -gt 0 ]; do
    case "$1" in
        --formats)
            require_value --formats "$#"
            formats="$2"
            shift 2
            ;;
        --formats=*) formats="${1#*=}"; shift ;;
        --sh-artifact)
            require_value --sh-artifact "$#"
            sh_artifact="$2"
            shift 2
            ;;
        --sh-artifact=*) sh_artifact="${1#*=}"; shift ;;
        --icon)
            require_value --icon "$#"
            icon="$2"
            shift 2
            ;;
        --icon=*) icon="${1#*=}"; shift ;;
        --output-dir)
            require_value --output-dir "$#"
            out_dir="$2"
            shift 2
            ;;
        --output-dir=*) out_dir="${1#*=}"; shift ;;
        --arch)
            require_value --arch "$#"
            arch="$2"
            shift 2
            ;;
        --arch=*) arch="${1#*=}"; shift ;;
        --no-sums) with_sums=0; shift ;;
        -h|--help) usage; exit 0 ;;
        --)
            shift
            while [ $# -gt 0 ]; do
                positional="$1"
                positional_count=$((positional_count + 1))
                shift
            done
            break
            ;;
        -*) usage_error "unknown option: $1" ;;
        *)
            positional="$1"
            positional_count=$((positional_count + 1))
            shift
            ;;
    esac
done

[ "$positional_count" -eq 1 ] || usage_error "expected exactly one <version>; use --sh-artifact, --icon and --output-dir for the rest"

version="$positional"
[ -n "$version" ] || usage_error "version must not be empty"

[ -n "$sh_artifact" ] || sh_artifact="${repo_dir}/build/libs/hdsl-${version}.sh"
[ -n "$icon" ] || icon="${repo_dir}/src/main/resources/assets/img/icon@8x.png"
[ -n "$out_dir" ] || out_dir="${repo_dir}/dist"

if [ -z "$arch" ]; then
    arch="$(uname -m)"
fi
case "$arch" in
    aarch64|arm64) arch="aarch64" ;;
    x64|x86_64|amd64) arch="x64" ;;
    *) usage_error "unsupported arch '$arch' (want aarch64 or x64)" ;;
esac

formats="${formats//[[:space:]]/}"
[ -n "$formats" ] || usage_error "--formats needs a value"
if [ "$formats" = all ]; then
    formats="dmg,zip"
fi
case ",$formats," in
    *,all,*) usage_error "'all' cannot be combined with other formats" ;;
esac
validate_formats "$formats" dmg zip

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