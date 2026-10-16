package com.gdiffuser.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Shared tracks and machines: the `.gdxc` files in `exports/`.
 *
 * The game's own Content Library (Workshop → Content Library, port/gdx_content_window.cpp)
 * writes every export there and lists every `.gdxc` there as installable, and on Android
 * "next to the executable" is the app's external files directory (port/android/
 * gdx_android_paths.h). So getting a track onto the disk is two steps, and only the first one is
 * this class's job: put the file in that folder. The install — full validation, the free-slot
 * checks, the disk-thread write — stays with the C side, which is the only place that knows how
 * to do it safely.
 *
 * The header is read here only to show what a file is before it is installed. It is a hand-packed
 * little-endian layout documented in port/gdx_content_io.h; nothing here decides whether a file
 * is valid beyond the magic.
 */
object TrackStore {

    private const val TAG = "TrackStore"
    private const val COPY_BUFFER = 1 shl 16

    /** The game's listing matches this suffix case-sensitively (gdx_content_import.c). */
    const val EXTENSION = ".gdxc"

    private val MAGIC = "GXC1".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 0x41
    private const val NAME_LENGTH = 21
    private const val BUNDLE_ENTRY_HEADER = 33

    private const val TYPE_TRACK = 1
    private const val TYPE_MACHINE = 2
    private const val TYPE_BUNDLE = 3

    data class Item(
        val file: File,
        /** What the container says it holds, or why that could not be read. */
        val kind: String,
        /** The name stored on the disk, which is not necessarily the filename. */
        val diskName: String?,
        /** For a cup bundle: the tracks inside it, in slot order. */
        val tracks: List<String>
    ) {
        val sizeLabel: String
            get() = "%.0f KB".format(file.length() / 1024.0)
    }

    fun exportsDir(context: Context) = File(GameAssets.gameDir(context), "exports")

    fun list(context: Context): List<Item> =
        (exportsDir(context).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .sortedBy { it.name.lowercase() }
            .map { describe(it) }

    fun delete(item: Item): String? =
        if (item.file.delete()) null else "Could not delete ${item.file.name}."

    /**
     * Copies a picked file into `exports/`. Accepted by its magic rather than its name, because
     * browsers and chat apps like to rename downloads (`edit-cup.gdxc.bin`, `edit-cup (1)`), and
     * the extension is normalised to the lowercase spelling the game's listing looks for.
     */
    fun import(context: Context, uri: Uri): String? {
        val displayName = queryDisplayName(context, uri) ?: "track$EXTENSION"
        val dir = exportsDir(context).apply { mkdirs() }
        val staging = File.createTempFile("import", ".part", dir)

        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The document provider did not open $displayName." }
                FileOutputStream(staging).use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            if (!hasMagic(staging)) {
                staging.delete()
                return "$displayName is not a track or machine file (.gdxc)."
            }
            val target = nextFreeFile(dir, cleanName(displayName))
            check(staging.renameTo(target)) { "could not move it into exports/" }
            null
        } catch (error: Exception) {
            staging.delete()
            Log.e(TAG, "Import of $displayName failed", error)
            "Could not import $displayName: ${error.message ?: error}"
        }
    }

    fun export(context: Context, item: Item, destination: Uri): String? = try {
        context.contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "The document provider did not open the destination." }
            item.file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
        }
        null
    } catch (error: Exception) {
        Log.e(TAG, "Export of ${item.file.name} failed", error)
        "Could not export ${item.file.name}: ${error.message ?: error}"
    }

    /** `Edit Cup (1).GDXC.bin` → `Edit Cup (1).gdxc`. Path separators cannot survive either. */
    private fun cleanName(displayName: String): String {
        var stem = displayName.replace('/', '_').replace('\\', '_')
        val at = stem.lowercase().lastIndexOf(EXTENSION)
        if (at >= 0) stem = stem.substring(0, at)
        stem = stem.trim().ifEmpty { "track" }
        return stem + EXTENSION
    }

    /** Never overwrite: the file already there may be the player's own export. */
    private fun nextFreeFile(dir: File, name: String): File {
        val stem = name.removeSuffix(EXTENSION)
        var candidate = File(dir, name)
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_$attempt$EXTENSION")
            attempt++
        }
        return candidate
    }

    private fun hasMagic(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(MAGIC.size)
            raf.length() >= HEADER_SIZE && raf.read(head) == head.size && head.contentEquals(MAGIC)
        }
    } catch (error: Exception) {
        false
    }

    private fun describe(file: File): Item = try {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            if (raf.length() < HEADER_SIZE || raf.read(header) != HEADER_SIZE ||
                !header.copyOfRange(0, 4).contentEquals(MAGIC)
            ) {
                return Item(file, "Not a .gdxc file", null, emptyList())
            }

            val type = u32(header, 0x08)
            val name = cString(header, 0x10, NAME_LENGTH)
            when (type) {
                TYPE_TRACK -> Item(file, "Track", name, emptyList())
                TYPE_MACHINE -> Item(file, "Machine", name, emptyList())
                TYPE_BUNDLE -> Item(file, "Cup bundle", name, bundleTracks(raf))
                else -> Item(file, "Unknown content (type $type)", name, emptyList())
            }
        }
    } catch (error: Exception) {
        Log.w(TAG, "Could not read ${file.name}", error)
        Item(file, "Unreadable", null, emptyList())
    }

    /**
     * Entry 0 of a bundle is the cup table itself; entries 1..N are the tracks it references.
     * A malformed tail just ends the list early — the game's validation is what rejects it.
     */
    private fun bundleTracks(raf: RandomAccessFile): List<String> {
        raf.seek(HEADER_SIZE.toLong())
        val countBytes = ByteArray(2)
        if (raf.read(countBytes) != 2) return emptyList()
        val count = (countBytes[0].toInt() and 0xFF) or ((countBytes[1].toInt() and 0xFF) shl 8)

        val names = mutableListOf<String>()
        val entry = ByteArray(BUNDLE_ENTRY_HEADER)
        for (index in 0 until minOf(count, 8)) {
            if (raf.read(entry) != entry.size) break
            val size = u32(entry, 4 + NAME_LENGTH)
            if (index > 0) names += cString(entry, 4, NAME_LENGTH)
            raf.seek(raf.filePointer + size)
        }
        return names
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun cString(bytes: ByteArray, at: Int, length: Int): String {
        var end = at
        while (end < at + length && bytes[end] != 0.toByte()) end++
        return String(bytes, at, end - at, Charsets.US_ASCII).trim()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
