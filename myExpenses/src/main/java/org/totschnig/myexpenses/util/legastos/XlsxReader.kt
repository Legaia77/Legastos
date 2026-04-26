package org.totschnig.myexpenses.util.legastos

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

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
 * Uses [ZipFile] (random-access from the central directory), not
 * [java.util.zip.ZipInputStream], because some xlsx producers (e.g. Mercado
 * Pago's account statements) emit ZIP entries with data descriptors and zeroed
 * sizes in the local headers, which ZipInputStream rejects with
 * "invalid entry size (expected 0 but got N bytes)".
 */
object XlsxReader {

    /** Reads the first worksheet from a [File]. */
    fun readFirstSheet(file: File, maxRows: Int? = null): List<List<String?>> {
        var sharedStrings: List<String> = emptyList()
        ZipFile(file).use { zip ->
            zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                sharedStrings = parseSharedStrings(zip.getInputStream(entry).readBytes())
            }
            // Pick the first sheet by lexicographic order of sheetN.xml.
            val sheetNames = mutableListOf<String>()
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val name = e.nextElement().name
                if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
                    sheetNames.add(name)
                }
            }
            sheetNames.sort()
            val firstSheet = sheetNames.firstOrNull() ?: return emptyList()
            val sheetEntry = zip.getEntry(firstSheet) ?: return emptyList()
            return parseSheet(zip.getInputStream(sheetEntry).readBytes(), sharedStrings, maxRows)
        }
    }

    /**
     * Convenience overload that copies an [InputStream] to a temp file under
     * [tempDir] and reads from there. The temp file is always deleted before
     * returning.
     */
    fun readFirstSheet(input: InputStream, tempDir: File, maxRows: Int? = null): List<List<String?>> {
        val temp = File.createTempFile("legastos_xlsx_", ".xlsx", tempDir)
        try {
            input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }
            return readFirstSheet(temp, maxRows)
        } finally {
            temp.delete()
        }
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
