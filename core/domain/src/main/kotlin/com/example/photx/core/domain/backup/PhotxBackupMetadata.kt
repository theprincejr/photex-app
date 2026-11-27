package com.example.photx.core.domain.backup

import com.example.photx.core.common.AppError
import com.example.photx.core.common.Outcome

/**
 * Immutable domain representation of the PhotX backup metadata embedded in a Telegram document
 * caption (`#photx v1`).
 *
 * @property sha256 Lowercase 64-character hexadecimal SHA-256 digest of the exact uploaded bytes.
 * @property correlationToken Stable per-attempt correlation token persisted before sending.
 * @property version Metadata schema version (currently `1`).
 */
public data class PhotxBackupMetadata(
    public val sha256: String,
    public val correlationToken: String,
    public val version: Int = SUPPORTED_VERSION
) {
    init {
        require(version == SUPPORTED_VERSION) {
            "Unsupported PhotX backup metadata version: $version."
        }
        require(SHA256_REGEX.matches(sha256)) {
            "SHA-256 must be a 64-character lowercase hexadecimal digest."
        }
        require(CORRELATION_TOKEN_REGEX.matches(correlationToken)) {
            "Correlation token must be 1..$MAX_CORRELATION_TOKEN_LENGTH safe non-whitespace characters."
        }
    }

    public companion object {
        public const val SUPPORTED_VERSION: Int = 1
        public const val HEADER_PREFIX: String = "#photx"
        public const val HEADER_V1: String = "#photx v1"
        public const val MAX_CAPTION_LENGTH: Int = 200
        public const val MAX_CORRELATION_TOKEN_LENGTH: Int = 64

        internal val SHA256_REGEX: Regex = Regex("^[0-9a-f]{64}$")
        internal val CORRELATION_TOKEN_REGEX: Regex = Regex("^[A-Za-z0-9._:-]{1,$MAX_CORRELATION_TOKEN_LENGTH}$")

        public fun isValidCorrelationToken(token: String): Boolean = CORRELATION_TOKEN_REGEX.matches(token)
    }
}

/**
 * Strict result of parsing a Telegram document caption into [PhotxBackupMetadata].
 */
public sealed interface BackupMetadataDecodeResult {
    public data class Valid(public val metadata: PhotxBackupMetadata) : BackupMetadataDecodeResult

    /** Ordinary Telegram document caption (or empty caption) without a `#photx` header. */
    public data object NotPhotxMetadata : BackupMetadataDecodeResult

    /** Recognized `#photx` header carrying an unsupported schema version. */
    public data class UnsupportedVersion(public val versionRaw: String) : BackupMetadataDecodeResult

    /** `#photx` metadata header present, but fields are missing, invalid, unknown, or oversized. */
    public data class Malformed(public val reason: String) : BackupMetadataDecodeResult
}

/**
 * Deterministic, strict encoder/decoder for `#photx v1` Telegram document captions (`<= 200` chars).
 */
public object PhotxBackupMetadataCodec {
    private const val KEY_SHA256 = "sha256"
    private const val KEY_CORR = "corr"

    public fun encode(metadata: PhotxBackupMetadata): Outcome<String, AppError.Validation> {
        val caption = buildString {
            append(PhotxBackupMetadata.HEADER_V1)
            append('\n')
            append(KEY_SHA256)
            append(':')
            append(metadata.sha256)
            append('\n')
            append(KEY_CORR)
            append(':')
            append(metadata.correlationToken)
        }
        return if (caption.length > PhotxBackupMetadata.MAX_CAPTION_LENGTH) {
            Outcome.Failure(
                AppError.Validation(
                    "Encoded PhotX caption length (${caption.length}) exceeds " +
                        "${PhotxBackupMetadata.MAX_CAPTION_LENGTH} characters."
                )
            )
        } else {
            Outcome.Success(caption)
        }
    }

    public fun decode(caption: String?): BackupMetadataDecodeResult {
        val lines = caption
            ?.replace("\r\n", "\n")
            ?.trim()
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val header = lines.firstOrNull()
        return when {
            header == null || !isPhotxHeader(header) -> BackupMetadataDecodeResult.NotPhotxMetadata

            caption != null && caption.length > PhotxBackupMetadata.MAX_CAPTION_LENGTH ->
                BackupMetadataDecodeResult.Malformed(
                    "Caption length (${caption.length}) exceeds ${PhotxBackupMetadata.MAX_CAPTION_LENGTH} chars."
                )

            header != PhotxBackupMetadata.HEADER_V1 ->
                BackupMetadataDecodeResult.UnsupportedVersion(
                    versionRaw = header.removePrefix(PhotxBackupMetadata.HEADER_PREFIX).trim()
                )

            else -> parseV1Fields(lines.drop(1))
        }
    }

    private fun isPhotxHeader(firstLine: String): Boolean = firstLine == PhotxBackupMetadata.HEADER_PREFIX ||
        firstLine.startsWith("${PhotxBackupMetadata.HEADER_PREFIX} ")

    private fun parseV1Fields(fieldLines: List<String>): BackupMetadataDecodeResult {
        val values = LinkedHashMap<String, String>()
        var error: BackupMetadataDecodeResult.Malformed? = null
        for (line in fieldLines) {
            val parsed = parseFieldLine(line, values)
            if (parsed != null) {
                error = parsed
                break
            }
        }
        val sha256 = values[KEY_SHA256]
        val corr = values[KEY_CORR]
        return when {
            error != null -> error
            sha256 == null -> BackupMetadataDecodeResult.Malformed("Missing required sha256 field.")
            corr == null -> BackupMetadataDecodeResult.Malformed("Missing required corr field.")
            else -> BackupMetadataDecodeResult.Valid(PhotxBackupMetadata(sha256 = sha256, correlationToken = corr))
        }
    }

    private fun parseFieldLine(
        line: String,
        values: MutableMap<String, String>
    ): BackupMetadataDecodeResult.Malformed? {
        val separatorIndex = line.indexOf(':')
        if (separatorIndex <= 0) {
            return BackupMetadataDecodeResult.Malformed("Expected key:value line, got '$line'.")
        }
        val key = line.substring(0, separatorIndex).trim()
        val value = line.substring(separatorIndex + 1).trim()
        return when (key) {
            KEY_SHA256 -> validateField(key, value, PhotxBackupMetadata.SHA256_REGEX, values)
            KEY_CORR -> validateField(key, value, PhotxBackupMetadata.CORRELATION_TOKEN_REGEX, values)
            else -> BackupMetadataDecodeResult.Malformed("Unknown metadata field '$key'.")
        }
    }

    private fun validateField(
        key: String,
        value: String,
        regex: Regex,
        values: MutableMap<String, String>
    ): BackupMetadataDecodeResult.Malformed? = when {
        key in values -> BackupMetadataDecodeResult.Malformed("Duplicate $key field.")

        !regex.matches(value) -> BackupMetadataDecodeResult.Malformed("Invalid $key format.")

        else -> {
            values[key] = value
            null
        }
    }
}
