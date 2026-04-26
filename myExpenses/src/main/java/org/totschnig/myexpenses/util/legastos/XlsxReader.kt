package org.totschnig.myexpenses.util.legastos

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal read-only XLSX (Office Open XML) parser.
 *
 * An xlsx file is a zip containing XML. We only need:
 *  - `xl/sharedStrings.xml` — string table
 *  - `xl/worksheets/sheet1.xml` (or another sheetN.xml) — actual cells
 *
 * For the formats we care about, we extract the FIRST worksheet as a list of
 * rows, where each row is a list of cell values normalized to strings (or null
 * for empty cells). Numbers are preserved as their string representation.
 *
 * This is intentionally tiny: no styles, no formulas, no dates-as-serials. The
 * BBVA / Mercado Pago files we target don't use those.
 */
object XlsxReader {

    /**
     * Reads the first worksheet of [input] and returns its rows.
     *
     * If [maxRows] is non-null, parsing stops after that many data rows are
     * collected (useful for previewing).
     */
    fun readFirstSheet(input: InputStream, maxRows: Int? = null): List<List<String?>> {
        var sharedStrings: List<String> = emptyList()
        val sheetCandidates = mutableMapOf<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" ->
                        sharedStrings = parseSharedStrings(zip.readBytes())
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") ->
                        sheetCandidates[name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val sheetBytes = sheetCandidates.entries
            .sortedBy { it.key }
            .firstOrNull()?.value ?: return emptyList()

        return parseSheet(sheetBytes, sharedStrings, maxRows)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var current = StringBuilder()
        var insideSi = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { insideSi = true; current = StringBuilder() }
                    "t" -> if (insideSi) {
                        val txt = parser.nextText()
                        current.append(txt)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    result.add(current.toString())
                    insideSi = false
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(
        bytes: ByteArray,
        sharedStrings: List<String>,
        maxRows: Int?,
    ): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), null)
        }

        // Per-row state.
        var rowCells = mutableMapOf<Int, String?>()

        // Per-cell state.
        var cellRef: String? = null
        var cellType: String? = null
        var cellValue: String? = null
        var inlineString: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowCells = mutableMapOf()
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r")
                        cellType = parser.getAttributeValue(null, "t")
                        cellValue = null
                        inlineString = null
                    }
                    "v" -> cellValue = parser.nextText()
                    "t" -> if (cellType == "inlineStr" || cellType == "str") {
                        inlineString = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val colIndex = columnIndex(cellRef)
                        val resolved: String? = when (cellType) {
                            "s" -> cellValue?.toIntOrNull()?.let { sharedStrings.getOrNull(it) }
                            "inlineStr" -> inlineString
                            "str" -> inlineString ?: cellValue
                            "b" -> if (cellValue == "1") "true" else "false"
                            else -> cellValue
                        }
                        if (colIndex >= 0) rowCells[colIndex] = resolved
                    }
                    "row" -> {
                        val maxCol = rowCells.keys.maxOrNull() ?: -1
                        val list = if (maxCol < 0) emptyList()
                        else (0..maxCol).map { rowCells[it] }
                        rows.add(list)
                        if (maxRows != null && rows.size >= maxRows) {
                            return rows
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /**
     * Converts an XLSX cell reference like "B12" or "AA3" to a 0-based column
     * index. Returns -1 if [ref] is null or malformed.
     */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var col = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        return col - 1
    }
}
