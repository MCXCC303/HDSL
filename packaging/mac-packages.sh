#!/usr/bin/env bash

set -euo pipefail

version="${1:?usage: mac-packages.sh <version> <sh-artifact> <icon.png> <output-dir> [arch]}"
sh_artifact="${2:?}"
icon="${3:?}"
out_dir="${4:?}"
arch_arg="${5:-}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"

name="hdsl-${version}"
bundle_id="run.hdsl.HDSL"
launcher_class="org.jackhuang.hmcl.Main"

for file in "${sh_artifact}" "${icon}"; do
    [ -f "${file}" ] || { echo "error: ${file} does not exist" >&2; exit 1; }
done

if [ -n "${arch_arg}" ]; then
    case "${arch_arg}" in
        aarch64|arm64) arch="aarch64" ;;
        x64|x86_64|amd64) arch="x64" ;;
        *) echo "error: unknown arch ${arch_arg} (want aarch64 or x64)" >&2; exit 1 ;;
    esac
else
    case "$(uname -m)" in
        arm64|aarch64) arch="aarch64" ;;
        x86_64|amd64) arch="x64" ;;
        *) echo "error: unknown host arch $(uname -m)" >&2; exit 1 ;;
    esac
fi

mkdir -p "${out_dir}"
out_dir="$(cd "${out_dir}" && pwd)"
stage="$(mktemp -d)"
trap 'rm -rf "${stage}"' EXIT

appdir="${stage}/HDSL.app/Contents"
mkdir -p "${appdir}/MacOS" "${appdir}/Java" "${appdir}/Resources"
cp "${sh_artifact}" "${appdir}/Java/${name}.sh"
chmod 0755 "${appdir}/Java/${name}.sh"
for file in LICENSE NOTICE; do
    [ -f "${repo_dir}/${file}" ] && cp "${repo_dir}/${file}" "${stage}/" && chmod 0644 "${stage}/${file}"
done

icon_file=""
if command -v sips >/dev/null 2>&1 && sips -s format icns "${icon}" --out "${appdir}/Resources/hdsl.icns" >/dev/null 2>&1; then
    icon_file="hdsl.icns"
else
    echo "warning: sips is unavailable, bundle carries hdsl.png instead of hdsl.icns" >&2
fi

cp "${icon}" "${appdir}/Resources/hdsl.png"
chmod 0644 "${appdir}/Resources/hdsl.png"

if [ -n "${icon_file}" ]; then
    icon_key="	<key>CFBundleIconFile</key>
	<string>${icon_file}</string>"
else
    icon_key=""
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
# Refuse to start anywhere but the user's home, for the reason above.
cd "\${HOME}"
APP_ROOT="\$(cd "\$(dirname "\$0")/.." && pwd)"
# The launcher stub names the dock tile from this image at JVM startup; see it
# for why the name and icon cannot wait for a window.
export HDSL_DOCK_ICON="\${APP_ROOT}/Resources/hdsl.png"
exec "\${APP_ROOT}/Java/${name}.sh" "\$@"
EOF
chmod 0755 "${appdir}/MacOS/HDSL"

printf 'APPL????' > "${appdir}/PkgInfo"
chmod 0644 "${appdir}/PkgInfo"

echo "== dmg =="
dmg="${out_dir}/HDSL-${version}-${arch}.dmg"
rm -f "${dmg}"
hdiutil create -volname "HDSL" -srcfolder "${stage}" -ov -format UDZO "${dmg}"
hdiutil verify "${dmg}"

echo "== zip =="
zip_artifact="${out_dir}/HDSL-${version}-${arch}.zip"
rm -f "${zip_artifact}"
(cd "${stage}" && ditto -c -k --sequesterRsrc --keepParent HDSL.app "${zip_artifact}")

for artifact in "${dmg}" "${zip_artifact}"; do
    if command -v sha256sum >/dev/null 2>&1; then
        ( cd "${out_dir}" && sha256sum "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
    else
        ( cd "${out_dir}" && shasum -a 256 "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
    fi
done

ls -l "${dmg}" "${zip_artifact}"
