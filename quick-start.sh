#!/bin/sh

set -eu

DEFAULT_TARGET_DIR="sendium"
DEFAULT_IMAGE="cytechmobile/sendium:latest"
PROSMS_HOST="smpp.prosms.gr"
PROSMS_PORT="2775"
PROSMS_REGISTRATION_URL="https://prosms.gr/sms-tool/?v=2&m=8"

target_dir=$DEFAULT_TARGET_DIR
image=$DEFAULT_IMAGE
start_sendium=true
force=false
provider=''
allow_windows_mount=false
upstream_password_from_environment=${SENDIUM_UPSTREAM_PASSWORD-}
database_jdbc_url=${SENDIUM_DLR_POSTGRESQL_JDBC_URL-}
database_username=${SENDIUM_DLR_POSTGRESQL_USERNAME-}
database_password=${SENDIUM_DLR_POSTGRESQL_PASSWORD-}
unset SENDIUM_UPSTREAM_PASSWORD
unset SENDIUM_DLR_POSTGRESQL_PASSWORD
unset SENDIUM_LOCAL_POSTGRESQL_PASSWORD

usage() {
    cat <<'EOF'
Usage: ./quick-start.sh [options]

Generate a local Sendium runtime and start it with Docker Compose.

Options:
  --directory DIR  Output directory (default: sendium)
  --image IMAGE    Docker image (default: cytechmobile/sendium:latest)
  --provider MODE  Provider mode: local, prosms, or custom
  --allow-windows-mount
                  Allow secrets on a WSL-mounted Windows directory
  --no-start       Generate files without starting Sendium
  --force          Replace generated files in a non-empty output directory
  -h, --help       Show this help

Non-interactive provider configuration:
  Set SENDIUM_UPSTREAM_USERNAME and SENDIUM_UPSTREAM_PASSWORD for ProSMS.
  Custom SMPP also uses SENDIUM_UPSTREAM_HOST, SENDIUM_UPSTREAM_PORT,
  and SENDIUM_UPSTREAM_TLS.

External PostgreSQL configuration:
  Set SENDIUM_DLR_POSTGRESQL_JDBC_URL, SENDIUM_DLR_POSTGRESQL_USERNAME,
  and SENDIUM_DLR_POSTGRESQL_PASSWORD together. Otherwise Quick Start creates
  a private PostgreSQL 17 service with a persistent Docker volume.
EOF
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

require_value() {
    option=$1
    value=${2-}
    [ -n "$value" ] || fail "$option requires a value"
}

generate_secret() {
    byte_count=$1
    secret=''

    if [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
        secret=$(LC_ALL=C od -An -N "$byte_count" -tx1 /dev/urandom | tr -d ' \n')
    elif command -v openssl >/dev/null 2>&1; then
        secret=$(openssl rand -hex "$byte_count")
    fi

    expected_length=$((byte_count * 2))
    [ "${#secret}" -eq "$expected_length" ] || fail "could not generate a secure random password"
    printf '%s' "$secret"
}

generate_smpp_secret() {
    secret=''

    if [ -r /dev/urandom ] && command -v tr >/dev/null 2>&1 && command -v dd >/dev/null 2>&1; then
        # Bound the input so tr reaches EOF even when SIGPIPE is ignored.
        secret=$(dd if=/dev/urandom bs=256 count=1 2>/dev/null | LC_ALL=C tr -dc 'A-Za-z0-9' | dd bs=1 count=8 2>/dev/null)
    elif command -v openssl >/dev/null 2>&1; then
        secret=$(openssl rand -base64 12 | LC_ALL=C tr -dc 'A-Za-z0-9' | dd bs=1 count=8 2>/dev/null)
    fi

    [ "${#secret}" -eq 8 ] || fail "could not generate a secure random SMPP password"
    printf '%s' "$secret"
}

prompt_value() {
    prompt=$1
    default_value=${2-}

    if [ -n "$default_value" ]; then
        printf '%s [%s]: ' "$prompt" "$default_value" >&2
    else
        printf '%s: ' "$prompt" >&2
    fi

    if ! IFS= read -r REPLY; then
        fail "could not read interactive input"
    fi
    if [ -z "$REPLY" ]; then
        REPLY=$default_value
    fi
}

prompt_secret() {
    prompt=$1
    printf '%s: ' "$prompt" >&2
    terminal_state=$(stty -g) || fail "could not read terminal settings"
    trap 'stty "$terminal_state" 2>/dev/null || true; exit 130' HUP INT TERM
    stty -echo
    if ! IFS= read -r REPLY; then
        stty "$terminal_state"
        trap - HUP INT TERM
        fail "could not read interactive input"
    fi
    stty "$terminal_state"
    trap - HUP INT TERM
    printf '\n' >&2
}

is_yes() {
    case "$1" in
        y|Y|yes|YES|Yes|true|TRUE|True)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

validate_property_value() {
    label=$1
    property_value=$2
    [ -n "$property_value" ] || fail "$label cannot be empty"
    property_value_without_line_breaks=$(printf '%s' "$property_value" | LC_ALL=C tr -d '\r\n')
    if [ "$property_value" != "$property_value_without_line_breaks" ]; then
        fail "$label cannot contain control characters"
    fi
    if printf '%s' "$property_value" | LC_ALL=C grep -q '[[:cntrl:]]'; then
        fail "$label cannot contain control characters"
    fi
    case "$property_value" in
        ' '*|*' ')
            fail "$label cannot start or end with a space"
            ;;
    esac
}

validate_smpp_credential() {
    label=$1
    credential_value=$2
    maximum_bytes=$3
    validate_property_value "$label" "$credential_value"

    if printf '%s' "$credential_value" | LC_ALL=C grep -q '[^ -~]'; then
        fail "$label must contain printable ASCII characters only"
    fi

    credential_bytes=$(printf '%s' "$credential_value" | LC_ALL=C wc -c | tr -d ' ')
    [ "$credential_bytes" -le "$maximum_bytes" ] || fail "$label must not exceed $maximum_bytes bytes"
}

escape_property_value() {
    printf '%s' "$1" | sed 's/\\/\\\\/g'
}

validate_port() {
    port_value=$1
    case "$port_value" in
        ''|*[!0-9]*)
            fail "SMPP port must be a number"
            ;;
    esac
    [ "$port_value" -ge 1 ] && [ "$port_value" -le 65535 ] || fail "SMPP port must be between 1 and 65535"
}

