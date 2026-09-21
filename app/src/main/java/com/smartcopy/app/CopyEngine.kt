package com.smartcopy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MIN_BLOCK = 256 * 1024
private const val MAX_BLOCK = 8 * 1024 * 1024
private const val BIG_FILE = 16L * 1024 * 1024
private const val TUNE_WINDOW = 32L * 1024 * 1024
private const val HISTORY_SIZE = 120

enum class ItemStatus { PENDING, COPYING, DONE, FAILED, CANCELLED }

class CopyItem(
    val id: Long,
    val src: Uri,
    val name: String,
    val size: Long,
    val relDir: List<String>
) {
    val copied = AtomicLong(0)
    @Volatile var status: ItemStatus = ItemStatus.PENDING
    @Volatile var error: String? = null
    @Volatile var holdsBig: Boolean = false
}

data class ItemUi(
    val id: Long,
    val name: String,
    val path: String,
    val size: Long,
    val copied: Long,
    val status: ItemStatus,
    val error: String?
)

data class UiState(
    val items: List<ItemUi> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val totalBytes: Long = 0L,
    val copiedBytes: Long = 0L,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val filesFailed: Int = 0,
    val speed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val peakSpeed: Double = 0.0,
    val elapsedMs: Long = 0L,
    val etaMs: Long = -1L,
    val blockSize: Int = 1024 * 1024,
    val activeStreams: Int = 0,
    val history: List<Float> = emptyList(),
    val destination: Uri? = null,
    val destinationName: String? = null,
    val destinationInfo: VolumeInfo? = null,
    val analysis: AnalysisResult? = null,
    val analyzing: String? = null,
    val message: String? = null
)

object CopyEngine {

    private lateinit var app: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val items = CopyOnWriteArrayList<CopyItem>()
    private val nextId = AtomicLong(1)
    private val copiedTotal = AtomicLong(0)
    private val active = AtomicInteger(0)
    private val liveWorkers = AtomicInteger(0)
    private val paused = MutableStateFlow(false)
    private val dirLock = Mutex()
    private val dirCache = HashMap<String, Uri>()
    private val claimLock = Any()
    private val statLock = Any()

    @Volatile private var bigBusy = false
    @Volatile private var runJob: Job? = null
    @Volatile private var runScope: CoroutineScope? = null
    @Volatile private var blockSize = 1024 * 1024
    @Volatile private var workers = 2

    private val history = ArrayDeque<Float>()
    private var smoothed = 0.0
    private var peak = 0.0
    private var elapsedMs = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    val isRunning: Boolean
        get() = runJob?.isActive == true

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    // ---------- Destination ----------

    fun setDestination(uri: Uri) {
        if (isRunning) return
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        _state.update {
            it.copy(destination = uri, destinationName = treeName(uri), destinationInfo = null, analysis = null)
        }
        scope.launch {
            dirLock.withLock { dirCache.clear() }
            val info = try {
                StorageAnalyzer.describe(app, uri)
            } catch (e: Exception) {
                null
            }
            _state.update { it.copy(destinationInfo = info) }
        }
    }

