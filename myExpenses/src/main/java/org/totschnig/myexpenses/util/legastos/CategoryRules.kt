package org.totschnig.myexpenses.util.legastos

import org.totschnig.myexpenses.util.legastos.LegastosCategories.Cat

/**
 * Keyword → category rules. Applied to the transaction description.
 *
 * Order matters: first match wins. Longer / more specific tokens go first
 * so e.g. "MERPAGO*BET365" matches Apuestas before the generic "MERPAGO" rule.
 *
 * Rules take priority over any classification provided in the source file
 * (e.g. BBVA's "Clasificacion" column). The BBVA classification is used only
 * as a fallback when no rule matches.
 */
object CategoryRules {

    private data class Rule(val needle: String, val cat: Cat)

    private val rules: List<Rule> = listOf(
        // Apuestas
        Rule("BET365", Cat.APUESTAS),
        Rule("CODERE", Cat.APUESTAS),
        Rule("BPLAY", Cat.APUESTAS),
        Rule("BETSSON", Cat.APUESTAS),

        // Suscripciones / software
        Rule("CLAUDE.AI", Cat.SUSCRIPCIONES),
        Rule("ANTHROPIC", Cat.SUSCRIPCIONES),
        Rule("OPENAI", Cat.SUSCRIPCIONES),
        Rule("CHATGPT", Cat.SUSCRIPCIONES),
        Rule("GITHUB", Cat.SUSCRIPCIONES),
        Rule("GOOGLE", Cat.SUSCRIPCIONES),
        Rule("SPOTIFY", Cat.SUSCRIPCIONES),
        Rule("NETFLIX", Cat.SUSCRIPCIONES),
        Rule("DISNEY", Cat.SUSCRIPCIONES),
        Rule("HBO", Cat.SUSCRIPCIONES),
        Rule("AMAZON PRIME", Cat.SUSCRIPCIONES),
        Rule("APPLE.COM", Cat.SUSCRIPCIONES),
        Rule("ICLOUD", Cat.SUSCRIPCIONES),
        Rule("MICROSOFT", Cat.SUSCRIPCIONES),
        Rule("ADOBE", Cat.SUSCRIPCIONES),
        Rule("CURSOR", Cat.SUSCRIPCIONES),
        Rule("YOUTUBE PREMIUM", Cat.SUSCRIPCIONES),
        Rule("DROPBOX", Cat.SUSCRIPCIONES),

        // Servicios (luz, gas, internet, telefonía, cable)
        Rule("TELECENTRO", Cat.SERVICIOS),
        Rule("DIRECTV", Cat.SERVICIOS),
        Rule("CABLEVISION", Cat.SERVICIOS),
        Rule("FIBERTEL", Cat.SERVICIOS),
        Rule("PERSONAL", Cat.SERVICIOS),
        Rule("CLARO", Cat.SERVICIOS),
        Rule("MOVISTAR", Cat.SERVICIOS),
        Rule("TUENTI", Cat.SERVICIOS),
        Rule("EDESUR", Cat.SERVICIOS),
        Rule("EDENOR", Cat.SERVICIOS),
        Rule("METROGAS", Cat.SERVICIOS),
        Rule("AYSA", Cat.SERVICIOS),
        Rule("ABL", Cat.SERVICIOS),
        Rule("RENTAS", Cat.IMPUESTOS),

        // Transporte
        Rule("DIDI", Cat.TRANSPORTE),
        Rule("DLO*DIDI", Cat.TRANSPORTE),
        Rule("UBER", Cat.TRANSPORTE),
        Rule("CABIFY", Cat.TRANSPORTE),
        Rule("SUBE", Cat.TRANSPORTE),
        Rule("YPF", Cat.TRANSPORTE),
        Rule("SHELL", Cat.TRANSPORTE),
        Rule("AXION", Cat.TRANSPORTE),
        Rule("PUMA", Cat.TRANSPORTE),

        // Comida (incluye delivery, supermercados, gastronomía)
        Rule("PEDIDOSYA", Cat.COMIDA),
        Rule("RAPPI", Cat.COMIDA),
        Rule("MCDONALDS", Cat.COMIDA),
        Rule("BURGER KING", Cat.COMIDA),
        Rule("STARBUCKS", Cat.COMIDA),
        Rule("CAFE MARTINEZ", Cat.COMIDA),
        Rule("HAVANNA", Cat.COMIDA),
        Rule("SUSHI POP", Cat.COMIDA),
        Rule("DIA ", Cat.COMIDA),
        Rule("COTO", Cat.COMIDA),
        Rule("CARREFOUR", Cat.COMIDA),
        Rule("JUMBO", Cat.COMIDA),
        Rule("DISCO", Cat.COMIDA),
        Rule("VEA", Cat.COMIDA),
        Rule("CHINO", Cat.COMIDA),
        Rule("HELADERIA", Cat.COMIDA),
        Rule("PIZZ", Cat.COMIDA),
        Rule("RESTAURANTE", Cat.COMIDA),
        Rule("SUPERCOMPRE", Cat.COMIDA),
        Rule("PROPINA", Cat.COMIDA),

        // Salud
        Rule("FARMACIA", Cat.SALUD),
        Rule("FARMACITY", Cat.SALUD),
        Rule("OSDE", Cat.SALUD),
        Rule("SWISS MEDICAL", Cat.SALUD),
        Rule("GALENO", Cat.SALUD),
        Rule("MEDICUS", Cat.SALUD),

        // Pago tarjeta
        Rule("SU PAGO EN PESOS", Cat.PAGO_TARJETA),
        Rule("SU PAGO EN USD", Cat.PAGO_TARJETA),
        Rule("PAGO DE TARJETA", Cat.PAGO_TARJETA),

        // Impuestos / percepciones
        Rule("IIBB", Cat.IMPUESTOS),
        Rule("IVA RG", Cat.IMPUESTOS),
        Rule("DB IVA", Cat.IMPUESTOS),
        Rule("DB.RG", Cat.IMPUESTOS),
        Rule("CR.RG", Cat.IMPUESTOS),
        Rule("PERCEP", Cat.IMPUESTOS),
        Rule("INTERESES POR ADELANTOS", Cat.IMPUESTOS),

        // Hogar
        Rule("RESERVA POR GASTOS CASA", Cat.HOGAR),
        Rule("RESERVA PROGRAMADA CASA", Cat.HOGAR),
        Rule("EXPENSAS", Cat.HOGAR),
        Rule("CONS PROP", Cat.HOGAR),
    )

