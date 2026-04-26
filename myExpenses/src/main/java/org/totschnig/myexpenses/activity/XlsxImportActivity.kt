package org.totschnig.myexpenses.activity

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.compose.AppTheme
import org.totschnig.myexpenses.injector
import org.totschnig.myexpenses.util.legastos.ParsedTransaction
import org.totschnig.myexpenses.util.legastos.RowCurrency
import org.totschnig.myexpenses.viewmodel.XlsxImportViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

class XlsxImportActivity : ProtectedFragmentActivity() {

    private lateinit var viewModel: XlsxImportViewModel

    override fun injectDependencies() {
        injector.inject(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[XlsxImportViewModel::class.java]
        with(injector) { inject(viewModel) }

        setContent {
            AppTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResourceCompat(R.string.menu_import_xlsx)) },
                        )
                    }
                ) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        when (val s = state) {
                            XlsxImportViewModel.UiState.Idle -> IdleScreen(
                                onPick = { uri -> viewModel.parse(uri) },
                                onCancel = { finish() }
                            )
                            XlsxImportViewModel.UiState.Loading -> LoadingScreen()
                            is XlsxImportViewModel.UiState.Preview -> PreviewScreen(
                                state = s,
                                onToggleRow = viewModel::toggleRow,
                                onToggleAll = viewModel::toggleAll,
                                onSetAccount = viewModel::setAccount,
                                onConfirm = viewModel::confirmImport,
                                onCancel = { viewModel.reset() }
                            )
                            is XlsxImportViewModel.UiState.Done -> DoneScreen(
                                inserted = s.inserted,
                                skipped = s.skipped,
                                onClose = { finish() }
                            )
                            is XlsxImportViewModel.UiState.Error -> ErrorScreen(
                                message = s.message,
                                onRetry = { viewModel.reset() },
                                onClose = { finish() }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun stringResourceCompat(id: Int) = androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun IdleScreen(onPick: (Uri) -> Unit, onCancel: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPick(uri)
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Importar movimientos desde un archivo XLSX.",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Formatos soportados: BBVA tarjeta de crédito, Mercado Pago.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            launcher.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream",
                    "*/*",
                )
            )
        }) {
            Text("Elegir archivo")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Procesando archivo…")
        }
    }
}

@Composable
private fun PreviewScreen(
    state: XlsxImportViewModel.UiState.Preview,
    onToggleRow: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onSetAccount: (RowCurrency, Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val hasArs = state.transactions.any { it.currency == RowCurrency.ARS }
    val hasUsd = state.transactions.any { it.currency == RowCurrency.USD }
    val canConfirm = state.selected.isNotEmpty() &&
        (!hasArs || state.accountForArs != null) &&
        (!hasUsd || state.accountForUsd != null)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Formato detectado: ${formatLabel(state.format)}",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.selected.size} de ${state.transactions.size} movimientos seleccionados",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (hasArs) {
                AccountPicker(
                    label = "Cuenta para movimientos en ARS",
                    selected = state.accountForArs,
                    options = state.accounts.filter { it.currency == "ARS" },
                    onSelect = { onSetAccount(RowCurrency.ARS, it) }
                )
                Spacer(Modifier.height(4.dp))
            }
            if (hasUsd) {
                AccountPicker(
                    label = "Cuenta para movimientos en USD",
                    selected = state.accountForUsd,
                    options = state.accounts.filter { it.currency == "USD" },
                    onSelect = { onSetAccount(RowCurrency.USD, it) }
                )
                Spacer(Modifier.height(4.dp))
            }
            Row {
                OutlinedButton(onClick = onToggleAll) {
                    Text(if (state.selected.size == state.transactions.size) "Deseleccionar todo" else "Seleccionar todo")
                }
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(state.transactions) { idx, tx ->
                TransactionRow(
                    tx = tx,
                    selected = idx in state.selected,
                    onToggle = { onToggleRow(idx) }
                )
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            Button(onClick = onConfirm, enabled = canConfirm) { Text("Importar") }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: ParsedTransaction,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val df = remember { DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ROOT) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            Row {
                Text(
                    "${tx.date.format(df)} · ${tx.category.label}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                if (tx.isInternalTransfer) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "(transferencia interna)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Text(
            text = formatAmount(tx.amountMinor, tx.currency),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AccountPicker(
    label: String,
    selected: Long?,
    options: List<XlsxImportViewModel.AccountInfo>,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = options.firstOrNull { it.id == selected }
    Column {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedAccount?.label ?: "(elegir cuenta)")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No hay cuentas en esta moneda") },
                    onClick = { expanded = false },
                    enabled = false
                )
            }
            options.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.label} (${acc.currency})") },
                    onClick = {
                        onSelect(acc.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DoneScreen(inserted: Int, skipped: Int, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Importación completa",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text("Insertados: $inserted")
        if (skipped > 0) Text("Omitidos: $skipped")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose) { Text("Cerrar") }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "No se pudo importar",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("Cerrar") }
            Button(onClick = onRetry) { Text("Probar otro archivo") }
        }
    }
}

private fun formatLabel(format: org.totschnig.myexpenses.util.legastos.XlsxFormat): String =
    when (format) {
        org.totschnig.myexpenses.util.legastos.XlsxFormat.BBVA_CARD -> "BBVA tarjeta"
        org.totschnig.myexpenses.util.legastos.XlsxFormat.MERCADO_PAGO -> "Mercado Pago"
        org.totschnig.myexpenses.util.legastos.XlsxFormat.UNKNOWN -> "Desconocido"
    }

private fun formatAmount(minor: Long, currency: RowCurrency): String {
    val sign = if (minor < 0) "-" else ""
    val abs = kotlin.math.abs(minor)
    val whole = abs / 100
    val cents = abs % 100
    val symbol = when (currency) {
        RowCurrency.ARS -> "$"
        RowCurrency.USD -> "US$"
    }
    return String.format(Locale.ROOT, "%s%s%,d,%02d", sign, symbol, whole, cents)
}
