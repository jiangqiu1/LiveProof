#!/usr/bin/env bash
# 备份可验证性检查：把 dump 导入一个临时库，与源库逐表比对行数与 CHECKSUM。
#
# 用法：scripts/db-verify-backup.sh <备份文件> <源库> <临时库>
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
DUMP_FILE="${1:?用法: scripts/db-verify-backup.sh <备份文件> <源库> <临时库>}"
SRC_DB="${2:?缺少源库}"
TMP_DB="${3:?缺少临时库}"
export MYSQL_PWD="${MYSQL_PASSWORD:?缺少 MYSQL_PASSWORD}"

mysql_cmd() {
  "$MYSQL_BIN" -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-root}" \
    --default-character-set=utf8mb4 "$@"
}

echo "== 导入备份到临时库 $TMP_DB =="
mysql_cmd -e "DROP DATABASE IF EXISTS \`$TMP_DB\`; CREATE DATABASE \`$TMP_DB\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_cmd -D "$TMP_DB" < "$DUMP_FILE"

echo "== 逐表比对（行数 / CHECKSUM）=="
fail=0
for table in $(mysql_cmd -N -B -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$SRC_DB' AND table_type='BASE TABLE' ORDER BY table_name"); do
  src_count="$(mysql_cmd -N -B -e "SELECT COUNT(*) FROM \`$SRC_DB\`.\`$table\`")"
  tmp_count="$(mysql_cmd -N -B -e "SELECT COUNT(*) FROM \`$TMP_DB\`.\`$table\`")"
  src_sum="$(mysql_cmd -N -B -e "CHECKSUM TABLE \`$SRC_DB\`.\`$table\`" | awk -F'\t' '{print $2}')"
  tmp_sum="$(mysql_cmd -N -B -e "CHECKSUM TABLE \`$TMP_DB\`.\`$table\`" | awk -F'\t' '{print $2}')"
  if [[ "$src_count" == "$tmp_count" && "$src_sum" == "$tmp_sum" ]]; then
    echo "  [OK]   $table  rows=$src_count checksum=$src_sum"
  else
    echo "  [FAIL] $table  源 rows=$src_count sum=$src_sum / 备份 rows=$tmp_count sum=$tmp_sum"
    fail=1
  fi
done

mysql_cmd -e "DROP DATABASE IF EXISTS \`$TMP_DB\`;"
if [[ "$fail" == "0" ]]; then
  echo "== 备份校验通过：可完整还原 =="
else
  echo "== 备份校验失败：禁止继续执行迁移 =="
  exit 1
fi
