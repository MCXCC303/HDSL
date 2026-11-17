#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
source "${script_dir}/lib/common.sh"
source "${script_dir}/lib/linux-formats.sh"

usage() {
    cat <<'EOF'
Usage: linux-packages.sh [options] <version>

Builds the Linux packages. The launcher needs a Java 21+ runtime.

Formats:
  tar        hdsl-<version>-linux-<x64|aarch64>.tar.zst
  arch       hdsl-<archversion>-1-<x86_64|aarch64>.pkg.tar.zst
  appimage   HDSL-<version>-<x86_64|aarch64>.AppImage (needs appimagetool)
  deb        checksum only for the .deb Gradle already built
  all        every format above (default)

Options:
  --sh-artifact PATH   Self-executing launcher; default build/libs/hdsl-<version>.sh.
  --icon PATH          Desktop icon; default src/main/resources/assets/img/icon@8x.png.
  --output-dir DIR     Artifact directory; default dist.
  --formats LIST       Comma-separated tar,arch,appimage,deb, or all.
  --arch ARCH          x86_64 (default host) or aarch64; the .sh and appimagetool
                       must match it.
  --appimagetool PATH  Tool to use; defaults to $APPIMAGETOOL or PATH.
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
appimagetool="${APPIMAGETOOL:-}"
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
        --appimagetool)
            require_value --appimagetool "$#"
            appimagetool="$2"
            shift 2
            ;;
        --appimagetool=*) appimagetool="${1#*=}"; shift ;;
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
    x86_64|amd64|x64)
        pkg_arch="x86_64"
        linux_arch="x64"
        appimage_arch="x86_64"
        ;;
    aarch64|arm64)
        pkg_arch="aarch64"
        linux_arch="aarch64"
        appimage_arch="aarch64"
        ;;
    *)
        usage_error "unsupported arch '$arch' (want x86_64 or aarch64)"
        ;;
esac

formats="${formats//[[:space:]]/}"
[ -n "$formats" ] || usage_error "--formats needs a value"
appimage_explicit=0
if [ "$formats" != all ]; then
    case ",$formats," in
        *,appimage,*) appimage_explicit=1 ;;
    esac
fi
if [ "$formats" = all ]; then
    formats="tar,arch,appimage,deb"
fi
case ",$formats," in
    *,all,*) usage_error "'all' cannot be combined with other formats" ;;
esac
validate_formats "$formats" tar arch appimage deb

if want tar || want arch || want appimage; then
    require_file "$sh_artifact"
    require_file "$icon"
fi
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

name="hdsl-${version}"
desktop_id="hdsl"
launcher_class="org.jackhuang.hmcl.Main"
arch_version="$(arch_version_of "$version")"
tar_artifact="${out_dir}/${name}-linux-${linux_arch}.tar.zst"
arch_artifact="${out_dir}/${desktop_id}-${arch_version}-1-${pkg_arch}.pkg.tar.zst"
appimage_artifact="${out_dir}/HDSL-${version}-${appimage_arch}.AppImage"
deb_artifact="${out_dir}/${name}.deb"

if want appimage; then
    if [ -z "$appimagetool" ]; then
        appimagetool="$(command -v appimagetool || true)"
    else
        appimagetool="$(command -v "$appimagetool" || printf '%s' "$appimagetool")"
    fi
    if [ -z "$appimagetool" ]; then
        if [ "$appimage_explicit" -eq 1 ]; then
            die "appimagetool not found; set --appimagetool PATH or APPIMAGETOOL"
        fi
        echo "== AppImage skipped: appimagetool not found (set APPIMAGETOOL to build it) =="
    elif [ ! -f "$appimagetool" ] || [ ! -x "$appimagetool" ]; then
        die "appimagetool does not exist or is not executable: $appimagetool"
    fi
fi

if want tar || want arch || want appimage; then
    stage_init
fi

if [ "$with_sums" -eq 0 ]; then
    echo "warning: --no-sums leaves existing .sha256 files alone; they may be stale after a rebuild." >&2
fi

if want tar; then build_tar; fi
if want arch; then build_arch; fi
if want appimage && [ -n "$appimagetool" ]; then build_appimage; fi

if want tar; then finish_artifact "$with_sums" "$tar_artifact"; fi
if want arch; then finish_artifact "$with_sums" "$arch_artifact"; fi
if want appimage; then finish_artifact "$with_sums" "$appimage_artifact"; fi
if want deb; then finish_artifact "$with_sums" "$deb_artifact"; fi

if want tar && [ -f "$tar_artifact" ]; then ls -l "$tar_artifact"; fi
if want arch && [ -f "$arch_artifact" ]; then ls -l "$arch_artifact"; fi
if want appimage && [ -f "$appimage_artifact" ]; then ls -l "$appimage_artifact"; fi
if want deb && [ -f "$deb_artifact" ]; then ls -l "$deb_artifact"; fi