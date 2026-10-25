package de.ugur.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat

/** „Meine Listen“: weitere Listen per Link, als Datei oder per Teilen aus anderen Apps hinzufügen. */
class SourcesActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var container: LinearLayout

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)
        store = Store(this)
        container = findViewById(R.id.sources)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.add_link).setOnClickListener { showAddLink("", "") }
        findViewById<View>(R.id.add_file).setOnClickListener { pickFile() }
        renderList()
        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = Regex("https?://\\S+").find(text)?.value
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    link != null -> showAddLink(link, subject)
                    stream != null -> importFile(stream)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme == "http" || uri.scheme == "https") showAddLink(uri.toString(), "") else importFile(uri)
            }
        }
    }

    private fun renderList() {
        container.removeAllViews()
        addRow(getString(R.string.builtin_name), getString(R.string.builtin_desc), null)
        for (s in store.sources()) {
            addRow(s.name, if (s.isFile) getString(R.string.from_file) else s.url, s)
        }
    }

    private fun addRow(title: String, subtitle: String, source: Source?) {
        val row = layoutInflater.inflate(R.layout.row_source, container, false)
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.subtitle).text = subtitle
        val remove = row.findViewById<TextView>(R.id.remove)
        if (source == null) {
            remove.visibility = View.GONE
        } else {
            remove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.remove_question, source.name))
                    .setPositiveButton(R.string.remove) { _, _ ->
                        store.removeSource(source.id)
                        renderList()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        container.addView(row)
    }

    private fun showAddLink(url: String, name: String) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val urlField = EditText(this).apply {
            hint = getString(R.string.link_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(url)
        }
        val nameField = EditText(this).apply {
            hint = getString(R.string.name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setText(name)
        }
        box.addView(urlField)
        box.addView(nameField)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_link_title)
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                val link = urlField.text.toString().trim()
                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    Toast.makeText(this, R.string.invalid_link, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val listName = nameField.text.toString().trim()
                    .ifEmpty { Uri.parse(link).host?.removePrefix("www.") ?: getString(R.string.my_list) }
                store.addSource(listName, link)
                Toast.makeText(this, getString(R.string.added, listName), Toast.LENGTH_LONG).show()
                renderList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickFile() {
        try {
            openFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun importFile(uri: Uri) {
        val name = (displayName(uri) ?: uri.lastPathSegment.orEmpty())
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifEmpty { getString(R.string.my_list) }
        Thread {
            val text = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 40 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (text == null || Lists.interpret(Source("test", name, ""), text).isEmpty()) {
                    Toast.makeText(this, R.string.file_invalid, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val source = store.addSource(name, "")
                store.writeSourceCache(source.id, text)
                Toast.makeText(this, getString(R.string.added, name), Toast.LENGTH_LONG).show()
                renderList()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
