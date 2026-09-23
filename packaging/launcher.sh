#!/usr/bin/env bash
# HMCL-DSH self-executing launcher.
#
# This script is prepended to the application jar, so `"$0"` is the executable
# itself. A zip reader finds the central directory at the end of the file, so
# the jar stays loadable with this preamble in front of it.
#
# HMCL-DSH targets Linux and macOS, so unlike HMCL's launcher this one has no
# BSD or Windows branches: it looks for a JDK 21+ and runs the jar.

set -e

_HMCLDSH_REQUIRED_JAVA=21

# Resolve a java executable: an explicit override, then JAVA_HOME, then PATH.
find_java() {
    if [ -n "${HMCLDSH_JAVA_HOME:-}" ] && [ -x "${HMCLDSH_JAVA_HOME}/bin/java" ]; then
        echo "${HMCLDSH_JAVA_HOME}/bin/java"
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
        echo "HMCL-DSH 需要 Java ${_HMCLDSH_REQUIRED_JAVA} 或更高版本，但没有找到 java。" >&2
        echo "请安装 JRE ${_HMCLDSH_REQUIRED_JAVA}+，或设置 JAVA_HOME。" >&2
    else
        echo "HMCL-DSH needs Java ${_HMCLDSH_REQUIRED_JAVA} or newer, but no java was found." >&2
        echo "Install a JRE ${_HMCLDSH_REQUIRED_JAVA}+, or set JAVA_HOME." >&2
    fi
    exit 1
fi

# Warn rather than refuse when the version is only just too old: the JVM will
# produce a clearer message than this script can.
JAVA_MAJOR="$("$JAVA_EXE" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^ *java\.specification\.version *= *//p' | head -1 | cut -d. -f1)"

case "$JAVA_MAJOR" in
    ''|*[!0-9]*) ;;
    *)
        if [ "$JAVA_MAJOR" -lt "$_HMCLDSH_REQUIRED_JAVA" ]; then
            if [ -z "${LANG##zh_*}" ]; then
                echo "HMCL-DSH 需要 Java ${_HMCLDSH_REQUIRED_JAVA} 或更高版本，当前是 Java ${JAVA_MAJOR}。" >&2
            else
                echo "HMCL-DSH needs Java ${_HMCLDSH_REQUIRED_JAVA}+, found Java ${JAVA_MAJOR}." >&2
            fi
            exit 1
        fi
        ;;
esac

exec "$JAVA_EXE" -jar "$0" "$@"
