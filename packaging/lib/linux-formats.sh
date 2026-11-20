#!/usr/bin/env bash
# Expects the caller to set: version, name, desktop_id, launcher_class,
# sh_artifact, icon, out_dir, stage, repo_dir, appimagetool, arch_version,
# pkg_arch, appimage_arch, tar_artifact, arch_artifact, appimage_artifact.

write_desktop_entry() {
    local target="$1" launcher="$2"
    cat > "$target" <<EOF
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
    chmod 0644 "$target"
}

# Arch-scope package need special version.
arch_version_of() {
    case "$1" in
    *-*) printf '%s%s\n' "${1%%-*}" "$(printf '%s' "${1#*-}" | tr '[:upper:]' '[:lower:]' | tr -d '._-')" ;;
    *) printf '%s\n' "$1" ;;
    esac
}

build_tar() {
    local tree="${stage}/${name}"
    mkdir -p "$tree"
    cp "$sh_artifact" "${tree}/${name}.sh"
    chmod 0755 "${tree}/${name}.sh"
    cp "$icon" "${tree}/${desktop_id}.png"
    chmod 0644 "${tree}/${desktop_id}.png"
    copy_notices "$tree" "$repo_dir" LICENSE NOTICE README.md
    write_desktop_entry "${tree}/${desktop_id}.desktop" "${name}.sh"

    echo "== tar.zst =="
    tar --zstd -cf "$tar_artifact" -C "$stage" "$name"
    tar --zstd -tf "$tar_artifact"
}

build_arch() {
    local arch_stage="${stage}/arch"
    local installed_size
    local previous_name="hmcl-dsh"

    mkdir -p "${arch_stage}/usr/bin" "${arch_stage}/usr/share/applications" \
             "${arch_stage}/usr/share/icons/hicolor/256x256/apps" \
             "${arch_stage}/usr/share/licenses/${desktop_id}"

    cp "$sh_artifact" "${arch_stage}/usr/bin/${desktop_id}"
    chmod 0755 "${arch_stage}/usr/bin/${desktop_id}"
    cp "$icon" "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
    chmod 0644 "${arch_stage}/usr/share/icons/hicolor/256x256/apps/${desktop_id}.png"
    write_desktop_entry "${arch_stage}/usr/share/applications/${desktop_id}.desktop" "$desktop_id"
    copy_notices "${arch_stage}/usr/share/licenses/${desktop_id}" "$repo_dir" LICENSE NOTICE

    installed_size="$(du -sb "$arch_stage" | cut -f1)"
    cat > "${arch_stage}/.PKGINFO" <<EOF
pkgname = ${desktop_id}
pkgbase = ${desktop_id}
pkgver = ${arch_version}-1
pkgdesc = DeepSeek Harness launcher
url = https://github.com/
builddate = $(date +%s)
packager = HDSL contributors
size = ${installed_size}
arch = ${pkg_arch}
license = GPL-3.0-or-later
depend = java-runtime>=21
replaces = ${previous_name}
conflict = ${previous_name}
EOF

    echo "== Arch package =="
    tar --zstd -cf "$arch_artifact" -C "$arch_stage" .PKGINFO usr
    echo "arch package: ${arch_artifact}"
    tar --zstd -tf "$arch_artifact" > /dev/null
}

build_appimage() {
    local appdir="${stage}/HDSL.AppDir"
    mkdir -p "${appdir}/usr/bin"
    cp "$sh_artifact" "${appdir}/usr/bin/${desktop_id}"
    chmod 0755 "${appdir}/usr/bin/${desktop_id}"
    cp "$icon" "${appdir}/${desktop_id}.png"
    chmod 0644 "${appdir}/${desktop_id}.png"
    write_desktop_entry "${appdir}/${desktop_id}.desktop" "$desktop_id"
    copy_notices "$appdir" "$repo_dir" LICENSE NOTICE

    cat > "${appdir}/AppRun" <<EOF
#!/usr/bin/env bash
# A session is scoped to the process working directory, so start in \$HOME
# instead of inside the mounted image.
cd "\${HOME}"
exec "\${APPDIR}/usr/bin/${desktop_id}" "\$@"
EOF
    chmod 0755 "${appdir}/AppRun"

    echo "== AppImage =="
    ARCH=${appimage_arch} APPIMAGE_EXTRACT_AND_RUN=1 "$appimagetool" \
        --no-appstream \
        "$appdir" "$appimage_artifact"
}