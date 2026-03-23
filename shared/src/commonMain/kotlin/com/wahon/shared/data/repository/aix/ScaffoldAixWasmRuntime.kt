package com.wahon.shared.data.repository.aix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.Inflater
import okio.InflaterSource

class ScaffoldAixWasmRuntime : AixWasmRuntime {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun inspect(payload: ByteArray): AixWasmInspection {
        if (!isZipArchive(payload)) {
            return AixWasmInspection(
                isAixPackage = false,
                isExecutable = false,
                runtimeMessage = "Payload is not an Aidoku .aix archive",
            )
        }

        val archive = runCatching { AixZipArchiveReader.read(payload) }
            .getOrNull()
            ?: return AixWasmInspection(
                isAixPackage = true,
                isExecutable = false,
                runtimeMessage = "Aidoku .aix archive is unreadable",
            )

        val sourceJson = archive.readUtf8("Payload/source.json")
            ?: archive.readUtf8("source.json")
        val sourceManifest = sourceJson?.let { raw ->
            runCatching { json.decodeFromString<AixSourceManifest>(raw) }.getOrNull()
        }
        val mainModuleBytes = archive.readBytes("Payload/main.wasm")
            ?: archive.readBytes("main.wasm")
        val mainModuleInspection = mainModuleBytes?.let { module ->
            runCatching { AixWasmBinaryInspector.inspect(module) }.getOrNull()
        }
        val hasMainModule = mainModuleBytes != null

        val declaredSourceId = sourceManifest?.info?.id?.ifBlank { null }
        val declaredName = sourceManifest?.info?.name?.ifBlank { null }
        val declaredLanguage = sourceManifest?.info?.lang?.ifBlank { null }
        val declaredVersion = sourceManifest?.info?.version
        val declaredMinAppVersion = sourceManifest?.info?.minAppVersion?.ifBlank { null }

        val title = when {
            !declaredName.isNullOrBlank() && !declaredSourceId.isNullOrBlank() ->
                "$declaredName ($declaredSourceId)"

            !declaredName.isNullOrBlank() -> declaredName
            !declaredSourceId.isNullOrBlank() -> declaredSourceId
            else -> "unknown source"
        }
        val runtimeMessage = buildRuntimeMessage(
            title = title,
            declaredMinAppVersion = declaredMinAppVersion,
            hasMainModule = hasMainModule,
            mainModuleInspection = mainModuleInspection,
        )

        return AixWasmInspection(
            isAixPackage = true,
            declaredSourceId = declaredSourceId,
            declaredName = declaredName,
            declaredLanguage = declaredLanguage,
            declaredVersion = declaredVersion,
            declaredMinAppVersion = declaredMinAppVersion,
            hasMainModule = hasMainModule,
            mainModuleImportModules = mainModuleInspection?.importModules.orEmpty(),
            mainModuleImports = mainModuleInspection?.imports.orEmpty(),
            mainModuleExports = mainModuleInspection?.exports.orEmpty(),
            aidokuHostAbiDetected = mainModuleInspection?.aidokuHostAbiDetected ?: false,
            isExecutable = false,
            runtimeMessage = runtimeMessage,
        )
    }