    private val transferKeywords: List<String> = listOf(
        "CHANCHITO",
        "MAXIMO DIAZ",
        "DIAZ MAXIMO",
        "DIAZ MAXIMO ",
    )

    /**
     * Returns the matched category or null if no rule matched.
     */
    fun classify(description: String?): Cat? {
        if (description.isNullOrBlank()) return null
        val upper = description.uppercase()
        return rules.firstOrNull { upper.contains(it.needle) }?.cat
    }

    fun isInternalTransfer(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        val upper = description.uppercase()
        return transferKeywords.any { upper.contains(it) }
    }

    /**
     * Maps BBVA's "Clasificacion" column to a [Cat]. Used as a fallback when
     * [classify] doesn't match anything more specific.
     */
    fun bbvaFallback(bbvaClass: String?): Cat = when (bbvaClass?.trim()?.lowercase()) {
        "transporte y viajes" -> Cat.TRANSPORTE
        "gastronomía", "gastronomia" -> Cat.COMIDA
        "servicios" -> Cat.SERVICIOS
        "suscripciones y software" -> Cat.SUSCRIPCIONES
        "salud" -> Cat.SALUD
        "hogar" -> Cat.HOGAR
        "impuestos y percepciones" -> Cat.IMPUESTOS
        "pago de tarjeta" -> Cat.PAGO_TARJETA
        else -> Cat.OTROS
    }
}
