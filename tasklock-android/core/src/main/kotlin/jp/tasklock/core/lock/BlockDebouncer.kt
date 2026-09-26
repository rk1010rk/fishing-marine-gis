package jp.tasklock.core.lock

/**
 * 1回のアプリ起動で同じパッケージのウィンドウイベントが連続するため、ブロック画面の多重起動を抑える。
 *
 * 間引くのは「同じパッケージのイベントが途切れずに続いている間」だけ。
 * 別パッケージ（ホーム・ブロック画面自身・他アプリ）のイベントを1つでも挟んだら状態をリセットするので、
 * 「対象アプリ → ブロック → ホーム → すぐ対象アプリ」は時間に関係なく必ず再ブロックされる。
 */
class BlockDebouncer(private val windowMillis: Long = 700) {
    private var lastPackage: String? = null
    private var lastAt = 0L

    /**
     * すべてのウィンドウイベントで呼ぶこと（ブロック対象外や自アプリのイベントも含む）。
     * @return ブロック画面を起動すべきなら true
     */
    fun onWindowEvent(packageName: String, shouldBlock: Boolean, nowMillis: Long): Boolean {
        if (packageName != lastPackage) lastPackage = null
        if (!shouldBlock) return false
        if (packageName == lastPackage && nowMillis - lastAt < windowMillis) return false
        lastPackage = packageName
        lastAt = nowMillis
        return true
    }
}