validate_environment_value() {
    label=$1
    environment_value=$2
    validate_property_value "$label" "$environment_value"
    case "$environment_value" in
        *"'"*) fail "$label cannot contain a single quote" ;;
    esac
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --directory)
            require_value "$1" "${2-}"
            target_dir=$2
            shift 2
            ;;
        --image)
            require_value "$1" "${2-}"
            image=$2
            shift 2
            ;;
        --provider)
            require_value "$1" "${2-}"
            provider=$2
            shift 2
            ;;
        --allow-windows-mount)
            allow_windows_mount=true
            shift
            ;;
        --no-start)
            start_sendium=false
            shift
            ;;
        --force)
            force=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown option: $1"
            ;;
    esac
done

[ -n "$target_dir" ] || fail "output directory cannot be empty"
[ -n "$image" ] || fail "Docker image cannot be empty"

if [ -L "$target_dir" ]; then
    fail "output directory cannot be a symbolic link: $target_dir"
fi

if [ -e "$target_dir" ] && [ ! -d "$target_dir" ]; then
    fail "output path exists and is not a directory: $target_dir"
fi

if [ -d "$target_dir" ] && [ -n "$(ls -A "$target_dir" 2>/dev/null)" ] && [ "$force" != true ]; then
    fail "output directory is not empty: $target_dir (use --force to replace generated files)"
