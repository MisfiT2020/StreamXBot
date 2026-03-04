#!/bin/bash
set -eo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
echo "[common] APP_DIR=${APP_DIR}"


IS_RENDER=0
IS_HEROKU=0
IS_KOYEB=0

if [ -n "${RENDER:-}" ] || [ -n "${RENDER_SERVICE_ID:-}" ]; then
  IS_RENDER=1
fi

if [ -n "${DYNO:-}" ]; then
  IS_HEROKU=1
fi

if [ -n "${KOYEB_APP_NAME:-}" ] || [ -n "${KOYEB_SERVICE_NAME:-}" ]; then
  IS_KOYEB=1
fi


WANT_ENV_CONFIG=0

if [ "$IS_RENDER" = "1" ] || [ "$IS_HEROKU" = "1" ] || [ "$IS_KOYEB" = "1" ]; then
  WANT_ENV_CONFIG=1
fi

echo "[common] WANT_ENV_CONFIG=${WANT_ENV_CONFIG}"


if [ "$WANT_ENV_CONFIG" = "1" ]; then
  if [ -z "${CONFIG_GIST:-}" ]; then
    echo "[common] CONFIG_GIST not set"
    exit 1
  fi

  echo "[common] Downloading config.py from CONFIG_GIST"
  echo "[common] CONFIG_GIST=${CONFIG_GIST}"

  tmp_cfg="$(mktemp)"

  if curl -fsSL \
      --retry 3 \
      --retry-delay 2 \
      --connect-timeout 10 \
      --max-time 30 \
      "${CONFIG_GIST}" -o "${tmp_cfg}"; then

    if [ -s "${tmp_cfg}" ]; then
      mv "${tmp_cfg}" "${APP_DIR}/config.py"
      echo "[common] config.py downloaded successfully"
    else
      echo "[common] Downloaded config is empty"
      rm -f "${tmp_cfg}"
      exit 1
    fi
  else
    rm -f "${tmp_cfg}"
    echo "[common] Failed to download config.py"
    exit 1
  fi
fi


echo "[common] Checking config.py..."

if [ -f "${APP_DIR}/config.py" ]; then
  echo "[common] config.py exists"
else
  echo "[common] config.py NOT found!"
fi


VENV_PATH="${VENV_PATH:-/app/streamvenv}"

if [ -d "$VENV_PATH" ]; then
  echo "[common] Activating venv at $VENV_PATH"
  source "$VENV_PATH/bin/activate"
else
  echo "[common] No venv found at $VENV_PATH"
fi

if [ "$IS_RENDER" = "1" ]; then
  echo "[Render] Environment detected → skipping git pull"

elif [ "$IS_HEROKU" = "1" ]; then
  echo "[Heroku] Environment detected → skipping git pull"

elif [ "$IS_KOYEB" = "1" ]; then
  echo "[Koyeb] Environment detected → skipping git pull"

elif git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "[VPS] Git repo detected → running git pull"

  if [ -n "${GITHUB_TOKEN:-}" ]; then
    echo "[VPS] Configuring Git authentication"

    ORIGIN_URL="$(git remote get-url origin 2>/dev/null || true)"
    ORIGIN_PATH=""

    if echo "${ORIGIN_URL}" | grep -qE '^git@github\.com:'; then
      ORIGIN_PATH="${ORIGIN_URL#git@github.com:}"
    elif echo "${ORIGIN_URL}" | grep -qE '^https?://([^@/]+@)?github\.com/'; then
      ORIGIN_PATH="$(echo "${ORIGIN_URL}" | sed -E 's#^https?://([^@/]+@)?github\.com/##')"
    fi

    if [ -n "${ORIGIN_PATH}" ]; then
      git remote set-url origin "https://${GITHUB_TOKEN}@github.com/${ORIGIN_PATH}"
    fi
  fi

  git reset --hard
  git pull --ff-only

else
  echo "[common] No git repo detected → skipping git pull"
fi


echo "[common] Starting bot..."

exec python3 -m stream