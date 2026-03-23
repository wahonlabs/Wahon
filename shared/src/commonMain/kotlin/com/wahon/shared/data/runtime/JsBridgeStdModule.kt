package com.wahon.shared.data.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.ExperimentalQuickJsApi
import com.dokar.quickjs.alias.func
import io.github.aakira.napier.Napier
import okio.ByteString.Companion.encodeUtf8

@OptIn(ExperimentalQuickJsApi::class)
internal object JsBridgeStdModule {
    suspend fun install(
        quickJs: QuickJs,
        context: JsRuntimeContext,
    ) {
        quickJs.func<Boolean>(NATIVE_LOG_FUNCTION) { args: Array<Any?> ->
            val level = args.getOrNull(0)
                ?.toString()
                ?.lowercase()
                .orEmpty()
            val message = args.drop(1)
                .joinToString(separator = " ") { value ->
                    renderLogArgument(value)
                }

            when (level) {
                "warn" -> Napier.w(message = message, tag = LOG_TAG)
                "error" -> Napier.e(message = message, tag = LOG_TAG)
                else -> Napier.d(message = message, tag = LOG_TAG)
            }

            true
        }

        quickJs.func<String?>(NATIVE_SETTINGS_GET_FUNCTION) { args: Array<Any?> ->
            val key = normalizeKey(args.getOrNull(0)) ?: return@func null
            context.database.source_dataQueries
                .selectSourceDataValue(context.extensionId, settingsStorageKey(key))
                .executeAsOneOrNull()
        }

        quickJs.func<Boolean>(NATIVE_SETTINGS_SET_FUNCTION) { args: Array<Any?> ->
            val key = normalizeKey(args.getOrNull(0)) ?: return@func false
            val rawValue = args.getOrNull(1)
                ?.toString()
                ?: "null"

            context.database.source_dataQueries.upsertSourceData(
                source_id = context.extensionId,
                key = settingsStorageKey(key),
                value_ = rawValue,
            )
            true
        }

        quickJs.func<Boolean>(NATIVE_SETTINGS_REMOVE_FUNCTION) { args: Array<Any?> ->
            val key = normalizeKey(args.getOrNull(0)) ?: return@func false
            context.database.source_dataQueries.deleteSourceData(
                source_id = context.extensionId,
                key = settingsStorageKey(key),
            )
            true
        }

        quickJs.func<String>(NATIVE_CRYPTO_MD5_FUNCTION) { args: Array<Any?> ->
            val rawValue = args.getOrNull(0)?.toString().orEmpty()
            rawValue.encodeUtf8().md5().hex()
        }

        quickJs.func<String>(NATIVE_CRYPTO_SHA256_FUNCTION) { args: Array<Any?> ->
            val rawValue = args.getOrNull(0)?.toString().orEmpty()
            rawValue.encodeUtf8().sha256().hex()
        }

        evaluateBridgeScript(
            quickJs = quickJs,
            script = STD_BRIDGE_SCRIPT,
        )
    }

    private fun renderLogArgument(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value
            else -> value.toString()
        }
    }

    private fun normalizeKey(raw: Any?): String? {
        val normalized = raw
            ?.toString()
            ?.trim()
            .orEmpty()
        return normalized.takeIf { key -> key.isNotBlank() }
    }

    private fun settingsStorageKey(key: String): String {
        return "$SETTINGS_KEY_PREFIX$key"
    }

    private suspend fun evaluateBridgeScript(
        quickJs: QuickJs,
        script: String,
    ) {
        quickJs.evaluate<String>(
            """
            $script
            "__wahon_std_bridge_ready__";
            """.trimIndent(),
        )
    }
}

private const val LOG_TAG = "JsBridgeStd"
private const val NATIVE_LOG_FUNCTION = "__wahonNativeLog"
private const val NATIVE_SETTINGS_GET_FUNCTION = "__wahonNativeSettingsGet"
private const val NATIVE_SETTINGS_SET_FUNCTION = "__wahonNativeSettingsSet"
private const val NATIVE_SETTINGS_REMOVE_FUNCTION = "__wahonNativeSettingsRemove"
private const val NATIVE_CRYPTO_MD5_FUNCTION = "__wahonNativeCryptoMd5"
private const val NATIVE_CRYPTO_SHA256_FUNCTION = "__wahonNativeCryptoSha256"

private const val SETTINGS_KEY_PREFIX = "runtime.setting."

private const val STD_BRIDGE_SCRIPT =
    """
    (() => {
      const stringify = (value) => {
        if (typeof value === "string") {
          return value;
        }
        try {
          return JSON.stringify(value);
        } catch (_) {
          return String(value);
        }
      };

      const nativeLog = globalThis.__wahonNativeLog;
      if (typeof nativeLog === "function") {
        const makeLogger = (level) => (...args) => {
          nativeLog(level, ...args.map((arg) => stringify(arg)));
        };

        if (!globalThis.console || typeof globalThis.console !== "object") {
          globalThis.console = {};
        }

        if (typeof globalThis.console.log !== "function") {
          globalThis.console.log = makeLogger("log");
        }
        if (typeof globalThis.console.info !== "function") {
          globalThis.console.info = makeLogger("log");
        }
        if (typeof globalThis.console.warn !== "function") {
          globalThis.console.warn = makeLogger("warn");
        }
        if (typeof globalThis.console.error !== "function") {
          globalThis.console.error = makeLogger("error");
        }
      }

      const nativeGet = globalThis.__wahonNativeSettingsGet;
      const nativeSet = globalThis.__wahonNativeSettingsSet;
      const nativeRemove = globalThis.__wahonNativeSettingsRemove;
      const nativeMd5 = globalThis.__wahonNativeCryptoMd5;
      const nativeSha256 = globalThis.__wahonNativeCryptoSha256;

      if (
        typeof nativeGet === "function" &&
        typeof nativeSet === "function" &&
        typeof nativeRemove === "function"
      ) {
        globalThis.settings = {
          get(key, defaultValue = null) {
            const raw = nativeGet(String(key ?? ""));
            if (raw === null || raw === undefined) {
              return defaultValue;
            }
            try {
              return JSON.parse(raw);
            } catch (_) {
              return defaultValue;
            }
          },
          set(key, value) {
            let serialized = "null";
            try {
              serialized = JSON.stringify(value === undefined ? null : value);
            } catch (_) {
              serialized = "null";
            }
            nativeSet(String(key ?? ""), serialized);
            return true;
          },
          remove(key) {
            nativeRemove(String(key ?? ""));
            return true;
          },
        };
      }

      if (!globalThis.crypto || typeof globalThis.crypto !== "object") {
        globalThis.crypto = {};
      }

      if (typeof nativeMd5 === "function" && typeof globalThis.crypto.md5 !== "function") {
        globalThis.crypto.md5 = (value) => nativeMd5(String(value ?? ""));
      }

      if (typeof nativeSha256 === "function" && typeof globalThis.crypto.sha256 !== "function") {
        globalThis.crypto.sha256 = (value) => nativeSha256(String(value ?? ""));
      }
    })();
    """
