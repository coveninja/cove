package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.COVE_PLUGIN_API_VERSION
import com.coveninja.cove.shared.data.PluginCatalog
import com.coveninja.cove.shared.data.PluginManifest
import com.coveninja.cove.shared.network.CoveJson
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal const val PLUGIN_CATALOG_ASSET = "cove-plugin-catalog-v1.json"
internal const val PLUGIN_CATALOG_SIGNATURE_ASSET = "cove-plugin-catalog-v1.json.sig"

internal class PluginSignatureVerifier(private val encodedPublicKeys: Map<String, String>) {
    fun verifyCatalog(bytes: ByteArray, encodedSignature: ByteArray): PluginCatalog {
        require(bytes.size <= MAX_CATALOG_BYTES) { "plugin catalog is too large" }
        val catalog = runCatching { CoveJson.decodeFromString<PluginCatalog>(bytes.decodeToString()) }
            .getOrElse { throw SecurityException("plugin catalog is malformed", it) }
        verify(bytes, encodedSignature, catalog.keyId)
        require(catalog.schemaVersion == 1) { "unsupported plugin catalog schema ${catalog.schemaVersion}" }
        require(catalog.plugins.map { it.manifest.id }.distinct().size == catalog.plugins.size) {
            "plugin catalog contains duplicate ids"
        }
        catalog.plugins.forEach { entry ->
            validateManifest(entry.manifest)
            require(entry.sizeBytes in 1..MAX_PACKAGE_BYTES) { "invalid plugin package size" }
            require(entry.sha256.matches(SHA256)) { "invalid plugin package checksum" }
            require(entry.packageUrl.startsWith("https://")) { "plugin package URL must use HTTPS" }
            require(entry.signatureUrl.startsWith("https://")) { "plugin signature URL must use HTTPS" }
        }
        return catalog
    }

    fun verifyPackage(bytes: ByteArray, encodedSignature: ByteArray, keyId: String) {
        require(bytes.size in 1..MAX_PACKAGE_BYTES.toInt()) { "plugin package is too large" }
        verify(bytes, encodedSignature, keyId)
    }

    private fun verify(bytes: ByteArray, encodedSignature: ByteArray, keyId: String) {
        require(encodedSignature.size <= MAX_SIGNATURE_BYTES) { "plugin signature is too large" }
        val encodedKey = encodedPublicKeys[keyId]
            ?: throw SecurityException("plugin package uses an unknown signing key")
        val key = runCatching {
            KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)),
            )
        }.getOrElse { throw SecurityException("embedded plugin public key is malformed", it) }
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(encodedSignature.decodeToString().trim())
        }.getOrElse { throw SecurityException("plugin signature is malformed", it) }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(bytes)
        if (!verifier.verify(signatureBytes)) throw SecurityException("plugin signature is invalid")
    }

    companion object {
        const val MAX_CATALOG_BYTES = 256 * 1024
        const val MAX_SIGNATURE_BYTES = 4096
        const val MAX_PACKAGE_BYTES = 10L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class ExtractedPlugin(val manifest: PluginManifest, val directory: Path)

internal object PluginArchive {
    private const val MAX_FILES = 128
    private const val MAX_EXTRACTED_BYTES = 25L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_ENTRYPOINT_BYTES = 2L * 1024 * 1024

    fun inspect(bytes: ByteArray): PluginManifest {
        var manifestBytes: ByteArray? = null
        var count = 0
        var extracted = 0L
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_FILES) { "plugin package contains too many files" }
                val name = validateEntryName(entry.name)
                require(names.add(name)) { "plugin package contains duplicate entries" }
                require(!entry.isDirectory || name.endsWith('/')) { "invalid plugin directory entry" }
                if (!entry.isDirectory) {
                    val content = zip.readBounded(MAX_EXTRACTED_BYTES - extracted)
                    extracted += content.size
                    require(extracted <= MAX_EXTRACTED_BYTES) { "plugin package expands beyond 25 MiB" }
                    if (name == "plugin.json") {
                        require(manifestBytes == null) { "plugin package contains duplicate plugin.json" }
                        require(content.size <= MAX_MANIFEST_BYTES) { "plugin manifest is too large" }
                        manifestBytes = content
                    }
                }
                zip.closeEntry()
            }
        }
        val manifest = runCatching {
            CoveJson.decodeFromString<PluginManifest>(
                requireNotNull(manifestBytes) { "plugin package has no plugin.json" }.decodeToString(),
            )
        }.getOrElse { throw IllegalArgumentException("plugin manifest is malformed", it) }
        validateManifest(manifest)
        return manifest
    }

    fun extract(bytes: ByteArray, root: Path): ExtractedPlugin {
        val manifest = inspect(bytes)
        val target = root.resolve(manifest.id).resolve(manifest.version).toAbsolutePath().normalize()
        require(target.startsWith(root.toAbsolutePath().normalize())) { "invalid plugin install path" }
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        Files.createDirectories(temporary)
        try {
            var count = 0
            var extracted = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_FILES) { "plugin package contains too many files" }
                    val name = validateEntryName(entry.name)
                    val output = temporary.resolve(name).normalize()
                    require(output.startsWith(temporary)) { "plugin entry escapes its package" }
                    if (entry.isDirectory) {
                        Files.createDirectories(output)
                    } else {
                        Files.createDirectories(output.parent)
                        Files.newOutputStream(output).use { stream ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extracted += read
                                require(extracted <= MAX_EXTRACTED_BYTES) {
                                    "plugin package expands beyond 25 MiB"
                                }
                                stream.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(Files.isRegularFile(temporary.resolve(manifest.entrypoint))) {
                "plugin entrypoint ${manifest.entrypoint} is missing"
            }
            require(Files.size(temporary.resolve(manifest.entrypoint)) <= MAX_ENTRYPOINT_BYTES) {
                "plugin entrypoint exceeds 2 MiB"
            }
            if (Files.exists(target)) deleteTree(target)
            Files.createDirectories(target.parent)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
            return ExtractedPlugin(manifest, target)
        } finally {
            if (Files.exists(temporary)) deleteTree(temporary)
        }
    }

    private fun validateEntryName(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 240) { "invalid plugin entry name" }
        require('\\' !in raw && !raw.startsWith('/') && !raw.startsWith("../")) {
            "plugin entry uses an unsafe path"
        }
        val comparable = raw.removeSuffix("/")
        val normalized = Path.of(comparable).normalize().toString().replace('\\', '/')
        require(normalized != ".." && !normalized.startsWith("../")) { "plugin entry escapes its package" }
        require(normalized == comparable && comparable != ".") { "plugin entry path is not normalized" }
        return raw
    }
}

