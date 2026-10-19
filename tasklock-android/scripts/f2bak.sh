#!/bin/bash
# §10 F の手順2の前のバックアップ（読むだけ。端末の DB・設定・アプリは変えない）
# 使い方: bash ~/tasklock-adb-check/f2bak.sh   （テストの当日、アンインストールの直前に1回だけ実行する）
#
# - 端末の databases/ と shared_prefs/ のファイルを、手元の新しいフォルダにコピーする（既存のフォルダは上書きしない）
# - 対象の列挙: databases/ は tasklock.db・-wal・-shm・-journal だけを認める。それ以外のファイルがあれば STOP
#   （未知の関連ファイルを黙って無視しない）。shared_prefs/ は .xml だけを認め、それ以外があれば STOP
# - 整合性: コピーの前に「両フォルダの一覧」と「全ファイルの SHA-256」を記録し、全ファイルをコピーした後にもう一度
#   両方を記録する。一覧が変わっていないこと、全ファイルで「前」「手元」「後」の SHA-256 が一致することを確かめる。
#   違えば STOP（実行中は TaskLock に触らない）
# - 限界: SHA-256 の照合は「その間に内容が変わらなかった」ことの確認で、SQLite の一貫したスナップショットを直接保証する
#   仕組みではない。「前」を読む前と「後」を読んだ後の書き込みは対象外。書き込み後に元と同じ内容に戻った場合は検出できない
# - 検証: 作業用のコピー（verify/）を読み取り専用で開き、PRAGMA integrity_check と各表の行数を記録する。
#   元のコピー（raw/）は開かず、最後に SHA-256 が変わっていないことを確かめる
# - APK が run #56 の SHA-256 と違えば STOP（テスト対象の取り違えを防ぐため）
# - 判定（合否）はしない。STOP が出なければ、記録を人が確かめる
set -euo pipefail
trap 'echo "STOP: ${BASH_SOURCE[0]} の ${LINENO} 行目で失敗した（終了コード $?）"' ERR

pkg=jp.tasklock.app
expected_apk=5db65ee2f9e25f2a574fac9917117bff920a1675a963fae52d436baa7b04ee2d
base="$HOME/tasklock-adb-check/f2bak"
out="$base/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$base"
mkdir "$out"            # 既にあれば失敗する（上書きしない）
mkdir "$out/raw" "$out/verify"
exec > >(tee "$out/f2bak.log") 2>&1

echo "out: $out"
echo "start (Chromebook): $(date '+%F %T %Z')"

# sha256sum の出力が「64桁の小文字16進数・空白2つ・期待するパス」の1行だけなら、ハッシュを出す。それ以外は失敗
# 複数行の出力は、期待するパスとの完全一致に失敗するため STOP する
sha_of() {
  [[ "$1" =~ ^([0-9a-f]{64})\ \ (.+)$ ]] && [ "${BASH_REMATCH[2]}" = "$2" ] && printf '%s' "${BASH_REMATCH[1]}"
}

echo "--- 端末"
n=$(adb devices | sed '1d' | grep -c 'device$' || true)
adb devices -l
if [ "$n" -ne 1 ]; then echo "STOP: 端末が1台ではない（${n} 台）"; exit 1; fi
echo "端末の時刻: $(adb shell "date '+%F %T %Z'" | tr -d '\r')"

echo "--- 版"
adb shell dumpsys package "$pkg" | grep -E "versionName|firstInstallTime|lastUpdateTime" || echo "(該当する行なし)"
apk=$(adb shell pm path "$pkg" | tr -d '\r' | sed -n 's/^package://p')
if [ "$(printf '%s\n' "$apk" | grep -c .)" -ne 1 ]; then echo "STOP: APK のパスが1つではない"; exit 1; fi
apk_out=$(adb shell sha256sum "$apk" | tr -d '\r') || {
  echo "STOP: APK の SHA-256 取得コマンドが失敗した"
  exit 1
}
echo "APK: $apk"
if ! apk_sha=$(sha_of "$apk_out" "$apk"); then echo "STOP: APK の SHA-256 の出力の形が違う（出力: ${apk_out:-なし}）"; exit 1; fi
echo "APK SHA-256: $apk_sha"
if [ "$apk_sha" != "$expected_apk" ]; then echo "STOP: APK の SHA-256 が run #56（${expected_apk}）と違う"; exit 1; fi

