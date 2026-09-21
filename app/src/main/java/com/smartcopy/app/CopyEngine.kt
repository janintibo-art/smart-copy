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
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MIN_BLOCK = 256 * 1024
private const val MAX_BLOCK = 8 * 1024 * 1024
private const val BIG_FILE = 16L * 1024 * 1024
private const val TUNE_WINDOW = 32L * 1024 * 1024
private const val HISTORY_SIZE = 120

enum class ItemStatus { PENDING, COPYING, VERIFYING, DONE, SKIPPED, FAILED, CANCELLED }

enum class TransferMode(val label: String, val help: String) {
    COPY("Copier", "Les originaux restent en place."),
    MOVE(
        "Déplacer",
        "Chaque original est supprimé seulement après une copie vérifiée. Sur le même support, le déplacement est instantané."
    )
}

enum class ConflictPolicy(val label: String, val help: String) {
    RENAME("Renommer", "Garde les deux fichiers : la copie devient « nom (1) »."),
    OVERWRITE("Remplacer", "Écrase le fichier déjà présent dans la destination."),
    SKIP("Ignorer", "Ne copie pas un fichier si un fichier du même nom existe déjà."),
    SYNC("Si différent", "Ignore les fichiers identiques (contenu comparé par empreinte), remplace les autres.")
}

