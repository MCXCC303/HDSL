#!/usr/bin/env bash
# Expects the caller to set: version, name, bundle_id, sh_artifact, icon,
# out_dir, stage, repo_dir, arch, dmg_artifact, zip_artifact.

build_bundle() {
    local appdir="${stage}/HDSL.app/Contents"
    local icon_file="" icon_key=""

    mkdir -p "${appdir}/MacOS" "${appdir}/Java" "${appdir}/Resources"
    cp "$sh_artifact" "${appdir}/Java/${name}.sh"
    chmod 0755 "${appdir}/Java/${name}.sh"
    copy_notices "$stage" "$repo_dir" LICENSE NOTICE

    if command -v sips >/dev/null 2>&1 && sips -s format icns "$icon" --out "${appdir}/Resources/hdsl.icns" >/dev/null 2>&1; then
        icon_file="hdsl.icns"
    else
        echo "warning: sips is unavailable, bundle carries hdsl.png instead of hdsl.icns" >&2
    fi
    cp "$icon" "${appdir}/Resources/hdsl.png"
    chmod 0644 "${appdir}/Resources/hdsl.png"
    if [ -n "$icon_file" ]; then
        icon_key="	<key>CFBundleIconFile</key>
	<string>${icon_file}</string>"
    fi

    cat > "${appdir}/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key>
	<string>HDSL</string>
	<key>CFBundleDisplayName</key>
	<string>Hello DeepSeek Launcher</string>
	<key>CFBundleIdentifier</key>
	<string>${bundle_id}</string>
	<key>CFBundleVersion</key>
	<string>${version}</string>
	<key>CFBundleShortVersionString</key>
	<string>${version}</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleExecutable</key>
	<string>HDSL</string>
${icon_key}
	<key>NSHighResolutionCapable</key>
	<true/>
	<key>LSMinimumSystemVersion</key>
	<string>12.0</string>
</dict>
</plist>
EOF
    chmod 0644 "${appdir}/Info.plist"

    cat > "${appdir}/MacOS/HDSL" <<EOF
#!/usr/bin/env bash
# A session is scoped to the process working directory, so start in \$HOME
# instead of inside the mounted image.
cd "\${HOME}"
APP_ROOT="\$(cd "\$(dirname "\$0")/.." && pwd)"
# The launcher stub sets the dock tile name and icon at JVM startup.
export HDSL_DOCK_ICON="\${APP_ROOT}/Resources/hdsl.png"
exec "\${APP_ROOT}/Java/${name}.sh" "\$@"
EOF
    chmod 0755 "${appdir}/MacOS/HDSL"

    printf 'APPL????' > "${appdir}/PkgInfo"
    chmod 0644 "${appdir}/PkgInfo"
}

build_dmg() {
    command -v hdiutil >/dev/null 2>&1 || die "hdiutil is required to build the dmg"
    echo "== dmg =="
    rm -f "$dmg_artifact"
    hdiutil create -volname "HDSL" -srcfolder "$stage" -ov -format UDZO "$dmg_artifact"
    hdiutil verify "$dmg_artifact"
}

build_zip() {
    command -v ditto >/dev/null 2>&1 || die "ditto is required to build the zip"
    echo "== zip =="
    rm -f "$zip_artifact"
    ( cd "$stage" && ditto -c -k --sequesterRsrc --keepParent HDSL.app "$zip_artifact" )
}