fi

if [ "$start_sendium" = true ]; then
    command -v docker >/dev/null 2>&1 || fail "Docker is required unless --no-start is used"
    docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
    docker info >/dev/null 2>&1 || fail "Docker is not running or is not accessible"
    command -v curl >/dev/null 2>&1 || fail "curl is required for the startup check"
fi

umask 077
mkdir -p "$target_dir"
absolute_target=$(cd "$target_dir" && pwd -P)
windows_mount_insecure=false

case "$(uname -r 2>/dev/null || true)" in
    *[Mm]icrosoft*)
        permission_probe=$(mktemp "$target_dir/.quick-start-permission-check.XXXXXX") || fail "could not test target directory permissions"
        chmod 600 "$permission_probe"
        permission_mode=$(stat -c %a "$permission_probe")
        rm -f "$permission_probe"
        if [ "$permission_mode" != 600 ]; then
            windows_mount_insecure=true
            if [ "$allow_windows_mount" != true ]; then
                fail "WSL cannot enforce Unix secret-file modes in $absolute_target; use a directory under your WSL home or pass --allow-windows-mount"
            fi
        fi
        ;;
esac

for runtime_dir in conf data logs; do
    if [ -L "$target_dir/$runtime_dir" ]; then
        fail "generated directory cannot be a symbolic link: $target_dir/$runtime_dir"
    fi
    if [ -e "$target_dir/$runtime_dir" ] && [ ! -d "$target_dir/$runtime_dir" ]; then
        fail "generated directory path is not a directory: $target_dir/$runtime_dir"
    fi
done

for generated_file in .sendium.env .gitignore compose.yml conf/credentials.yml conf/smsg.properties conf/routingTable.conf; do
    if [ -L "$target_dir/$generated_file" ]; then
        fail "generated file cannot be a symbolic link: $target_dir/$generated_file"
    fi
    if [ -e "$target_dir/$generated_file" ] && [ ! -f "$target_dir/$generated_file" ]; then
        fail "generated file path is not a regular file: $target_dir/$generated_file"
    fi
done

interactive=false
if [ -t 0 ]; then
    interactive=true
fi

if [ -z "$provider" ]; then
    if [ "$interactive" = true ]; then
        printf '\nChoose an upstream SMS provider:\n' >&2
        printf '  1. ProSMS\n' >&2
        printf '  2. Existing SMPP provider\n' >&2
        printf '  3. Local setup only\n' >&2
        prompt_value "Selection" "3"
        case "$REPLY" in
            1) provider='prosms' ;;
            2) provider='custom' ;;
            3) provider='local' ;;
            *) fail "provider selection must be 1, 2, or 3" ;;
        esac
    else
        provider='local'
    fi
fi

case "$provider" in
    local|prosms|custom) ;;
    *) fail "provider must be local, prosms, or custom" ;;
esac

upstream_enabled=false
prosms_pending=false
upstream_host=''
upstream_port=''
upstream_username=${SENDIUM_UPSTREAM_USERNAME-}
upstream_password=$upstream_password_from_environment
upstream_password_from_environment=''
upstream_tls='false'

if [ "$provider" = prosms ]; then
    upstream_host=$PROSMS_HOST
    upstream_port=$PROSMS_PORT

    if [ -n "$upstream_username" ] || [ -n "$upstream_password" ]; then
        [ -n "$upstream_username" ] && [ -n "$upstream_password" ] || fail "both ProSMS username and password must be provided"
        upstream_enabled=true
    elif [ "$interactive" = true ]; then
        prompt_value "Do you already have approved ProSMS SMPP credentials? (y/N)" "N"
        if is_yes "$REPLY"; then
            prompt_value "ProSMS SMPP username" ""
            upstream_username=$REPLY
            prompt_secret "ProSMS SMPP password"
            upstream_password=$REPLY
            upstream_enabled=true
        else
            prosms_pending=true
        fi
    else
        prosms_pending=true
    fi
