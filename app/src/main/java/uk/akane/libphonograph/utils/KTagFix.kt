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

package uk.akane.libphonograph.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Gramophone K: tag text repair and folder-based album grouping.
 */
object KTagFix {
    private fun charsetOrNull(vararg names: String): Charset? {
        for (name in names) {
            try {
                if (Charset.isSupported(name)) return Charset.forName(name)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private val utf8 = Charsets.UTF_8

    // windows-1252 0x80..0x9F, for strings that were decoded as cp1252 instead of ISO-8859-1
    private val cp1252Reverse: Map<Char, Int> = run {
        val chars = "€‚ƒ„…†‡ˆ‰Š‹ŒŽ" +
                "‘’“”•–—˜™š›œžŸ"
        chars.withIndex().associate { (i, c) -> c to (0x80 + i) }
    }
    private val sjis = charsetOrNull("windows-31j", "MS932", "Shift_JIS")
    private val cp949 = charsetOrNull("x-windows-949", "windows-949", "MS949", "EUC-KR")
    private val eucKr = charsetOrNull("EUC-KR")

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun isHangulSyllable(c: Char) = c in '가'..'힣'
    private fun isKana(c: Char) = c in '぀'..'ヿ'
    private fun isHan(c: Char) = c in '一'..'鿿'
    private fun isGenerallyBad(c: Char) =
        c in '\u0080'..'\u009F' || c in ''..'' || c == '�' || c.isISOControl()

    private fun looksJapanese(s: String): Boolean {
        if (s.none { isKana(it) || isHan(it) }) return false
        // Korean text read as Shift_JIS turns into half-width katakana; reject that
        return s.none { isGenerallyBad(it) || it in '｡'..'ﾟ' || isHangulSyllable(it) }
    }

    private fun looksKorean(s: String): Boolean {
        if (s.none { isHangulSyllable(it) || isHan(it) }) return false
        val enc = eucKr?.newEncoder()
        // Japanese text read as CP949 turns into rare (non KS X 1001) syllables; reject that
        return s.none {
            isGenerallyBad(it) || (isHangulSyllable(it) && enc != null && !enc.canEncode(it))
        }
    }

    /**
     * Tags written in Shift_JIS / CP949 (or double-encoded UTF-8) often end up being read as
     * ISO-8859-1, which gives garbage like "Ã¬Â•" or "‚¨‚Í‚æ‚¤". Try to undo that.
     */
    fun fixMojibake(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        // Each char must map back to one byte, either as ISO-8859-1 or as windows-1252
        val bytes = ByteArray(s.length)
        var hasHigh = false
        for (i in s.indices) {
            val c = s[i]
            val b = if (c.code <= 0xFF) c.code else cp1252Reverse[c] ?: return s
            if (b >= 0x80) hasHigh = true
            bytes[i] = b.toByte()
        }
        if (!hasHigh) return s
        decodeStrict(bytes, utf8)?.let { return it }
        val ja = sjis?.let { decodeStrict(bytes, it) }?.takeIf { looksJapanese(it) }
        val ko = cp949?.let { decodeStrict(bytes, it) }?.takeIf { looksKorean(it) }
        return when {
            ja != null && ko != null ->
                if (ko.any { isHangulSyllable(it) }) ko else ja
            else -> ja ?: ko ?: s
        }
    }

    private val discFolderRegex =
        Regex("^(cd|disc|disk|dvd)[\\s._-]*\\d+.*$", RegexOption.IGNORE_CASE)

    /** The folder that represents an album; "CD1"/"Disc 2" sub folders count as their parent. */
    fun albumFolder(parent: File): File {
        if (discFolderRegex.matches(parent.name)) {
            parent.parentFile?.let { return it }
        }
        return parent
    }

    fun normalizeAlbumTitle(title: String) =
        title.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Album id derived from (album folder, album title) instead of MediaStore's ALBUM_ID, so
     * that songs of one album in one folder are always one album, even if their album artists
     * differ. Always negative so it can't collide with MediaStore ids.
     */
    fun albumId(parent: File, album: String): Long {
        val key = albumFolder(parent).absolutePath.lowercase() + "\u0000" +
                normalizeAlbumTitle(album)
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (c in key) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash or Long.MIN_VALUE
    }
}
