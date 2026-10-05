package jp.tasklock.core.lock

import java.time.Instant

/**
 * アクセシビリティサービスのブロック判定。スナップショットの読み込み状態と、直近の前面アプリを保持する。
 *
 * - スナップショット未ロード（null）と「ロック対象なし」（lockedPackages が空）を区別する
 * - 未ロード中はロック対象が分からないのでブロックしないが、前面アプリは記録しておく
 * - 初回ロード完了時に、記録しておいた前面アプリを再判定する（未ロード中に開いた SNS がそのまま使えてしまうのを防ぐ）
 * - 自アプリ・ホーム等（exempt）は未ロード中でもロード後でもブロックしない
 *
 * スレッド: すべてのメソッドを同じスレッド（メインスレッド）から呼ぶこと。
 */
class BlockGate(
    private val ownPackage: String,
    private val debouncer: BlockDebouncer = BlockDebouncer(),
) {
    private var snapshot: BlockSnapshot? = null
    private var foregroundPackage: String? = null

    val isLoaded: Boolean get() = snapshot != null

    /** @return ブロック画面を起動すべきなら true */
    fun onWindowEvent(packageName: String, now: Instant, nowMillis: Long, exemptPackages: () -> Set<String>): Boolean {
        foregroundPackage = packageName
        return decide(packageName, now, nowMillis, exemptPackages)
    }

    /**
     * スナップショットの更新を受け取る。
     * @return 初回ロード時に、前面にいるアプリをブロックすべきならそのパッケージ名。それ以外は null
     */
    fun onSnapshot(newSnapshot: BlockSnapshot, now: Instant, nowMillis: Long, exemptPackages: () -> Set<String>): String? {
        val firstLoad = snapshot == null
        snapshot = newSnapshot
        if (!firstLoad) return null
        val foreground = foregroundPackage ?: return null
        return foreground.takeIf { decide(it, now, nowMillis, exemptPackages) }
    }

    /**
     * 時刻だけが進んだとき（一時解除の期限など、DB が変わらない変化）に、記録している前面アプリを再判定する。
     * いつ呼ぶかは呼び出し側が決める。
     * @return 前面のアプリをブロックすべきならそのパッケージ名。未ロード・前面アプリ不明・ブロック不要なら null
     */
    fun reevaluateForeground(now: Instant, nowMillis: Long, exemptPackages: () -> Set<String>): String? {
        val foreground = foregroundPackage ?: return null
        return foreground.takeIf { decide(it, now, nowMillis, exemptPackages) }
    }

    private fun decide(packageName: String, now: Instant, nowMillis: Long, exemptPackages: () -> Set<String>): Boolean {
        val snap = snapshot
        val block = snap != null &&
            packageName != ownPackage &&
            // ロック対象でなければ exemptPackages()（PackageManager への問い合わせ）を呼ばずに終える
            packageName in snap.lockedPackages &&
            snap.shouldBlock(packageName, now, exemptPackages())
        // ブロックしないイベントも渡し、別パッケージを挟んだときに間引き状態をリセットさせる
        return debouncer.onWindowEvent(packageName, block, nowMillis)
    }
}
