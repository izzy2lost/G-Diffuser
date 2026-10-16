package com.gdiffuser.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Offers the game directory to the system Files app and every document picker.
 *
 * From Android 11 no file manager can open Android/data/<package>, so without this the only way
 * at the game's files is the in-app Files screen. With it, "G-Diffuser" shows up as a location in
 * the Files app's sidebar and in any app's file picker, with full read, write, create, rename and
 * delete, which is how emulators on the platform solve the same problem.
 *
 * A document id is the path relative to the game directory, with [ROOT_ID] for the directory
 * itself; every id is resolved and checked through [GameFiles.isInside] before it is touched.
 */
class GameFilesProvider : DocumentsProvider() {

    override fun onCreate() = true

    /** ContentProvider.requireContext() is API 30+; minSdk is lower. */
    private val ctx get() = context ?: throw IllegalStateException("Provider not attached")

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = ctx
        val root = GameFiles.root(context)
        return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, "Game files, packs, saves and tracks")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_AVAILABLE_BYTES, root.freeSpace)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply { addRow(this, fileFor(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = fileFor(parentDocumentId)
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            GameFiles.list(parent).forEach { addRow(this, it) }
            setNotificationUri(ctx.contentResolver, childrenUri(parentDocumentId))
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        val writing = mode.contains('w')
        if (!writing) return ParcelFileDescriptor.open(file, accessMode)

        // Tell anyone listing the folder once the writer closes, so sizes refresh.
        val handler = Handler(ctx.mainLooper)
        return ParcelFileDescriptor.open(file, accessMode, handler) {
            notifyChildren(file.parentFile)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        val name = GameFiles.safeName(displayName) ?: throw FileNotFoundException("Bad name: $displayName")

        var target = File(parent, name)
        var attempt = 1
        while (target.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            target = File(parent, "$stem ($attempt)$ext")
            attempt++
        }
        checkInside(target)

        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Could not create $name")
        notifyChildren(parent)
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        val error = GameFiles.delete(ctx, file)
        if (error != null) throw FileNotFoundException(error)
        notifyChildren(file.parentFile)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        if (documentId == ROOT_ID) throw FileNotFoundException("The game folder cannot be renamed.")
        val name = GameFiles.safeName(displayName) ?: throw FileNotFoundException("Bad name: $displayName")
        val target = File(file.parentFile, name)
        checkInside(target)
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("Could not rename to $name")
        notifyChildren(file.parentFile)
        return idFor(target)
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileFor(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId).canonicalPath
        val child = fileFor(documentId).canonicalPath
        return child.startsWith(parent + File.separator)
    }

    private fun addRow(cursor: MatrixCursor, file: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = if (file.isDirectory) flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags or Document.FLAG_SUPPORTS_WRITE

        val isRoot = file.canonicalFile == GameFiles.root(ctx)
        if (isRoot) flags = Document.FLAG_DIR_SUPPORTS_CREATE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idFor(file))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) ctx.getString(R.string.app_name) else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun fileFor(documentId: String): File {
        val root = GameFiles.root(ctx)
        val file = if (documentId == ROOT_ID) root else File(root, documentId)
        checkInside(file)
        if (!file.exists()) throw FileNotFoundException("Missing: $documentId")
        return file
    }

    private fun idFor(file: File): String {
        val relative = file.canonicalFile.toRelativeString(GameFiles.root(ctx))
        return relative.ifEmpty { ROOT_ID }
    }

    private fun checkInside(file: File) {
        if (!GameFiles.isInside(ctx, file)) throw FileNotFoundException("Outside the game folder")
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun childrenUri(parentDocumentId: String) =
        DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId)

    private fun notifyChildren(parent: File?) {
        if (parent == null || !GameFiles.isInside(ctx, parent)) return
        ctx.contentResolver.notifyChange(childrenUri(idFor(parent)), null)
    }

    private fun authority() = "${ctx.packageName}$AUTHORITY_SUFFIX"

    companion object {
        /** Appended to the package name in the manifest's android:authorities as well. */
        const val AUTHORITY_SUFFIX = ".files"
        private const val ROOT_ID = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }
}