fi

if [ "$provider" = custom ]; then
    upstream_host=${SENDIUM_UPSTREAM_HOST-}
    upstream_port=${SENDIUM_UPSTREAM_PORT-2775}
    upstream_tls=${SENDIUM_UPSTREAM_TLS-false}

    if [ "$interactive" = true ]; then
        prompt_value "SMPP host" "$upstream_host"
        upstream_host=$REPLY
        prompt_value "SMPP port" "$upstream_port"
        upstream_port=$REPLY
        prompt_value "SMPP username" "$upstream_username"
        upstream_username=$REPLY
        if [ -z "$upstream_password" ]; then
            prompt_secret "SMPP password"
            upstream_password=$REPLY
        fi
        prompt_value "Use TLS? (y/N)" "$upstream_tls"
        if is_yes "$REPLY"; then
            upstream_tls='true'
        else
            upstream_tls='false'
        fi
    fi

    [ -n "$upstream_host" ] || fail "custom SMPP requires SENDIUM_UPSTREAM_HOST or interactive input"
    [ -n "$upstream_username" ] || fail "custom SMPP requires SENDIUM_UPSTREAM_USERNAME or interactive input"
    [ -n "$upstream_password" ] || fail "custom SMPP requires SENDIUM_UPSTREAM_PASSWORD or interactive input"
    upstream_enabled=true
fi

if [ "$upstream_enabled" = true ]; then
    validate_property_value "SMPP host" "$upstream_host"
    validate_port "$upstream_port"
    validate_smpp_credential "SMPP username" "$upstream_username" 15
    validate_smpp_credential "SMPP password" "$upstream_password" 8

    case "$upstream_tls" in
        true|false) ;;
        *) fail "SMPP TLS must be true or false" ;;
    esac

    upstream_host=$(escape_property_value "$upstream_host")
    upstream_username=$(escape_property_value "$upstream_username")
    upstream_password=$(escape_property_value "$upstream_password")
fi

existing_bundled_database=false
local_database_password=''
if [ "$force" = true ]; then
    if [ -f "$target_dir/compose.yml" ] && \
        grep -q 'postgres-data:/var/lib/postgresql/data' "$target_dir/compose.yml"; then
        existing_bundled_database=true
    fi
    if [ -f "$target_dir/.sendium.env" ]; then
        local_database_password=$(sed -n "s/^SENDIUM_LOCAL_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$target_dir/.sendium.env")
        if [ "${#local_database_password}" -ne 64 ] && [ "$existing_bundled_database" = true ]; then
            local_database_password=$(sed -n "s/^SENDIUM_DLR_POSTGRESQL_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$target_dir/.sendium.env")
        fi
        if [ "${#local_database_password}" -ne 64 ] && [ "$existing_bundled_database" = true ]; then
            local_database_password=$(sed -n "s/^POSTGRES_PASSWORD='\([0-9a-f][0-9a-f]*\)'$/\1/p" "$target_dir/.sendium.env")
        fi
    fi
    if [ "$existing_bundled_database" = true ] && [ "${#local_database_password}" -ne 64 ]; then
        fail \
"the existing PostgreSQL volume requires the password recorded in $target_dir/.sendium.env, which could not be read.
Restore that file, or permanently delete the database and its generated Compose marker with:
  docker compose -f \"$target_dir/compose.yml\" --project-directory \"$target_dir\" down --volumes
  rm -f \"$target_dir/compose.yml\"
Then rerun Quick Start with --force."
    fi
fi

external_database=false
if [ -n "$database_jdbc_url" ] || [ -n "$database_username" ] || [ -n "$database_password" ]; then
    [ -n "$database_jdbc_url" ] && [ -n "$database_username" ] && [ -n "$database_password" ] || \
        fail "external PostgreSQL requires JDBC URL, username, and password"
    case "$database_jdbc_url" in
        jdbc:postgresql://*) ;;
        *) fail "PostgreSQL JDBC URL must start with jdbc:postgresql://" ;;
    esac
    validate_environment_value "PostgreSQL JDBC URL" "$database_jdbc_url"
    validate_environment_value "PostgreSQL username" "$database_username"
    validate_environment_value "PostgreSQL password" "$database_password"
    external_database=true
