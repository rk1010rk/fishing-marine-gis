package jp.tasklock.core.policy

/**
 * 連絡・移動等の重要アプリの判定（DESIGN.md §9.6-3）。I/O を持たない純粋関数。
 * 警告のための判定で、ロック対象への登録は禁止しない（[ChangePolicy] の判定とは無関係）。
 */
object ImportantApps {

    /**
     * インテントでは検出できない主要アプリの固定リスト。最小限とし、実際に確かめたパッケージ名だけを登録する
     * （2026-09-27 に Pixel 9a で確認）。追加する場合は §9.6-3 の仕様変更として扱う
     */
    val FIXED_LIST: Set<String> = setOf(
        "jp.naver.line.android", // LINE
        "jp.co.yahoo.android.apps.transit", // Y!乗換案内
    )

    /**
     * [added]（ロック対象に追加するアプリ）のうち、`smsto:` の受け手・`geo:` の受け手・固定リストの
     * いずれかに該当するものを返す。受け手の一覧にあっても、追加しないアプリは返さない
     */
    fun detect(
        added: Set<String>,
        smsHandlers: Set<String>,
        geoHandlers: Set<String>,
        fixedList: Set<String> = FIXED_LIST,
    ): Set<String> = added.filterTo(mutableSetOf()) { it in smsHandlers || it in geoHandlers || it in fixedList }
}