internal fun validateManifest(manifest: PluginManifest) {
    require(manifest.schemaVersion == 1) { "unsupported plugin manifest schema ${manifest.schemaVersion}" }
    require(manifest.apiVersion == COVE_PLUGIN_API_VERSION) {
        "unsupported plugin API ${manifest.apiVersion}"
    }
    require(manifest.id.matches(Regex("[a-z0-9]+(?:[._-][a-z0-9]+){1,127}"))) {
        "invalid plugin id"
    }
    require(manifest.name.isNotBlank() && manifest.name.length <= 80) { "invalid plugin name" }
    require(manifest.description.length <= 500) { "plugin description is too long" }
    require(manifest.publisher.isNotBlank() && manifest.publisher.length <= 120) { "invalid plugin publisher" }
    require(parsePluginVersion(manifest.version) != null) { "invalid plugin version" }
    require(parsePluginVersion(manifest.minimumCoveVersion) != null) { "invalid minimum Cove version" }
    require(manifest.entrypoint.matches(Regex("[a-zA-Z0-9._/-]{1,160}"))) { "invalid plugin entrypoint" }
    require(
        !manifest.entrypoint.startsWith('/') &&
            Path.of(manifest.entrypoint).none { it.toString() == ".." },
    ) {
        "plugin entrypoint must stay inside the package"
    }
    require(manifest.allowedHosts.all { it.matches(Regex("[a-zA-Z0-9.-]{1,253}")) }) {
        "invalid plugin network host"
    }
    require(manifest.allowedHosts.size <= 64) { "plugin declares too many network hosts" }
    require(
        manifest.allowedHosts.isEmpty() ||
            com.coveninja.cove.shared.data.PluginCapability.NetworkHttp in manifest.capabilities,
    ) { "plugin declares network hosts without network.http" }
    require(
        com.coveninja.cove.shared.data.PluginCapability.NetworkLan !in manifest.capabilities ||
            com.coveninja.cove.shared.data.PluginCapability.NetworkHttp in manifest.capabilities,
    ) { "network.lan requires network.http" }
    require(manifest.settings.size <= 64) { "plugin declares too many settings" }
    require(
        manifest.settings.isEmpty() ||
            com.coveninja.cove.shared.data.PluginCapability.UiSettings in manifest.capabilities,
    ) { "plugin settings require ui.settings" }
    require(manifest.settings.map { it.key }.distinct().size == manifest.settings.size) {
        "plugin settings contain duplicate keys"
    }
    manifest.settings.forEach { setting ->
        require(setting.key.matches(Regex("[a-zA-Z][a-zA-Z0-9._-]{0,63}"))) { "invalid plugin setting key" }
        require(setting.label.isNotBlank() && setting.label.length <= 100) { "invalid plugin setting label" }
        require(setting.description.length <= 500) { "plugin setting description is too long" }
        require(setting.options.size <= 100) { "plugin setting declares too many choices" }
        require(setting.options.map { it.value }.distinct().size == setting.options.size) {
            "plugin setting contains duplicate choices"
        }
        setting.options.forEach { option ->
            require(option.value.length <= 200 && option.label.isNotBlank() && option.label.length <= 100) {
                "invalid plugin setting choice"
            }
        }
        when (setting.type) {
            com.coveninja.cove.shared.data.PluginSettingType.Number -> {
                require(setting.minimum?.isFinite() != false && setting.maximum?.isFinite() != false) {
                    "plugin number bounds must be finite"
                }
                val minimum = setting.minimum
                val maximum = setting.maximum
                require(minimum == null || maximum == null || minimum <= maximum) {
                    "plugin number setting has inverted bounds"
                }
            }
            com.coveninja.cove.shared.data.PluginSettingType.Select ->
                require(setting.options.isNotEmpty()) { "plugin select setting has no choices" }
            com.coveninja.cove.shared.data.PluginSettingType.Action ->
                require(setting.default is JsonNull) { "plugin actions cannot have defaults" }
            else -> require(setting.options.isEmpty()) { "only select settings can declare choices" }
        }
        if (setting.default !is JsonNull) validatePluginSetting(setting, setting.default)
    }
    if (com.coveninja.cove.shared.data.PluginCapability.DiscordPresence in manifest.capabilities) {
        require(manifest.discordApplicationId?.matches(Regex("[0-9]{16,22}")) == true) {
            "Discord presence plugins require an application id"
        }
    } else {
        require(manifest.discordApplicationId == null) {
            "Discord application id requires discord.presence"
        }
    }
}