else
    database_jdbc_url='jdbc:postgresql://postgres:5432/sendium'
    database_username='sendium'
    if [ "${#local_database_password}" -eq 64 ]; then
        database_password=$local_database_password
    fi
    if [ -z "$database_password" ]; then
        database_password=$(generate_secret 32)
    fi
    local_database_password=$database_password
fi

mkdir -p "$target_dir/conf" "$target_dir/logs"
staging_dir=$(mktemp -d "$target_dir/.quick-start.XXXXXX") || fail "could not create a staging directory"
mkdir -p "$staging_dir/conf"
install_started=false
install_complete=false
backup_dir="$staging_dir/backup"

cleanup_quick_start() {
    if [ "$install_started" = true ] && [ "$install_complete" = false ]; then
        for generated_file in .sendium.env .gitignore compose.yml conf/credentials.yml conf/smsg.properties conf/routingTable.conf; do
            if [ -f "$backup_dir/$generated_file" ]; then
                mv -f "$backup_dir/$generated_file" "$target_dir/$generated_file"
            else
                rm -f "$target_dir/$generated_file"
            fi
        done
    fi
    if [ -d "$staging_dir" ]; then
        rm -rf "$staging_dir"
    fi
}
trap cleanup_quick_start 0

http_user='sendium-http'
smpp_user='sendium-smpp'
http_password=$(generate_secret 24)
# SMPP 3.4 bind passwords are limited to eight characters.
smpp_password=$(generate_smpp_secret)

cat > "$staging_dir/.sendium.env" <<EOF
SENDIUM_HTTP_USER='$http_user'
SENDIUM_HTTP_PASSWORD='$http_password'
SENDIUM_SMPP_USER='$smpp_user'
SENDIUM_SMPP_PASSWORD='$smpp_password'
SENDIUM_DLR_POSTGRESQL_JDBC_URL='$database_jdbc_url'
SENDIUM_DLR_POSTGRESQL_USERNAME='$database_username'
SENDIUM_DLR_POSTGRESQL_PASSWORD='$database_password'
EOF

if [ -n "$local_database_password" ]; then
    cat >> "$staging_dir/.sendium.env" <<EOF
SENDIUM_LOCAL_POSTGRESQL_PASSWORD='$local_database_password'
EOF
fi

if [ "$external_database" != true ]; then
    cat >> "$staging_dir/.sendium.env" <<EOF
POSTGRES_DB='sendium'
POSTGRES_USER='$database_username'
POSTGRES_PASSWORD='$database_password'
EOF
fi

cat > "$staging_dir/.gitignore" <<'EOF'
.sendium.env
conf/credentials.yml
conf/smsg.properties
data/
logs/
EOF

cat > "$staging_dir/conf/credentials.yml" <<EOF
credentials:
  - type: SMPP
    accountId: "quickstart-smpp"
    systemId: "$smpp_user"
    password: "$smpp_password"
  - type: HTTP
    accountId: "quickstart-http"
    systemId: "$http_user"
    password: "$http_password"
EOF

cat > "$staging_dir/conf/smsg.properties" <<'EOF'
# Local SMPP server for downstream clients.
outSms.instance.smpp.enable = true
outSms.instance.smpp.type = smppserver
outSms.instance.smpp.tps = 0
outSms.instance.smpp.print.msgs = false
outSms.instance.smpp.srv.host = 0.0.0.0
outSms.instance.smpp.srv.port = 27777
outSms.instance.smpp.srv.maxConnections = 1000
outSms.instance.smpp.srv.maxConnectionsPerIP = 4
outSms.instance.smpp.conf.maxPending.default = 1000
outSms.instance.smpp.conf.maxConnectionsPerUser.default = 4
outSms.instance.smpp.conf.maxRate.default = 0
EOF

