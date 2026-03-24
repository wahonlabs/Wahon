package com.wahon.shared.data.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.ExperimentalQuickJsApi
import com.dokar.quickjs.alias.asyncFunc
import com.wahon.shared.data.remote.detectAntiBotChallenge
import com.wahon.shared.data.remote.detectAntiBotProtectionByHtml
import io.github.aakira.napier.Napier
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalQuickJsApi::class)
internal object JsBridgeHttpModule {
    suspend fun install(
        quickJs: QuickJs,
        context: JsRuntimeContext,
    ) {
        quickJs.asyncFunc<String>(NATIVE_HTTP_REQUEST_FUNCTION) { args: Array<Any?> ->
            executeRequest(
                context = context,
                args = args,
            )
        }

        evaluateBridgeScript(
            quickJs = quickJs,
            script = HTTP_BRIDGE_SCRIPT,
        )
    }

    private suspend fun executeRequest(
        context: JsRuntimeContext,
        args: Array<Any?>,
    ): String {
        val method = args.getOrNull(0)
            ?.toString()
            ?.trim()
            .orEmpty()
            .ifBlank { HttpMethod.Get.value }
        val url = args.getOrNull(1)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (url.isBlank()) {
            error("HTTP bridge: url is blank")
        }

        val options = parseOptions(
            rawOptions = args.getOrNull(2)?.toString(),
            context = context,
        )
        val requestMethod = runCatching {
            HttpMethod.parse(method.uppercase())
        }.getOrDefault(HttpMethod.Get)

        val response = context.httpClient.request(urlString = url) {
            this.method = requestMethod

            options.headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    header(name, value)
                }
            }

            if (options.jsonBody != null && !options.hasContentTypeHeader) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }

            val resolvedBody = options.body ?: options.jsonBody
            if (resolvedBody != null) {
                setBody(resolvedBody)
            }
        }
        val responseBody = response.bodyAsText()
        enforceNoAntiBotChallenge(
            statusCode = response.status.value,
            serverHeader = response.headers[HttpHeaders.Server],
            responseBody = responseBody,
            requestUrl = url,
        )

        val responsePayload = NativeHttpResponse(
            status = response.status.value,
            statusText = response.status.description,
            headers = response.headers.entries()
                .sortedBy { entry -> entry.key.lowercase() }
                .associate { entry -> entry.key to entry.value.joinToString(separator = ",") },
            body = responseBody,
        )

        return context.json.encodeToString(responsePayload)
    }

    private fun parseOptions(
        rawOptions: String?,
        context: JsRuntimeContext,
    ): ParsedHttpOptions {
        if (rawOptions.isNullOrBlank() || rawOptions == "undefined" || rawOptions == "null") {
            return ParsedHttpOptions()
        }

        val root = runCatching {
            context.json.decodeFromString<JsonObject>(rawOptions)
        }.getOrNull() ?: return ParsedHttpOptions()

        val headers = (root["headers"] as? JsonObject)
            ?.entries
            ?.mapNotNull { (name, value) ->
                val headerValue = value.asStringOrNull() ?: return@mapNotNull null
                name to headerValue
            }
            ?.toMap()
            .orEmpty()

        val body = root["body"].asStringOrNull()
        val jsonBody = root["json"]?.let { payload -> context.json.encodeToString(payload) }

        return ParsedHttpOptions(
            headers = headers,
            body = body,
            jsonBody = jsonBody,
            hasContentTypeHeader = headers.keys.any { name -> name.equals(HttpHeaders.ContentType, ignoreCase = true) },
        )
    }

    private fun JsonElement?.asStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    private fun enforceNoAntiBotChallenge(
        statusCode: Int,
        serverHeader: String?,
        responseBody: String,
        requestUrl: String,
    ) {
        val byStatus = detectAntiBotChallenge(
            statusCode = statusCode,
            serverHeader = serverHeader,
        )
        val byHtml = detectAntiBotProtectionByHtml(responseBody)
        if (byStatus == null && byHtml == null) return

        Napier.w(
            message = "JS bridge anti-bot challenge detected for $requestUrl (status=$statusCode, statusProtection=${byStatus?.protection}, htmlProtection=$byHtml)",
            tag = LOG_TAG,
        )
        error(ANTI_BOT_ERROR_MESSAGE)
    }

    private suspend fun evaluateBridgeScript(
        quickJs: QuickJs,
        script: String,
    ) {
        quickJs.evaluate<String>(
            """
            $script
            "__wahon_http_bridge_ready__";
            """.trimIndent(),
        )
    }

    @Serializable
    private data class NativeHttpResponse(
        val status: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class ParsedHttpOptions(
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val jsonBody: String? = null,
        val hasContentTypeHeader: Boolean = false,
    )

}

private const val NATIVE_HTTP_REQUEST_FUNCTION = "__wahonNativeHttpRequest"
private const val LOG_TAG = "JsBridgeHttpModule"
private const val ANTI_BOT_ERROR_MESSAGE =
    "The site requested an anti-bot challenge. Try VPN or another network and retry."

private const val HTTP_BRIDGE_SCRIPT =
    """
    (() => {
      const nativeRequest = globalThis.__wahonNativeHttpRequest;
      if (typeof nativeRequest !== "function") {
        return;
      }

      const toOptionsJson = (options) => {
        try {
          return JSON.stringify(options ?? {});
        } catch (_) {
          return "{}";
        }
      };

      const buildResponse = (rawPayload) => {
        const payload = JSON.parse(String(rawPayload ?? "{}"));
        return {
          status: payload.status ?? 0,
          statusText: payload.statusText ?? "",
          headers: payload.headers ?? {},
          text() {
            return payload.body ?? "";
          },
          json() {
            const bodyText = payload.body ?? "";
            if (!bodyText) {
              return null;
            }
            return JSON.parse(bodyText);
          },
        };
      };

      const request = async (method, url, options) => {
        const rawPayload = await nativeRequest(
          String(method ?? "GET"),
          String(url ?? ""),
          toOptionsJson(options),
        );
        return buildResponse(rawPayload);
      };

      globalThis.http = {
        __wahonBridge: true,
        get(url, options = {}) {
          return request("GET", url, options);
        },
        post(url, options = {}) {
          return request("POST", url, options);
        },
        request(method, url, options = {}) {
          return request(method, url, options);
        },
      };
    })();
    """
