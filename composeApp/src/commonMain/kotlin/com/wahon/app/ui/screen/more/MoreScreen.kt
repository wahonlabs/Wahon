package com.wahon.app.ui.screen.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.wahon.shared.data.remote.DnsOverHttpsProvider
import com.wahon.shared.data.remote.NetworkPreferencesStore
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
    var dohProvider by remember {
        mutableStateOf(networkPreferencesStore.selectedDohProvider())
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                    "Current: ${dohProvider.displayName()}. Tap to switch. Requires app restart.",
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