if [ "$upstream_enabled" = true ]; then
    cat >> "$staging_dir/conf/smsg.properties" <<EOF

# Upstream SMPP provider.
outSms.instance.upstream.enable = true
outSms.instance.upstream.type = smppclient
outSms.instance.upstream.host = $upstream_host
outSms.instance.upstream.port = $upstream_port
outSms.instance.upstream.username = $upstream_username
outSms.instance.upstream.password = $upstream_password
outSms.instance.upstream.ssl = $upstream_tls
outSms.instance.upstream.tps = 0
outSms.instance.upstream.connections.transceivers = 1
outSms.instance.upstream.connections.transmitters = 0
EOF
fi

cat > "$staging_dir/conf/routingTable.conf" <<'EOF'
[default]
MESSAGE:type:==:0
MESSAGE:type:==:11
MESSAGE:type:==:14
MESSAGE:type:==:17
MESSAGE:type:==:10
smppserver.smpp:type:==:18

# An upstream provider route is added when provider configuration is selected.
[MESSAGE]
EOF

if [ "$upstream_enabled" = true ]; then
    printf 'upstream::default:\n' >> "$staging_dir/conf/routingTable.conf"
fi

cat > "$staging_dir/compose.yml" <<'EOF'
services:
EOF

if [ "$external_database" != true ]; then
    cat >> "$staging_dir/compose.yml" <<'EOF'
  postgres:
    image: postgres:17-alpine
    env_file:
      - ./.sendium.env
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 2s
      timeout: 3s
      retries: 30
    volumes:
      - postgres-data:/var/lib/postgresql/data

EOF
fi

cat >> "$staging_dir/compose.yml" <<EOF
  sendium:
    image: $image
    env_file:
      - ./.sendium.env
EOF

if [ "$external_database" != true ]; then
    cat >> "$staging_dir/compose.yml" <<'EOF'
    depends_on:
      postgres:
        condition: service_healthy
EOF
fi

cat >> "$staging_dir/compose.yml" <<'EOF'
    environment:
      SENDIUM_LOCAL_POSTGRESQL_PASSWORD: ''
      QUARKUS_LOG_FILE_ENABLE: "true"
      QUARKUS_LOG_CONSOLE_ENABLE: "true"
      QUARKUS_LOG_FILE_PATH: /work/logs/smsg.log
      QUARKUS_LOG_FILE_SMPPCLIENT_PATH: /work/logs/smppclient.log
      QUARKUS_LOG_FILE_SMPPSERVER_PATH: /work/logs/smppserver.log
      QUARKUS_HTTP_ACCESS_LOG_DIRECTORY: /work/logs
    ports:
      - "127.0.0.1:8080:8080"
      - "127.0.0.1:27777:27777"
    volumes:
      - ./conf:/work/conf
      - ./logs:/work/logs
EOF

if [ "$external_database" != true ]; then
    cat >> "$staging_dir/compose.yml" <<'EOF'

volumes:
  postgres-data:
EOF
fi

chmod 600 \
    "$staging_dir/.sendium.env" \
    "$staging_dir/.gitignore" \
    "$staging_dir/compose.yml" \
    "$staging_dir/conf/credentials.yml" \
    "$staging_dir/conf/smsg.properties" \
    "$staging_dir/conf/routingTable.conf"

mkdir -p "$backup_dir/conf"
for generated_file in .sendium.env .gitignore compose.yml conf/credentials.yml conf/smsg.properties conf/routingTable.conf; do
    if [ -f "$target_dir/$generated_file" ]; then
        cp -p "$target_dir/$generated_file" "$backup_dir/$generated_file"
    fi
done

