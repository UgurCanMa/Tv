package de.ugur.tv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private val tabViews = LinkedHashMap<Section, TextView>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var all: List<Channel> = emptyList()
    private var sections: Map<Section, List<Group>> = emptyMap()
    private var section = Section.LIVE
    private var openGroup: String? = null
    private var groupScroll = 0
    private var query = ""
    private var loading = false
    private var isTv = false
    private var autoplayDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        isTv = uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature("android.hardware.touchscreen")

        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        tabs = findViewById(R.id.tabs)

        adapter = RowAdapter(store, ::onGroupClick, ::onItemClick, ::onItemLongClick, ::onBackRow)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        list.adapter = adapter

        buildTabs()
        findViewById<View>(R.id.refresh).setOnClickListener { refresh() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    query.isNotEmpty() -> search.setText("")
                    openGroup != null -> {
                        openGroup = null
                        render()
                        list.scrollToPosition(groupScroll)
                        focusRow(groupScroll)
                    }
                    else -> finish()
                }
            }
        })

        loadCacheThenRefresh()
    }

    override fun onResume() {
        super.onResume()
        if (section == Section.RECENT || section == Section.FAVORITES || openGroup != null) {
            adapter.notifyDataSetChanged()
            if (section == Section.RECENT && query.isEmpty()) render()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Laden

    private fun loadCacheThenRefresh() {
        loading = true
        render()
        worker.execute {
            val cached = store.cachedPlaylist()
            if (cached != null) {
                val parsed = M3u.parse(cached)
                val secs = M3u.bySection(parsed)
                val time = store.cacheTime()
                main.post { if (!isDestroyed) setData(parsed, secs, time) }
            }
            fetch()
        }
    }

    private fun refresh() {
        loading = true
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        worker.execute { fetch() }
    }

    /** Läuft im Hintergrund. */
    private fun fetch() {
        try {
            val text = Net.download(PLAYLIST_URL)
            val parsed = M3u.parse(text)
            if (parsed.size < 50) throw IllegalStateException("Liste unvollständig")
            store.savePlaylist(text)
            val secs = M3u.bySection(parsed)
            val now = System.currentTimeMillis()
            main.post {
                if (!isDestroyed) {
                    loading = false
                    setData(parsed, secs, now)
                }
            }
        } catch (e: Exception) {
            main.post {
                if (!isDestroyed) {
                    loading = false
                    if (all.isEmpty()) {
                        status.text = getString(R.string.offline)
                        status.visibility = View.VISIBLE
                        render()
                    } else {
                        Toast.makeText(this, R.string.refresh_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setData(channels: List<Channel>, secs: Map<Section, List<Group>>, time: Long) {
        all = channels
        sections = secs
        val og = openGroup
        if (og != null && secs[section]?.any { it.key == og } != true) openGroup = null
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY).format(Date(time))
        status.text = getString(R.string.status, String.format(Locale.GERMANY, "%,d", channels.size), date)
        status.visibility = View.VISIBLE
        render()
        maybeAutoplay()
    }

    /** Für automatische Tests: startet direkt einen Eintrag, z. B. --es autoplay "DW". */
    private fun maybeAutoplay() {
        val wanted = intent?.getStringExtra("autoplay")
        if (autoplayDone || wanted.isNullOrEmpty()) return
        val ch = all.firstOrNull { it.name.contains(wanted, ignoreCase = true) } ?: return
        autoplayDone = true
        play(listOf(ch), 0)
    }

    // ------------------------------------------------------------------ Anzeige

    private fun buildTabs() {
        for (s in Section.values()) {
            val tab = layoutInflater.inflate(R.layout.tab, tabs, false) as TextView
            tab.text = s.label
            tab.setOnClickListener { selectSection(s) }
            tabs.addView(tab)
            tabViews[s] = tab
        }
        updateTabs()
    }

    private fun selectSection(s: Section) {
        section = s
        openGroup = null
        updateTabs()
        if (query.isNotEmpty()) search.setText("") else render()
        list.scrollToPosition(0)
    }

    private fun updateTabs() {
        for ((s, view) in tabViews) view.isSelected = s == section
    }

    private fun render() {
        val rows = ArrayList<Row>()
        val q = query.trim()
        if (q.length >= 2) {
            val needle = q.lowercase(Locale.GERMANY)
            val hits = all.asSequence()
                .filter { it.name.lowercase(Locale.GERMANY).contains(needle) }
                .distinctBy { it.url }
                .take(MAX_HITS)
                .toList()
            rows.add(
                Row.Info(
                    if (hits.isEmpty()) getString(R.string.no_hits, q)
                    else getString(R.string.hits, if (hits.size >= MAX_HITS) "$MAX_HITS+" else hits.size.toString(), q)
                )
            )
            hits.mapTo(rows) { Row.Item(it) }
        } else {
            when (section) {
                Section.FAVORITES -> {
                    val fav = store.favorites()
                    if (fav.isEmpty()) rows.add(Row.Info(getString(R.string.no_favorites)))
                    else fav.mapTo(rows) { Row.Item(it) }
                }
                Section.RECENT -> {
                    val rec = store.recents()
                    if (rec.isEmpty()) rows.add(Row.Info(getString(R.string.no_recents)))
                    else rec.mapTo(rows) { Row.Item(it) }
                }
                else -> {
                    val groups = sections[section].orEmpty()
                    val open = groups.firstOrNull { it.key == openGroup }
                    when {
                        open != null -> {
                            rows.add(Row.Back(section.label + "  ›  " + open.title))
                            open.items.mapTo(rows) { Row.Item(it) }
                        }
                        groups.isEmpty() ->
                            rows.add(Row.Info(getString(if (loading) R.string.loading else R.string.empty)))
                        else -> groups.mapTo(rows) { Row.GroupRow(it.key, it.title, it.items.size) }
                    }
                }
            }
        }
        adapter.submit(rows)
    }

    private fun focusRow(position: Int) {
        if (!isTv) return
        list.post {
            val holder = list.findViewHolderForAdapterPosition(position) ?: list.findViewHolderForAdapterPosition(0)
            holder?.itemView?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ Klicks

    private fun onGroupClick(position: Int, row: Row.GroupRow) {
        groupScroll = position.coerceAtLeast(0)
        openGroup = row.key
        render()
        list.scrollToPosition(0)
        focusRow(1)
    }

    private fun onBackRow() {
        onBackPressedDispatcher.onBackPressed()
    }

    private fun onItemClick(ch: Channel) {
        val items = adapter.rows.filterIsInstance<Row.Item>().map { it.channel }
        val index = items.indexOfFirst { it.url == ch.url }.coerceAtLeast(0)
        play(items.ifEmpty { listOf(ch) }, index)
    }

    private fun onItemLongClick(ch: Channel) {
        val added = store.toggleFavorite(ch)
        Toast.makeText(this, if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (section == Section.FAVORITES && query.isEmpty()) render() else adapter.notifyDataSetChanged()
    }

    private fun play(items: List<Channel>, index: Int) {
        PlayerActivity.queue = items
        PlayerActivity.startIndex = index
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        private const val MAX_HITS = 300
    }
}
