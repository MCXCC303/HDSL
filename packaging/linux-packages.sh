#!/usr/bin/env bash
#
# Builds the two Linux packages Gradle does not: a tar.zst of the launcher and an
# AppImage. Both wrap the self-executing `.sh` that `makeExecutable` produces, and
# both carry the same desktop entry and icon the `.deb` installs, so the three
# artifacts describe one application rather than three.
#
# The launcher needs a Java 21+ runtime, exactly as the `.deb` declares in its
# `Depends`: none of these packages bundles a JVM, which is what HMCL ships too.
#
# usage: linux-packages.sh <version> <sh-artifact> <icon.png> <output-dir>
#
# The AppImage tool is taken from $APPIMAGETOOL when set (the release workflow
# downloads it); otherwise it is looked for on PATH.

set -euo pipefail

version="${1:?usage: linux-packages.sh <version> <sh-artifact> <icon.png> <output-dir>}"
sh_artifact="${2:?}"
icon="${3:?}"
out_dir="${4:?}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"

name="hdsl-${version}"
desktop_id="hmcl-dsh"
launcher_class="org.jackhuang.hmcl.Main"

for file in "${sh_artifact}" "${icon}"; do
    [ -f "${file}" ] || { echo "error: ${file} does not exist" >&2; exit 1; }
done

mkdir -p "${out_dir}"
out_dir="$(cd "${out_dir}" && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf "${stage}"' EXIT

# ---------------------------------------------------------------- the tree ----
# What a user unpacks: the launcher, its licence, and a desktop entry so the
# unpacked copy can be installed by hand into a menu.
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
Name=HMCL-DSH
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

# ------------------------------------------------------------- the AppImage ---
# An AppDir is the tree above plus an AppRun; appimagetool turns it into the
# single-file image. The launcher is run from the user's home, as the `.deb`'s
# wrapper does: DeepSeek Harness scopes a session to the process's working
# directory, and starting inside a mounted image would file the first session
# under a path inside the mount.
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
[ -n "${appimagetool}" ] || { echo "error: appimagetool not found; set APPIMAGETOOL" >&2; exit 1; }

echo "== AppImage =="
# ARCH is normally read from the host; naming it keeps the artifact's name stable
# on a runner whose uname says something else.
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "${appimagetool}" \
    --no-appstream \
    "${appdir}" "${out_dir}/HDSL-${version}-x86_64.AppImage"

# ------------------------------------------------------------- the sums -------
# One checksum file per artifact, as HMCL publishes them, so a download can be
# verified without a table.
for artifact in "${out_dir}/${name}-linux-x64.tar.zst" "${out_dir}/HDSL-${version}-x86_64.AppImage"; do
    ( cd "${out_dir}" && sha256sum "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
done

ls -l "${out_dir}"/*.tar.zst "${out_dir}"/*.AppImage
