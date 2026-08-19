#!/bin/sh

set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd -P)
repo_dir=$(CDPATH= cd "$script_dir/.." && pwd -P)
quick_start="$repo_dir/quick-start.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/sendium-quick-start-test.XXXXXX")
integration_dir=''
integration_project=''
tests_run=0

unset SENDIUM_DLR_POSTGRESQL_JDBC_URL
unset SENDIUM_DLR_POSTGRESQL_USERNAME
unset SENDIUM_DLR_POSTGRESQL_PASSWORD

cleanup() {
    if [ -n "$integration_dir" ] && [ -f "$integration_dir/compose.yml" ] && command -v docker >/dev/null 2>&1; then
        docker compose -p "$integration_project" -f "$integration_dir/compose.yml" --project-directory "$integration_dir" down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
    rm -rf "$test_root"
}
trap cleanup 0

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

pass() {
    tests_run=$((tests_run + 1))
    printf 'ok %s - %s\n' "$tests_run" "$1"
}

assert_file() {
    [ -f "$1" ] || fail "expected file: $1"
}

assert_contains() {
    expected=$1
    file=$2
    grep -F "$expected" "$file" >/dev/null 2>&1 || fail "expected '$expected' in $file"
}

assert_not_contains() {
    unexpected=$1
    file=$2
    if grep -F "$unexpected" "$file" >/dev/null 2>&1; then
        fail "did not expect '$unexpected' in $file"
    fi
}

assert_equals() {
    expected=$1
    actual=$2
    label=$3
    [ "$expected" = "$actual" ] || fail "$label: expected '$expected', got '$actual'"
}

file_mode() {
    stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"
}

