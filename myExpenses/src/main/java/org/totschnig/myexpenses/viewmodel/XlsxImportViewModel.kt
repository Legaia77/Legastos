package org.totschnig.myexpenses.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.totschnig.myexpenses.db2.Repository
import org.totschnig.myexpenses.db2.findCategory
import org.totschnig.myexpenses.db2.insertTransaction
import org.totschnig.myexpenses.provider.KEY_CURRENCY
import org.totschnig.myexpenses.provider.KEY_LABEL
import org.totschnig.myexpenses.provider.KEY_ROWID
import org.totschnig.myexpenses.provider.KEY_SEALED
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.provider.asSequence
import org.totschnig.myexpenses.provider.getString
import org.totschnig.myexpenses.util.legastos.LearnedRules
import org.totschnig.myexpenses.util.legastos.LegastosCategories.Cat
import org.totschnig.myexpenses.util.legastos.ParsedTransaction
import org.totschnig.myexpenses.util.legastos.RowCurrency
import org.totschnig.myexpenses.util.legastos.XlsxFormat
import org.totschnig.myexpenses.util.legastos.XlsxParser
import org.totschnig.myexpenses.util.legastos.XlsxReader
import timber.log.Timber
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

class XlsxImportViewModel(application: Application) : AndroidViewModel(application) {

    @Inject
    lateinit var repository: Repository

    data class AccountInfo(val id: Long, val label: String, val currency: String)

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Preview(
            val format: XlsxFormat,
            val transactions: List<ParsedTransaction>,
            val accounts: List<AccountInfo>,
            val selected: Set<Int>,
            val accountForArs: Long?,
            val accountForUsd: Long?,
        ) : UiState
        data class Done(val inserted: Int, val skipped: Int) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun parse(uri: Uri) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val (format, txs, accounts) = withContext(Dispatchers.IO) {
                    val cacheDir = getApplication<Application>().cacheDir
                    val rows = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        .use { input ->
                            requireNotNull(input) { "No se pudo abrir el archivo" }
                            XlsxReader.readFirstSheet(input, cacheDir)
                        }
                    val format = XlsxParser.detect(rows)
                    val prefs = LearnedRules.prefs(getApplication())
                    val txs = XlsxParser.parse(format, rows, prefs)
                    val accounts = loadAccounts()
                    Triple(format, txs, accounts)
                }
                if (format == XlsxFormat.UNKNOWN) {
                    _state.value = UiState.Error("Formato de archivo no reconocido. Soporta BBVA tarjeta y Mercado Pago.")
                    return@launch
                }
                if (txs.isEmpty()) {
                    _state.value = UiState.Error("No se encontraron movimientos importables.")
                    return@launch
                }
                _state.value = UiState.Preview(
                    format = format,
                    transactions = txs,
                    accounts = accounts,
                    selected = txs.indices.toSet(),
                    accountForArs = accounts.firstOrNull { it.currency == "ARS" }?.id,
                    accountForUsd = accounts.firstOrNull { it.currency == "USD" }?.id,
                )
            } catch (e: Exception) {
                Timber.e(e, "XLSX parse failed")
                _state.value = UiState.Error(e.message ?: "Error parseando el archivo")
            }
        }
    }

    fun toggleRow(index: Int) {
        val s = _state.value as? UiState.Preview ?: return
        _state.value = s.copy(
            selected = if (index in s.selected) s.selected - index else s.selected + index
        )
    }

    fun toggleAll() {
        val s = _state.value as? UiState.Preview ?: return
        _state.value = s.copy(
            selected = if (s.selected.size == s.transactions.size) emptySet()
                       else s.transactions.indices.toSet()
        )
    }

    fun setRowCategory(index: Int, cat: Cat, remember: Boolean) {
        val s = _state.value as? UiState.Preview ?: return
        val current = s.transactions.getOrNull(index) ?: return
        val updated = s.transactions.toMutableList().apply {
            this[index] = current.copy(category = cat)
        }
        if (remember) {
            LearnedRules.remember(
                LearnedRules.prefs(getApplication()),
                current.description,
                cat,
            )
        }
        _state.value = s.copy(transactions = updated)
    }

    fun setAccount(currency: RowCurrency, accountId: Long) {
        val s = _state.value as? UiState.Preview ?: return
        _state.value = when (currency) {
            RowCurrency.ARS -> s.copy(accountForArs = accountId)
            RowCurrency.USD -> s.copy(accountForUsd = accountId)
        }
    }

    fun confirmImport() {
        val s = _state.value as? UiState.Preview ?: return
        viewModelScope.launch {
            val (inserted, skipped) = withContext(Dispatchers.IO) {
                doImport(s)
            }
            _state.value = UiState.Done(inserted, skipped)
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    private fun doImport(s: UiState.Preview): Pair<Int, Int> {
        val categoryIds = mutableMapOf<Cat, Long?>()
        fun catId(cat: Cat): Long? = categoryIds.getOrPut(cat) {
            repository.findCategory(cat.label, parentId = null).takeIf { it > 0 }
        }

        var inserted = 0
        var skipped = 0
        s.transactions.forEachIndexed { idx, tx ->
            if (idx !in s.selected) return@forEachIndexed
            val accountId = when (tx.currency) {
                RowCurrency.ARS -> s.accountForArs
                RowCurrency.USD -> s.accountForUsd
            }
            if (accountId == null) {
                skipped++
                return@forEachIndexed
            }
            try {
                repository.insertTransaction(
                    accountId = accountId,
                    amount = tx.amountMinor,
                    categoryId = catId(tx.category),
                    comment = tx.description,
                    date = LocalDateTime.of(tx.date, LocalTime.NOON),
                )
                inserted++
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert row %d", tx.sourceRow)
                skipped++
            }
        }
        return inserted to skipped
    }

    private fun loadAccounts(): List<AccountInfo> {
        val cr = getApplication<Application>().contentResolver
        return cr.query(
            TransactionProvider.ACCOUNTS_URI,
            arrayOf(KEY_ROWID, KEY_LABEL, KEY_CURRENCY, KEY_SEALED),
            null,
            null,
            "$KEY_LABEL ASC",
        )?.use { c ->
            c.asSequence
                .filter { it.getInt(c.getColumnIndexOrThrow(KEY_SEALED)) == 0 }
                .map {
                    AccountInfo(
                        id = it.getLong(c.getColumnIndexOrThrow(KEY_ROWID)),
                        label = it.getString(KEY_LABEL),
                        currency = it.getString(KEY_CURRENCY),
                    )
                }
                .toList()
        }.orEmpty()
    }
}
