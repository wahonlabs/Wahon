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
    var cbzDirectoryPath by remember { mutableStateOf("") }
    var cbzDirectoryImportInProgress by remember { mutableStateOf(false) }
    var cbzDirectoryStatus by remember { mutableStateOf<String?>(null) }
    var pdfImportPath by remember { mutableStateOf("") }
    var pdfImportInProgress by remember { mutableStateOf(false) }
    var pdfImportStatus by remember { mutableStateOf<String?>(null) }
    var pdfDirectoryPath by remember { mutableStateOf("") }
    var pdfDirectoryImportInProgress by remember { mutableStateOf(false) }
    var pdfDirectoryStatus by remember { mutableStateOf<String?>(null) }
    var cbrImportPath by remember { mutableStateOf("") }
    var cbrImportInProgress by remember { mutableStateOf(false) }
    var cbrImportStatus by remember { mutableStateOf<String?>(null) }
    var cbrDirectoryPath by remember { mutableStateOf("") }
    var cbrDirectoryImportInProgress by remember { mutableStateOf(false) }
    var cbrDirectoryStatus by remember { mutableStateOf<String?>(null) }
    var mixedDirectoryPath by remember { mutableStateOf("") }
    var mixedDirectoryImportInProgress by remember { mutableStateOf(false) }
    var mixedDirectoryStatus by remember { mutableStateOf<String?>(null) }
    val isIosPlatform = platformName.startsWith("iOS", ignoreCase = true)
    val dohNote = "Current: ${dohProvider.displayName()}. Tap to switch. Applies immediately for new requests."

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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import Local CBR",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cbrImportPath,
                onValueChange = { value ->
                    cbrImportPath = value
                    if (cbrImportStatus != null) {
                        cbrImportStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute .cbr or .rar path") },
                supportingText = {
                    Text("CBR/RAR parser backend is not implemented yet. This action is a scaffold.")
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = cbrImportPath.trim()
                    if (rawPath.isBlank() || cbrImportInProgress) {
                        return@Button
                    }
                    cbrImportInProgress = true
                    cbrImportStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importCbrFile(rawPath)
                            .onSuccess { result ->
                                cbrImportStatus =
                                    "Imported: ${result.title} (${result.pageCount} pages). Open it from Library."
                            }
                            .onFailure { error ->
                                cbrImportStatus = error.message ?: "Failed to import CBR file"
                            }
                        cbrImportInProgress = false
                    }
                },
                enabled = cbrImportPath.trim().isNotEmpty() && !cbrImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cbrImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import CBR")
                }
            }
            val status = cbrImportStatus
            if (!status.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import CBR Directory",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cbrDirectoryPath,
                onValueChange = { value ->
                    cbrDirectoryPath = value
                    if (cbrDirectoryStatus != null) {
                        cbrDirectoryStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute directory path") },
                supportingText = {
                    Text("Scans recursively for .cbr and .rar files.")
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = cbrDirectoryPath.trim()
                    if (rawPath.isBlank() || cbrDirectoryImportInProgress) {
                        return@Button
                    }
                    cbrDirectoryImportInProgress = true
                    cbrDirectoryStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importCbrDirectory(rawPath)
                            .onSuccess { result ->
                                cbrDirectoryStatus = buildDirectoryImportStatus(
                                    result = result,
                                    fileExtension = ".cbr/.rar",
                                )
                            }
                            .onFailure { error ->
                                cbrDirectoryStatus = error.message ?: "Failed to import CBR directory"
                            }
                        cbrDirectoryImportInProgress = false
                    }
                },
                enabled = cbrDirectoryPath.trim().isNotEmpty() && !cbrDirectoryImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cbrDirectoryImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import CBR Directory")
                }
            }
            val directoryStatus = cbrDirectoryStatus
            if (!directoryStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = directoryStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import CBZ Directory",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cbzDirectoryPath,
                onValueChange = { value ->
                    cbzDirectoryPath = value
                    if (cbzDirectoryStatus != null) {
                        cbzDirectoryStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute directory path") },
                supportingText = {
                    Text("Scans recursively and imports all .cbz archives.")
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = cbzDirectoryPath.trim()
                    if (rawPath.isBlank() || cbzDirectoryImportInProgress) {
                        return@Button
                    }
                    cbzDirectoryImportInProgress = true
                    cbzDirectoryStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importCbzDirectory(rawPath)
                            .onSuccess { result ->
                                cbzDirectoryStatus = buildDirectoryImportStatus(
                                    result = result,
                                    fileExtension = ".cbz",
                                )
                            }
                            .onFailure { error ->
                                cbzDirectoryStatus = error.message ?: "Failed to import CBZ directory"
                            }
                        cbzDirectoryImportInProgress = false
                    }
                },
                enabled = cbzDirectoryPath.trim().isNotEmpty() && !cbzDirectoryImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cbzDirectoryImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import Directory")
                }
            }
            val directoryStatus = cbzDirectoryStatus
            if (!directoryStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = directoryStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import Local PDF",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = pdfImportPath,
                onValueChange = { value ->
                    pdfImportPath = value
                    if (pdfImportStatus != null) {
                        pdfImportStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute .pdf path") },
                supportingText = {
                    val supportText = if (isIosPlatform) {
                        "PDF import currently works on Android only."
                    } else {
                        "Each PDF page is rendered to PNG and added to Local source."
                    }
                    Text(supportText)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = pdfImportPath.trim()
                    if (rawPath.isBlank() || pdfImportInProgress) {
                        return@Button
                    }
                    pdfImportInProgress = true
                    pdfImportStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importPdfFile(rawPath)
                            .onSuccess { result ->
                                pdfImportStatus =
                                    "Imported: ${result.title} (${result.pageCount} pages). Open it from Library."
                            }
                            .onFailure { error ->
                                pdfImportStatus = error.message ?: "Failed to import PDF file"
                            }
                        pdfImportInProgress = false
                    }
                },
                enabled = pdfImportPath.trim().isNotEmpty() && !pdfImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pdfImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import PDF")
                }
            }
            val status = pdfImportStatus
            if (!status.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import PDF Directory",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = pdfDirectoryPath,
                onValueChange = { value ->
                    pdfDirectoryPath = value
                    if (pdfDirectoryStatus != null) {
                        pdfDirectoryStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute directory path") },
                supportingText = {
                    val supportText = if (isIosPlatform) {
                        "PDF directory import currently works on Android only."
                    } else {
                        "Scans recursively and imports all .pdf files."
                    }
                    Text(supportText)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = pdfDirectoryPath.trim()
                    if (rawPath.isBlank() || pdfDirectoryImportInProgress) {
                        return@Button
                    }
                    pdfDirectoryImportInProgress = true
                    pdfDirectoryStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importPdfDirectory(rawPath)
                            .onSuccess { result ->
                                pdfDirectoryStatus = buildDirectoryImportStatus(
                                    result = result,
                                    fileExtension = ".pdf",
                                )
                            }
                            .onFailure { error ->
                                pdfDirectoryStatus = error.message ?: "Failed to import PDF directory"
                            }
                        pdfDirectoryImportInProgress = false
                    }
                },
                enabled = pdfDirectoryPath.trim().isNotEmpty() && !pdfDirectoryImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pdfDirectoryImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import PDF Directory")
                }
            }
            val directoryStatus = pdfDirectoryStatus
            if (!directoryStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = directoryStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import Mixed Directory",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = mixedDirectoryPath,
                onValueChange = { value ->
                    mixedDirectoryPath = value
                    if (mixedDirectoryStatus != null) {
                        mixedDirectoryStatus = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Absolute directory path") },
                supportingText = {
                    val supportText = if (isIosPlatform) {
                        "Mixed import supports only CBZ on iOS currently."
                    } else {
                        "Imports all .cbz, .pdf, .cbr, and .rar files recursively."
                    }
                    Text(supportText)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val rawPath = mixedDirectoryPath.trim()
                    if (rawPath.isBlank() || mixedDirectoryImportInProgress) {
                        return@Button
                    }
                    mixedDirectoryImportInProgress = true
                    mixedDirectoryStatus = null
                    coroutineScope.launch {
                        localArchiveRepository.importSupportedDirectory(rawPath)
                            .onSuccess { result ->
                                mixedDirectoryStatus = buildDirectoryImportStatus(
                                    result = result,
                                    fileExtension = ".cbz/.pdf/.cbr/.rar",
                                )
                            }
                            .onFailure { error ->
                                mixedDirectoryStatus = error.message ?: "Failed to import mixed directory"
                            }
                        mixedDirectoryImportInProgress = false
                    }
                },
                enabled = mixedDirectoryPath.trim().isNotEmpty() && !mixedDirectoryImportInProgress,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mixedDirectoryImportInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Import Mixed")
                }
            }
            val directoryStatus = mixedDirectoryStatus
            if (!directoryStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = directoryStatus,
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

private fun buildDirectoryImportStatus(
    result: com.wahon.shared.domain.model.LocalCbzImportBatchResult,
    fileExtension: String,
): String {
    if (result.discovered == 0) {
        return "No $fileExtension files were found in the selected directory."
    }
    if (result.failed == 0) {
        return "Imported ${result.imported}/${result.discovered} archives."
    }

    val failedPreview = result.failures
        .take(3)
        .joinToString(separator = " | ") { failure ->
            "${failure.archivePath.substringAfterLast('/').substringAfterLast('\\')}: ${failure.reason}"
        }
    return "Imported ${result.imported}/${result.discovered}. Failed: ${result.failed}. $failedPreview"
}
