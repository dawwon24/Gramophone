package uk.akane.libphonograph.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class KTagFixTest {
    // what Android shows when tag bytes in `charset` are read as ISO-8859-1
    private fun garble(s: String, charset: String) =
        String(s.toByteArray(charset(charset)), Charsets.ISO_8859_1)

    private fun garble1252(s: String, charset: String) =
        String(s.toByteArray(charset(charset)), charset("windows-1252"))

    @Test
    fun japanese() {
        for (s in listOf("ミスチル", "しるし", "名もなき詩", "掌", "Mr.Children 抱きしめたい", "君が好き")) {
            assertEquals(s, KTagFix.fixMojibake(garble(s, "windows-31j")))
        }
        assertEquals("しるし", KTagFix.fixMojibake(garble1252("しるし", "windows-31j")))
    }

    @Test
    fun korean() {
        for (s in listOf("리센느", "블레이드 러너", "아이유 좋은 날", "가수 노래 제목")) {
            assertEquals(s, KTagFix.fixMojibake(garble(s, "x-windows-949")))
        }
    }

    @Test
    fun utf8DoubleEncoded() {
        assertEquals("케이팝 데몬 헌터스", KTagFix.fixMojibake(garble("케이팝 데몬 헌터스", "UTF-8")))
        assertEquals("ロビンソン", KTagFix.fixMojibake(garble("ロビンソン", "UTF-8")))
    }

    @Test
    fun leavesNormalTextAlone() {
        for (s in listOf("Beyoncé", "Sigur Rós", "Café del Mar", "Motörhead", "I ♥ U", "スピッツ", "HUNTR/X", "")) {
            assertEquals(s, KTagFix.fixMojibake(s))
        }
        assertEquals(null, KTagFix.fixMojibake(null))
    }

    @Test
    fun albumFolder() {
        assertEquals(File("/m/Spitz/Hachimitsu"), KTagFix.albumFolder(File("/m/Spitz/Hachimitsu/CD1")))
        assertEquals(File("/m/X"), KTagFix.albumFolder(File("/m/X/Disc 2")))
        assertEquals(File("/m/I LOVE U"), KTagFix.albumFolder(File("/m/I LOVE U")))
        assertEquals(KTagFix.albumId(File("/m/a/CD1"), "I ♥ U"), KTagFix.albumId(File("/m/a/CD2"), "i ♥  u"))
        assert(KTagFix.albumId(File("/m/a"), "x") < 0)
    }
}
