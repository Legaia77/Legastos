package org.totschnig.myexpenses.util.legastos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.totschnig.myexpenses.util.legastos.LegastosCategories.Cat

/**
 * User-trained merchant → category rules. Persisted in a dedicated
 * SharedPreferences file (one entry per merchant key, value = [Cat.name]).
 *
 * Rules added here are checked BEFORE the hardcoded [CategoryRules.classify]
 * keywords so the user can override and extend the built-in mapping just by
 * touching a row in the import preview.
 */
object LearnedRules {

    private const val FILE = "legastos_learned_rules"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Pick a merchant signature from a transaction description. Heuristic:
     * uppercase, drop trailing whitespace and trailing all-digit tokens that
     * tend to be transaction IDs ("MERPAGO*BET365 (ADEL.) 740749" → "MERPAGO*BET365 (ADEL.)").
     * The result is trimmed to 60 chars max so the prefs key stays bounded.
     */
    fun extractKey(description: String): String {
        val upper = description.trim().uppercase()
        val tokens = upper.split(Regex("\\s+")).toMutableList()
        // Drop trailing tokens that are pure digits or digits with separators.
        while (tokens.isNotEmpty() &&
            tokens.last().matches(Regex("[0-9./\\-]{4,}"))
        ) {
            tokens.removeAt(tokens.size - 1)
        }
        val joined = tokens.joinToString(" ").trim()
        return (if (joined.isNotEmpty()) joined else upper).take(60)
    }

    fun match(prefs: SharedPreferences, description: String): Cat? {
        if (description.isBlank()) return null
        val upper = description.uppercase()
        // First, try the exact key for this description (fastest path).
        val ownKey = extractKey(description)
        prefs.getString(ownKey, null)?.let { return runCatching { Cat.valueOf(it) }.getOrNull() }
        // Otherwise, walk all stored rules and check substring match.
        for ((needle, value) in prefs.all) {
            val v = value as? String ?: continue
            if (needle.isNotBlank() && upper.contains(needle)) {
                return runCatching { Cat.valueOf(v) }.getOrNull()
            }
        }
        return null
    }

    fun remember(prefs: SharedPreferences, description: String, cat: Cat) {
        val key = extractKey(description)
        if (key.isBlank()) return
        prefs.edit { putString(key, cat.name) }
    }

    fun forget(prefs: SharedPreferences, description: String) {
        val key = extractKey(description)
        prefs.edit { remove(key) }
    }
}