    private fun treeName(uri: Uri): String {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return uri.toString()
        }
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val volName = if (vol == "primary") "Mémoire interne" else vol
        return if (path.isEmpty()) volName else "$volName / $path"
    }

    // ---------- Ajout de fichiers (possible pendant la copie) ----------

    fun addFiles(uris: List<Uri>) {
        scope.launch {
            val found = uris.map { queryItem(it) }
            items.addAll(found)
            onItemsAdded()
        }
    }

    fun addFolder(tree: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        scope.launch {
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val rootName = rootId.substringAfterLast('/').substringAfter(':').ifEmpty { "Dossier" }
                val found = ArrayList<CopyItem>()
                walk(tree, rootId, listOf(rootName), found)
                items.addAll(found)
                onItemsAdded()
                if (found.isEmpty()) message("Ce dossier ne contient aucun fichier.")
            } catch (e: Exception) {
                message("Lecture du dossier impossible : ${e.message}")
            }
        }
    }

    private fun queryItem(uri: Uri): CopyItem {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
        var size = 0L
        try {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
        }
        return CopyItem(nextId.getAndIncrement(), uri, name, size, emptyList())
    }

    private fun walk(tree: Uri, docId: String, rel: List<String>, out: MutableList<CopyItem>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val subDirs = ArrayList<Pair<String, String>>()
        app.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(Pair(id, name))
                } else {
                    out.add(
                        CopyItem(
                            nextId.getAndIncrement(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, size, rel
                        )
                    )
                }
            }
        }
        for (sub in subDirs) walk(tree, sub.first, rel + sub.second, out)
    }

    private fun onItemsAdded() {
        publish()
        if (isRunning) {
            _state.value.destination?.let { spawnWorkers(it) }
        }
    }

    // ---------- Analyse ----------

    fun analyze() {
        val dest = _state.value.destination ?: run {
            message("Choisissez d'abord un dossier de destination.")
            return
        }
        if (isRunning || _state.value.analyzing != null) return
        _state.update { it.copy(analyzing = "Préparation…") }
        scope.launch { runAnalysis(dest) }
    }

    private fun runAnalysis(dest: Uri) {
        val sources = items.filter { it.status == ItemStatus.PENDING }.map { it.src }
        try {
            val r = StorageAnalyzer.analyze(app, sources, dest) { step ->
                _state.update { it.copy(analyzing = step) }
            }
            blockSize = r.bestBlock
            workers = r.workers
            _state.update {
                it.copy(
                    analysis = r,
                    analyzing = null,
                    blockSize = r.bestBlock,
                    destinationInfo = r.destination ?: it.destinationInfo,
                    message = "Analyse terminée : bloc de ${formatBytes(r.bestBlock.toLong())}, ${r.workers} flux."
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(analyzing = null, message = "Analyse impossible : ${e.message}") }
        }
    }

    // ---------- Pilotage ----------

    fun start() {
        if (isRunning) return
        val dest = _state.value.destination ?: run {
            message("Choisissez d'abord un dossier de destination.")
            return
        }
        for (item in items) {
            if (item.status == ItemStatus.FAILED || item.status == ItemStatus.CANCELLED) {
                item.status = ItemStatus.PENDING
                item.error = null
            }
        }
        if (items.none { it.status == ItemStatus.PENDING }) {
            message("Rien à copier : ajoutez des fichiers.")
            return
        }
        paused.value = false
        liveWorkers.set(0)
        _state.update { it.copy(running = true, paused = false, message = null) }
        try {
            ContextCompat.startForegroundService(app, Intent(app, CopyService::class.java))
        } catch (e: Exception) {
        }
        runJob = scope.launch {
            if (_state.value.analysis == null) runAnalysis(dest)
            val ticker = launch { tickLoop() }
            try {
                supervisorScope {
                    runScope = this
                    spawnWorkers(dest)
                }
            } finally {
                runScope = null
                ticker.cancel()
                finish()
            }
        }
    }

    fun togglePause() {
        if (!isRunning) return
        paused.value = !paused.value
        _state.update { it.copy(paused = paused.value) }
    }

    fun cancel() {
        runJob?.cancel()
    }

    fun clear() {
        if (isRunning) return
        items.clear()
        copiedTotal.set(0)
        synchronized(statLock) {
            elapsedMs = 0L
            peak = 0.0
            smoothed = 0.0
            history.clear()
        }
        publish()
        _state.update { it.copy(message = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun message(text: String) {
        _state.update { it.copy(message = text) }
    }

    // ---------- Travailleurs ----------

    private fun spawnWorkers(dest: Uri) {
        val sc = runScope ?: return
        while (liveWorkers.get() < workers) {
            liveWorkers.incrementAndGet()
            sc.launch {
                try {
                    workerLoop(dest)
                } finally {
                    liveWorkers.decrementAndGet()
                }
            }
        }
    }

    private suspend fun workerLoop(dest: Uri) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val item = claimNext() ?: return
            copyOne(item, dest)
        }
    }

    /** Un seul gros fichier à la fois (évite de fragmenter / faire sauter la tête de lecture),
     *  les petits fichiers passent en parallèle pour masquer la latence. */
    private fun claimNext(): CopyItem? {
        synchronized(claimLock) {
            for (item in items) {
                if (item.status != ItemStatus.PENDING) continue
                if (item.size >= BIG_FILE) {
                    if (bigBusy) continue
                    bigBusy = true
                    item.holdsBig = true
                }
                item.status = ItemStatus.COPYING
                return item
            }
        }
        return null
    }

    private suspend fun copyOne(item: CopyItem, dest: Uri) {
        val cr = app.contentResolver
        var target: Uri? = null
        var ok = false
        active.incrementAndGet()
        try {
            val parent = ensureDir(dest, item.relDir)
            val mime = cr.getType(item.src) ?: "application/octet-stream"
            val created = DocumentsContract.createDocument(cr, parent, mime, item.name)
                ?: throw IllegalStateException("création du fichier refusée")
            target = created
            copyStreams(item, created, item.holdsBig)
            ok = true
            item.status = ItemStatus.DONE
        } catch (e: CancellationException) {
            item.status = ItemStatus.CANCELLED
            throw e
        } catch (e: Throwable) {
            item.status = ItemStatus.FAILED
            item.error = e.message ?: e.javaClass.simpleName
        } finally {
            active.decrementAndGet()
            if (item.holdsBig) {
                item.holdsBig = false
                synchronized(claimLock) { bigBusy = false }
            }
            if (!ok) {
                copiedTotal.addAndGet(-item.copied.getAndSet(0))
                val t = target
                if (t != null) {
                    try {
                        DocumentsContract.deleteDocument(cr, t)
                    } catch (e: Exception) {
                    }
                }
            }
        }
        spawnWorkers(dest)
    }

    private class Chunk(val buf: ByteArray) {
        var len = 0
    }

    /** Copie en pipeline : la lecture du bloc suivant se fait pendant l'écriture du bloc courant. */
    private suspend fun copyStreams(item: CopyItem, target: Uri, big: Boolean) {
        val cr = app.contentResolver
        val capacity = if (big) {
            MAX_BLOCK
        } else {
            blockSize.toLong().coerceAtMost(maxOf(item.size, 1L)).toInt().coerceAtLeast(64 * 1024)
        }
        val input = cr.openInputStream(item.src) ?: throw IllegalStateException("lecture impossible")
        val outPfd = try {
            cr.openFileDescriptor(target, "w") ?: throw IllegalStateException("écriture impossible")
        } catch (e: Exception) {
            input.close()
            throw e
        }
        val output = FileOutputStream(outPfd.fileDescriptor)
        val tuner = if (big) Tuner(blockSize) else null
        try {
            coroutineScope {
                val pool = Channel<Chunk>(3)
                repeat(3) { pool.trySend(Chunk(ByteArray(capacity))) }
                val filled = Channel<Chunk>(3)

                val reader = launch {
                    try {
                        while (true) {
                            paused.first { !it }
                            val chunk = pool.receive()
                            val want = if (tuner != null) tuner.current.coerceAtMost(capacity) else capacity
                            var n = 0
                            while (n < want) {
                                val r = input.read(chunk.buf, n, want - n)
                                if (r < 0) break
                                n += r
                            }
                            if (n == 0) break
                            chunk.len = n
                            filled.send(chunk)
                            if (n < want) break
                        }
                        filled.close()
                    } catch (e: Throwable) {
                        filled.close(e)
                        throw e
                    }
                }

                for (chunk in filled) {
                    output.write(chunk.buf, 0, chunk.len)
                    item.copied.addAndGet(chunk.len.toLong())
                    copiedTotal.addAndGet(chunk.len.toLong())
                    val next = tuner?.onBytes(chunk.len)
                    if (next != null) blockSize = next
                    pool.send(chunk)
                }
                reader.join()
            }
            output.flush()
            if (big) outPfd.fileDescriptor.sync()
        } finally {
            try {
                input.close()
            } catch (e: Exception) {
            }
            try {
                outPfd.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Ajuste la taille de bloc en continu (montée de gradient simple sur le débit mesuré). */
    private class Tuner(start: Int) {
        @Volatile var current: Int = start.coerceIn(MIN_BLOCK, MAX_BLOCK)
        private var direction = 1
        private var windowBytes = 0L
        private var windowStart = System.nanoTime()
        private var lastRate = 0.0

        fun onBytes(n: Int): Int? {
            windowBytes += n
            val elapsed = System.nanoTime() - windowStart
            if (windowBytes < TUNE_WINDOW || elapsed < 700_000_000L) return null
            val rate = windowBytes / (elapsed / 1e9)
            if (lastRate > 0.0 && rate < lastRate * 0.97) direction = -direction
            lastRate = rate
            var next = if (direction > 0) current * 2 else current / 2
            if (next > MAX_BLOCK || next < MIN_BLOCK) {
                direction = -direction
                next = current
            }
            current = next
            windowBytes = 0L
            windowStart = System.nanoTime()
            return current
        }
    }

    private suspend fun ensureDir(dest: Uri, rel: List<String>): Uri {
        val root = DocumentsContract.buildDocumentUriUsingTree(dest, DocumentsContract.getTreeDocumentId(dest))
        if (rel.isEmpty()) return root
        return dirLock.withLock {
            var parent = root
            var key = ""
            for (part in rel) {
                key += "/$part"
                val cached = dirCache[key]
                parent = if (cached != null) {
                    cached
                } else {
                    val dir = findChildDir(dest, parent, part)
                        ?: DocumentsContract.createDocument(
                            app.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part
                        )
                        ?: throw IllegalStateException("impossible de créer le dossier $part")
                    dirCache[key] = dir
                    dir
                }
            }
            parent
        }
    }

    private fun findChildDir(tree: Uri, parent: Uri, name: String): Uri? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        app.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            var found: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    break
                }
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Statistiques ----------

    private suspend fun tickLoop() {
        var lastBytes = copiedTotal.get()
        var lastNs = System.nanoTime()
        var half = false
        while (currentCoroutineContext().isActive) {
            delay(250)
            val now = System.nanoTime()
            val bytes = copiedTotal.get()
            val dt = (now - lastNs) / 1e9
            val inst = if (dt > 0) ((bytes - lastBytes) / dt).coerceAtLeast(0.0) else 0.0
            lastBytes = bytes
            lastNs = now
            synchronized(statLock) {
                val isPaused = paused.value
                if (!isPaused) elapsedMs += (dt * 1000).toLong()
                smoothed = when {
                    isPaused -> 0.0
                    smoothed == 0.0 -> inst
                    else -> smoothed * 0.7 + inst * 0.3
                }
                if (smoothed > peak) peak = smoothed
                half = !half
                if (half) {
                    history.addLast(smoothed.toFloat())
                    while (history.size > HISTORY_SIZE) history.removeFirst()
                }
            }
            publish()
        }
    }

    private fun publish() {
        val snapshot = items.toList()
        var total = 0L
        var done = 0
        var failed = 0
        for (i in snapshot) {
            total += i.size
            if (i.status == ItemStatus.DONE) done++ else if (i.status == ItemStatus.FAILED) failed++
        }
        val copied = copiedTotal.get()
        val running = isRunning
        val ui = snapshot.map {
            ItemUi(it.id, it.name, it.relDir.joinToString(" / "), it.size, it.copied.get(), it.status, it.error)
        }
        synchronized(statLock) {
            val avg = if (elapsedMs > 0) copied / (elapsedMs / 1000.0) else 0.0
            val rate = if (smoothed > 0) smoothed else avg
            val eta = if (running && rate > 1.0) ((total - copied).coerceAtLeast(0L) / rate * 1000).toLong() else -1L
            val hist = history.toList()
            val sm = if (running) smoothed else 0.0
            val pk = peak
            val el = elapsedMs
            _state.update { s ->
                s.copy(
                    items = ui,
                    paused = paused.value,
                    totalBytes = total,
                    copiedBytes = copied,
                    filesTotal = snapshot.size,
                    filesDone = done,
                    filesFailed = failed,
                    speed = sm,
                    avgSpeed = avg,
                    peakSpeed = pk,
                    elapsedMs = el,
                    etaMs = eta,
                    blockSize = blockSize,
                    activeStreams = active.get(),
                    history = hist
                )
            }
        }
    }

    private fun finish() {
        synchronized(statLock) { smoothed = 0.0 }
        paused.value = false
        publish()
        val s = _state.value
        val cancelled = items.any { it.status == ItemStatus.CANCELLED }
        val text = when {
            cancelled -> "Copie annulée. « Démarrer » reprend les fichiers restants."
            s.filesFailed > 0 -> "Terminé : ${s.filesDone} copiés, ${s.filesFailed} en échec (« Démarrer » pour réessayer)."
            else -> "Terminé : ${s.filesDone} fichier(s) copiés."
        }
        _state.update {
            it.copy(running = false, paused = false, speed = 0.0, etaMs = -1L, activeStreams = 0, message = text)
        }
    }
}
