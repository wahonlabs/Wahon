package com.wahon.shared.data.remote

enum class DnsOverHttpsProvider(
    val storageValue: String,
    val endpointUrl: String?,
    val bootstrapHosts: List<String>,
) {
    DISABLED(
        storageValue = "disabled",
        endpointUrl = null,
        bootstrapHosts = emptyList(),
    ),
    CLOUDFLARE(
        storageValue = "cloudflare",
        endpointUrl = "https://cloudflare-dns.com/dns-query",
        bootstrapHosts = listOf("1.1.1.1", "1.0.0.1"),
    ),
    GOOGLE(
        storageValue = "google",
        endpointUrl = "https://dns.google/dns-query",
        bootstrapHosts = listOf("8.8.8.8", "8.8.4.4"),
    ),
    ADGUARD(
        storageValue = "adguard",
        endpointUrl = "https://dns.adguard-dns.com/dns-query",
        bootstrapHosts = listOf("94.140.14.14", "94.140.15.15"),
    );

    companion object {
        fun fromStorageValue(raw: String?): DnsOverHttpsProvider {
            if (raw.isNullOrBlank()) return DISABLED
            return entries.firstOrNull { provider ->
                provider.storageValue.equals(raw, ignoreCase = true)
            } ?: DISABLED
        }
    }
}
