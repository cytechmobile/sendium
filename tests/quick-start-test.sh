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
    grep -F -- "$expected" "$file" >/dev/null 2>&1 || fail "expected '$expected' in $file"
}

assert_not_contains() {
    unexpected=$1
    file=$2
    if grep -F -- "$unexpected" "$file" >/dev/null 2>&1; then
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
http_api_key=$(sed -n "s/^SENDIUM_HTTP_API_KEY='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
smpp_user=$(sed -n "s/^SENDIUM_SMPP_USER='\([^']*\)'$/\1/p" "$local_dir/.sendium.env")
smpp_password=$(sed -n "s/^SENDIUM_SMPP_PASSWORD='\([A-Za-z0-9][A-Za-z0-9]*\)'$/\1/p" "$local_dir/.sendium.env")
database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
postgres_password=$(sed -n "s/^POSTGRES_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
assert_equals 'sendium-http' "$http_user" "HTTP environment username"
assert_equals 'sendium-smpp' "$smpp_user" "SMPP environment username"
assert_equals 48 "${#http_password}" "HTTP password length"
assert_equals 64 "${#http_api_key}" "native HTTP API key length"
assert_contains "apiKey: \"$http_api_key\"" "$local_dir/conf/credentials.yml"
assert_equals 2 "$(grep -c 'accountId: "quickstart-http"' "$local_dir/conf/credentials.yml")" "shared native and Kannel account"
assert_contains '/api/v1/messages' "$test_root/local.out"
assert_contains 'Authorization: Bearer ${SENDIUM_HTTP_API_KEY}' "$test_root/local.out"
assert_not_contains "$http_api_key" "$test_root/local.out"
assert_not_contains "$http_api_key" "$local_dir/compose.yml"
assert_not_contains "$http_api_key" "$local_dir/conf/smsg.properties"
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

monitoring_dir="$test_root/monitoring"
sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --no-start > "$test_root/monitoring.out" 2>&1
assert_file "$monitoring_dir/monitoring/grafana/dashboards/sms-operations.json"
cmp "$repo_dir/monitoring/grafana/dashboards/sms-operations.json" "$monitoring_dir/monitoring/grafana/dashboards/sms-operations.json" || fail "SMS dashboard differs"
cmp "$repo_dir/monitoring/grafana/dashboards/application-health.json" "$monitoring_dir/monitoring/grafana/dashboards/application-health.json" || fail "health dashboard differs"
assert_contains 'sendium:8080' "$monitoring_dir/monitoring/prometheus.yml"
assert_contains 'sendium-prometheus' "$monitoring_dir/monitoring/grafana/provisioning/datasources/prometheus.yml"
assert_contains '127.0.0.1:3000:3000' "$monitoring_dir/compose.yml"
assert_contains '127.0.0.1:9090:9090' "$monitoring_dir/compose.yml"
assert_contains 'grafana-data:/var/lib/grafana' "$monitoring_dir/compose.yml"
assert_contains 'prometheus-data:/prometheus' "$monitoring_dir/compose.yml"
assert_not_contains 'grafana' "$local_dir/compose.yml"
grafana_password=$(sed -n "s/^GF_SECURITY_ADMIN_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$monitoring_dir/.grafana.env")
assert_equals 64 "${#grafana_password}" "Grafana password length"
assert_equals 600 "$(file_mode "$monitoring_dir/.grafana.env")" "Grafana secret permissions"
assert_equals 644 "$(file_mode "$monitoring_dir/monitoring/prometheus.yml")" "Prometheus config permissions"
assert_not_contains "$grafana_password" "$monitoring_dir/.sendium.env"
assert_not_contains "$grafana_password" "$monitoring_dir/compose.yml"
assert_not_contains "$grafana_password" "$test_root/monitoring.out"
pass "monitoring generation reuses dashboards with isolated credentials and loopback ports"
SENDIUM_MONITORING_REF=ignored-test sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --no-start --force > "$test_root/override.out" 2>&1
assert_contains 'SENDIUM_MONITORING_REF is ignored' "$test_root/override.out"
pass "explicit asset override reports local checkout precedence"

sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --no-start --force > "$test_root/monitoring-rerun.out" 2>&1
assert_contains "$grafana_password" "$monitoring_dir/.grafana.env"
sh "$quick_start" --directory "$monitoring_dir" --provider local --no-start --force > "$test_root/monitoring-disabled.out" 2>&1
assert_file "$monitoring_dir/monitoring/source.txt"
assert_contains "$grafana_password" "$monitoring_dir/.grafana.env"
assert_not_contains 'grafana' "$monitoring_dir/compose.yml"
sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --no-start --force > "$test_root/monitoring-enabled.out" 2>&1
assert_contains "$grafana_password" "$monitoring_dir/.grafana.env"
cp "$monitoring_dir/compose.yml" "$test_root/monitoring-compose.saved"
mv "$monitoring_dir/.grafana.env" "$test_root/grafana.saved"
expect_failure "lost Grafana credentials" "$test_root/grafana-missing.out" \
    sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --no-start --force
cmp "$test_root/monitoring-compose.saved" "$monitoring_dir/compose.yml" || fail "failed credential recovery changed Compose"
mv "$test_root/grafana.saved" "$monitoring_dir/.grafana.env"
pass "monitoring credentials survive reruns and disabling; missing credentials fail closed"

# A standalone copy must download all assets from a single explicit reference.
mkdir -p "$test_root/standalone" "$test_root/download-bin"
cp "$quick_start" "$test_root/standalone/quick-start.sh"
cat > "$test_root/download-bin/curl" <<'EOF'
#!/bin/sh
url=''
destination=''
while [ "$#" -gt 0 ]; do
    case "$1" in
        https://*) url=$1; shift ;;
        -o) destination=$2; shift 2 ;;
        *) shift ;;
    esac
done
[ "${FAIL_MONITORING_DOWNLOAD:-false}" != true ] || exit 22
printf '%s\n' "$url" >> "$DOWNLOAD_TRACE"
asset=${url#*/monitoring/}
cp "$MONITORING_FIXTURES/$asset" "$destination"
EOF
cat > "$test_root/download-bin/docker" <<'EOF'
#!/bin/sh
echo "Docker must not run during generate-only" >&2
exit 99
EOF
chmod +x "$test_root/download-bin/curl" "$test_root/download-bin/docker"
PATH="$test_root/download-bin:$PATH" MONITORING_FIXTURES="$repo_dir/monitoring" \
    DOWNLOAD_TRACE="$test_root/download.trace" SENDIUM_MONITORING_REF=v-test \
    sh "$test_root/standalone/quick-start.sh" --directory "$test_root/downloaded" --provider local --monitoring --no-start > "$test_root/download.out" 2>&1
assert_equals 4 "$(wc -l < "$test_root/download.trace" | tr -d ' ')" "download count"
assert_equals 4 "$(grep -c '/v-test/monitoring/' "$test_root/download.trace")" "consistent asset reference"
cmp "$monitoring_dir/monitoring/grafana/dashboards/sms-operations.json" "$test_root/downloaded/monitoring/grafana/dashboards/sms-operations.json" || fail "downloaded asset differs"
cp "$test_root/downloaded/compose.yml" "$test_root/downloaded-compose.saved"
cp "$test_root/downloaded/.sendium.env" "$test_root/downloaded-env.saved"
expect_failure "asset download failure" "$test_root/download-failure.out" \
    env PATH="$test_root/download-bin:$PATH" FAIL_MONITORING_DOWNLOAD=true \
    sh "$test_root/standalone/quick-start.sh" --directory "$test_root/downloaded" --provider local --monitoring --no-start --force
cmp "$test_root/downloaded-compose.saved" "$test_root/downloaded/compose.yml" || fail "download failure changed Compose"
cmp "$test_root/downloaded-env.saved" "$test_root/downloaded/.sendium.env" || fail "download failure changed credentials"
pass "standalone versioned downloads start no services and fail without replacing runtime"
cp "$quick_start" "$test_root/downloaded/quick-start.sh"
PATH="$test_root/download-bin:$PATH" MONITORING_FIXTURES="$repo_dir/monitoring" \
    DOWNLOAD_TRACE="$test_root/rerun-download.trace" SENDIUM_MONITORING_REF=v-rerun \
    sh "$test_root/downloaded/quick-start.sh" --directory "$test_root/downloaded" --provider local --monitoring --no-start --force > "$test_root/rerun-download.out" 2>&1
assert_contains '/v-rerun/monitoring' "$test_root/downloaded/monitoring/source.txt"
assert_equals 4 "$(wc -l < "$test_root/rerun-download.trace" | tr -d ' ')" "generated assets are not checkout assets"
pass "same-directory standalone reruns retain versioned download semantics"

mkdir -p "$test_root/install-failure-bin"
cat > "$test_root/install-failure-bin/mv" <<'EOF'
#!/bin/sh
case "$*" in *monitoring/source.txt*) exit 1 ;; esac
exec "$REAL_MV" "$@"
EOF
chmod +x "$test_root/install-failure-bin/mv"
mkdir -p "$test_root/install-failure/monitoring"
printf 'preserve\n' > "$test_root/install-failure/monitoring/user-file"
expect_failure "monitoring install failure" "$test_root/install-failure.out" \
    env PATH="$test_root/install-failure-bin:$PATH" REAL_MV="$(command -v mv)" \
    sh "$quick_start" --directory "$test_root/install-failure" --provider local --monitoring --no-start --force
assert_contains preserve "$test_root/install-failure/monitoring/user-file"
[ ! -d "$test_root/install-failure/monitoring/grafana" ] || fail "new empty monitoring directories survived rollback"
[ ! -f "$test_root/install-failure/compose.yml" ] || fail "partially installed Compose survived rollback"
pass "failed installation removes newly created empty directories without deleting existing content"

mkdir -p "$test_root/lifecycle-bin"
cat > "$test_root/lifecycle-bin/docker" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$DOCKER_TRACE"
case "$*" in
    *' up -d'*)
        if [ "${FAIL_COMPOSE_UP:-false}" = true ]; then
            echo 'port is already allocated' >&2
            exit 1
        fi ;;
