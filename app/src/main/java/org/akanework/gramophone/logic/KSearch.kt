/*
 *     Gramophone K customizations.
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic

import java.text.Normalizer

/**
 * Gramophone K: list filter matching.
 *  - plain substring (case / width / accent insensitive, spaces ignored)
 *  - Korean initial consonants: "ㅇㅅㅊ" finds "이승철" (can be mixed: "이ㅅ철")
 *  - Japanese kana by romaji: "spitz" finds "スピッツ" (loose: vowels may be left out)
 */
object KSearch {
    private val choseong = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    private fun isSyllable(c: Char) = c in '가'..'힣'
    private fun isJamo(c: Char) = c in 'ㄱ'..'ㅎ'
    private fun initialOf(c: Char) = choseong[(c.code - 0xAC00) / 588]

    // NFKC turns compatibility jamo into conjoining jamo (U+1100..); map initials back
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
            .filterNot { it.isWhitespace() }
            .map { if (it in '\u1100'..'\u1112') choseong[it.code - 0x1100] else it }
            .joinToString("")

    private fun charMatches(q: Char, c: Char) =
        q == c || (isJamo(q) && isSyllable(c) && initialOf(c) == q)

    private fun hangulMatch(text: String, query: String): Boolean {
        if (query.length > text.length) return false
        outer@ for (start in 0..text.length - query.length) {
            for (i in query.indices) {
                if (!charMatches(query[i], text[start + i])) continue@outer
            }
            return true
        }
        return false
    }

    // --- kana -> romaji (Hepburn-ish) ---
    private val kana: Map<String, String> = run {
        val m = HashMap<String, String>()
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"),
            "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "さしすせそ" to listOf("sa", "shi", "su", "se", "so"),
            "ざじずぜぞ" to listOf("za", "ji", "zu", "ze", "zo"),
            "たちつてと" to listOf("ta", "chi", "tsu", "te", "to"),
            "だぢづでど" to listOf("da", "ji", "zu", "de", "do"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "fu", "he", "ho"),
            "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "ぁぃぅぇぉ" to listOf("a", "i", "u", "e", "o"),
            "ゃゅょ" to listOf("ya", "yu", "yo"),
            "ゔ" to listOf("vu"),
        )
        for ((chars, romaji) in rows) chars.forEachIndexed { i, c -> m[c.toString()] = romaji[i] }
        // youon: きゃ -> kya, しゃ -> sha, ちゃ -> cha, じゃ -> ja
        val small = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")
        for (base in "きぎしじちぢにひびぴみり") {
            val r = m[base.toString()]!!
            for ((s, v) in small) {
                m["$base$s"] = when {
                    r == "shi" -> "sh$v"
                    r == "chi" -> "ch$v"
                    r == "ji" -> "j$v"
                    else -> r.dropLast(1) + "y" + v
                }
            }
        }
        // Katakana extras (written as hiragana after conversion)
        m["ふぁ"] = "fa"; m["ふぃ"] = "fi"; m["ふぇ"] = "fe"; m["ふぉ"] = "fo"
        m["てぃ"] = "ti"; m["でぃ"] = "di"; m["うぃ"] = "wi"; m["うぇ"] = "we"
        m["ゔぁ"] = "va"; m["ゔぃ"] = "vi"; m["ゔぇ"] = "ve"; m["ゔぉ"] = "vo"
        m
    }

    private fun toHiragana(c: Char) = if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c

    /** Romaji for the kana in [s]; other characters are kept. Null if there is no kana. */
    fun romaji(s: String): String? {
        val h = s.map { toHiragana(it) }.joinToString("")
        if (h.none { it in 'ぁ'..'ゖ' }) return null
        val sb = StringBuilder()
        var i = 0
        var geminate = false
        while (i < h.length) {
            val c = h[i]
            if (c == 'っ') { geminate = true; i++; continue }
            if (c == 'ー') { sb.lastOrNull()?.let { sb.append(it) }; i++; continue }
            val two = if (i + 1 < h.length) h.substring(i, i + 2) else null
            val r = (two?.let { kana[it] }?.also { i += 2 }) ?: kana[c.toString()]?.also { i++ }
                ?: c.toString().also { i++ }
            if (geminate && r.isNotEmpty() && r[0].isLetter()) sb.append(if (r.startsWith("ch")) 't' else r[0])
            geminate = false
            sb.append(r)
        }
        return sb.toString()
    }

    /** Loose form so that "spit", "spitz" ~ "supittsu": no double letters, tsu/tz -> ts, z -> s, drop vowels after the first letter. */
    private fun loose(s: String): String {
        val doubles = Regex("(.)\\1+")
        var t = s.lowercase().replace(doubles, "$1")
        t = t.replace("tsu", "ts").replace("tz", "ts").replace("z", "s")
        t = t.replace(doubles, "$1")
        return t.firstOrNull()?.let { first ->
            first + t.drop(1).filterNot { it in "aeiou" }
        } ?: ""
    }

    private fun isSubsequence(q: String, t: String): Boolean {
        var j = 0
        for (c in t) if (j < q.length && q[j] == c) j++
        return j == q.length
    }

    fun matches(text: String?, rawQuery: String): Boolean {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return true
        if (text == null) return false
        val t = normalize(text)
        if (t.contains(query)) return true
        if (query.any { isJamo(it) } && hangulMatch(t, query)) return true
        if (query.all { it in 'a'..'z' } && query.length >= 2) {
            val r = romaji(t) ?: return false
            if (r.contains(query)) return true
            if (query.length < 3) return false
            val lq = loose(query)
            val lr = loose(r)
            return lq.isNotEmpty() && lr.firstOrNull() == lq.first() && isSubsequence(lq, lr)
        }
        return false
    }
}
