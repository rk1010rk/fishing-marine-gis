package jp.tasklock.core

import jp.tasklock.core.policy.ImportantApps
import org.junit.Assert.assertEquals
import org.junit.Test

/** DESIGN.md §9.6-3: 連絡・移動等の重要アプリの判定 */
class ImportantAppsTest {
    private val sms = setOf("com.example.sms")
    private val geo = setOf("com.google.android.apps.maps", "com.garmin.android.marine")
    private val line = "jp.naver.line.android"
    private val transit = "jp.co.yahoo.android.apps.transit"

    @Test
    fun `an added smsto handler is detected`() {
        assertEquals(setOf("com.example.sms"), ImportantApps.detect(setOf("com.example.sms"), sms, geo))
    }

    @Test
    fun `an added geo handler is detected including unexpected ones`() {
        assertEquals(
            setOf("com.google.android.apps.maps", "com.garmin.android.marine"),
            ImportantApps.detect(setOf("com.google.android.apps.maps", "com.garmin.android.marine"), sms, geo),
        )
    }

    @Test
    fun `added apps on the fixed list are detected`() {
        assertEquals(setOf(line, transit), ImportantApps.detect(setOf(line, transit), emptySet(), emptySet()))
    }

    @Test
    fun `an added app that matches nothing is not detected`() {
        assertEquals(emptySet<String>(), ImportantApps.detect(setOf("com.twitter.android"), sms, geo))
    }

    @Test
    fun `handlers that are not being added are not returned`() {
        // インストール済みの重要アプリを全部警告するのではなく、追加するアプリだけを判定する
        assertEquals(setOf(line), ImportantApps.detect(setOf(line, "com.twitter.android"), sms, geo))
    }

    @Test
    fun `an app matching several rules is returned once`() {
        assertEquals(setOf(line), ImportantApps.detect(setOf(line), smsHandlers = setOf(line), geoHandlers = setOf(line)))
    }

    @Test
    fun `nothing added gives nothing`() {
        assertEquals(emptySet<String>(), ImportantApps.detect(emptySet(), sms, geo))
    }

    @Test
    fun `fixed list holds exactly the two verified packages`() {
        assertEquals(setOf(line, transit), ImportantApps.FIXED_LIST)
    }
}
