#!/bin/sh
# イントラネット（外部接続なし）用の単体HTMLを作る。
# index.html に文書の骨組み（doctype・head）を付け、Google Fonts の読み込みを外す。
# フォントは端末の日本語フォント（游ゴシック・メイリオなど）で表示される。
set -eu
cd "$(dirname "$0")"
{
  printf '<!doctype html>\n<html lang="ja">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n'
  grep -v -e 'fonts.googleapis.com' -e 'fonts.gstatic.com' -e 'claude:viewport-fit-sides' index.html
  printf '</html>\n'
} > vta-board-offline.html
echo "vta-board-offline.html を作成しました"