esac
EOF
cat > "$test_root/lifecycle-bin/curl" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "$test_root/lifecycle-bin/"*
PATH="$test_root/lifecycle-bin:$PATH" DOCKER_TRACE="$test_root/lifecycle.trace" \
    sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --force > "$test_root/lifecycle.out" 2>&1
assert_contains 'up -d --force-recreate --remove-orphans' "$test_root/lifecycle.trace"
assert_contains 'Monitoring services are ready' "$test_root/lifecycle.out"
assert_contains 'http://127.0.0.1:3000/d/sendium-sms-operations' "$test_root/lifecycle.out"
assert_contains 'http://127.0.0.1:3000/d/sendium-application-health' "$test_root/lifecycle.out"
assert_contains 'stop prometheus grafana' "$test_root/lifecycle.out"
assert_not_contains "$grafana_password" "$test_root/lifecycle.out"
pass "monitoring startup and non-secret service instructions"
PATH="$test_root/lifecycle-bin:$PATH" DOCKER_TRACE="$test_root/disable.trace" \
    sh "$quick_start" --directory "$monitoring_dir" --provider local --force > "$test_root/disable.out" 2>&1
assert_contains '--remove-orphans' "$test_root/disable.trace"
assert_not_contains '--volumes' "$test_root/disable.trace"
assert_contains 'files, credentials and data volumes are retained' "$test_root/disable.out"
assert_contains "$grafana_password" "$monitoring_dir/.grafana.env"
PATH="$test_root/lifecycle-bin:$PATH" DOCKER_TRACE="$test_root/no-start.trace" \
    sh "$quick_start" --directory "$monitoring_dir" --provider local --force --no-start > "$test_root/disable-generate.out" 2>&1
