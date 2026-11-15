#!/usr/bin/env bash
#
# Builds the two Linux packages. The launcher needs a Java 21+ runtime.
#
# usage: linux-packages.sh <version> <sh-artifact> <icon.png> <output-dir>
#
# The AppImage tool is taken from $APPIMAGETOOL when set; otherwise it is looked for on PATH.

set -euo pipefail

version="${1:?usage: linux-packages.sh <version> <sh-artifact> <icon.png> <output-dir>}"
sh_artifact="${2:?}"
icon="${3:?}"
out_dir="${4:?}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"

name="hdsl-${version}"
desktop_id="hdsl"
launcher_class="org.jackhuang.hmcl.Main"

for file in "${sh_artifact}" "${icon}"; do
    [ -f "${file}" ] || { echo "error: ${file} does not exist" >&2; exit 1; }
done

mkdir -p "${out_dir}"
out_dir="$(cd "${out_dir}" && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf "${stage}"' EXIT

tree="${stage}/${name}"
mkdir -p "${tree}"
cp "${sh_artifact}" "${tree}/${name}.sh"
chmod 0755 "${tree}/${name}.sh"
cp "${icon}" "${tree}/${desktop_id}.png"
for file in LICENSE NOTICE README.md; do
    [ -f "${repo_dir}/${file}" ] && cp "${repo_dir}/${file}" "${tree}/" && chmod 0644 "${tree}/${file}"
done

write_desktop_entry() {
    target="$1"
    launcher="$2"
    cat > "${target}" <<EOF
[Desktop Entry]
Type=Application
Name=HDSL
Comment=DeepSeek Harness launcher
Exec=${launcher}
Icon=${desktop_id}
Terminal=false
StartupNotify=false
Categories=Development;Utility;
Keywords=deepseek;harness;dsh;ai;
StartupWMClass=${launcher_class}
EOF
    chmod 0644 "${target}"
}

write_desktop_entry "${tree}/${desktop_id}.desktop" "${name}.sh"


echo "== tar.zst =="
tar --zstd -cf "${out_dir}/${name}-linux-x64.tar.zst" -C "${stage}" "${name}"
tar --zstd -tf "${out_dir}/${name}-linux-x64.tar.zst"

# Arch package
arch_stage="${stage}/arch"
mkdir -p "${arch_stage}/usr/bin" "${arch_stage}/usr/share/applications" \
         "${arch_stage}/usr/share/icons/hicolor/256x256/apps" \
         "${arch_stage}/usr/share/licenses/${desktop_id}"

cp "${sh_artifact}" "${arch_stage}/usr/bin/${desktop_id}"
chmod 0755 "${arch_stage}/usr/bin/${desktop_id}"
cp "${icon}" "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
chmod 0644 "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
write_desktop_entry "${arch_stage}/usr/share/applications/${desktop_id}.desktop" "${desktop_id}"
for file in LICENSE NOTICE; do
    [ -f "${repo_dir}/${file}" ] && cp "${repo_dir}/${file}" "${arch_stage}/usr/share/licenses/${desktop_id}/" \
        && chmod 0644 "${arch_stage}/usr/share/licenses/${desktop_id}/${file}"
done

arch_version_of() {
    case "$1" in
    *-*) printf '%s%s\n' "${1%%-*}" "$(printf '%s' "${1#*-}" | tr '[:upper:]' '[:lower:]' | tr -d '._-')" ;;
    *) printf '%s\n' "$1" ;;
    esac
}
arch_version="$(arch_version_of "${version}")"

previous_name="hmcl-dsh"
installed_size="$(du -sb "${arch_stage}" | cut -f1)"
cat > "${arch_stage}/.PKGINFO" <<EOF
pkgname = ${desktop_id}
pkgbase = ${desktop_id}
pkgver = ${arch_version}-1
pkgdesc = DeepSeek Harness launcher
url = https://github.com/
builddate = $(date +%s)
packager = HDSL contributors
size = ${installed_size}
arch = x86_64
license = GPL-3.0-or-later
depend = java-runtime>=21
replaces = ${previous_name}
conflict = ${previous_name}
EOF

arch_pkg="${out_dir}/${desktop_id}-${arch_version}-1-x86_64.pkg.tar.zst"
tar --zstd -cf "${arch_pkg}" -C "${arch_stage}" .PKGINFO usr
echo "arch package: ${arch_pkg}"
tar --zstd -tf "${arch_pkg}" > /dev/null

# appimage
appdir="${stage}/HDSL.AppDir"
mkdir -p "${appdir}/usr/bin"
cp "${sh_artifact}" "${appdir}/usr/bin/${desktop_id}"
chmod 0755 "${appdir}/usr/bin/${desktop_id}"
cp "${icon}" "${appdir}/${desktop_id}.png"
write_desktop_entry "${appdir}/${desktop_id}.desktop" "${desktop_id}"
for file in LICENSE NOTICE; do
    [ -f "${repo_dir}/${file}" ] && cp "${repo_dir}/${file}" "${appdir}/" && chmod 0644 "${appdir}/${file}"
done

cat > "${appdir}/AppRun" <<EOF
#!/usr/bin/env bash
# Refuse to start anywhere but the user's home, for the reason above.
cd "\${HOME}"
exec "\${APPDIR}/usr/bin/${desktop_id}" "\$@"
EOF
chmod 0755 "${appdir}/AppRun"

appimagetool="${APPIMAGETOOL:-}"
if [ -z "${appimagetool}" ]; then
    appimagetool="$(command -v appimagetool || true)"
fi

if [ -n "${appimagetool}" ]; then
    echo "== AppImage =="
    ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "${appimagetool}" \
        --no-appstream \
        "${appdir}" "${out_dir}/HDSL-${version}-x86_64.AppImage"
else
    echo "== AppImage skipped: appimagetool not found (set APPIMAGETOOL to build it) =="
fi

# sha256 sums
for artifact in "${out_dir}/${name}-linux-x64.tar.zst" \
                "${out_dir}/${desktop_id}-${arch_version}-1-x86_64.pkg.tar.zst" \
                "${out_dir}/${name}.deb" \
                "${out_dir}/HDSL-${version}-x86_64.AppImage"; do
    if [ -f "${artifact}" ]; then
        ( cd "${out_dir}" && sha256sum "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
    else
        rm -f "${artifact}.sha256"
        echo "no checksum for $(basename "${artifact}"): it was not built"
    fi
done

ls -l "${out_dir}"/*.tar.zst "${out_dir}"/*.pkg.tar.zst "${out_dir}"/HDSL-*.AppImage 2>/dev/null || true