class CopyItem(
    val id: Long,
    val src: Uri,
    val name: String,
    val size: Long,
    val relDir: List<String>,
    val srcParent: Uri? = null
) {
    @Volatile var moved: Boolean = false
    @Volatile var instant: Boolean = false
    @Volatile var warning: String? = null
    val copied = AtomicLong(0)
    val verified = AtomicLong(0)
    @Volatile var hash: String? = null
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
    val error: String?,
    val verified: Long,
    val hash: String?,
    val moved: Boolean,
    val instant: Boolean,
    val warning: String?
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
    val filesSkipped: Int = 0,
    val filesWarned: Int = 0,
    val mode: TransferMode = TransferMode.COPY,
    val verify: Boolean = true,
    val policy: ConflictPolicy = ConflictPolicy.RENAME,
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
    private val listingLock = Any()
    private val listings = HashMap<String, HashMap<String, Pair<Uri, Long>>>()

    private data class FolderRec(val tree: Uri, val docId: String, val rel: List<String>)
    private val sourceFolders = CopyOnWriteArrayList<FolderRec>()
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
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        val verify = p.getBoolean("verify", true)
        val policy = try {
            ConflictPolicy.valueOf(p.getString("policy", ConflictPolicy.RENAME.name) ?: ConflictPolicy.RENAME.name)
        } catch (e: Exception) {
            ConflictPolicy.RENAME
        }
        val mode = try {
            TransferMode.valueOf(p.getString("mode", TransferMode.COPY.name) ?: TransferMode.COPY.name)
        } catch (e: Exception) {
            TransferMode.COPY
        }
        _state.update { it.copy(verify = verify, policy = policy, mode = mode) }
    }

    private fun prefs() = app.getSharedPreferences("smartcopy", Context.MODE_PRIVATE)

    // ---------- Options ----------

    fun setVerify(value: Boolean) {
        _state.update { it.copy(verify = value) }
        prefs().edit().putBoolean("verify", value).apply()
    }

    fun setMode(value: TransferMode) {
        if (isRunning) return
        _state.update { it.copy(mode = value) }
        prefs().edit().putString("mode", value.name).apply()
    }

    fun setPolicy(value: ConflictPolicy) {
        _state.update { it.copy(policy = value) }
        prefs().edit().putString("policy", value.name).apply()
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
        sourceFolders.add(FolderRec(tree, docId, rel))
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
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
                            name, size, rel, parentUri
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
                item.verified.set(0)
            }
        }
        if (items.none { it.status == ItemStatus.PENDING }) {
            message("Rien à copier : ajoutez des fichiers.")
            return
        }
        if (_state.value.mode == TransferMode.MOVE) {
            val destId = try {
                DocumentsContract.getTreeDocumentId(dest)
            } catch (e: Exception) {
                ""
            }
            val inside = sourceFolders.any { f ->
                f.rel.size == 1 && f.tree.authority == dest.authority &&
                    (destId == f.docId || destId.startsWith(f.docId + "/"))
            }
            if (inside) {
                message("Impossible de déplacer un dossier dans lui-même : choisissez une autre destination.")
                return
            }
        }
        paused.value = false
        liveWorkers.set(0)
        synchronized(listingLock) { listings.clear() }
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
                if (_state.value.mode == TransferMode.MOVE) cleanupSourceFolders(dest)
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
        sourceFolders.clear()
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
        val dirKey = item.relDir.joinToString("/")
        val settings = _state.value
        val move = settings.mode == TransferMode.MOVE
        val verify = settings.verify || move
        var target: Uri? = null
        var createdNew = false
        var truncated = false
        var ok = false
        active.incrementAndGet()
        try {
            val parent = ensureDir(dest, item.relDir)
            val policy = settings.policy
            val existing = if (policy == ConflictPolicy.RENAME && !move) null else findExisting(dest, parent, dirKey, item.name)

            // Déplacement instantané quand le fournisseur le permet (même support) : aucune donnée recopiée.
            val srcParent = item.srcParent
            if (move && existing == null && srcParent != null) {
                val movedUri = try {
                    DocumentsContract.moveDocument(cr, item.src, srcParent, parent)
                } catch (e: Exception) {
                    null
                }
                if (movedUri != null) {
                    item.instant = true
                    item.moved = true
                    rememberFile(dirKey, item.name, movedUri, item.size)
                    item.status = ItemStatus.DONE
                    ok = true
                    return
                }
            }

            var mode = "w"
            if (existing != null) {
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        item.status = ItemStatus.SKIPPED
                        ok = true
                        return
                    }
                    ConflictPolicy.SYNC -> {
                        if (existing.second == item.size) {
                            item.status = ItemStatus.VERIFYING
                            val a = hashUri(item.src, null)
                            val b = hashUri(existing.first, null)
                            if (a == b) {
                                item.hash = a
                                if (move) deleteSource(item)
                                item.status = ItemStatus.SKIPPED
                                ok = true
                                return
                            }
                            item.status = ItemStatus.COPYING
                        }
                        target = existing.first
                        mode = "wt"
                    }
                    ConflictPolicy.OVERWRITE -> {
                        target = existing.first
                        mode = "wt"
                    }
                    ConflictPolicy.RENAME -> {
                    }
                }
            }
            val out: Uri = target ?: run {
                val mime = cr.getType(item.src) ?: "application/octet-stream"
                val created = DocumentsContract.createDocument(cr, parent, mime, item.name)
                    ?: throw IllegalStateException("création du fichier refusée")
                createdNew = true
                rememberFile(dirKey, item.name, created, item.size)
                created
            }
            target = out
            val srcHash = copyStreams(item, out, item.holdsBig, mode) { truncated = true }
            item.hash = srcHash
            if (verify) {
                item.status = ItemStatus.VERIFYING
                val dstHash = hashUri(out) { n -> item.verified.addAndGet(n.toLong()) }
                if (dstHash != srcHash) {
                    throw IllegalStateException("empreinte différente : la copie est corrompue")
                }
            }
            ok = true
            item.status = ItemStatus.DONE
            if (move) deleteSource(item)
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
                item.verified.set(0)
                val t = target
                if (t != null && (createdNew || truncated)) {
                    try {
                        DocumentsContract.deleteDocument(cr, t)
                    } catch (e: Exception) {
                    }
                    forgetFile(dirKey, item.name)
                }
            }
        }
        spawnWorkers(dest)
    }

    private fun deleteSource(item: CopyItem) {
        val deleted = try {
            DocumentsContract.deleteDocument(app.contentResolver, item.src)
        } catch (e: Exception) {
            false
        }
        if (deleted) {
            item.moved = true
        } else {
            item.warning = "Copié et vérifié, mais l'original n'a pas pu être supprimé."
        }
    }

    /** Après un déplacement : recrée à la destination les dossiers restés vides et supprime
     *  les dossiers source vidés, du plus profond au moins profond. */
    private suspend fun cleanupSourceFolders(dest: Uri) {
        val cr = app.contentResolver
        for (f in sourceFolders.sortedByDescending { it.rel.size }) {
            currentCoroutineContext().ensureActive()
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(f.tree, f.docId)
                val count = cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.count } ?: -1
                if (count == 0) {
                    ensureDir(dest, f.rel)
                    val folderUri = DocumentsContract.buildDocumentUriUsingTree(f.tree, f.docId)
                    if (DocumentsContract.deleteDocument(cr, folderUri)) sourceFolders.remove(f)
                }
            } catch (e: Exception) {
            }
        }
    }

    private class Chunk(val buf: ByteArray) {
        var len = 0
    }

    /** Copie en pipeline : la lecture (et le calcul d'empreinte) du bloc suivant se fait
     *  pendant l'écriture du bloc courant. Renvoie l'empreinte SHA-256 de la source. */
    private suspend fun copyStreams(
        item: CopyItem,
        target: Uri,
        big: Boolean,
        mode: String,
        onOutputOpened: () -> Unit
    ): String {
        val cr = app.contentResolver
        val capacity = if (big) {
            MAX_BLOCK
        } else {
            blockSize.toLong().coerceAtMost(maxOf(item.size, 1L)).toInt().coerceAtLeast(64 * 1024)
        }
        val input = cr.openInputStream(item.src) ?: throw IllegalStateException("lecture impossible")
        val outPfd = try {
            cr.openFileDescriptor(target, mode) ?: throw IllegalStateException("écriture impossible")
        } catch (e: Exception) {
            input.close()
            throw e
        }
        onOutputOpened()
        val output = FileOutputStream(outPfd.fileDescriptor)
        val tuner = if (big) Tuner(blockSize) else null
        val md = MessageDigest.getInstance("SHA-256")
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
                            md.update(chunk.buf, 0, n)
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
        return toHex(md.digest())
    }

    /** Relit un fichier et calcule son empreinte SHA-256 (respecte pause et annulation). */
    private suspend fun hashUri(uri: Uri, onBytes: ((Int) -> Unit)?): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("relecture impossible")
        try {
            while (true) {
                paused.first { !it }
                currentCoroutineContext().ensureActive()
                val r = input.read(buf)
                if (r < 0) break
                if (r > 0) {
                    md.update(buf, 0, r)
                    onBytes?.invoke(r)
                }
            }
        } finally {
            try {
                input.close()
            } catch (e: Exception) {
            }
        }
        return toHex(md.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    // ---------- Fichiers déjà présents dans la destination ----------

    private fun findExisting(tree: Uri, parent: Uri, dirKey: String, name: String): Pair<Uri, Long>? {
        synchronized(listingLock) {
            val map = listings.getOrPut(dirKey) { loadListing(tree, parent) }
            return map[name]
        }
    }

    private fun rememberFile(dirKey: String, name: String, uri: Uri, size: Long) {
        synchronized(listingLock) { listings[dirKey]?.put(name, Pair(uri, size)) }
    }

    private fun forgetFile(dirKey: String, name: String) {
        synchronized(listingLock) { listings[dirKey]?.remove(name) }
    }

    private fun loadListing(tree: Uri, parent: Uri): HashMap<String, Pair<Uri, Long>> {
        val map = HashMap<String, Pair<Uri, Long>>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            app.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    map[name] = Pair(DocumentsContract.buildDocumentUriUsingTree(tree, id), size)
                }
            }
        } catch (e: Exception) {
        }
        return map
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
        var skipped = 0
        var warned = 0
        for (i in snapshot) {
            if (i.warning != null) warned++
            when (i.status) {
                ItemStatus.SKIPPED -> skipped++
                ItemStatus.DONE -> done++
                ItemStatus.FAILED -> failed++
                else -> {
                }
            }
            if (i.status != ItemStatus.SKIPPED && !i.instant) total += i.size
        }
        val copied = copiedTotal.get()
        val running = isRunning
        val ui = snapshot.map {
            ItemUi(
                it.id, it.name, it.relDir.joinToString(" / "), it.size, it.copied.get(), it.status, it.error,
                it.verified.get(), it.hash, it.moved, it.instant, it.warning
            )
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
                    filesSkipped = skipped,
                    filesWarned = warned,
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
        val move = s.mode == TransferMode.MOVE
        val verb = if (move) "déplacés" else "copiés"
        val skippedText = if (s.filesSkipped > 0) ", ${s.filesSkipped} ignorés" else ""
        val anyVerified = items.any { it.status == ItemStatus.DONE && it.verified.get() > 0 }
        val verifiedText = if (anyVerified) " et vérifiés" else ""
        val warnText = if (s.filesWarned > 0) " Attention : ${s.filesWarned} original(aux) non supprimé(s)." else ""
        val text = when {
            cancelled -> "Transfert annulé. « Démarrer » reprend les fichiers restants."
            s.filesFailed > 0 -> "Terminé : ${s.filesDone} $verb$skippedText, ${s.filesFailed} en échec (« Démarrer » pour réessayer).$warnText"
            else -> "Terminé : ${s.filesDone} fichier(s) $verb$verifiedText$skippedText.$warnText"
        }
        _state.update {
            it.copy(running = false, paused = false, speed = 0.0, etaMs = -1L, activeStreams = 0, message = text)
        }
    }
}
