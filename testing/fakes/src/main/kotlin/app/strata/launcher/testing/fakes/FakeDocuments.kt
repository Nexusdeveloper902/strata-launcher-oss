package app.strata.launcher.testing.fakes

import app.strata.launcher.core.platformapi.system.DocumentIO
import app.strata.launcher.core.platformapi.system.DocumentRead
import app.strata.launcher.core.platformapi.system.SharedFiles

/** [DocumentIO] over a map of `content:` URIs; other schemes are refused like the real adapter (T-SEC-01). */
class FakeDocumentIO : DocumentIO {
    val documents = linkedMapOf<String, ByteArray>()

    /** URIs that were opened for reading (a refused scheme never appears here). */
    val opened = mutableListOf<String>()
    val failing = mutableSetOf<String>()

    override suspend fun read(
        uri: String,
        maxBytes: Int,
    ): DocumentRead {
        if (!uri.startsWith("content:")) return DocumentRead.UnsupportedScheme
        opened += uri
        if (uri in failing) return DocumentRead.Failed
        val b = documents[uri] ?: return DocumentRead.Failed
        return if (b.size > maxBytes) DocumentRead.TooLarge else DocumentRead.Ok(b.copyOf())
    }

    override suspend fun write(
        uri: String,
        bytes: ByteArray,
    ): Boolean {
        if (!uri.startsWith("content:") || uri in failing) return false
        documents[uri] = bytes.copyOf()
        return true
    }
}

/** [SharedFiles] in memory: URIs look like the real FileProvider's (`content://<authority>/share/<name>`). */
class FakeSharedFiles(private val authority: String = "app.strata.launcher.files") : SharedFiles {
    val published = linkedMapOf<String, ByteArray>()
    var purges = 0
        private set
    var failWrites = false

    override suspend fun publish(
        fileName: String,
        bytes: ByteArray,
    ): String? {
        if (failWrites) return null
        val uri = "content://$authority/share/${published.size}/$fileName"
        published[uri] = bytes.copyOf()
        return uri
    }

    override suspend fun purgeExpired() {
        purges++
    }
}
