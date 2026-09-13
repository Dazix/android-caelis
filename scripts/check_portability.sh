#!/usr/bin/env bash
#
# Enforces repository portability gates:
# 1. No machine-specific paths or personal values in tracked files
# 2. Local machine configurations remain untracked
# 3. scripts/env.example.sh template is present
#
# Usage:
#   scripts/check_portability.sh [--all | --machine-specific | --untracked-config]

set -eu

MODE="${1:---all}"

check_machine_specific() {
    local hits
    hits=$(git ls-files -z \
        | grep -zv -E '^gradlew(\.bat)?$' \
        | grep -zv -E '^scripts/check_portability\.sh$' \
        | xargs -0 grep -InE '/home/[a-z]|/Users/[a-z]|/snap/|--user [0-9]' || true)

    if [ -n "$hits" ]; then
        echo "$hits" >&2
        echo >&2
        echo "Machine-specific values must not be committed. Put them in scripts/env.local.sh" >&2
        echo "(gitignored) and reference \$ADB / \$JAVA_HOME / \$ANDROID_USER_ID instead." >&2
        exit 1
    fi
    echo "no machine-specific values found"
}

check_untracked_config() {
    local tracked
    tracked=$(git ls-files -- local.properties scripts/env.local.sh .device_snapshot)
    if [ -n "$tracked" ]; then
        echo "these belong to one machine and must stay untracked:" >&2
        echo "$tracked" >&2
        exit 1
    fi
    test -f scripts/env.example.sh || { echo "scripts/env.example.sh is missing - it is the template the scripts point at" >&2; exit 1; }
    echo "local configuration is untracked and the template is present"
}

case "$MODE" in
    --machine-specific)
        check_machine_specific
        ;;
    --untracked-config)
        check_untracked_config
        ;;
    --all)
        check_machine_specific
        check_untracked_config
        ;;
    *)
        echo "Unknown option: $MODE. Supported options: --all, --machine-specific, --untracked-config" >&2
        exit 1
        ;;
esac
