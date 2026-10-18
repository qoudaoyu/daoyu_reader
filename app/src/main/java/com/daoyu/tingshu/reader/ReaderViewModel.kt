package com.daoyu.tingshu.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.Book
import com.daoyu.tingshu.data.Bookmark
import com.daoyu.tingshu.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

class PageRef(val layout: ChapterLayout, val page: Page) {
    val key get() = "${page.chapter}:${page.index}:${layout.hashCode()}"
}

data class ReaderUi(
    val cur: PageRef? = null,
    val prev: PageRef? = null,
    val next: PageRef? = null,
    val loading: Boolean = true,
)

class ReaderViewModel(private val bookId: Long) : ViewModel() {
    private val app = App.instance

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()

    val bookmarks = app.db.bookmarks().observe(bookId)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // anchor: the reading position independent of layout
    private var anchorChapter = 0
    private var anchorOffset = 0

    private var spec: LayoutSpec? = null
    private val cache = LinkedHashMap<Int, ChapterLayout>()
    private val lock = Mutex()
    private var rebuildJob: Job? = null
    private var saveJob: Job? = null

    /** time of the last page turn made by the user; listening auto-follow waits after it */
    private var lastUserTurn = 0L

    init {
        viewModelScope.launch {
            val b = withContext(Dispatchers.IO) { app.db.books().get(bookId) } ?: return@launch
            _chapters.value = withContext(Dispatchers.IO) { app.db.chapters().list(bookId) }
            _book.value = b
            anchorChapter = b.readChapter.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
            anchorOffset = b.readOffset
            rebuild()
        }
        // follow the listening position
        viewModelScope.launch {
            app.tts.state.collectLatest { st ->
                if (!st.active || st.bookId != bookId) return@collectLatest
                if (System.currentTimeMillis() - lastUserTurn < 8000) return@collectLatest
                val cur = _ui.value.cur ?: return@collectLatest
                val inPage = st.chapter == cur.page.chapter && st.segStart >= cur.page.startBody && st.segStart < cur.page.endBody
                if (!inPage) {
                    anchorChapter = st.chapter
                    anchorOffset = st.segStart
                    rebuild()
                }
            }
        }
    }

    fun setSpec(s: LayoutSpec) {
        if (s == spec) return
        spec = s
        synchronized(cache) { cache.clear() }
        rebuild()
    }

    private suspend fun layoutOf(ch: Int): ChapterLayout? {
        val s = spec ?: return null
        val chapters = _chapters.value
        if (ch !in chapters.indices) return null
        synchronized(cache) { cache[ch] }?.let { return it }
        return lock.withLock {
            synchronized(cache) { cache[ch] }?.let { return@withLock it }
            val l = withContext(Dispatchers.Default) {
                val body = app.storage.readChapter(bookId, ch)
                Paginator.layout(ch, chapters[ch].title, body, s)
            }
            if (spec != s) return@withLock null
            synchronized(cache) {
                cache[ch] = l
                // keep a small window of chapters in memory
                while (cache.size > 5) cache.remove(cache.keys.first { it != ch })
            }
            l
        }
    }

    private fun rebuild() {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            val cl = layoutOf(anchorChapter) ?: return@launch
            val idx = cl.pageForBody(anchorOffset)
            val cur = PageRef(cl, cl.pages[idx])
            // first open / jump: show the page at once, neighbours follow
            if (_ui.value.cur?.page?.let { it.chapter == cur.page.chapter && it.index == cur.page.index && _ui.value.cur?.layout === cl } != true)
                _ui.value = ReaderUi(cur = cur, prev = null, next = null, loading = false)
            val prev = if (idx > 0) PageRef(cl, cl.pages[idx - 1])
            else layoutOf(anchorChapter - 1)?.let { PageRef(it, it.pages.last()) }
            val next = if (idx < cl.pages.size - 1) PageRef(cl, cl.pages[idx + 1])
            else layoutOf(anchorChapter + 1)?.let { PageRef(it, it.pages.first()) }
            _ui.value = ReaderUi(cur = cur, prev = prev, next = next, loading = false)
            scheduleSave()
        }
    }

    fun turn(forward: Boolean, byUser: Boolean = true): Boolean {
        val target = if (forward) _ui.value.next else _ui.value.prev
        if (target == null) {
            val ch = _ui.value.cur?.page?.chapter ?: return false
            val chapters = _chapters.value
            if (forward && ch >= chapters.size - 1 && _ui.value.cur?.let { it.page.index == it.layout.pages.size - 1 } == true) toast.tryEmit("已经是最后一页了")
            else if (!forward && ch == 0 && _ui.value.cur?.page?.index == 0) toast.tryEmit("已经是第一页了")
            return false
        }
        if (byUser) lastUserTurn = System.currentTimeMillis()
        anchorChapter = target.page.chapter
        anchorOffset = target.page.startBody
        // optimistic shift so the animation lands without flicker
        _ui.value = ReaderUi(cur = target, prev = if (forward) _ui.value.cur else null, next = if (forward) null else _ui.value.cur, loading = false)
        rebuild()
        return true
    }

    fun jumpToChapter(ch: Int, offset: Int = 0) {
        lastUserTurn = System.currentTimeMillis()
        anchorChapter = ch.coerceIn(0, (_chapters.value.size - 1).coerceAtLeast(0))
        anchorOffset = offset
        rebuild()
    }

    fun currentPosition(): Pair<Int, Int> = (_ui.value.cur?.page?.chapter ?: anchorChapter) to (_ui.value.cur?.page?.startBody ?: anchorOffset)

    fun progressOf(ref: PageRef?): Float {
        val b = _book.value ?: return 0f
        val ch = _chapters.value.getOrNull(ref?.page?.chapter ?: return 0f) ?: return 0f
        if (b.totalChars <= 0) return 0f
        return ((ch.startChar + ref.page.endBody).toFloat() / b.totalChars).coerceIn(0f, 1f)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val cur = _ui.value.cur ?: return
        withContext(Dispatchers.IO + NonCancellable) {
            app.db.books().saveProgress(bookId, cur.page.chapter, cur.page.startBody, progressOf(cur))
        }
    }

    fun addBookmark() {
        val cur = _ui.value.cur ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val body = cur.layout.body
            val s = cur.page.startBody.coerceIn(0, body.length)
            val snippet = body.substring(s, (s + 60).coerceAtMost(body.length)).replace('\n', ' ')
            app.db.bookmarks().insert(Bookmark(bookId = bookId, chapter = cur.page.chapter, offset = s, chapterTitle = cur.layout.title, snippet = snippet))
            toast.emit("已添加书签")
        }
    }

    fun deleteBookmark(b: Bookmark) = viewModelScope.launch(Dispatchers.IO) { app.db.bookmarks().delete(b.id) }

    fun startListening() {
        val b = _book.value ?: return
        val (ch, off) = currentPosition()
        lastUserTurn = 0
        app.tts.start(b, ch, off)
    }

    /** start listening from the beginning of a given paragraph-ish position (long press) */
    fun listenFrom(chapter: Int, offset: Int) {
        val b = _book.value ?: return
        lastUserTurn = 0
        app.tts.start(b, chapter, offset)
    }

    fun refreshBook() = viewModelScope.launch { _book.value = withContext(Dispatchers.IO) { app.db.books().get(bookId) } }

    override fun onCleared() {
        app.appScope.launch { saveNow() }
        super.onCleared()
    }

    class Factory(private val bookId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(bookId) as T
    }
}
