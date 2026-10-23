#!/usr/bin/env bash
# 数据库迁移执行器：按文件名顺序应用 backend/src/main/resources/db/migration/*.sql
# 已应用的版本记录在目标库的 schema_migrations 表，重复执行自动跳过。
#
# 用法：scripts/db-migrate.sh <数据库名>
# 示例：scripts/db-migrate.sh liveproof_test
#       scripts/db-migrate.sh liveproof
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f ./.env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi

MYSQL_BIN="${MYSQL_BIN:-/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe}"
DB="${1:?用法: scripts/db-migrate.sh <数据库名>}"
MIGRATION_DIR="backend/src/main/resources/db/migration"
export MYSQL_PWD="${MYSQL_PASSWORD:?缺少 MYSQL_PASSWORD}"

run_sql() {
  "$MYSQL_BIN" -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-root}" \
    --default-character-set=utf8mb4 -D "$DB" "$@"
}

echo "== 目标数据库：$DB =="

run_sql -e "CREATE TABLE IF NOT EXISTS schema_migrations (
  version    VARCHAR(128) NOT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"

applied=0
skipped=0
for file in $(ls "$MIGRATION_DIR"/*.sql | sort); do
  version="$(basename "$file" .sql)"
  exists="$(run_sql -N -B -e "SELECT COUNT(*) FROM schema_migrations WHERE version='$version'")"
  if [[ "$exists" != "0" ]]; then
    echo "  [skip] $version（已应用）"
    skipped=$((skipped + 1))
    continue
  fi
  echo "  [apply] $version"
  run_sql < "$file"
  run_sql -e "INSERT INTO schema_migrations (version) VALUES ('$version')"
  applied=$((applied + 1))
done

echo "== 完成：新应用 $applied 个，跳过 $skipped 个 =="