    override suspend fun executeMethod(
        extensionId: String,
        payload: ByteArray,
        methodName: String,
        argsJson: List<String>,
    ): String {
        val inspection = inspect(payload)
        val expectedExports = expectedWasmExports(methodName)
        val hint = if (expectedExports.isEmpty()) {
            ""
        } else {
            " Expected WASM exports: ${expectedExports.joinToString(separator = ", ")}."
        }
        error(
            "Source $extensionId cannot run method $methodName: ${inspection.runtimeMessage}$hint",
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildRuntimeMessage(
        title: String,
        declaredMinAppVersion: String?,
        hasMainModule: Boolean,
        mainModuleInspection: AixWasmModuleInspection?,
    ): String {
        return WASM_RUNTIME_UNAVAILABLE_MESSAGE
    }

    private fun expectedWasmExports(methodName: String): List<String> {
        return when (methodName) {
            "getPopularManga" -> listOf("get_manga_list", "get_manga_listing")
            "searchManga" -> listOf("get_search_manga_list")
            "getMangaDetails" -> listOf("get_manga_details", "get_manga_update")
            "getChapterList" -> listOf("get_chapter_list", "get_manga_update")
            "getPageList" -> listOf("get_page_list")
            else -> emptyList()
        }
    }

    private fun isZipArchive(payload: ByteArray): Boolean {
        if (payload.size < ZIP_SIGNATURE_SIZE) return false
        return payload[0] == ZIP_SIGNATURE_1 &&
            payload[1] == ZIP_SIGNATURE_2 &&
            payload[2] == ZIP_SIGNATURE_3 &&
            payload[3] == ZIP_SIGNATURE_4
    }

    @Serializable
    private data class AixSourceManifest(
        val info: AixSourceInfo = AixSourceInfo(),
    )

    @Serializable
    private data class AixSourceInfo(
        val id: String = "",
        val lang: String = "",
        val name: String = "",
        val version: Long? = null,
        val minAppVersion: String? = null,
    )

    private companion object {
        private const val ZIP_SIGNATURE_SIZE = 4
        private const val ZIP_SIGNATURE_1: Byte = 0x50
        private const val ZIP_SIGNATURE_2: Byte = 0x4B
        private const val ZIP_SIGNATURE_3: Byte = 0x03
        private const val ZIP_SIGNATURE_4: Byte = 0x04
        private const val WASM_RUNTIME_UNAVAILABLE_MESSAGE =
            "Это расширение требует WASM-рантайм (пока не реализован). Ищите JS-альтернативу или ждите обновления."
    }
}

private class AixZipArchive private constructor(
    private val entries: Map<String, ByteArray>,
) {
    fun contains(path: String): Boolean {
        return entries.containsKey(normalize(path))
    }

    fun readBytes(path: String): ByteArray? {
        return entries[normalize(path)]
    }

    fun readUtf8(path: String): String? {
        return readBytes(path)?.decodeToString()
    }

    private fun normalize(path: String): String {
        return path.replace('\\', '/').removePrefix("/")
    }

    companion object {
        fun fromEntries(entries: Map<String, ByteArray>): AixZipArchive {
            return AixZipArchive(entries = entries)
        }
    }
}

private object AixZipArchiveReader {
    fun read(payload: ByteArray): AixZipArchive {
        val entries = linkedMapOf<String, ByteArray>()
        var offset = 0

        while (offset + SIGNATURE_SIZE <= payload.size) {
            val signature = readIntLe(payload, offset)
            when (signature) {
                LOCAL_FILE_HEADER_SIGNATURE -> {
                    val localHeader = parseLocalHeader(payload, offset)
                    val normalizedName = localHeader.fileName
                        .replace('\\', '/')
                        .removePrefix("/")
                    if (normalizedName.isNotEmpty() && !normalizedName.endsWith("/")) {
                        entries[normalizedName] = localHeader.data
                    }
                    offset = localHeader.nextOffset
                }

                CENTRAL_DIRECTORY_SIGNATURE,
                END_OF_CENTRAL_DIRECTORY_SIGNATURE,
                    -> break

                else -> error("Unsupported ZIP signature at offset $offset")
            }
        }

        return AixZipArchive.fromEntries(entries = entries)
    }

    private fun parseLocalHeader(
        payload: ByteArray,
        headerOffset: Int,
    ): LocalHeader {
        val fixedHeaderEnd = headerOffset + LOCAL_HEADER_FIXED_SIZE
        require(fixedHeaderEnd <= payload.size) {
            "Truncated ZIP local header"
        }

        val flags = readShortLe(payload, headerOffset + 6)
        require((flags and DATA_DESCRIPTOR_FLAG) == 0) {
            "ZIP data descriptors are not supported"
        }

        val compressionMethod = readShortLe(payload, headerOffset + 8)
        val compressedSize = readIntLe(payload, headerOffset + 18)
        val uncompressedSize = readIntLe(payload, headerOffset + 22)
        val fileNameLength = readShortLe(payload, headerOffset + 26)
        val extraLength = readShortLe(payload, headerOffset + 28)

        val nameStart = fixedHeaderEnd
        val nameEnd = nameStart + fileNameLength
        val extraEnd = nameEnd + extraLength
        require(extraEnd <= payload.size) {
            "Truncated ZIP local header fields"
        }

        val fileName = payload.copyOfRange(nameStart, nameEnd).decodeToString()
        val dataStart = extraEnd
        val dataEnd = dataStart + compressedSize
        require(dataEnd <= payload.size) {
            "Truncated ZIP entry data for $fileName"
        }

        val compressedData = payload.copyOfRange(dataStart, dataEnd)
        val entryData = when (compressionMethod) {
            COMPRESSION_STORED -> compressedData
            COMPRESSION_DEFLATE -> inflateRawDeflate(
                compressed = compressedData,
                expectedSize = uncompressedSize,
            )

            else -> error("Unsupported ZIP compression method: $compressionMethod for $fileName")
        }

        return LocalHeader(
            fileName = fileName,
            data = entryData,
            nextOffset = dataEnd,
        )
    }

    private fun inflateRawDeflate(
        compressed: ByteArray,
        expectedSize: Int,
    ): ByteArray {
        val compressedBuffer = Buffer().write(compressed)
        val inflater = Inflater(true)
        val inflaterSource = InflaterSource(compressedBuffer, inflater)
        val outBuffer = Buffer()
        return try {
            outBuffer.writeAll(inflaterSource)
            val inflated = outBuffer.readByteArray()
            if (expectedSize > 0 && inflated.size != expectedSize) {
                error("ZIP inflate size mismatch: expected=$expectedSize actual=${inflated.size}")
            }
            inflated
        } finally {
            runCatching { inflaterSource.close() }
        }
    }

    private fun readShortLe(
        payload: ByteArray,
        offset: Int,
    ): Int {
        return (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readIntLe(
        payload: ByteArray,
        offset: Int,
    ): Int {
        return (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8) or
            ((payload[offset + 2].toInt() and 0xFF) shl 16) or
            ((payload[offset + 3].toInt() and 0xFF) shl 24)
    }

    private data class LocalHeader(
        val fileName: String,
        val data: ByteArray,
        val nextOffset: Int,
    )

    private const val SIGNATURE_SIZE = 4
    private const val LOCAL_HEADER_FIXED_SIZE = 30
    private const val DATA_DESCRIPTOR_FLAG = 0x08
    private const val COMPRESSION_STORED = 0
    private const val COMPRESSION_DEFLATE = 8

    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50
}

private data class AixWasmModuleInspection(
    val importModules: List<String>,
    val imports: List<String>,
    val exports: List<String>,
    val aidokuHostAbiDetected: Boolean,
)

private object AixWasmBinaryInspector {
    fun inspect(module: ByteArray): AixWasmModuleInspection {
        val reader = WasmReader(module)
        reader.requireMagicAndVersion()

        val imports = mutableListOf<WasmImport>()
        val exports = linkedSetOf<String>()

        while (reader.hasRemaining()) {
            val sectionId = reader.readByte()
            val sectionSize = reader.readUleb32()
            val sectionEnd = reader.position + sectionSize
            require(sectionEnd <= module.size) { "WASM section exceeds module boundary" }

            when (sectionId) {
                SECTION_ID_IMPORT -> parseImportSection(reader, imports)
                SECTION_ID_EXPORT -> parseExportSection(reader, exports)
                else -> reader.skipTo(sectionEnd)
            }

            if (reader.position < sectionEnd) {
                reader.skipTo(sectionEnd)
            } else {
                require(reader.position == sectionEnd) {
                    "WASM parser crossed section boundary for section=$sectionId"
                }
            }
        }

        val importModules = imports
            .map { it.module }
            .distinct()
            .sorted()
        val importNames = imports
            .map { "${it.module}.${it.name}" }
            .distinct()
            .sorted()
        val exportNames = exports.toList()
            .distinct()
            .sorted()
        val aidokuHostAbiDetected = importModules.any { moduleName ->
            moduleName in KNOWN_AIDOKU_HOST_MODULES
        }

        return AixWasmModuleInspection(
            importModules = importModules,
            imports = importNames,
            exports = exportNames,
            aidokuHostAbiDetected = aidokuHostAbiDetected,
        )
    }

    private fun parseImportSection(
        reader: WasmReader,
        out: MutableList<WasmImport>,
    ) {
        val count = reader.readUleb32()
        repeat(count) {
            val module = reader.readName()
            val name = reader.readName()
            val kind = reader.readByte()
            when (kind) {
                IMPORT_KIND_FUNCTION -> {
                    reader.readUleb32()
                }

                IMPORT_KIND_TABLE -> {
                    reader.readByte()
                    skipLimits(reader)
                }

                IMPORT_KIND_MEMORY -> {
                    skipLimits(reader)
                }

                IMPORT_KIND_GLOBAL -> {
                    reader.readByte()
                    reader.readByte()
                }

                IMPORT_KIND_TAG -> {
                    reader.readUleb32()
                    reader.readUleb32()
                }

                else -> error("Unsupported WASM import kind: $kind")
            }

            out += WasmImport(
                module = module,
                name = name,
            )
        }
    }

    private fun parseExportSection(
        reader: WasmReader,
        out: MutableSet<String>,
    ) {
        val count = reader.readUleb32()
        repeat(count) {
            val name = reader.readName()
            reader.readByte()
            reader.readUleb32()
            out += name
        }
    }

    private fun skipLimits(reader: WasmReader) {
        val flags = reader.readUleb32()
        reader.readUleb32()
        if ((flags and LIMIT_HAS_MAX) != 0) {
            reader.readUleb32()
        }
    }

    private data class WasmImport(
        val module: String,
        val name: String,
    )

    private class WasmReader(
        private val bytes: ByteArray,
    ) {
        var position: Int = 0
            private set

        fun hasRemaining(): Boolean = position < bytes.size

        fun skipTo(newPosition: Int) {
            require(newPosition in position..bytes.size) {
                "Invalid WASM parser skip target: $newPosition"
            }
            position = newPosition
        }

        fun readByte(): Int {
            require(position < bytes.size) {
                "Unexpected end of WASM binary"
            }
            return bytes[position++].toInt() and 0xFF
        }

        fun readUleb32(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val value = readByte()
                result = result or ((value and 0x7F) shl shift)
                if ((value and 0x80) == 0) {
                    return result
                }
                shift += 7
                require(shift <= 35) {
                    "Invalid LEB128 sequence in WASM binary"
                }
            }
        }

        fun readName(): String {
            val length = readUleb32()
            require(length >= 0) { "Negative WASM string length" }
            require(position + length <= bytes.size) {
                "WASM string exceeds module boundary"
            }
            val value = bytes.copyOfRange(position, position + length).decodeToString()
            position += length
            return value
        }

        fun requireMagicAndVersion() {
            require(bytes.size >= 8) {
                "WASM binary is too short"
            }
            val magic0 = readByte()
            val magic1 = readByte()
            val magic2 = readByte()
            val magic3 = readByte()
            require(
                magic0 == WASM_MAGIC_0 &&
                    magic1 == WASM_MAGIC_1 &&
                    magic2 == WASM_MAGIC_2 &&
                    magic3 == WASM_MAGIC_3,
            ) {
                "Invalid WASM magic header"
            }
            val version0 = readByte()
            val version1 = readByte()
            val version2 = readByte()
            val version3 = readByte()
            require(
                version0 == WASM_VERSION_0 &&
                    version1 == WASM_VERSION_1 &&
                    version2 == WASM_VERSION_2 &&
                    version3 == WASM_VERSION_3,
            ) {
                "Unsupported WASM version header"
            }
        }
    }

    private const val SECTION_ID_IMPORT = 2
    private const val SECTION_ID_EXPORT = 7

    private const val IMPORT_KIND_FUNCTION = 0
    private const val IMPORT_KIND_TABLE = 1
    private const val IMPORT_KIND_MEMORY = 2
    private const val IMPORT_KIND_GLOBAL = 3
    private const val IMPORT_KIND_TAG = 4

    private const val LIMIT_HAS_MAX = 1

    private const val WASM_MAGIC_0 = 0x00
    private const val WASM_MAGIC_1 = 0x61
    private const val WASM_MAGIC_2 = 0x73
    private const val WASM_MAGIC_3 = 0x6D

    private const val WASM_VERSION_0 = 0x01
    private const val WASM_VERSION_1 = 0x00
    private const val WASM_VERSION_2 = 0x00
    private const val WASM_VERSION_3 = 0x00

    private val KNOWN_AIDOKU_HOST_MODULES = setOf(
        "aidoku",
        "canvas",
        "defaults",
        "env",
        "html",
        "js",
        "json",
        "net",
        "std",
    )
}
