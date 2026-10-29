package app.strata.launcher.core.data.onboarding

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream

/**
 * Temporary M0 store for `onboarding_done` (IMPLEMENTATION_ROADMAP M0). M1 moves the value into
 * field 3 of `settings.pb` (ARCHITECTURE §4.2) and deletes this file.
 */
internal data class EntryFlags(val onboardingDone: Boolean = false)

/** Two bytes: format version, then the flag. Any other content is corruption. */
internal object EntryFlagsSerializer : Serializer<EntryFlags> {
    private const val VERSION: Int = 1

    override val defaultValue: EntryFlags = EntryFlags()

    override suspend fun readFrom(input: InputStream): EntryFlags {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        if (bytes.size != 2 || bytes[0].toInt() != VERSION || bytes[1].toInt() !in 0..1) {
            throw CorruptionException("Unexpected entry flags content (${bytes.size} bytes)")
        }
        return EntryFlags(onboardingDone = bytes[1].toInt() == 1)
    }

    override suspend fun writeTo(t: EntryFlags, output: OutputStream) {
        output.write(byteArrayOf(VERSION.toByte(), if (t.onboardingDone) 1 else 0))
    }
}
