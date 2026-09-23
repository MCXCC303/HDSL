#!/usr/bin/env bash
#
# Builds the macOS packages Gradle does not: an HDSL.app bundle, a .dmg
# containing it, and a .zip of the same bundle. All three wrap the
# self-executing `.sh` that `makeExecutable` produces, and the bundle uses
# the same application identifier the launcher reports, so the macOS
# artifacts describe one application rather than three.
#
# The launcher needs a Java 21+ runtime, exactly as the `.deb` declares in its
# `Depends`: none of these packages bundles a JVM, which is what HMCL ships too.
#
# usage: mac-packages.sh <version> <sh-artifact> <icon.png> <output-dir> [arch]
#
# The architecture defaults to the host (`uname -m`): arm64/aarch64 becomes
# `aarch64`, x86_64/amd64 becomes `x64`. Pass it explicitly when packaging
# for the other Mac.
#

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

# ---------------------------------------------------------------- the bundle --
# A bundle is a directory with a fixed layout: the stub in MacOS, the payload
# in Java, the icon in Resources, and a plist tying them together. The stub
# runs from the user's home, as the `.deb` wrapper and the AppImage AppRun do:
# DeepSeek Harness scopes a session to the process's working directory, and
# starting inside a mounted image would file the first session under a path
# inside the mount.
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
    cp "${icon}" "${appdir}/Resources/hdsl.png"
    echo "warning: sips is unavailable, bundle carries hdsl.png instead of hdsl.icns" >&2
fi

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
exec "\$(cd "\$(dirname "\$0")/../Java" && pwd)/${name}.sh" "\$@"
EOF
chmod 0755 "${appdir}/MacOS/HDSL"

# PkgInfo is the four-letter creator stamp Finder still looks for.
printf 'APPL????' > "${appdir}/PkgInfo"
chmod 0644 "${appdir}/PkgInfo"

# ------------------------------------------------------------------ the dmg ---
# hdiutil is the system tool for this; ditto is the system tool that zips a
# bundle without breaking its resource forks, which is why neither is taken
# from PATH with a fallback.
echo "== dmg =="
dmg="${out_dir}/HDSL-${version}-${arch}.dmg"
rm -f "${dmg}"
hdiutil create -volname "HDSL" -srcfolder "${stage}" -ov -format UDZO "${dmg}"
hdiutil verify "${dmg}"

echo "== zip =="
zip_artifact="${out_dir}/HDSL-${version}-${arch}.zip"
rm -f "${zip_artifact}"
(cd "${stage}" && ditto -c -k --sequesterRsrc --keepParent HDSL.app "${zip_artifact}")

# ------------------------------------------------------------- the sums -------
# One checksum file per artifact, as the Linux packages publish them, so a
# download can be verified without a table.
for artifact in "${dmg}" "${zip_artifact}"; do
    if command -v sha256sum >/dev/null 2>&1; then
        ( cd "${out_dir}" && sha256sum "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
    else
        ( cd "${out_dir}" && shasum -a 256 "$(basename "${artifact}")" > "$(basename "${artifact}").sha256" )
    fi
done

ls -l "${dmg}" "${zip_artifact}"
