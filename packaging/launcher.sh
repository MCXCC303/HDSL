#!/usr/bin/env bash
# HDSL self-executing launcher.

set -e

_HDSL_REQUIRED_JAVA=21

# Resolve a java executable: an explicit override, then JAVA_HOME, then PATH.
find_java() {
    if [ -n "${HDSL_JAVA_HOME:-}" ] && [ -x "${HDSL_JAVA_HOME}/bin/java" ]; then
        echo "${HDSL_JAVA_HOME}/bin/java"
        return 0
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
        return 0
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    return 1
}

JAVA_EXE="$(find_java || true)"

if [ -z "$JAVA_EXE" ]; then
    if [ -z "${LANG##zh_*}" ]; then
        echo "HDSL 需要 Java ${_HDSL_REQUIRED_JAVA} 或更高版本，但没有找到 java。" >&2
        echo "请安装 JRE ${_HDSL_REQUIRED_JAVA}+，或设置 JAVA_HOME。" >&2
    else
        echo "HDSL needs Java ${_HDSL_REQUIRED_JAVA} or newer, but no java was found." >&2
        echo "Install a JRE ${_HDSL_REQUIRED_JAVA}+, or set JAVA_HOME." >&2
    fi
    exit 1
fi

JAVA_INFO="$("$JAVA_EXE" -XshowSettings:properties -XX:+PrintFlagsFinal -version 2>&1 | cat)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_INFO" \
    | sed -n 's/^ *java\.specification\.version *= *//p' | head -1 | cut -d. -f1)"

case "$JAVA_MAJOR" in
    ''|*[!0-9]*) ;;
    *)
        if [ "$JAVA_MAJOR" -lt "$_HDSL_REQUIRED_JAVA" ]; then
            if [ -z "${LANG##zh_*}" ]; then
                echo "HDSL 需要 Java ${_HDSL_REQUIRED_JAVA} 或更高版本，当前是 Java ${JAVA_MAJOR}。" >&2
            else
                echo "HDSL needs Java ${_HDSL_REQUIRED_JAVA}+, found Java ${JAVA_MAJOR}." >&2
            fi
            exit 1
        fi
        ;;
esac

DOCK_ARGS=()
if [ "$(uname -s)" = "Darwin" ]; then
    DOCK_ARGS+=("-Xdock:name=HDSL")
    if [ -n "${HDSL_DOCK_ICON:-}" ] && [ -f "${HDSL_DOCK_ICON}" ]; then
        DOCK_ARGS+=("-Xdock:icon=${HDSL_DOCK_ICON}")
    fi
fi

# CVE-2026-114938
BASE64_ARGS=()
if printf '%s\n' "$JAVA_INFO" | grep -q 'UseBASE64Intrinsics'; then
    BASE64_ARGS+=("-XX:-UseBASE64Intrinsics")
fi

exec "$JAVA_EXE" "${DOCK_ARGS[@]}" "${BASE64_ARGS[@]}" -jar "$0" "$@"
