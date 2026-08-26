package dev.dydent.filecolorrules.config

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object AtomicConfigWriter {
    fun write(path: Path, source: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".file-color-rules-", ".tmp")
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IllegalStateException(
                    "The filesystem does not support an atomic configuration update; the original file was preserved.",
                    exception,
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun fingerprint(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
