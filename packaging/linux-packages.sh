#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
source "${script_dir}/lib/common.sh"
source "${script_dir}/lib/linux-formats.sh"

usage() {
    cat <<'EOF'
Usage: linux-packages.sh [options] <version> <sh-artifact> <icon.png> <output-dir>

Builds the Linux packages. The launcher needs a Java 21+ runtime.

Formats:
  tar        hdsl-<version>-linux-x64.tar.zst
  arch       hdsl-<archversion>-1-x86_64.pkg.tar.zst
  appimage   HDSL-<version>-x86_64.AppImage (needs appimagetool)
  deb        checksum only for the .deb Gradle already built
  all        every format above (default)

Options:
  --formats LIST       Comma-separated tar,arch,appimage,deb, or all.
  --appimagetool PATH  Tool to use; defaults to $APPIMAGETOOL or PATH.
  --no-sums            Do not write .sha256 files; existing ones are left alone.
  -h, --help           Print this help.

Naming appimage explicitly fails when appimagetool is missing; all skips it.
Options must come before the positional arguments. Run on the target platform;
the Linux packages are x86_64.
EOF
}

formats=all
with_sums=1
appimagetool="${APPIMAGETOOL:-}"
stage=""

while [ $# -gt 0 ]; do
    case "$1" in
        --formats)
            [ $# -ge 2 ] || usage_error "--formats needs a value"
            formats="$2"
            shift 2
            ;;
        --formats=*) formats="${1#*=}"; shift ;;
        --appimagetool)
            [ $# -ge 2 ] || usage_error "--appimagetool needs a value"
            appimagetool="$2"
            shift 2
            ;;
        --appimagetool=*) appimagetool="${1#*=}"; shift ;;
        --no-sums) with_sums=0; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; break ;;
        -*) usage_error "unknown option: $1" ;;
        *) break ;;
    esac
done

[ $# -eq 4 ] || usage_error "expected 4 positional arguments, got $#; put options before them"

version="$1"
sh_artifact="$2"
icon="$3"
out_dir="$4"

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

require_file "$sh_artifact"
require_file "$icon"
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

name="hdsl-${version}"
desktop_id="hdsl"
launcher_class="org.jackhuang.hmcl.Main"
arch_version="$(arch_version_of "$version")"
tar_artifact="${out_dir}/${name}-linux-x64.tar.zst"
arch_artifact="${out_dir}/${desktop_id}-${arch_version}-1-x86_64.pkg.tar.zst"
appimage_artifact="${out_dir}/HDSL-${version}-x86_64.AppImage"
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