[ ! -e "$test_root/no-start.trace" ] || fail "generate-only touched Docker"
assert_contains 'leaves existing containers running' "$test_root/disable-generate.out"
pass "disabling monitoring removes orphans only at startup and preserves data"
expect_failure "monitoring port conflict" "$test_root/port-conflict.out" \
    env PATH="$test_root/lifecycle-bin:$PATH" DOCKER_TRACE="$test_root/conflict.trace" FAIL_COMPOSE_UP=true \
    sh "$quick_start" --directory "$monitoring_dir" --provider local --monitoring --force
assert_contains 'port is already allocated' "$test_root/port-conflict.out"
assert_contains 'monitoring: 3000/9090' "$test_root/port-conflict.out"
assert_not_contains '--volumes' "$test_root/conflict.trace"
pass "Compose failures preserve the Docker error and identify conflicting ports"

expect_failure "non-empty target protection" "$test_root/non-empty.out" \
    sh "$quick_start" --directory "$local_dir" --provider local --no-start
assert_contains 'output directory is not empty' "$test_root/non-empty.out"
pass "non-empty target protection"

printf 'keep me\n' > "$local_dir/user-file.txt"
old_http_password=$http_password
old_http_api_key=$http_api_key
old_database_password=$database_password
sh "$quick_start" --directory "$local_dir" --provider local --force --no-start > "$test_root/force.out" 2>&1
assert_file "$local_dir/user-file.txt"
assert_contains 'keep me' "$local_dir/user-file.txt"
assert_contains 'up -d --force-recreate' "$test_root/force.out"
http_password=$(sed -n "s/^SENDIUM_HTTP_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
http_api_key=$(sed -n "s/^SENDIUM_HTTP_API_KEY='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$local_dir/.sendium.env")
[ -n "$http_password" ] || fail "forced regeneration did not produce an HTTP password"
assert_equals 48 "${#http_password}" "regenerated HTTP password length"
[ "$old_http_password" != "$http_password" ] || fail "forced regeneration did not rotate the HTTP password"
assert_equals 64 "${#http_api_key}" "regenerated native API key length"
[ "$old_http_api_key" != "$http_api_key" ] || fail "forced regeneration did not rotate the native API key"
assert_contains "apiKey: \"$http_api_key\"" "$local_dir/conf/credentials.yml"
assert_not_contains "$http_api_key" "$test_root/force.out"
assert_equals "$old_database_password" "$database_password" "preserved PostgreSQL password"
pass "explicit regeneration preserves unrelated files"

unrecoverable_dir="$test_root/unrecoverable-postgresql"
sh "$quick_start" --directory "$unrecoverable_dir" --provider local --no-start > "$test_root/unrecoverable-setup.out" 2>&1
grep -v '^SENDIUM_DLR_POSTGRESQL_PASSWORD=' "$unrecoverable_dir/.sendium.env" > "$unrecoverable_dir/.sendium.env.stripped"
mv "$unrecoverable_dir/.sendium.env.stripped" "$unrecoverable_dir/.sendium.env"
grep -v '^SENDIUM_LOCAL_POSTGRESQL_PASSWORD=' "$unrecoverable_dir/.sendium.env" > "$unrecoverable_dir/.sendium.env.stripped"
mv "$unrecoverable_dir/.sendium.env.stripped" "$unrecoverable_dir/.sendium.env"
grep -v '^POSTGRES_PASSWORD=' "$unrecoverable_dir/.sendium.env" > "$unrecoverable_dir/.sendium.env.stripped"
mv "$unrecoverable_dir/.sendium.env.stripped" "$unrecoverable_dir/.sendium.env"
expect_failure "unrecoverable PostgreSQL password" "$test_root/unrecoverable-postgresql.out" \
    sh "$quick_start" --directory "$unrecoverable_dir" --provider local --force --no-start
assert_contains 'the existing PostgreSQL volume requires the password' "$test_root/unrecoverable-postgresql.out"
assert_contains 'down --volumes' "$test_root/unrecoverable-postgresql.out"
assert_contains 'rm -f' "$test_root/unrecoverable-postgresql.out"
rm -f "$unrecoverable_dir/compose.yml"
sh "$quick_start" --directory "$unrecoverable_dir" --provider local --force --no-start > "$test_root/recovered-postgresql.out" 2>&1
recovered_password=$(sed -n "s/^SENDIUM_LOCAL_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$unrecoverable_dir/.sendium.env")
assert_equals 64 "${#recovered_password}" "recovered PostgreSQL password length"
pass "unrecoverable local PostgreSQL password stops regeneration"

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
assert_contains "SENDIUM_LOCAL_POSTGRESQL_PASSWORD: ''" "$external_dir/compose.yml"
pass "external PostgreSQL configuration"

switched_dir="$test_root/switched-postgresql"
sh "$quick_start" --directory "$switched_dir" --provider local --no-start > "$test_root/switched-local-setup.out" 2>&1
original_local_password=$(sed -n "s/^SENDIUM_LOCAL_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$switched_dir/.sendium.env")
SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://database.example.test:5432/sendium' \
SENDIUM_DLR_POSTGRESQL_USERNAME='external-user' \
SENDIUM_DLR_POSTGRESQL_PASSWORD='external-password' \
    sh "$quick_start" --directory "$switched_dir" --provider local --force --no-start > "$test_root/switched-external.out" 2>&1
assert_contains "SENDIUM_LOCAL_POSTGRESQL_PASSWORD='$original_local_password'" "$switched_dir/.sendium.env"
sh "$quick_start" --directory "$switched_dir" --provider local --force --no-start > "$test_root/switched-postgresql.out" 2>&1
switched_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$switched_dir/.sendium.env")
assert_equals 64 "${#switched_password}" "regenerated PostgreSQL password length"
assert_equals "$original_local_password" "$switched_password" "preserved PostgreSQL password after mode switch"
assert_contains 'image: postgres:17-alpine' "$switched_dir/compose.yml"
pass "local PostgreSQL password survives an external database round trip"

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

SENDIUM_DLR_POSTGRESQL_JDBC_URL='jdbc:postgresql://db.example.test:5432/sendium' \
SENDIUM_DLR_POSTGRESQL_USERNAME='operator' \
SENDIUM_DLR_POSTGRESQL_PASSWORD='test-only-password' \
SENDIUM_UPSTREAM_HOST='smpp.example.test' \
SENDIUM_UPSTREAM_USERNAME='custom-user' \
SENDIUM_UPSTREAM_PASSWORD='pass1234' \
    sh "$quick_start" --directory "$test_root/monitoring-external" --provider custom --monitoring --no-start > "$test_root/monitoring-external.out" 2>&1
assert_contains 'grafana-data:' "$test_root/monitoring-external/compose.yml"
assert_not_contains 'postgres-data:' "$test_root/monitoring-external/compose.yml"
assert_contains 'outSms.instance.upstream.host = smpp.example.test' "$test_root/monitoring-external/conf/smsg.properties"
SENDIUM_UPSTREAM_USERNAME='prosms-user' SENDIUM_UPSTREAM_PASSWORD='pass1234' \
    sh "$quick_start" --directory "$test_root/monitoring-prosms" --provider prosms --monitoring --no-start > "$test_root/monitoring-prosms.out" 2>&1
assert_contains 'postgres-data:' "$test_root/monitoring-prosms/compose.yml"
assert_contains 'outSms.instance.upstream.host = smpp.prosms.gr' "$test_root/monitoring-prosms/conf/smsg.properties"
pass "monitoring supports custom/external-database and ProSMS/bundled-database modes"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    docker compose -f "$local_dir/compose.yml" --project-directory "$local_dir" config --quiet
    docker compose -f "$prosms_dir/compose.yml" --project-directory "$prosms_dir" config --quiet
    docker compose -f "$custom_dir/compose.yml" --project-directory "$custom_dir" config --quiet
    docker compose -f "$external_dir/compose.yml" --project-directory "$external_dir" config --quiet
    docker compose -f "$monitoring_dir/compose.yml" --project-directory "$monitoring_dir" config --quiet
    docker compose -f "$test_root/monitoring-external/compose.yml" config --quiet
    docker compose -f "$test_root/monitoring-prosms/compose.yml" config --quiet
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
        --monitoring \
        --image "$SENDIUM_TEST_IMAGE" > "$test_root/integration.out" 2>&1
    assert_contains 'Sendium is ready.' "$test_root/integration.out"
    assert_contains 'Follow live logs with:' "$test_root/integration.out"
    integration_grafana_password=$(sed -n "s/^GF_SECURITY_ADMIN_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$integration_dir/.grafana.env")
    for dashboard in sendium-sms-operations sendium-application-health; do
        attempt=0
        until curl -fsS --max-time 10 -u "admin:$integration_grafana_password" \
            "http://127.0.0.1:3000/api/dashboards/uid/$dashboard" > "$test_root/$dashboard.json" 2>/dev/null; do
            attempt=$((attempt + 1))
            [ "$attempt" -lt 30 ] || fail "dashboard not provisioned: $dashboard"
            sleep 1
        done
    done
    attempt=0
    while :; do
        curl -fsS --max-time 10 --get --data-urlencode 'query=up{job="sendium",instance="sendium:8080"}' 'http://127.0.0.1:9090/api/v1/query' > "$test_root/scrape.json"
        if grep -E '"value":[[:space:]]*\[[^]]*,"1"\]' "$test_root/scrape.json" >/dev/null; then
            break
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -lt 30 ] || fail "Prometheus did not scrape Sendium"
        sleep 1
    done
    pass "live monitoring dashboards and Sendium scrape"
    integration_http_user=$(sed -n "s/^SENDIUM_HTTP_USER='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    integration_http_password=$(sed -n "s/^SENDIUM_HTTP_PASSWORD='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    integration_http_api_key=$(sed -n "s/^SENDIUM_HTTP_API_KEY='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    integration_database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    native_status=$(curl -sS -o "$test_root/native.out" -w '%{http_code}' http://127.0.0.1:8080/api/v1/messages \
        -H "Authorization: Bearer $integration_http_api_key" \
        -H 'Content-Type: application/json' \
        --data '{"from":"Sendium","to":"306910000000","text":"Quick-start native integration test"}')
    assert_equals 202 "$native_status" "generated native API key submission"
    grep -E '"messageId"[[:space:]]*:[[:space:]]*"[0-9a-f-]{36}"' "$test_root/native.out" >/dev/null 2>&1 || fail "expected JSON gateway messageId"
    grep -E '"status"[[:space:]]*:[[:space:]]*"accepted"' "$test_root/native.out" >/dev/null 2>&1 || fail "expected JSON accepted status"
    assert_not_contains "$integration_http_api_key" "$test_root/integration.out"
    http_status=$(curl -sS -o "$test_root/sendsms.out" -w '%{http_code}' -G http://127.0.0.1:8080/sendsms \
        --data-urlencode "username=$integration_http_user" \
        --data-urlencode "password=$integration_http_password" \
        --data-urlencode 'from=Sendium' \
        --data-urlencode 'to=306910000000' \
        --data-urlencode 'text=Quick-start integration test')
    assert_equals 202 "$http_status" "generated HTTP credential submission"
    grep -E '^[0-9a-f-]{36}$' "$test_root/sendsms.out" >/dev/null 2>&1 || fail "expected gateway UUID response"
    pass "real container startup, readiness, native JSON and Kannel submissions"

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
    recreated_api_key=$(sed -n "s/^SENDIUM_HTTP_API_KEY='\([^']*\)'$/\1/p" "$integration_dir/.sendium.env")
    native_status=$(curl -sS -o "$test_root/native-rotated.out" -w '%{http_code}' http://127.0.0.1:8080/api/v1/messages \
        -H "Authorization: Bearer $integration_http_api_key" -H 'Content-Type: application/json' \
        --data '{"from":"Sendium","to":"306910000000","text":"Old key"}')
    assert_equals 401 "$native_status" "old native API key after regeneration"
    native_status=$(curl -sS -o "$test_root/native-rotated.out" -w '%{http_code}' http://127.0.0.1:8080/api/v1/messages \
        -H "Authorization: Bearer $recreated_api_key" -H 'Content-Type: application/json' \
        --data '{"from":"Sendium","to":"306910000000","text":"New key"}')
    assert_equals 202 "$native_status" "regenerated native API key submission"
    pass "forced regeneration recreates the running container"
    assert_equals '' "$(docker ps -q --filter "label=com.docker.compose.project=$integration_project" --filter label=com.docker.compose.service=grafana)" "Grafana removed when monitoring disabled"
    docker volume inspect "${integration_project}_grafana-data" >/dev/null || fail "Grafana data volume removed"
    COMPOSE_PROJECT_NAME="$integration_project" sh "$quick_start" \
        --directory "$integration_dir" --provider local --monitoring \
        --image "$SENDIUM_TEST_IMAGE" --force > "$test_root/integration-reenable.out" 2>&1
    assert_contains "$integration_grafana_password" "$integration_dir/.grafana.env"
    attempt=0
    until curl -fsS --max-time 10 -u "admin:$integration_grafana_password" \
        'http://127.0.0.1:3000/api/dashboards/uid/sendium-sms-operations' > "$test_root/reenabled-dashboard.json" 2>/dev/null; do
        attempt=$((attempt + 1))
        [ "$attempt" -lt 30 ] || fail "dashboard not restored after re-enabling monitoring"
        sleep 1
    done
    pass "live disable/re-enable preserves Grafana credentials and history volume"
else
    printf 'skip - real container startup (set SENDIUM_TEST_IMAGE to enable)\n'
fi

printf 'Completed %s quick-start tests.\n' "$tests_run"
