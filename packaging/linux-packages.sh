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

# ------------------------------------------------------- the Arch package ---
# A pacman package, which is a different thing from the tar.zst above and has to be built
# separately: `pacman -U` reads `.PKGINFO` out of the archive and refuses anything without it, so a
# plain tarball can never be installed however it is named. The two exist because they answer
# different questions — one is "unpack this anywhere", the other is "install this on Arch".
#
# Built by hand rather than with `makepkg`, which would require a PKGBUILD and a build directory and
# would rebuild what is already built. What pacman needs is a zstd tarball whose members are the
# paths the files will be installed to, plus `.PKGINFO`.
arch_stage="${stage}/arch"
mkdir -p "${arch_stage}/usr/bin" "${arch_stage}/usr/share/applications" \
         "${arch_stage}/usr/share/icons/hicolor/256x256/apps" \
         "${arch_stage}/usr/share/licenses/${desktop_id}"

cp "${sh_artifact}" "${arch_stage}/usr/bin/${desktop_id}"
chmod 0755 "${arch_stage}/usr/bin/${desktop_id}"
cp "${icon}" "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
chmod 0644 "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
write_desktop_entry "${arch_stage}/usr/share/applications/${desktop_id}.desktop" "${desktop_id}"
# The launcher is run from the user's home, not from `/usr`: DeepSeek Harness scopes a session to the
# process's working directory, and a launcher started inside a package directory would file the first
# session under a path that belongs to the package.
for file in LICENSE NOTICE; do
    [ -f "${repo_dir}/${file}" ] && cp "${repo_dir}/${file}" "${arch_stage}/usr/share/licenses/${desktop_id}/" \
        && chmod 0644 "${arch_stage}/usr/share/licenses/${desktop_id}/${file}"
done

# `.PKGINFO` is the whole of what pacman reads first, and two of its fields have a shape it
# enforces rather than merely reads:
#
# - `pkgver` must be `<version>-<pkgrel>`. A bare `0.1.0` is refused with "invalid package version";
#   the release number is what distinguishes a repackaging of the same upstream version, and 1 is the
#   first packaging of this one.
# - `size` is the installed size in bytes, which pacman shows. A wrong one is reported rather than
#   fatal, but there is no reason to write a wrong one.
installed_size="$(du -sb "${arch_stage}" | cut -f1)"
cat > "${arch_stage}/.PKGINFO" <<EOF
pkgname = ${desktop_id}
pkgbase = ${desktop_id}
pkgver = ${version}-1
pkgdesc = DeepSeek Harness launcher
url = https://github.com/
builddate = $(date +%s)
packager = HMCL-DSH contributors
size = ${installed_size}
arch = x86_64
license = GPL-3.0-or-later
depend = java-runtime>=21
EOF

# The licence files are already in the tree, so the members are the paths plus `.PKGINFO`, and
# `.PKGINFO` comes first because that is the order pacman's own packages use.
arch_pkg="${out_dir}/${desktop_id}-${version}-1-x86_64.pkg.tar.zst"
tar --zstd -cf "${arch_pkg}" -C "${arch_stage}" .PKGINFO usr
echo "arch package: ${arch_pkg}"
tar --zstd -tf "${arch_pkg}" > /dev/null

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
for artifact in "${out_dir}/${name}-linux-x64.tar.zst" \
                "${out_dir}/${desktop_id}-${version}-1-x86_64.pkg.tar.zst" \
                "${out_dir}/HDSL-${version}-x86_64.AppImage"; do
    ( cd "${out_dir}" && sha256sum "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
done

ls -l "${out_dir}"/*.tar.zst "${out_dir}"/*.AppImage
