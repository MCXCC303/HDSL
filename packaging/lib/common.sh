#!/usr/bin/env bash

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage_error() {
    printf 'error: %s\n' "$*" >&2
    printf 'Try --help for usage.\n' >&2
    exit 2
}

require_file() {
    [ -f "$1" ] || die "$1 does not exist"
}

require_value() { # option count
    [ "$2" -ge 2 ] || usage_error "$1 needs a value"
}

# Reads the caller's global `formats`.
want() {
    case ",${formats}," in
        *",$1,"*) return 0 ;;
    esac
    return 1
}

validate_formats() {
    local list="$1"; shift
    local format candidate allowed

    case "$list" in
        ''|,*|*,|*,,*) usage_error "invalid --formats value: '$list'" ;;
    esac

    local IFS=,
    local rest="${list},"
    while [ -n "$rest" ]; do
        format="${rest%%,*}"
        rest="${rest#*,}"
        allowed=0
        for candidate in "$@"; do
            if [ "$format" = "$candidate" ]; then
                allowed=1
                break
            fi
        done
        [ "$allowed" -eq 1 ] || usage_error "unknown format '$format' (choose from: $*)"
    done
}

copy_notices() {
    local destination="$1" repo="$2"; shift 2
    local file
    for file in "$@"; do
        if [ -f "${repo}/${file}" ]; then
            cp "${repo}/${file}" "${destination}/${file}"
            chmod 0644 "${destination}/${file}"
        fi
    done
}

write_checksum() {
    local artifact="$1" name sum
    name="$(basename "$artifact")"
    if command -v sha256sum >/dev/null 2>&1; then
        sum="$(sha256sum "$artifact" | cut -d' ' -f1)"
    elif command -v shasum >/dev/null 2>&1; then
        sum="$(shasum -a 256 "$artifact" | cut -d' ' -f1)"
    else
        die "sha256sum or shasum is required to write ${name}.sha256"
    fi
    printf '%s  %s\n' "$sum" "$name" > "${artifact}.sha256"
}

finish_artifact() {
    local with_sums="$1" artifact="$2"
    [ "$with_sums" -eq 1 ] || return 0
    if [ -f "$artifact" ]; then
        write_checksum "$artifact"
    else
        rm -f "${artifact}.sha256"
        printf 'no checksum for %s: it was not built\n' "$(basename "$artifact")"
    fi
}

stage_init() {
    stage="$(mktemp -d)"
    trap 'rm -rf "${stage}"' EXIT
}