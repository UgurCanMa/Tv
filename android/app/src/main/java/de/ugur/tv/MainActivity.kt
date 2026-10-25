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

    private sealed class TabKey {
        data class Sec(val section: Section) : TabKey()
        data class Src(val id: String) : TabKey()
        object Manage : TabKey()
    }

    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var adapter: RowAdapter
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var tabs: LinearLayout
    private val tabViews = LinkedHashMap<TabKey, TextView>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var mainList: List<Channel> = emptyList()
    private var sections: Map<Section, List<Group>> = emptyMap()
    private var sources: List<Source> = emptyList()
    private var custom: Map<String, List<Channel>> = emptyMap()
    private var customGroups: Map<String, List<Group>> = emptyMap()
    private var customErrors: Map<String, String> = emptyMap()
    private var sourcesVersion = -1

    private var current: TabKey = TabKey.Sec(Section.LIVE)
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

        sourcesVersion = store.sourcesVersion()
        sources = store.sources()
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

        startLoading()
    }

    override fun onResume() {
        super.onResume()
        val version = store.sourcesVersion()
        if (version != sourcesVersion) {
            sourcesVersion = version
            sources = store.sources()
            val cur = current
            if (cur is TabKey.Src && sources.none { it.id == cur.id }) current = TabKey.Sec(Section.LIVE)
            buildTabs()
            render()
            worker.execute {
                loadCustom(fromNetwork = false)
                loadCustom(fromNetwork = true)
            }
        } else if (current == TabKey.Sec(Section.RECENT) || current == TabKey.Sec(Section.FAVORITES)) {
            render()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Laden

    private fun startLoading() {
        loading = true
        render()
        worker.execute {
            val cached = store.cachedPlaylist()
            if (cached != null) {
                val parsed = M3u.parse(cached)
                val secs = M3u.bySection(parsed)
                val time = store.cacheTime()
                main.post { if (!isDestroyed) setMain(parsed, secs, time) }
            }
            loadCustom(fromNetwork = false)
            fetchMain()
            loadCustom(fromNetwork = true)
        }
    }

    private fun refresh() {
        loading = true
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show()
        worker.execute {
            fetchMain()
            loadCustom(fromNetwork = true)
        }
    }

    /** Läuft im Hintergrund. */
    private fun fetchMain() {
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
                    setMain(parsed, secs, now)
                }
            }
        } catch (e: Exception) {
            main.post {
                if (!isDestroyed) {
                    loading = false
                    if (mainList.isEmpty()) {
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

    /** Lädt die eigenen Listen (erst aus dem Speicher, dann aus dem Netz). Läuft im Hintergrund. */
    private fun loadCustom(fromNetwork: Boolean) {
        val srcs = store.sources()
        val result = LinkedHashMap<String, List<Channel>>()
        val errors = HashMap<String, String>()
        for (s in srcs) {
            var text = store.readSourceCache(s.id)
            if (fromNetwork && !s.isFile) {
                try {
                    val fresh = Net.fetchList(s.url)
                    text = fresh ?: Lists.STREAM_MARKER
                    store.writeSourceCache(s.id, text)
                } catch (e: Exception) {
                    if (text == null) errors[s.id] = e.message ?: e.javaClass.simpleName
                }
            }
            result[s.id] = Lists.interpret(s, text)
            if (fromNetwork && text != null && result[s.id].isNullOrEmpty()) errors[s.id] = getString(R.string.list_empty)
        }
        main.post {
            if (!isDestroyed) {
                sources = srcs
                custom = result
                customGroups = result.mapValues { M3u.groups(it.value) }
                if (fromNetwork) customErrors = errors
                if (tabViews.keys.filterIsInstance<TabKey.Src>().map { it.id } != srcs.map { it.id }) buildTabs()
                render()
            }
        }
    }

    private fun setMain(channels: List<Channel>, secs: Map<Section, List<Group>>, time: Long) {
        mainList = channels
        sections = secs
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
        val ch = mainList.firstOrNull { it.name.contains(wanted, ignoreCase = true) } ?: return
        autoplayDone = true
        play(listOf(ch), 0)
    }

    // ------------------------------------------------------------------ Reiter

    private fun buildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        val keys = ArrayList<Pair<TabKey, String>>()
        for (s in Section.values()) {
            if (s != Section.FAVORITES && s != Section.RECENT) keys.add(TabKey.Sec(s) to s.label)
        }
        for (src in sources) keys.add(TabKey.Src(src.id) to src.name)
        keys.add(TabKey.Sec(Section.FAVORITES) to Section.FAVORITES.label)
        keys.add(TabKey.Sec(Section.RECENT) to Section.RECENT.label)
        keys.add(TabKey.Manage to getString(R.string.manage_tab))
        for ((key, label) in keys) {
            val tab = layoutInflater.inflate(R.layout.tab, tabs, false) as TextView
            tab.text = label
            tab.setOnClickListener { selectTab(key) }
            tabs.addView(tab)
            tabViews[key] = tab
        }
        updateTabs()
    }

    private fun selectTab(key: TabKey) {
        if (key == TabKey.Manage) {
            startActivity(Intent(this, SourcesActivity::class.java))
            return
        }
        current = key
        openGroup = null
        updateTabs()
        if (query.isNotEmpty()) search.setText("") else render()
        list.scrollToPosition(0)
    }

    private fun updateTabs() {
        for ((key, view) in tabViews) view.isSelected = key == current
    }

    // ------------------------------------------------------------------ Anzeige

    private fun render() {
        val rows = ArrayList<Row>()
        val q = query.trim()
        val key = current
        if (q.length >= 2) {
            val needle = q.lowercase(Locale.GERMANY)
            val hits = (mainList.asSequence() + custom.values.asSequence().flatten())
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
        } else if (key == TabKey.Sec(Section.FAVORITES)) {
            val fav = store.favorites()
            if (fav.isEmpty()) rows.add(Row.Info(getString(R.string.no_favorites))) else fav.mapTo(rows) { Row.Item(it) }
        } else if (key == TabKey.Sec(Section.RECENT)) {
            val rec = store.recents()
            if (rec.isEmpty()) rows.add(Row.Info(getString(R.string.no_recents))) else rec.mapTo(rows) { Row.Item(it) }
        } else {
            val label: String
            val groups: List<Group>
            var error: String? = null
            if (key is TabKey.Src) {
                groups = customGroups[key.id].orEmpty()
                label = sources.firstOrNull { it.id == key.id }?.name.orEmpty()
                error = customErrors[key.id]
            } else {
                val section = (key as? TabKey.Sec)?.section ?: Section.LIVE
                groups = sections[section].orEmpty()
                label = section.label
            }
            val open = groups.firstOrNull { it.key == openGroup }
            when {
                open != null -> {
                    rows.add(Row.Back("$label  ›  ${open.title}"))
                    open.items.mapTo(rows) { Row.Item(it) }
                }
                groups.size == 1 -> groups[0].items.mapTo(rows) { Row.Item(it) }
                groups.isEmpty() && error != null -> rows.add(Row.Info(getString(R.string.list_error, error)))
                groups.isEmpty() -> rows.add(Row.Info(getString(if (loading || key is TabKey.Src) R.string.loading else R.string.empty)))
                else -> groups.mapTo(rows) { Row.GroupRow(it.key, it.title, it.items.size) }
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
        if (current == TabKey.Sec(Section.FAVORITES) && query.isEmpty()) render() else adapter.notifyDataSetChanged()
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
