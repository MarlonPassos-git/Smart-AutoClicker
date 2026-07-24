#!/usr/bin/env bash

set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly build_file="$project_root/smartautoclicker/build.gradle.kts"
readonly changelog_file="$project_root/CHANGELOG.md"
readonly semantic_version_pattern='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'

fail_release_validation() {
    local message="$1"
    echo "Erro de validação da versão: $message" >&2
    exit 1
}

report_unexpected_error() {
    local exit_code="$?"
    local line_number="$1"
    echo "Erro inesperado na linha '$line_number'; esperado comando com saída zero, recebido '$exit_code'." >&2
    exit "$exit_code"
}

trap 'report_unexpected_error "$LINENO"' ERR

read_gradle_value() {
    local source_file="$1"
    local property_name="$2"
    sed -nE \
        "s/^[[:space:]]*${property_name} = \"?([^\"[:space:]]+)\"?/\1/p" \
        "$source_file"
}

validate_requested_version() {
    local requested_version="$1"
    if [[ "$requested_version" =~ $semantic_version_pattern ]]; then
        return
    fi

    fail_release_validation \
        "versão recebida '$requested_version'; esperado formato como '4.0.0-beta05' ou '4.0.0'."
}

validate_gradle_version() {
    local requested_version="$1"
    local configured_version
    configured_version="$(read_gradle_value "$build_file" "versionName")"

    if [[ "$configured_version" == "$requested_version" ]]; then
        return
    fi

    fail_release_validation \
        "versionName '$configured_version'; esperado '$requested_version'."
}

find_previous_version_code() {
    local current_code="$1"
    local commit_hash previous_code
    while IFS= read -r commit_hash; do
        previous_code="$(git -C "$project_root" show \
            "$commit_hash:smartautoclicker/build.gradle.kts" |
            sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)/\1/p')"
        [[ "$previous_code" =~ ^[0-9]+$ ]] || continue
        [[ "$previous_code" == "$current_code" ]] && continue
        echo "$previous_code ${commit_hash:0:8}"
        return 0
    done < <(git -C "$project_root" log --format='%H' -- \
        smartautoclicker/build.gradle.kts)
    return 0
}

validate_version_code_increment() {
    local current_code previous_code previous_revision previous_version
    current_code="$(read_gradle_value "$build_file" "versionCode")"
    [[ "$current_code" =~ ^[0-9]+$ ]] ||
        fail_release_validation \
            "versionCode '$current_code'; esperado número inteiro positivo."
    previous_version="$(find_previous_version_code "$current_code")"
    [[ -n "$previous_version" ]] || return 0

    read -r previous_code previous_revision <<< "$previous_version"
    (( current_code > previous_code )) && return
    fail_release_validation \
        "versionCode '$current_code'; esperado valor maior que '$previous_code' da revisão '$previous_revision'."
}

write_changelog_section() {
    local requested_version="$1"
    local output_file="$2"
    local expected_header="## [$requested_version] - "

    awk -v header="$expected_header" '
        index($0, header) == 1 { found = 1; next }
        found && /^## \[/ { exit }
        found && /^\[Unreleased\]:/ { exit }
        found { print }
        END { if (!found) exit 2 }
    ' "$changelog_file" > "$output_file" ||
        fail_release_validation \
            "seção ausente para '$requested_version'; esperado cabeçalho '$expected_headerAAAA-MM-DD'."

    grep -q '[^[:space:]]' "$output_file" ||
        fail_release_validation \
            "seção vazia para '$requested_version'; esperado ao menos um item de mudança."
}

main() {
    local requested_version="${1:-}"
    local output_file="${2:-}"
    [[ -n "$requested_version" ]] ||
        fail_release_validation "versão ausente; esperado primeiro argumento com a versão."
    [[ -n "$output_file" ]] ||
        fail_release_validation "arquivo de saída ausente; esperado segundo argumento com o caminho."

    validate_requested_version "$requested_version"
    validate_gradle_version "$requested_version"
    validate_version_code_increment
    write_changelog_section "$requested_version" "$output_file"
}

main "$@"