credential_value() {
    credential_type=$1
    credential_key=$2
    credential_file=$3
    awk -v type="$credential_type" -v key="$credential_key" '
        $0 ~ "- type: " type { in_credential = 1; next }
        in_credential && $1 == key ":" {
            value = $0
            sub(/^[^:]*:[[:space:]]*"/, "", value)
            sub(/"[[:space:]]*$/, "", value)
            print value
            exit
        }
        in_credential && /- type:/ { exit }
    ' "$credential_file"
}

expect_failure() {
    label=$1
    output=$2
    shift 2
    if "$@" > "$output" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

sh -n "$quick_start"
pass "POSIX shell syntax"

local_dir="$test_root/local"
sh "$quick_start" --directory "$local_dir" --provider local --no-start > "$test_root/local.out" 2>&1
assert_file "$local_dir/compose.yml"
assert_file "$local_dir/.sendium.env"
assert_file "$local_dir/conf/credentials.yml"
assert_file "$local_dir/conf/smsg.properties"
assert_file "$local_dir/conf/routingTable.conf"
assert_contains '127.0.0.1:8080:8080' "$local_dir/compose.yml"
assert_contains '127.0.0.1:27777:27777' "$local_dir/compose.yml"
assert_contains 'image: postgres:17-alpine' "$local_dir/compose.yml"
assert_contains 'pg_isready' "$local_dir/compose.yml"
assert_contains 'condition: service_healthy' "$local_dir/compose.yml"
assert_contains 'postgres-data:/var/lib/postgresql/data' "$local_dir/compose.yml"
assert_contains 'postgres-data:' "$local_dir/compose.yml"
assert_not_contains 'outSms.instance.upstream' "$local_dir/conf/smsg.properties"
assert_not_contains 'upstream::default:' "$local_dir/conf/routingTable.conf"
assert_equals 600 "$(file_mode "$local_dir/.sendium.env")" ".sendium.env mode"
assert_equals 600 "$(file_mode "$local_dir/conf/credentials.yml")" "credentials.yml mode"

http_user=$(sed -n "s/^SENDIUM_HTTP_USER='\([^']*\)'$/\1/p" "$local_dir/.sendium.env")
http_password=$(sed -n "s/^SENDIUM_HTTP_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
smpp_user=$(sed -n "s/^SENDIUM_SMPP_USER='\([^']*\)'$/\1/p" "$local_dir/.sendium.env")
smpp_password=$(sed -n "s/^SENDIUM_SMPP_PASSWORD='\([A-Za-z0-9][A-Za-z0-9]*\)'$/\1/p" "$local_dir/.sendium.env")
database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
postgres_password=$(sed -n "s/^POSTGRES_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
assert_equals 'sendium-http' "$http_user" "HTTP environment username"
assert_equals 'sendium-smpp' "$smpp_user" "SMPP environment username"
assert_equals 48 "${#http_password}" "HTTP password length"
assert_equals 8 "${#smpp_password}" "SMPP password length"
assert_equals 64 "${#database_password}" "PostgreSQL password length"
assert_equals "$database_password" "$postgres_password" "PostgreSQL container password"
assert_contains "SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://postgres:5432/sendium'" "$local_dir/.sendium.env"
assert_not_contains "$database_password" "$local_dir/compose.yml"
assert_equals "$http_user" "$(credential_value HTTP systemId "$local_dir/conf/credentials.yml")" "HTTP credential username"
assert_equals "$http_password" "$(credential_value HTTP password "$local_dir/conf/credentials.yml")" "HTTP credential password"
assert_equals "$smpp_user" "$(credential_value SMPP systemId "$local_dir/conf/credentials.yml")" "SMPP credential username"
assert_equals "$smpp_password" "$(credential_value SMPP password "$local_dir/conf/credentials.yml")" "SMPP credential password"
pass "secure local-only generation"

expect_failure "non-empty target protection" "$test_root/non-empty.out" \
    sh "$quick_start" --directory "$local_dir" --provider local --no-start
assert_contains 'output directory is not empty' "$test_root/non-empty.out"
pass "non-empty target protection"

printf 'keep me\n' > "$local_dir/user-file.txt"
old_http_password=$http_password
old_database_password=$database_password
sh "$quick_start" --directory "$local_dir" --provider local --force --no-start > "$test_root/force.out" 2>&1
assert_file "$local_dir/user-file.txt"
assert_contains 'keep me' "$local_dir/user-file.txt"
assert_contains 'up -d --force-recreate' "$test_root/force.out"
http_password=$(sed -n "s/^SENDIUM_HTTP_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
[ -n "$http_password" ] || fail "forced regeneration did not produce an HTTP password"
assert_equals 48 "${#http_password}" "regenerated HTTP password length"
[ "$old_http_password" != "$http_password" ] || fail "forced regeneration did not rotate the HTTP password"
assert_equals "$old_database_password" "$database_password" "preserved PostgreSQL password"
pass "explicit regeneration preserves unrelated files"

external_dir="$test_root/external-postgresql"
SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://database.example.test:5432/sendium?sslmode=require' \
SENDIUM_DLR_POSTGRESQL_USERNAME='external-user' \
SENDIUM_DLR_POSTGRESQL_PASSWORD='external-password' \
    sh "$quick_start" --directory "$external_dir" --provider local --no-start > "$test_root/external-postgresql.out" 2>&1
assert_contains "SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://database.example.test:5432/sendium?sslmode=require'" "$external_dir/.sendium.env"
assert_contains "SENDIUM_DLR_POSTGRESQL_USERNAME='external-user'" "$external_dir/.sendium.env"
assert_contains "SENDIUM_DLR_POSTGRESQL_PASSWORD='external-password'" "$external_dir/.sendium.env"
assert_not_contains 'image: postgres:17-alpine' "$external_dir/compose.yml"
assert_not_contains 'condition: service_healthy' "$external_dir/compose.yml"
assert_not_contains 'postgres-data:' "$external_dir/compose.yml"
assert_not_contains 'external-password' "$external_dir/compose.yml"
pass "external PostgreSQL configuration"

expect_failure "partial external PostgreSQL configuration" "$test_root/partial-postgresql.out" \
    env SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://database.example.test:5432/sendium' \
        sh "$quick_start" --directory "$test_root/partial-postgresql" --provider local --no-start
assert_contains 'external PostgreSQL requires JDBC URL, username, and password' "$test_root/partial-postgresql.out"
pass "incomplete external PostgreSQL configuration"

regenerated_dir="$test_root/regenerated-upstream"
sh "$quick_start" --directory "$regenerated_dir" --provider local --no-start > "$test_root/regenerated-local.out" 2>&1
SENDIUM_UPSTREAM_USERNAME='prosms-user' \
SENDIUM_UPSTREAM_PASSWORD='pass1234' \
    sh "$quick_start" --directory "$regenerated_dir" --provider prosms --force --no-start > "$test_root/regenerated-force.out" 2>&1
assert_contains 'outSms.instance.upstream.enable = true' "$regenerated_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.type = smppclient' "$regenerated_dir/conf/smsg.properties"
assert_not_contains 'outSms.instance.upstream.enable = false' "$regenerated_dir/conf/smsg.properties"
assert_contains 'up -d --force-recreate' "$test_root/regenerated-force.out"
pass "forced regeneration requires container recreation"

pending_dir="$test_root/prosms-pending"
sh "$quick_start" --directory "$pending_dir" --provider prosms --no-start > "$test_root/prosms-pending.out" 2>&1
assert_contains 'https://prosms.gr/sms-tool/?v=2&m=8' "$test_root/prosms-pending.out"
assert_not_contains 'outSms.instance.upstream' "$pending_dir/conf/smsg.properties"
assert_not_contains 'upstream::default:' "$pending_dir/conf/routingTable.conf"
pass "pending ProSMS approval remains local-only"

prosms_dir="$test_root/prosms-approved"
SENDIUM_UPSTREAM_USERNAME='prosms-user' \
SENDIUM_UPSTREAM_PASSWORD='pass1234' \
    sh "$quick_start" --directory "$prosms_dir" --provider prosms --no-start > "$test_root/prosms-approved.out" 2>&1
assert_contains 'outSms.instance.upstream.host = smpp.prosms.gr' "$prosms_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.port = 2775' "$prosms_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.username = prosms-user' "$prosms_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.password = pass1234' "$prosms_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.ssl = false' "$prosms_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.connections.transceivers = 1' "$prosms_dir/conf/smsg.properties"
assert_contains 'upstream::default:' "$prosms_dir/conf/routingTable.conf"
pass "approved ProSMS provider profile"

custom_dir="$test_root/custom"
SENDIUM_UPSTREAM_HOST='smpp.example.test' \
SENDIUM_UPSTREAM_PORT='3550' \
SENDIUM_UPSTREAM_USERNAME='custom-user' \
SENDIUM_UPSTREAM_PASSWORD='pass1234' \
SENDIUM_UPSTREAM_TLS='true' \
    sh "$quick_start" --directory "$custom_dir" --provider custom --no-start > "$test_root/custom.out" 2>&1
assert_contains 'outSms.instance.upstream.host = smpp.example.test' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.port = 3550' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.username = custom-user' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.password = pass1234' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.ssl = true' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.connections.transceivers = 1' "$custom_dir/conf/smsg.properties"
assert_contains 'outSms.instance.upstream.connections.transmitters = 0' "$custom_dir/conf/smsg.properties"
pass "custom SMPP provider profile"

invalid_dir="$test_root/invalid"
expect_failure "oversized SMPP password" "$test_root/password.out" \
    env SENDIUM_UPSTREAM_USERNAME='prosms-user' \
        SENDIUM_UPSTREAM_PASSWORD='password9' \
        sh "$quick_start" --directory "$invalid_dir" --provider prosms --no-start
assert_contains 'password must not exceed 8 bytes' "$test_root/password.out"
pass "invalid SMPP provider input"

multiline_host=$(printf 'smpp.example.test\noutSms.instance.smpp.enable = false')
expect_failure "multiline SMPP host" "$test_root/multiline-host.out" \
    env SENDIUM_UPSTREAM_HOST="$multiline_host" \
        SENDIUM_UPSTREAM_USERNAME='custom-user' \
        SENDIUM_UPSTREAM_PASSWORD='pass1234' \
        sh "$quick_start" --directory "$test_root/multiline-host" --provider custom --no-start
assert_contains 'SMPP host cannot contain control characters' "$test_root/multiline-host.out"
pass "multiline SMPP property input"

destination_dir="$test_root/destination-guard"
mkdir -p "$destination_dir/compose.yml"
expect_failure "generated destination guard" "$test_root/destination.out" \
    sh "$quick_start" --directory "$destination_dir" --provider local --force --no-start
assert_contains 'generated file path is not a regular file' "$test_root/destination.out"
pass "generated destination guard"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    docker compose -f "$local_dir/compose.yml" --project-directory "$local_dir" config --quiet
    docker compose -f "$prosms_dir/compose.yml" --project-directory "$prosms_dir" config --quiet
    docker compose -f "$custom_dir/compose.yml" --project-directory "$custom_dir" config --quiet
    docker compose -f "$external_dir/compose.yml" --project-directory "$external_dir" config --quiet
    pass "Docker Compose parsing"
else
    printf 'skip - Docker Compose parsing (Docker Compose unavailable)\n'
fi

if [ -n "${SENDIUM_TEST_IMAGE-}" ]; then
    integration_dir="$test_root/integration"
    integration_project="sendium-quickstart-test-$$"
    COMPOSE_PROJECT_NAME="$integration_project" sh "$quick_start" \
        --directory "$integration_dir" \
        --provider local \
        --image "$SENDIUM_TEST_IMAGE" > "$test_root/integration.out" 2>&1
    assert_contains 'Sendium is ready.' "$test_root/integration.out"
    assert_contains 'Follow live logs with:' "$test_root/integration.out"
    integration_http_user=$(sed -n "s/^SENDIUM_HTTP_USER='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    integration_http_password=$(sed -n "s/^SENDIUM_HTTP_PASSWORD='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    integration_database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    http_status=$(curl -sS -o "$test_root/sendsms.out" -w '%{http_code}' -G http://127.0.0.1:8080/sendsms \
        --data-urlencode "username=$integration_http_user" \
        --data-urlencode "password=$integration_http_password" \
        --data-urlencode 'from=Sendium' \
        --data-urlencode 'to=306910000000' \
        --data-urlencode 'text=Quick-start integration test')
    assert_equals 202 "$http_status" "generated HTTP credential submission"
    grep -E '^[0-9a-f-]{36}$' "$test_root/sendsms.out" >/dev/null 2>&1 || fail "expected gateway UUID response"
    pass "real container startup, readiness, and HTTP submission"

    original_container_id=$(docker compose -p "$integration_project" -f "$integration_dir/compose.yml" --project-directory "$integration_dir" ps -q sendium)
    SENDIUM_UPSTREAM_HOST='127.0.0.1' \
    SENDIUM_UPSTREAM_PORT='1' \
    SENDIUM_UPSTREAM_USERNAME='test-user' \
    SENDIUM_UPSTREAM_PASSWORD='pass1234' \
    COMPOSE_PROJECT_NAME="$integration_project" sh "$quick_start" \
        --directory "$integration_dir" \
        --provider custom \
        --image "$SENDIUM_TEST_IMAGE" \
        --force > "$test_root/integration-force.out" 2>&1
    recreated_container_id=$(docker compose -p "$integration_project" -f "$integration_dir/compose.yml" --project-directory "$integration_dir" ps -q sendium)
    recreated_database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    [ -n "$recreated_container_id" ] || fail "forced regeneration did not leave a running container"
    [ "$original_container_id" != "$recreated_container_id" ] || fail "forced regeneration did not recreate the container"
    assert_equals "$integration_database_password" "$recreated_database_password" "integration PostgreSQL password"
    assert_contains 'Recreating Sendium' "$test_root/integration-force.out"
    attempt=0
    while ! grep -F 'Starting: (smppclient.upstream)' "$integration_dir/logs/smsg.log" >/dev/null 2>&1 && [ "$attempt" -lt 10 ]; do
        attempt=$((attempt + 1))
        sleep 1
    done
    assert_contains 'Starting: (smppclient.upstream)' "$integration_dir/logs/smsg.log"
    assert_not_contains "Cannot start worker 'upstream': Missing '.type' property" "$integration_dir/logs/smsg.log"
    pass "forced regeneration recreates the running container"
else
    printf 'skip - real container startup (set SENDIUM_TEST_IMAGE to enable)\n'
fi

printf 'Completed %s quick-start tests.\n' "$tests_run"
