#!/usr/bin/env bash
# 数据库备份：mysqldump 一致性快照（不锁表），输出到项目外部目录。
#
# 用法：scripts/db-backup.sh <数据库名> <输出文件>
# 示例：scripts/db-backup.sh liveproof /d/_liveproof-backup/2026-10-23/before.sql
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f ./.env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi

DUMP_BIN="${DUMP_BIN:-/c/Program Files/MySQL/MySQL Server 8.4/bin/mysqldump.exe}"
DB="${1:?用法: scripts/db-backup.sh <数据库名> <输出文件>}"
OUT="${2:?缺少输出文件路径}"
export MYSQL_PWD="${MYSQL_PASSWORD:?缺少 MYSQL_PASSWORD}"

mkdir -p "$(dirname "$OUT")"
"$DUMP_BIN" \
  -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-root}" \
  --single-transaction --routines --triggers --events \
  --default-character-set=utf8mb4 --set-gtid-purged=OFF \
  "$DB" > "$OUT"

echo "备份完成：$OUT"
echo "体积：$(wc -c < "$OUT") 字节"
echo "包含表：$(grep -c '^CREATE TABLE' "$OUT" || true)"
echo "INSERT 行：$(grep -c '^INSERT INTO' "$OUT" || true)"
