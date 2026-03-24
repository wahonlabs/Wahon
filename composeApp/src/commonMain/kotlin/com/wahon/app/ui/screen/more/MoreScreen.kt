package com.wahon.app.ui.screen.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.wahon.shared.getPlatformName
import com.wahon.shared.data.remote.DnsOverHttpsProvider
import com.wahon.shared.data.remote.NetworkPreferencesStore
import com.wahon.shared.domain.repository.LocalArchiveRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class MoreScreenWrapper : Screen {
    @Composable
    override fun Content() {
        MoreScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    val navigator = LocalNavigator.currentOrThrow
    val networkPreferencesStore = koinInject<NetworkPreferencesStore>()
    val localArchiveRepository = koinInject<LocalArchiveRepository>()
    val platformName = remember { getPlatformName() }
    val coroutineScope = rememberCoroutineScope()
    var dohProvider by remember {
        mutableStateOf(networkPreferencesStore.selectedDohProvider())
    }
    var cbzImportPath by remember { mutableStateOf("") }
    var cbzImportInProgress by remember { mutableStateOf(false) }
    var cbzImportStatus by remember { mutableStateOf<String?>(null) }
    val isIosPlatform = platformName.startsWith("iOS", ignoreCase = true)
    val dohNote = buildString {
        append("Current: ${dohProvider.displayName()}. Tap to switch.")
        append(" Requires app restart.")
        if (isIosPlatform) {
            append(" iOS DoH backend is not implemented yet.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("More") },
        )

        ListItem(
            headlineContent = { Text("Extension Repos") },
            supportingContent = { Text("Manage extension source repositories") },
            leadingContent = {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { navigator.push(ExtensionRepoScreen()) },
        )

        ListItem(
            headlineContent = { Text("DNS-over-HTTPS") },
            supportingContent = {
                Text(
                    dohNote,
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val next = dohProvider.nextProvider()
                    networkPreferencesStore.setSelectedDohProvider(next)
                    dohProvider = next
                },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import Local CBZ",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cbzImportPath,
                onValueChange = { value ->
                    cbzImportPath = value
                    if (cbzImportStatus != null) {
                        cbzImportStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute .cbz path") },
                supportingText = {
                    Text("Imported archive becomes available in Library under Local CBZ source.")
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = cbzImportPath.trim()
                    if (rawPath.isBlank() || cbzImportInProgress) {
                        return@Button
                    }
                    cbzImportInProgress = true
                    cbzImportStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importCbzArchive(rawPath)
                            .onSuccess { result ->
                                cbzImportStatus =
                                    "Imported: ${result.title} (${result.pageCount} pages). Open it from Library."
                            }
                            .onFailure { error ->
                                cbzImportStatus = error.message ?: "Failed to import CBZ archive"
                            }
                        cbzImportInProgress = false
                    }
                },
                enabled = cbzImportPath.trim().isNotEmpty() && !cbzImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cbzImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import CBZ")
                }
            }
            val status = cbzImportStatus
            if (!status.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun DnsOverHttpsProvider.nextProvider(): DnsOverHttpsProvider {
    val providers = listOf(
        DnsOverHttpsProvider.DISABLED,
        DnsOverHttpsProvider.CLOUDFLARE,
        DnsOverHttpsProvider.GOOGLE,
        DnsOverHttpsProvider.ADGUARD,
    )
    val currentIndex = providers.indexOf(this).takeIf { it >= 0 } ?: 0
    return providers[(currentIndex + 1) % providers.size]
}

private fun DnsOverHttpsProvider.displayName(): String = when (this) {
    DnsOverHttpsProvider.DISABLED -> "Disabled"
    DnsOverHttpsProvider.CLOUDFLARE -> "Cloudflare"
    DnsOverHttpsProvider.GOOGLE -> "Google"
    DnsOverHttpsProvider.ADGUARD -> "AdGuard"
}
