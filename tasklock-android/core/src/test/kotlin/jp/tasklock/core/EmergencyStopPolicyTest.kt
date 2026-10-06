package jp.tasklock.core

import jp.tasklock.core.model.EmergencyStop
import jp.tasklock.core.model.EmergencyStopApp
import jp.tasklock.core.policy.EmergencyStopDecision
import jp.tasklock.core.policy.EmergencyStopPolicy
import jp.tasklock.core.policy.EmergencyStopRejection
import jp.tasklock.core.time.DayBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EmergencyStopPolicyTest {
    private val boundary = DayBoundary(ZoneId.of("Asia/Tokyo"))
    private val now = Instant.parse("2026-10-05T03:00:00Z") // 12:00 JST
    private val day = LocalDate.of(2026, 10, 5)

    private fun active(id: Long, stoppedAt: Instant = now.minusSeconds(3600)) =
        EmergencyStop(id = id, day = day, stoppedAt = stoppedAt)

    private fun resumed(id: Long, stoppedAt: Instant = now.minusSeconds(7200)) =
        EmergencyStop(id = id, day = day, stoppedAt = stoppedAt, resumedAt = stoppedAt.plusSeconds(600))

    private fun rejected(reason: EmergencyStopRejection) = EmergencyStopDecision.Rejected(reason)

    // --- 開始 ---

    @Test
    fun `ロック中で有効な緊急解除が無ければ開始できる`() {
        val decision = EmergencyStopPolicy.decideStart(now, boundary, lockedNow = true, stops = emptyList())
        assertEquals(EmergencyStopDecision.Allowed(EmergencyStop(id = 0, day = day, stoppedAt = now)), decision)
        val stop = (decision as EmergencyStopDecision.Allowed).stop
        assertNull(stop.resumedAt)
        assertNull(stop.reason)
        assertTrue(stop.isActive)
    }

    @Test
    fun `ロック中でなければ開始できない`() {
        assertEquals(
            rejected(EmergencyStopRejection.NOT_LOCKED),
            EmergencyStopPolicy.decideStart(now, boundary, lockedNow = false, stops = emptyList()),
        )
    }

    @Test
    fun `緊急解除中は開始できない`() {
        assertEquals(
            rejected(EmergencyStopRejection.ALREADY_STOPPED),
            EmergencyStopPolicy.decideStart(now, boundary, lockedNow = true, stops = listOf(active(1))),
        )
    }

    @Test
    fun `有効な緊急解除が2件ある異常な状態でも開始できない`() {
        assertEquals(
            rejected(EmergencyStopRejection.ALREADY_STOPPED),
            EmergencyStopPolicy.decideStart(now, boundary, lockedNow = true, stops = listOf(active(1), active(2))),
        )
    }

    @Test
    fun `緊急解除中かつロック中でないときは ALREADY_STOPPED を優先する`() {
        assertEquals(
            rejected(EmergencyStopRejection.ALREADY_STOPPED),
            EmergencyStopPolicy.decideStart(now, boundary, lockedNow = false, stops = listOf(active(1))),
        )
    }

    @Test
    fun `再開済みの記録だけなら開始できる`() {
        val decision = EmergencyStopPolicy.decideStart(
            now, boundary, lockedNow = true, stops = listOf(resumed(1), resumed(2)),
        )
        assertTrue(decision is EmergencyStopDecision.Allowed)
    }

    @Test
    fun `日付は DayBoundary で決まる（0時の前後）`() {
        val beforeMidnight = Instant.parse("2026-10-05T14:59:59Z") // 23:59:59 JST
        val atMidnight = Instant.parse("2026-10-05T15:00:00Z") // 翌日 00:00:00 JST
        val first = EmergencyStopPolicy.decideStart(beforeMidnight, boundary, lockedNow = true, stops = emptyList())
        val second = EmergencyStopPolicy.decideStart(atMidnight, boundary, lockedNow = true, stops = emptyList())
        assertEquals(LocalDate.of(2026, 10, 5), (first as EmergencyStopDecision.Allowed).stop.day)
        assertEquals(LocalDate.of(2026, 10, 6), (second as EmergencyStopDecision.Allowed).stop.day)
        assertEquals(atMidnight, second.stop.stoppedAt)
    }

    // --- 再開 ---

    @Test
    fun `反映後のロック対象が空なら再開ではない`() {
        assertEquals(emptyList<Long>(), EmergencyStopPolicy.resumeTargets(listOf(active(1)), emptySet()))
    }

    @Test
    fun `有効な緊急解除が無ければ再開する記録は無い`() {
        assertEquals(emptyList<Long>(), EmergencyStopPolicy.resumeTargets(emptyList(), setOf("com.sns")))
    }

    @Test
    fun `有効な緊急解除が1件ならその id を再開する`() {
        assertEquals(listOf(1L), EmergencyStopPolicy.resumeTargets(listOf(active(1)), setOf("com.sns")))
    }

    @Test
    fun `有効な緊急解除が2件ならすべて再開する`() {
        assertEquals(
            listOf(1L, 2L),
            EmergencyStopPolicy.resumeTargets(listOf(active(1), active(2)), setOf("com.sns")),
        )
    }

    @Test
    fun `再開済みの記録は再開の対象にしない`() {
        assertEquals(
            listOf(3L),
            EmergencyStopPolicy.resumeTargets(listOf(resumed(1), resumed(2), active(3)), setOf("com.sns")),
        )
    }

    // --- 復元候補 ---

    private val installed = setOf("com.sns", "com.game", "com.video", "com.study", "com.exempt")

    private fun candidates(
        stops: List<EmergencyStop>,
        apps: List<EmergencyStopApp>,
        installed: Set<String> = this.installed,
        exempt: Set<String> = emptySet(),
        studyPackages: Set<String> = emptySet(),
    ) = EmergencyStopPolicy.restoreCandidates(stops, apps, installed, exempt, studyPackages)

    @Test
    fun `アンインストール済み・除外アプリ・学習アプリは候補にしない`() {
        val apps = listOf(
            EmergencyStopApp(1, "com.sns", "SNS"),
            EmergencyStopApp(1, "com.removed", "消したアプリ"),
            EmergencyStopApp(1, "com.exempt", "除外"),
            EmergencyStopApp(1, "com.study", "学習"),
        )
        assertEquals(
            listOf(EmergencyStopApp(1, "com.sns", "SNS")),
            candidates(listOf(active(1)), apps, exempt = setOf("com.exempt"), studyPackages = setOf("com.study")),
        )
    }

    @Test
    fun `有効な緊急解除が無ければ候補は無い`() {
        val apps = listOf(EmergencyStopApp(1, "com.sns", "SNS"))
        assertEquals(emptyList<EmergencyStopApp>(), candidates(emptyList(), apps))
    }

    @Test
    fun `再開済みの緊急解除のアプリは候補にしない`() {
        val apps = listOf(
            EmergencyStopApp(1, "com.game", "ゲーム"),
            EmergencyStopApp(2, "com.sns", "SNS"),
        )
        assertEquals(
            listOf(EmergencyStopApp(2, "com.sns", "SNS")),
            candidates(listOf(resumed(1), active(2)), apps),
        )
    }

    @Test
    fun `2件の有効な緊急解除で同じアプリは1つにまとめ stoppedAt が新しい方の表示名を使う`() {
        // stoppedAt が新しい方の id を小さくし、id ではなく EmergencyStop の時刻で選ぶことを確かめる
        val older = active(id = 5, stoppedAt = now.minusSeconds(7200))
        val newer = active(id = 3, stoppedAt = now.minusSeconds(600))
        val apps = listOf(
            EmergencyStopApp(5, "com.sns", "SNS（古い名前）"),
            EmergencyStopApp(3, "com.sns", "SNS（新しい名前）"),
            EmergencyStopApp(5, "com.game", "ゲーム"),
        )
        assertEquals(
            listOf(
                EmergencyStopApp(3, "com.sns", "SNS（新しい名前）"),
                EmergencyStopApp(5, "com.game", "ゲーム"),
            ),
            candidates(listOf(older, newer), apps),
        )
        // 記録の並びを入れ替えても同じ
        assertEquals(
            candidates(listOf(older, newer), apps),
            candidates(listOf(newer, older), apps.reversed()),
        )
    }

    @Test
    fun `並びは表示名、次にパッケージ名の順`() {
        val apps = listOf(
            EmergencyStopApp(1, "com.video", "B"),
            EmergencyStopApp(1, "com.sns", "A"),
            EmergencyStopApp(1, "com.game", "A"),
        )
        assertEquals(
            listOf("com.game", "com.sns", "com.video"),
            candidates(listOf(active(1)), apps).map { it.packageName },
        )
    }

    // --- 理由の正規化 ---

    @Test
    fun `null と空と空白だけは null`() {
        assertNull(EmergencyStopPolicy.normalizeReason(null))
        assertNull(EmergencyStopPolicy.normalizeReason(""))
        assertNull(EmergencyStopPolicy.normalizeReason("   "))
        assertNull(EmergencyStopPolicy.normalizeReason("　　"))
        assertNull(EmergencyStopPolicy.normalizeReason(" 　\n\t"))
    }

    @Test
    fun `前後の空白を除く`() {
        assertEquals("急用", EmergencyStopPolicy.normalizeReason("　 急用 \n"))
        assertEquals("急 用", EmergencyStopPolicy.normalizeReason(" 急 用 "))
    }

    @Test
    fun `ちょうど200コードポイントはそのまま、201コードポイントは200にする`() {
        val exact = "あ".repeat(200)
        assertEquals(exact, EmergencyStopPolicy.normalizeReason(exact))
        assertEquals(exact, EmergencyStopPolicy.normalizeReason(exact + "い"))
    }

    @Test
    fun `200コードポイントの境界でサロゲートペアを壊さない`() {
        val emoji = "😀" // U+1F600。UTF-16 では2文字（サロゲートペア）
        assertEquals(2, emoji.length)
        // 200番目のコードポイントが絵文字。UTF-16 の length では 201 文字目の途中で切れる位置
        val raw = "a".repeat(199) + emoji + "bc"
        val result = EmergencyStopPolicy.normalizeReason(raw)!!
        assertEquals(200, result.codePointCount(0, result.length))
        assertEquals(201, result.length)
        assertTrue(result.endsWith(emoji))
        assertEquals("a".repeat(199) + emoji, result)
        assertTrue(result.indices.all { !Character.isSurrogate(result[it]) || isPaired(result, it) })
        // すべてが絵文字でも 200 コードポイント（400 UTF-16 文字）で切る
        val allEmoji = EmergencyStopPolicy.normalizeReason(emoji.repeat(201))!!
        assertEquals(emoji.repeat(200), allEmoji)
        assertEquals(200, allEmoji.codePointCount(0, allEmoji.length))
    }

    private fun isPaired(s: String, i: Int): Boolean =
        if (Character.isHighSurrogate(s[i])) i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
        else i > 0 && Character.isHighSurrogate(s[i - 1])

    // --- モデル ---

    @Test
    fun `resumedAt が stoppedAt より前の記録は作れない`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmergencyStop(day = day, stoppedAt = now, resumedAt = now.minusSeconds(1))
        }
    }
}
