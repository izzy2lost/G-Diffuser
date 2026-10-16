package com.gdiffuser.android

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import kotlin.concurrent.thread

/**
 * A plain file browser over the game directory (Android/data/<package>/files).
 *
 * Newer Android versions hide that folder from file managers, so this is the way in and out for
 * anything the dedicated screens do not cover: browse any folder, import files or a whole folder
 * into it, export a file or a folder (as .zip), make folders and delete. The same tree is also
 * offered to the system Files app by [GameFilesProvider].
 */
class FilesActivity : ComponentActivity() {

    private lateinit var current: File
    private lateinit var pathLabel: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyLabel: TextView
    private lateinit var upButton: Button

    private val importFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val dir = current
            runInBackground("Importing…") { uris.mapNotNull { GameFiles.importFile(this, it, dir) } }
        }

    private val importFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val dir = current
            runInBackground("Importing folder…") { listOfNotNull(GameFiles.importTree(this, uri, dir)) }
        }

    /** Set just before the export picker opens, since the contract carries only a filename. */
    private var pendingExport: File? = null

    private val exportPicked =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val file = pendingExport ?: return@registerForActivityResult
            pendingExport = null
            if (uri == null) return@registerForActivityResult
            runInBackground("Exporting…") {
                listOfNotNull(
                    if (file.isDirectory) GameFiles.exportFolder(this, file, uri)
                    else GameFiles.exportFile(this, file, uri)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = savedInstanceState?.getString(KEY_DIR)?.let(::File)
            ?.takeIf { it.isDirectory && GameFiles.isInside(this, it) }
            ?: GameFiles.root(this)
        buildUi()

        // Back climbs a folder before it leaves the screen, like any file manager.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, current.path)
    }

    private fun buildUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.files_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Everything the game keeps on this device. Newer Android versions hide this folder " +
                "from file managers, so copy files in and out from here — or open the system Files " +
                "app, where it also appears as \"${getString(R.string.app_name)}\".\n" +
                "Deleting the .o2r archives at the top level makes the launcher rebuild them."
            setTextColor(SUBTLE)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        pathLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(pathLabel)

        emptyLabel = TextView(this).apply {
            text = "This folder is empty."
            setTextColor(SUBTLE)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(emptyLabel)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(listContainer) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        upButton = button("Up") { goUp() }
        actions.addView(upButton)
        actions.addView(button("Import files") { importFiles.launch(arrayOf("*/*")) })
        actions.addView(button("Import folder") { importFolder.launch(null) })
        actions.addView(button("New folder") { askNewFolder() })
        actions.addView(button("Export folder") { export(current) })
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(button("Done") { finish() })
        root.addView(actions)

        setContentView(root)
        root.padForSystemBars()
    }

    private fun refresh() {
        if (!current.isDirectory) current = GameFiles.root(this)
        pathLabel.text = GameFiles.label(this, current)
        upButton.isEnabled = current.canonicalFile != GameFiles.root(this)

        val entries = GameFiles.list(current)
        listContainer.removeAllViews()
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEachIndexed { index, file -> listContainer.addView(row(file, index)) }
    }

    private fun goUp(): Boolean {
        val parent = current.parentFile
        if (current.canonicalFile == GameFiles.root(this) || parent == null) return false
        current = parent
        refresh()
        return true
    }

    private fun row(file: File, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(if (index % 2 == 0) ROW_EVEN else ROW_ODD)
        }

        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(TextView(this).apply {
            text = if (file.isDirectory) "${file.name}/" else file.name
            setTextColor(if (file.isDirectory) FOLDER else Color.WHITE)
            textSize = 15f
        })
        label.addView(TextView(this).apply {
            text = if (file.isDirectory) {
                val count = file.list()?.size ?: 0
                "Folder · $count item${if (count == 1) "" else "s"}"
            } else {
                GameFiles.sizeLabel(file)
            }
            setTextColor(SUBTLE)
            textSize = 11f
        })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (file.isDirectory) {
            row.isClickable = true
            row.setOnClickListener {
                current = file
                refresh()
            }
            row.addView(button("Open") {
                current = file
                refresh()
            })
        }
        row.addView(button("Export") { export(file) })
        row.addView(button("Delete") { confirmDelete(file) })

        return row
    }

    private fun export(file: File) {
        pendingExport = file
        exportPicked.launch(if (file.isDirectory) "${zipStem(file)}.zip" else file.name)
    }

    private fun zipStem(dir: File): String =
        if (dir.canonicalFile == GameFiles.root(this)) "gdiffuser-files" else dir.name

    private fun askNewFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                GameFiles.createFolder(this, current, input.text.toString())
                    ?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}?")
            .setMessage(
                if (file.isDirectory) "This deletes the folder and everything in it. It cannot be undone."
                else "This cannot be undone. Exporting first keeps a copy."
            )
            .setPositiveButton("Delete") { _, _ ->
                GameFiles.delete(this, file)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runInBackground(message: String, work: () -> List<String>) {
        val progress = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .show()

        thread(name = "files-io") {
            val errors = runCatching(work).getOrElse { listOf(it.message ?: it.toString()) }

            runOnUiThread {
                progress.dismiss()
                if (errors.isNotEmpty()) {
                    Toast.makeText(this, errors.joinToString("\n\n"), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_DIR = "dir"
        val BACKGROUND = Color.rgb(14, 16, 28)
        val SUBTLE = Color.rgb(150, 160, 175)
        val FOLDER = Color.rgb(140, 200, 255)
        val ROW_EVEN = Color.argb(24, 255, 255, 255)
        val ROW_ODD = Color.TRANSPARENT
    }
}