internal fun validatePluginSetting(
    definition: com.coveninja.cove.shared.data.PluginSettingDefinition,
    value: JsonElement,
) {
    when (definition.type) {
        com.coveninja.cove.shared.data.PluginSettingType.Boolean ->
            require(value is JsonPrimitive && value.booleanOrNull != null) {
                "plugin setting must be a boolean"
            }
        com.coveninja.cove.shared.data.PluginSettingType.String -> require(
            value is JsonPrimitive && value.isString && value.content.length <= 8_192,
        ) {
            "plugin setting must be a string of at most 8192 characters"
        }
        com.coveninja.cove.shared.data.PluginSettingType.Number -> {
            val number = (value as? JsonPrimitive)?.doubleOrNull
            val minimum = definition.minimum
            val maximum = definition.maximum
            require(number != null && number.isFinite()) { "plugin setting must be a finite number" }
            require(minimum == null || number >= minimum) { "plugin setting is below its minimum" }
            require(maximum == null || number <= maximum) { "plugin setting is above its maximum" }
        }
        com.coveninja.cove.shared.data.PluginSettingType.Select -> require(
            value is JsonPrimitive && value.content in definition.options.map { it.value },
        ) {
            "plugin setting has an unsupported choice"
        }
        com.coveninja.cove.shared.data.PluginSettingType.Action ->
            error("plugin actions do not hold values")
    }
}

internal data class PluginVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion): Int = compareValuesBy(
        this,
        other,
        PluginVersion::major,
        PluginVersion::minor,
        PluginVersion::patch,
    )
}

internal fun parsePluginVersion(value: String): PluginVersion? {
    val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        .matchEntire(value) ?: return null
    return runCatching {
        PluginVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }.getOrNull()
}

internal fun parsePluginPublicKeys(value: String): Map<String, String> = value
    .split(',')
    .mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0 || separator == entry.lastIndex) null
        else entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
    }
    .filter { (id, encoded) -> id.matches(Regex("[a-zA-Z0-9._-]{1,64}")) && encoded.isNotBlank() }
    .toMap()

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun atomicWrite(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temporary = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
    Files.write(temporary, bytes)
    try {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}

private fun ZipInputStream.readBounded(remaining: Long): ByteArray {
    require(remaining >= 0) { "plugin package expands beyond 25 MiB" }
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= remaining) { "plugin package expands beyond 25 MiB" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
