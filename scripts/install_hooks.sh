#!/usr/bin/env bash
# 装 .git/hooks/pre-push：每次 push 自动扫凭据，命中就拦下。
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

hook=".git/hooks/pre-push"
mkdir -p .git/hooks

cat > "$hook" <<'HOOK'
#!/usr/bin/env bash
# 自动生成：push 前检查要推上去的提交里有没有凭据
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
while read -r local_ref local_sha remote_ref remote_sha; do
  [ -z "${local_sha:-}" ] && continue
  case "$local_sha" in *0000000000000000000000000000000000000000) continue ;; esac
  if [ -z "${remote_sha:-}" ] || printf '%s' "$remote_sha" | grep -qE '^0+$'; then
    range="origin/main..$local_sha"
  else
    range="$remote_sha..$local_sha"
  fi
  echo "== 检查 $range =="
  if ! scripts/check_secrets.sh "$range"; then
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo
  echo "❌ 推送被拦下：要推的内容里有疑似凭据。"
  echo "   确认不是凭据后再推：git push --no-verify"
  exit 1
fi
HOOK

chmod +x "$hook"
chmod +x scripts/check_secrets.sh
echo "✅ 已安装 .git/hooks/pre-push"
