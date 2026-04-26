package org.totschnig.myexpenses.util.legastos

import android.content.SharedPreferences
import org.totschnig.myexpenses.util.legastos.LegastosCategories.Cat
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class XlsxFormat {
    BBVA_CARD,
    MERCADO_PAGO,
    UNKNOWN,
}

/**
 * Detected currency for a row.
 */
enum class RowCurrency { ARS, USD }

/**
 * Single transaction extracted from a source xlsx, ready to be reviewed and
 * inserted.
 *
 * [amountMinor] is in minor units of [currency] (i.e. cents). Sign convention:
 * negative = expense (money out), positive = income (money in).
 */
data class ParsedTransaction(
    val date: LocalDate,
    val description: String,
    val amountMinor: Long,
    val currency: RowCurrency,
    val category: Cat,
    val isInternalTransfer: Boolean = false,
    val sourceRow: Int,
)

object XlsxParser {

    fun detect(rows: List<List<String?>>): XlsxFormat {
        // Look at the first ~5 non-empty rows for signature headers.
        val firstFew = rows.take(8).map { row -> row.joinToString("|") { it.orEmpty() }.uppercase() }
        val joined = firstFew.joinToString("\n")
        return when {
            "TOTAL PESOS" in joined && "TOTAL DÓLARES" in joined -> XlsxFormat.BBVA_CARD
            "TOTAL DOLARES" in joined && "TOTAL PESOS" in joined -> XlsxFormat.BBVA_CARD
            "RELEASE_DATE" in joined && "TRANSACTION_NET_AMOUNT" in joined -> XlsxFormat.MERCADO_PAGO
            else -> XlsxFormat.UNKNOWN
        }
    }

    fun parse(
        format: XlsxFormat,
        rows: List<List<String?>>,
        learnedPrefs: SharedPreferences? = null,
    ): List<ParsedTransaction> = when (format) {
        XlsxFormat.BBVA_CARD -> parseBbva(rows, learnedPrefs)
        XlsxFormat.MERCADO_PAGO -> parseMercadoPago(rows, learnedPrefs)
        XlsxFormat.UNKNOWN -> emptyList()
    }

    private fun classify(description: String, learnedPrefs: SharedPreferences?): Cat? {
        if (learnedPrefs != null) {
            LearnedRules.match(learnedPrefs, description)?.let { return it }
        }
        return CategoryRules.classify(description)
    }

    // ---------- BBVA ----------

    /** BBVA Spanish month abbrev. */
    private val bbvaMonths = mapOf(
        "ENE" to 1, "FEB" to 2, "MAR" to 3, "ABR" to 4,
        "MAY" to 5, "JUN" to 6, "JUL" to 7, "AGO" to 8,
        "SEP" to 9, "OCT" to 10, "NOV" to 11, "DIC" to 12,
    )