if [ "$force" = true ] && [ "$start_sendium" = true ] && [ -f "$target_dir/compose.yml" ]; then
    existing_container=$(docker compose -f "$absolute_target/compose.yml" --project-directory "$absolute_target" ps -q sendium)
    if [ -n "$existing_container" ]; then
        printf '\nStopping the existing Sendium container before replacing its configuration...\n'
        docker compose -f "$absolute_target/compose.yml" --project-directory "$absolute_target" stop sendium
    fi
fi

install_started=true

mv -f "$staging_dir/.sendium.env" "$target_dir/.sendium.env"
mv -f "$staging_dir/.gitignore" "$target_dir/.gitignore"
mv -f "$staging_dir/compose.yml" "$target_dir/compose.yml"
mv -f "$staging_dir/conf/credentials.yml" "$target_dir/conf/credentials.yml"
mv -f "$staging_dir/conf/smsg.properties" "$target_dir/conf/smsg.properties"
mv -f "$staging_dir/conf/routingTable.conf" "$target_dir/conf/routingTable.conf"

chmod 600 \
    "$target_dir/.sendium.env" \
    "$target_dir/.gitignore" \
    "$target_dir/compose.yml" \
    "$target_dir/conf/credentials.yml" \
    "$target_dir/conf/smsg.properties" \
    "$target_dir/conf/routingTable.conf"

install_complete=true
rm -rf "$staging_dir"
trap - 0

printf '\nSendium runtime generated in %s\n' "$absolute_target"
printf 'Local credentials: %s\n' "$absolute_target/.sendium.env"

if [ "$upstream_enabled" = true ]; then
    printf 'Upstream provider: %s (%s:%s)\n' "$provider" "$upstream_host" "$upstream_port"
elif [ "$prosms_pending" = true ]; then
    printf '\nProSMS requires manual approval before SMPP credentials are issued.\n'
    printf 'Register or check your account at: %s\n' "$PROSMS_REGISTRATION_URL"
    printf 'After approval, rerun with --provider prosms and --force.\n'
else
    printf 'Upstream provider: none (local setup only)\n'
fi

if [ "$windows_mount_insecure" = true ]; then
    printf 'Warning: Unix file modes are not enforced on this WSL-mounted Windows directory.\n' >&2
fi

if [ "$start_sendium" != true ]; then
    if [ "$force" = true ]; then
        printf '\nRecreate Sendium later to apply the regenerated configuration and credentials:\n'
        printf '  docker compose -f "%s/compose.yml" --project-directory "%s" up -d --force-recreate --remove-orphans\n' "$absolute_target" "$absolute_target"
    else
        printf '\nStart Sendium later with:\n'
        printf '  docker compose -f "%s/compose.yml" --project-directory "%s" up -d\n' "$absolute_target" "$absolute_target"
    fi
    exit 0
fi

if [ "$force" = true ]; then
    printf '\nRecreating Sendium to apply the regenerated configuration and credentials...\n'
    docker compose -f "$absolute_target/compose.yml" --project-directory "$absolute_target" up -d --force-recreate --remove-orphans
else
    printf '\nStarting Sendium...\n'
    docker compose -f "$absolute_target/compose.yml" --project-directory "$absolute_target" up -d
fi

attempt=0
while [ "$attempt" -lt 60 ]; do
    if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:8080/q/health/ready >/dev/null 2>&1; then
        printf '\nSendium is ready.\n'
        printf 'Swagger UI: http://127.0.0.1:8080/swagger-ui\n'
        printf '\nFollow live logs with:\n'
        printf '  docker compose -f "%s/compose.yml" --project-directory "%s" logs -f\n' "$absolute_target" "$absolute_target"
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 1
done

printf '\nSendium did not become ready. Recent container logs:\n' >&2
docker compose -f "$absolute_target/compose.yml" --project-directory "$absolute_target" logs --tail 100 >&2 || true
fail "startup check timed out; generated files were kept in $absolute_target"