# 両フォルダの一覧（名前だけ。1行に1つ。並べ替え済み）
# shared_prefs の有無は、アプリのフォルダの一覧（ls -1 .）に名前があるかで判断する。一覧を読めたこと（databases が
# 載っていること）を確かめてから判断し、読めなければ STOP（確認の失敗を「shared_prefs が無い」と扱わない）。
# $(...) の中では set -e が効かないため、失敗はこの関数の中で明示的に返す
list_files() {
  local top db prefs
  top=$(adb shell run-as "$pkg" ls -1 . | tr -d '\r') || { echo "STOP: アプリのフォルダの一覧を読めない" >&2; return 1; }
  if ! printf '%s\n' "$top" | grep -qx 'databases'; then
    echo "STOP: アプリのフォルダの一覧に databases が無い（出力: ${top:-なし}）" >&2; return 1
  fi
  db=$(adb shell run-as "$pkg" ls -1 databases | tr -d '\r' | sort) || { echo "STOP: databases の一覧を読めない" >&2; return 1; }
  if printf '%s\n' "$top" | grep -qx 'shared_prefs'; then
    prefs=$(adb shell run-as "$pkg" ls -1 shared_prefs | tr -d '\r' | sort) || { echo "STOP: shared_prefs の一覧を読めない" >&2; return 1; }
    echo "shared_prefs: ある" >&2
  else
    prefs=""
    echo "shared_prefs: 無い（アプリのフォルダの一覧に無い）" >&2
  fi
  printf '%s\n' "$db" | sed '/^$/d; s#^#databases/#'
  printf '%s\n' "$prefs" | sed '/^$/d; s#^#shared_prefs/#'
}

echo "--- 端末のファイル一覧"
list_before=$(list_files)
adb shell run-as "$pkg" ls -l databases
if grep -q '^shared_prefs/' <<< "$list_before"; then adb shell run-as "$pkg" ls -l shared_prefs; fi
printf '%s\n' "$list_before" > "$out/list_before.txt"

# 対象の確認（未知のファイルがあれば STOP）
files=()
unknown=0
while read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    databases/tasklock.db|databases/tasklock.db-wal|databases/tasklock.db-shm|databases/tasklock.db-journal) files+=("$f") ;;
    shared_prefs/*.xml) files+=("$f") ;;
    *) echo "未知のファイル: $f"; unknown=1 ;;
  esac
done <<< "$list_before"
if [ "$unknown" -ne 0 ]; then echo "STOP: 想定していないファイルがある（扱いを決めてから実行する）"; exit 1; fi
if ! printf '%s\n' "${files[@]}" | grep -qx 'databases/tasklock.db'; then echo "STOP: tasklock.db が無い"; exit 1; fi
echo "対象: ${#files[@]} 個"
printf '  %s\n' "${files[@]}"

dev_sha() {
  local o h
  o=$(adb shell run-as "$pkg" sha256sum "$1" | tr -d '\r') || {
    echo "STOP: $1 の SHA-256 取得コマンドが失敗した" >&2; return 1
  }
  if ! h=$(sha_of "$o" "$1"); then echo "STOP: $1 の SHA-256 の出力の形が違う（出力: ${o:-なし}）" >&2; return 1; fi
  printf '%s' "$h"
}

echo "--- コピー（一覧と、全ファイルの前・手元・後の SHA-256 をまとめて照合）"
echo "copy start: $(date '+%F %T')"
declare -A before after
for f in "${files[@]}"; do before[$f]=$(dev_sha "$f"); done
for f in "${files[@]}"; do
  mkdir -p "$out/raw/$(dirname "$f")"
  adb exec-out run-as "$pkg" cat "$f" > "$out/raw/$f"
done
for f in "${files[@]}"; do after[$f]=$(dev_sha "$f"); done
list_after=$(list_files)
printf '%s\n' "$list_after" > "$out/list_after.txt"
echo "copy end: $(date '+%F %T')"

ng=0
if [ "$list_before" != "$list_after" ]; then
  echo "NG: コピーの前と後で、ファイルの一覧が違う"
  diff "$out/list_before.txt" "$out/list_after.txt" || true
  ng=1
fi
: > "$out/SHA256SUMS"
for f in "${files[@]}"; do
  local_sha=$(sha256sum "$out/raw/$f" | awk '{print $1}')
  size=$(stat -c %s "$out/raw/$f")
  echo "$f: size=$size before=${before[$f]} local=$local_sha after=${after[$f]}"
  if [ "${before[$f]}" != "$local_sha" ] || [ "$local_sha" != "${after[$f]}" ]; then
    echo "NG: $f の SHA-256 が一致しない"
    ng=1
  fi
  (cd "$out/raw" && sha256sum "$f") >> "$out/SHA256SUMS"
done
if [ "$ng" -ne 0 ]; then
  echo "STOP: コピーの間に変化があった可能性がある。このバックアップは使わず、TaskLock に触らずに新しく取り直す"
  exit 1
fi

echo "--- 検証（作業用のコピーを読み取り専用で開く。元のコピーは開かない）"
cp -p "$out"/raw/databases/* "$out/verify/"
python3 -I - "$out/verify/tasklock.db" <<'PY'
import sqlite3, sys
c = sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True)
print('sqlite', sqlite3.sqlite_version, 'journal_mode', c.execute('PRAGMA journal_mode').fetchone()[0])
r = c.execute('PRAGMA integrity_check').fetchall()
print('integrity_check', r)
if r != [('ok',)]:
    print('STOP: integrity_check が ok ではない')
    sys.exit(1)
for (t,) in c.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name"):
    print('rows', t, c.execute('select count(*) from "%s"' % t.replace('"', '""')).fetchone()[0])
PY

echo "--- 元のコピーが変わっていないことの確認"
(cd "$out/raw" && sha256sum --check --strict "$out/SHA256SUMS")
echo "end (Chromebook): $(date '+%F %T %Z')"
echo "完了: STOP は出ていない。記録は $out"
