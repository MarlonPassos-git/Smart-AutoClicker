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

find_previous_release_tag() {
    local requested_version="$1"
    local release_tag
    while IFS= read -r release_tag; do
        [[ "$release_tag" == "$requested_version" ]] && continue
        echo "$release_tag"
        return
    done < <(git -C "$project_root" tag --list '[0-9]*' --sort=-version:refname)
}

validate_version_code_increment() {
    local requested_version="$1"
    local current_code previous_code previous_tag
    current_code="$(read_gradle_value "$build_file" "versionCode")"
    previous_tag="$(find_previous_release_tag "$requested_version")"
    [[ -n "$previous_tag" ]] || return

    previous_code="$(git -C "$project_root" show \
        "$previous_tag:smartautoclicker/build.gradle.kts" |
        sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)/\1/p')"
    (( current_code > previous_code )) && return
    fail_release_validation \
        "versionCode '$current_code'; esperado valor maior que '$previous_code' da versão '$previous_tag'."
}

write_changelog_section() {
    local requested_version="$1"
    local output_file="$2"
    local expected_header="## [$requested_version] - "

    awk -v header="$expected_header" '
        index($0, header) == 1 { found = 1; next }
        found && /^## \[/ { exit }
        found && /^\[Não lançado\]:/ { exit }
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
    validate_version_code_increment "$requested_version"
    write_changelog_section "$requested_version" "$output_file"
}

main "$@"
