package com.gdiffuser.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Raw access to the whole game directory, for the Files screen.
 *
 * From Android 11, file managers can no longer open Android/data/<package>, which is exactly
 * where everything the game reads and writes lives (see [GameAssets]). The manager screens cover
 * the common cases — packs, saves, tracks — and this covers everything else: copying arbitrary
 * files in and out of any folder there, through the system document picker, which still can.
 *
 * Every path is checked against the game directory, so a crafted name or archive entry cannot
 * reach outside it.
 */
object GameFiles {

    private const val TAG = "GameFiles"
    private const val COPY_BUFFER = 1 shl 16

    fun root(context: Context): File = GameAssets.gameDir(context).canonicalFile

    /** True when [file] is [root] itself or somewhere beneath it. */
    fun isInside(context: Context, file: File): Boolean {
        val root = root(context).path
        val path = file.canonicalPath
        return path == root || path.startsWith(root + File.separator)
    }

    /** `files/mods/~romhacks` style label for the path line. */
    fun label(context: Context, dir: File): String {
        val relative = dir.canonicalFile.toRelativeString(root(context))
        return if (relative.isEmpty()) "files" else "files/$relative"
    }

    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

    fun sizeLabel(file: File): String {
        val bytes = file.length()
        return when {
            bytes >= 1 shl 30 -> "%.2f GB".format(bytes / (1 shl 30).toDouble())
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }

    /** Copies picked files into [dir], replacing any of the same name. */
    fun importFile(context: Context, uri: Uri, dir: File): String? {
        val name = safeName(queryName(context, uri)) ?: return "Could not read the file's name."
        return runOrReport("import $name") {
            val target = File(dir, name)
            check(isInside(context, target)) { "that name points outside the game folder" }
            copyIn(context, uri, target)
        }
    }

    /**
     * Copies a whole picked folder, subfolders included, into [dir] under its own name. The tree
     * is walked through DocumentsContract directly so no extra library is needed for it.
     */
    fun importTree(context: Context, treeUri: Uri, dir: File): String? = runOrReport("import the folder") {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = safeName(queryName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)))
            ?: "imported"
        copyTree(context, treeUri, rootId, File(dir, rootName))
    }

    private fun copyTree(context: Context, treeUri: Uri, documentId: String, target: File) {
        check(isInside(context, target)) { "that name points outside the game folder" }
        target.mkdirs()

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = safeName(cursor.getString(1)) ?: continue
                val child = File(target, name)
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    copyTree(context, treeUri, childId, child)
                } else {
                    check(isInside(context, child)) { "that name points outside the game folder" }
                    copyIn(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, childId), child)
                }
            }
        }
    }

    fun exportFile(context: Context, file: File, destination: Uri): String? = runOrReport("export ${file.name}") {
        context.contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "The document provider did not open the destination." }
            file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
        }
    }

    /** A folder goes out as one .zip, paths kept relative to the folder itself. */
    fun exportFolder(context: Context, dir: File, destination: Uri): String? = runOrReport("export ${dir.name}") {
        context.contentResolver.openOutputStream(destination).use { output ->
            requireNotNull(output) { "The document provider did not open the destination." }
            ZipOutputStream(output.buffered()).use { zip ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.toRelativeString(dir).replace(File.separatorChar, '/')))
                    file.inputStream().use { input -> input.copyTo(zip, COPY_BUFFER) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun createFolder(context: Context, parent: File, name: String): String? {
        val clean = safeName(name) ?: return "That is not a usable folder name."
        val target = File(parent, clean)
        return when {
            !isInside(context, target) -> "That name points outside the game folder."
            target.exists() -> "$clean already exists."
            target.mkdirs() -> null
            else -> "Could not create $clean."
        }
    }

    fun delete(context: Context, file: File): String? = when {
        file.canonicalFile == root(context) -> "The game folder itself cannot be deleted."
        !isInside(context, file) -> "That is outside the game folder."
        file.deleteRecursively() -> null
        else -> "Could not delete all of ${file.name}."
    }

    private fun copyIn(context: Context, uri: Uri, target: File) {
        // Written beside the target and moved over it, so a failed copy never leaves half a file
        // where the game expects a whole one.
        val staging = File(target.parentFile, ".${target.name}.part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The document provider did not open ${target.name}." }
                FileOutputStream(staging).use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            if (target.exists() && !target.delete()) error("could not replace ${target.name}")
            check(staging.renameTo(target)) { "could not move ${target.name} into place" }
        } finally {
            staging.delete()
        }
    }

    /** A leaf name only: no separators, no `.`/`..`, nothing empty. */
    fun safeName(name: String?): String? {
        val clean = name?.replace('/', '_')?.replace('\\', '_')?.replace('\u0000', '_')?.trim()
        return clean?.takeUnless { it.isEmpty() || it == "." || it == ".." }
    }

    private fun runOrReport(what: String, work: () -> Unit): String? = try {
        work()
        null
    } catch (error: Exception) {
        Log.e(TAG, "Could not $what", error)
        "Could not $what: ${error.message ?: error}"
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
