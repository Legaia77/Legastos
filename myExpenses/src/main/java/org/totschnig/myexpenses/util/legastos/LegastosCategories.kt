package org.totschnig.myexpenses.util.legastos

import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.edit
import org.totschnig.myexpenses.db2.FLAG_EXPENSE
import org.totschnig.myexpenses.db2.FLAG_INCOME
import org.totschnig.myexpenses.db2.Repository
import org.totschnig.myexpenses.db2.deleteAllCategories
import org.totschnig.myexpenses.db2.findCategory
import org.totschnig.myexpenses.db2.saveCategory
import org.totschnig.myexpenses.model2.Category
import timber.log.Timber
import java.util.UUID

/**
 * Custom flat category list for Legastos. No subcategories.
 *
 * The setup runs once via [maybeSeedFlatCategories], guarded by a SharedPreferences
 * flag, so existing categories from MyExpenses' default Grisbi seed are wiped and
 * replaced. After that, the user manages categories normally.
 */
object LegastosCategories {

    private const val PREF_KEY_SEEDED = "legastos_flat_categories_seeded_v1"

    enum class Cat(
        val label: String,
        val type: Byte,
        val color: Int,
        val icon: String? = null,
    ) {
        COMIDA("Comida", FLAG_EXPENSE, 0xFFE57373.toInt(), "utensils"),
        TRANSPORTE("Transporte", FLAG_EXPENSE, 0xFF64B5F6.toInt(), "car"),
        HOGAR("Hogar", FLAG_EXPENSE, 0xFF81C784.toInt(), "house"),
        SERVICIOS("Servicios", FLAG_EXPENSE, 0xFFFFB74D.toInt(), "bolt"),
        SUSCRIPCIONES("Suscripciones", FLAG_EXPENSE, 0xFFBA68C8.toInt(), "repeat"),
        SALUD("Salud", FLAG_EXPENSE, 0xFFF06292.toInt(), "heart-pulse"),
        APUESTAS("Apuestas", FLAG_EXPENSE, 0xFF8D6E63.toInt(), "dice"),
        IMPUESTOS("Impuestos", FLAG_EXPENSE, 0xFFA1887F.toInt(), "file-invoice-dollar"),
        PAGO_TARJETA("Pago de tarjeta", FLAG_EXPENSE, 0xFF4DB6AC.toInt(), "credit-card"),
        TRANSFERENCIA("Transferencia", FLAG_EXPENSE, 0xFF90A4AE.toInt(), "right-left"),
        OTROS("Otros", FLAG_EXPENSE, 0xFF9E9E9E.toInt(), "ellipsis"),
        INGRESOS("Ingresos", FLAG_INCOME, 0xFF66BB6A.toInt(), "money-bill-trend-up");

        companion object {
            fun byLabel(label: String): Cat? =
                entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
        }
    }

    fun maybeSeedFlatCategories(repository: Repository, prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_KEY_SEEDED, false)) return
        try {
            seedFlatCategories(repository)
            prefs.edit { putBoolean(PREF_KEY_SEEDED, true) }
            Timber.i("Legastos flat categories seeded")
        } catch (e: Exception) {
            Timber.e(e, "Failed to seed Legastos flat categories")
        }
    }

    fun seedFlatCategories(repository: Repository) {
        repository.deleteAllCategories()
        Cat.entries.forEach { cat ->
            repository.saveCategory(
                Category(
                    uuid = UUID.randomUUID().toString(),
                    label = cat.label,
                    icon = cat.icon,
                    color = cat.color,
                    type = cat.type,
                )
            )
        }
    }

    fun findCategoryId(repository: Repository, cat: Cat): Long? =
        repository.findCategory(cat.label, parentId = null)
            .takeIf { it > 0 }
}
