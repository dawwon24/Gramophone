package org.akanework.gramophone.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KSearchTest {
    @Test
    fun plain() {
        assertTrue(KSearch.matches("Mr.Children", "children"))
        assertTrue(KSearch.matches("이승철", "승철"))
        assertTrue(KSearch.matches("HUNTR/X", "huntr"))
        assertTrue(KSearch.matches("anything", ""))
        assertFalse(KSearch.matches(null, "a"))
    }

    @Test
    fun choseong() {
        assertTrue(KSearch.matches("이승철", "ㅇㅅㅊ"))
        assertTrue(KSearch.matches("이승철", "ㅅㅊ"))
        assertTrue(KSearch.matches("이승철", "이ㅅ철"))
        assertTrue(KSearch.matches("리센느", "ㄹㅅㄴ"))
        assertFalse(KSearch.matches("이승철", "ㄱㅅㅊ"))
    }

    @Test
    fun romaji() {
        assertEquals("supittsu", KSearch.romaji("スピッツ"))
        assertEquals("kyou", KSearch.romaji("きょう"))
        assertTrue(KSearch.matches("スピッツ", "spitz"))
        assertTrue(KSearch.matches("スピッツ", "supittsu"))
        assertTrue(KSearch.matches("スピッツ", "spitts"))
        assertTrue(KSearch.matches("ミスターチルドレン", "misuta"))
        assertTrue(KSearch.matches("あいみょん", "aimyon"))
        assertFalse(KSearch.matches("スピッツ", "beatles"))
        assertFalse(KSearch.matches("スピッツ", "sa"))
    }
}
