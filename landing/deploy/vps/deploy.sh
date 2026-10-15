#!/usr/bin/env bash
# Выкладка лендинга Padly (статический экспорт landing/out/) на VPS Beget рядом с myhonor.shop:
# каталог /var/www/padly, nginx sites-available/padly, сертификат padly. На сервер уходят только файлы out/
# (без заглушек public/downloads — настоящие сборки живут в GitHub Releases).
# Запуск из любого места:  bash landing/deploy/vps/deploy.sh      (SKIP_BUILD=1 — выложить уже собранную out/)
set -euo pipefail

HOST="${DEPLOY_HOST:-root@109.172.47.162}"
KEY="${DEPLOY_KEY:-$HOME/.ssh/myhonor_beget}"
DOMAIN="${DEPLOY_DOMAIN:-padly.109-172-47-162.sslip.io}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)" # папка landing/

LOCK="$HOME/.build-lock" # общий замок сборок на маке (8 ГБ): одна сборка / install за раз на все чаты
LOCK_HELD=0
trap '[ "$LOCK_HELD" = 1 ] && rmdir "$LOCK" 2>/dev/null; true' EXIT

acquire_build_lock() {
  local waited=0
  until mkdir "$LOCK" 2>/dev/null; do
    # Замок старше 30 минут без живой сборки — брошенный, снимаем
    if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +30 2>/dev/null)" ] && ! pgrep -f "next build|vite build" >/dev/null; then
      rmdir "$LOCK" 2>/dev/null && continue
    fi
    [ $((waited % 60)) -eq 0 ] && echo "    сборку сейчас делает другой чат ($LOCK) — жду"
    sleep 10
    waited=$((waited + 10))
  done
  LOCK_HELD=1
}

cd "$ROOT"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  free_gb="$(df -g /System/Volumes/Data 2>/dev/null | awk 'NR==2 {print $4}')"
  if [ "${free_gb:-99}" -lt 4 ]; then
    echo "На диске свободно ${free_gb} ГБ — сборка ждёт очистки Корзины"
    exit 1
  fi
  if command -v memory_pressure >/dev/null 2>&1; then
    free_pct="$(memory_pressure | awk -F': ' '/free percentage/ {gsub("%", "", $2); print $2}')"
    if [ "${free_pct:-100}" -lt 25 ]; then
      echo "Свободной памяти ${free_pct}% — подожди минуту и повтори"
      exit 1
    fi
  fi
  acquire_build_lock
  echo "==> Сборка (адрес https://$DOMAIN)"
  rm -rf out
  NEXT_TELEMETRY_DISABLED=1 NEXT_PUBLIC_SITE_URL="https://$DOMAIN" ./node_modules/.bin/next build
  rmdir "$LOCK" 2>/dev/null
  LOCK_HELD=0
fi
[ -f out/index.html ] || { echo "Нет out/index.html — сначала собери сайт"; exit 1; }

echo "==> Выкладка out/ → /var/www/padly"
rsync -rltz --delete \
  --exclude='/downloads/' \
  --exclude='.DS_Store' --exclude='ruvector.db*' --exclude='agentdb.rvf*' --exclude='.swarm' \
  --exclude='.claude-flow' --exclude='README.md' --exclude='.git' \
  -e "ssh -i $KEY -o BatchMode=yes" out/ "$HOST:/var/www/padly/"
ssh -i "$KEY" -o BatchMode=yes "$HOST" 'chown -R root:root /var/www/padly && chmod -R a+rX /var/www/padly'

echo "==> Проверка"
for path in / /setup /faq /download /ruvector.db /downloads/Padly.dmg; do
  echo "    $path $(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN$path")"
done
for asset in $(grep -oE '/_next/static/[^"]+\.(js|css)' out/index.html | sort -u | head -3); do
  echo "    $asset $(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN$asset")"
done
echo "    myhonor.shop $(curl -s -o /dev/null -w '%{http_code}' https://myhonor.shop)"
