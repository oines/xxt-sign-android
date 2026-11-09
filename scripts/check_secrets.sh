#!/usr/bin/env bash
# 提交/推送前扫一遍新增内容里有没有凭据。
#
# 用法：
#   scripts/check_secrets.sh                 # 扫已 staged 的改动（默认）
#   scripts/check_secrets.sh origin/main..HEAD   # 扫一段提交
#   scripts/check_secrets.sh --all           # 扫整个仓库当前快照
#
# 命中任何一条就退出码 1，并打印 文件:行号:内容。
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"

# 这些文件里出现"像 key 的字符串"是正常的：脚本自身的规则、测试用的假 key
ALLOW_FILES=(
  "scripts/check_secrets.sh"
  "scripts/install_hooks.sh"
)

is_allowed() {
  local path="$1"
  local a
  for a in "${ALLOW_FILES[@]}"; do
    [ "$path" = "$a" ] && return 0
  done
  # 测试里的假 key（sk-test 之类）不算泄漏
  case "$path" in
    app/src/test/*) return 1 ;;
  esac
  return 1
}

# 模式 → 人话说明
PATTERNS=(
  'sk-[A-Za-z0-9]{16,}'
  'gh[pousr]_[A-Za-z0-9]{20,}'
  'github_pat_[A-Za-z0-9_]{20,}'
  'Bearer[[:space:]]+[A-Za-z0-9._-]{20,}'
  '(_uid=|_d=|vc3=|fid=)[A-Za-z0-9%._-]{6,}.*(_uid=|_d=|vc3=)'
  '(^|[^0-9])1[3-9][0-9]{9}([^0-9]|$)'
  '(^|[^A-Za-z0-9])(password|passwd|pwd)[[:space:]]*=[[:space:]]*[^[:space:]"$]{3,}'
  'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
)

# 路径类：这些文件根本不该进仓库
FORBIDDEN_PATHS=(
  'local\.properties'
  '(^|/)\.idea/'
  '(^|/)\.env'
  '\.jks$'
  '\.keystore$'
)

mode="${1:---staged}"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
problems=0

check_line() {
  local file="$1" lineno="$2" text="$3"
  local p
  for p in "${PATTERNS[@]}"; do
    if printf '%s' "$text" | grep -qE "$p"; then
      # 占位符/变量引用不算
      case "$text" in
        *'$apiKey'*|*'$token'*|*'<token>'*|*'sk-…'*|*'sk-...'*|*'Bearer $'*) continue ;;
      esac
      printf '❌ %s:%s  %s\n   %s\n' "$file" "$lineno" "$p" "$(printf '%s' "$text" | cut -c1-160)"
      problems=$((problems + 1))
      return 0
    fi
  done
  return 0
}

check_path() {
  local file="$1"
  local p
  for p in "${FORBIDDEN_PATHS[@]}"; do
    if printf '%s' "$file" | grep -qE "$p"; then
      printf '❌ %s  不该提交的文件（本地配置/密钥库）\n' "$file"
      problems=$((problems + 1))
      return 0
    fi
  done
}

scan_patch() {
  local patch_file="$1"
  local cur="" lineno=0
  while IFS= read -r line; do
    case "$line" in
      '+++ b/'*)
        cur="${line#'+++ b/'}"
        lineno=0
        check_path "$cur"
        ;;
      '@@'*)
        # @@ -a,b +c,d @@ → 拿新增段的起始行号
        lineno=$(printf '%s' "$line" | sed -E 's/^@@ [^+]*\+([0-9]+).*/\1/')
        ;;
      @@*)
        ;;
      '+'*)
        [ -n "$cur" ] || continue
        is_allowed "$cur" && { lineno=$((lineno + 1)); continue; }
        check_line "$cur" "$lineno" "${line#+}"
        lineno=$((lineno + 1))
        ;;
      '-'*)
        ;;
      *)
        lineno=$((lineno + 1))
        ;;
    esac
  done < "$patch_file"
}

echo "== 检查凭据 =="
case "$mode" in
  --staged)
    echo "范围：已 staged 的改动"
    git diff --cached -U0 > "$tmp" || true
    scan_patch "$tmp"
    ;;
  --all)
    echo "范围：当前工作区全部文件"
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      check_path "$f"
      is_allowed "$f" && continue
      case "$f" in app/src/test/*) continue ;; esac
      n=0
      while IFS= read -r line; do
        n=$((n + 1))
        check_line "$f" "$n" "$line"
      done < "$f"
    done < <(git ls-files)
    ;;
  *)
    echo "范围：$mode"
    git log -p "$mode" > "$tmp" || true
    scan_patch "$tmp"
    ;;
esac

if [ "$problems" -gt 0 ]; then
  echo
  echo "发现 $problems 处疑似凭据/不该提交的文件。"
  echo "真的不是凭据，就把它换成占位符；确实要提交，加进脚本的 ALLOW_FILES 并说明原因。"
  exit 1
fi

echo "✅ 没发现凭据"
exit 0