    private fun parseBbvaDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        // e.g. "14-Ene-26"
        val parts = raw.trim().split("-")
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = bbvaMonths[parts[1].uppercase().take(3)] ?: return null
        val yy = parts[2].toIntOrNull() ?: return null
        val year = if (yy < 100) 2000 + yy else yy
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun parseBbva(rows: List<List<String?>>, learnedPrefs: SharedPreferences?): List<ParsedTransaction> {
        val out = mutableListOf<ParsedTransaction>()
        // Header row 0: Fecha | Descripción | Total Pesos | Total Dólares | Clasificacion | Nueva_Clasificacion | Tipo_Movimiento_Tarjeta
        rows.forEachIndexed { idx, row ->
            if (idx == 0) return@forEachIndexed
            val rawDate = row.getOrNull(0)
            val date = parseBbvaDate(rawDate) ?: return@forEachIndexed // skip totals/initial rows
            val description = row.getOrNull(1)?.trim().orEmpty()
            if (description.isEmpty()) return@forEachIndexed

            val pesos = parseAmount(row.getOrNull(2)) ?: 0.0
            val dolares = parseAmount(row.getOrNull(3)) ?: 0.0
            val bbvaClass = row.getOrNull(4)
            val tipo = row.getOrNull(6)?.lowercase()?.trim()

            val (amount, currency) = when {
                dolares != 0.0 && pesos == 0.0 -> dolares to RowCurrency.USD
                pesos != 0.0 -> pesos to RowCurrency.ARS
                else -> return@forEachIndexed // zero rows are noise (transfers / saldos)
            }

            // Sign: BBVA shows positive numbers for consumos, but we store
            // expenses as negative. "pago" rows are payments TO the card, so
            // they're "incoming" to the card account — but for the user, a card
            // payment from the bank account isn't really an expense or income
            // here either. We store consumo as expense (negative), pago as
            // income (positive) to keep card balance accurate.
            val signed = when (tipo) {
                "pago" -> kotlin.math.abs(amount)        // income (paying off card)
                "consumo", "impuesto", null, "" -> -kotlin.math.abs(amount)
                else -> -kotlin.math.abs(amount)
            }

            val cat = classify(description, learnedPrefs)
                ?: CategoryRules.bbvaFallback(bbvaClass)

            out.add(
                ParsedTransaction(
                    date = date,
                    description = description,
                    amountMinor = toMinor(signed),
                    currency = currency,
                    category = cat,
                    isInternalTransfer = false,
                    sourceRow = idx,
                )
            )
        }
        return out
    }

    // ---------- Mercado Pago ----------

    private val mpDateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT)

    private fun parseMpDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw.trim(), mpDateFormatter) }.getOrNull()
    }

    private fun parseMercadoPago(rows: List<List<String?>>, learnedPrefs: SharedPreferences?): List<ParsedTransaction> {
        val out = mutableListOf<ParsedTransaction>()
        // Find header row (the one that contains "RELEASE_DATE").
        val headerIdx = rows.indexOfFirst { row ->
            row.any { it?.equals("RELEASE_DATE", ignoreCase = true) == true }
        }
        if (headerIdx < 0) return emptyList()

        for (i in (headerIdx + 1) until rows.size) {
            val row = rows[i]
            val date = parseMpDate(row.getOrNull(0)) ?: continue
            val description = row.getOrNull(1)?.trim().orEmpty()
            if (description.isEmpty()) continue
            val amount = parseAmount(row.getOrNull(3)) ?: continue
            if (amount == 0.0) continue

            val isTransfer = CategoryRules.isInternalTransfer(description)
            // Learned rules win over everything (including built-in transfer detection),
            // so the user can override our heuristics.
            val learned = learnedPrefs?.let { LearnedRules.match(it, description) }
            val cat = when {
                learned != null -> learned
                isTransfer -> Cat.TRANSFERENCIA
                description.uppercase().contains("RENDIMIENTOS") -> Cat.INGRESOS
                amount > 0 && description.uppercase().startsWith("TRANSFERENCIA RECIBIDA") -> Cat.INGRESOS
                else -> CategoryRules.classify(description)
                    ?: if (amount > 0) Cat.INGRESOS else Cat.OTROS
            }

            out.add(
                ParsedTransaction(
                    date = date,
                    description = description,
                    amountMinor = toMinor(amount),
                    currency = RowCurrency.ARS,
                    category = cat,
                    isInternalTransfer = isTransfer,
                    sourceRow = i,
                )
            )
        }
        return out
    }

    // ---------- helpers ----------

    /**
     * Parses an Argentine-formatted amount like "1.234,56" or "-12.345,00", but
     * also tolerates plain numeric strings ("1234.56") and Excel numbers
     * already represented as decimal (e.g. "498.48").
     */
    fun parseAmount(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        // Try plain Double first (xlsx numeric cells).
        s.toDoubleOrNull()?.let { return it }
        // Argentine formatting: dot = thousands, comma = decimal.
        val cleaned = s.replace(".", "").replace(",", ".")
        return runCatching { cleaned.toDouble() }
            .getOrElse {
                Timber.d("Could not parse amount: %s", raw)
                null
            }
    }

    private fun toMinor(amount: Double): Long = Math.round(amount * 100.0)
}
