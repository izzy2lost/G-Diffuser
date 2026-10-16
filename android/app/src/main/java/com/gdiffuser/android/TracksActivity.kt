package com.gdiffuser.android

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * Custom tracks, machines and Edit Cup bundles (.gdxc).
 *
 * This screen only moves files in and out of `exports/`. Installing one onto the 64DD disk is
 * done in game, in Workshop → Content Library, which validates it and checks the disk has room —
 * so unlike packs, nothing here needs a restart: the Content Library rescans the folder each time
 * it is opened.
 */
class TracksActivity : ComponentActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyLabel: TextView

    private val importTracks =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            runInBackground("Importing…") { uris.mapNotNull { TrackStore.import(this, it) } }
        }

    /** Set just before the export picker opens, since the contract carries only a filename. */
    private var pendingExport: TrackStore.Item? = null

    private val exportTrack =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val item = pendingExport ?: return@registerForActivityResult
            pendingExport = null
            if (uri == null) return@registerForActivityResult
            runInBackground("Exporting…") { listOfNotNull(TrackStore.export(this, item, uri)) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    /** Also the first fill. The game may have exported something since this screen was last open. */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.tracks_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Custom tracks, machines and Edit Cup bundles are .gdxc files. Import one here, " +
                "then install it onto the disk in game: Menu → Workshop → Content Library → Import. " +
                "Tracks exported from the Content Library show up here too, ready to share."
            setTextColor(SUBTLE)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        emptyLabel = TextView(this).apply {
            text = "No .gdxc files yet.\n\nImport one, or copy it straight into\n" +
                "${TrackStore.exportsDir(this@TracksActivity)}"
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
        actions.addView(button("Import .gdxc") { importTracks.launch(arrayOf("*/*")) })
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(button("Done") { finish() })
        root.addView(actions)

        setContentView(root)
        root.padForSystemBars()
    }

    private fun refresh() {
        val items = TrackStore.list(this)
        listContainer.removeAllViews()
        emptyLabel.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEachIndexed { index, item -> listContainer.addView(row(item, index)) }
    }

    private fun row(item: TrackStore.Item, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(if (index % 2 == 0) ROW_EVEN else ROW_ODD)
        }

        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(TextView(this).apply {
            text = item.file.name
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        label.addView(TextView(this).apply {
            text = buildString {
                append(item.kind)
                if (!item.diskName.isNullOrEmpty() && item.tracks.isEmpty()) append(" · ${item.diskName}")
                append(" · ${item.sizeLabel}")
                if (item.tracks.isNotEmpty()) append("\n${item.tracks.joinToString(", ")}")
            }
            setTextColor(SUBTLE)
            textSize = 11f
        })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(button("Export") {
            pendingExport = item
            exportTrack.launch(item.file.name)
        })
        row.addView(button("Delete") { confirmDelete(item) })

        return row
    }

    private fun confirmDelete(item: TrackStore.Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${item.file.name}?")
            .setMessage("This removes the file only. Anything already installed onto the disk stays there.")
            .setPositiveButton("Delete") { _, _ ->
                val error = TrackStore.delete(item)
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
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

        thread(name = "tracks-io") {
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
        val BACKGROUND = Color.rgb(14, 16, 28)
        val SUBTLE = Color.rgb(150, 160, 175)
        val ROW_EVEN = Color.argb(24, 255, 255, 255)
        val ROW_ODD = Color.TRANSPARENT
    }
}
