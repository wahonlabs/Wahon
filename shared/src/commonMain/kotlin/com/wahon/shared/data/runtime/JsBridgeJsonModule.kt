package com.wahon.shared.data.runtime

import com.dokar.quickjs.QuickJs

internal object JsBridgeJsonModule {
    suspend fun install(quickJs: QuickJs) {
        quickJs.evaluate<String>(
            """
            (() => {
              if (!globalThis.json || typeof globalThis.json !== "object") {
                globalThis.json = {};
              }

              if (typeof globalThis.json.parse !== "function") {
                globalThis.json.parse = (raw) => JSON.parse(String(raw ?? "null"));
              }

              if (typeof globalThis.json.stringify !== "function") {
                globalThis.json.stringify = (value) => JSON.stringify(value);
              }
            })();
            "__wahon_json_bridge_ready__";
            """.trimIndent(),
        )
    }
}
