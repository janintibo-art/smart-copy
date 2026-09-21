package com.smartcopy.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.max

data class VolumeInfo(
    val label: String,
    val kind: String,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
)

data class BlockResult(val blockSize: Int, val mbPerSec: Double)

data class AnalysisResult(
    val source: VolumeInfo?,
    val destination: VolumeInfo?,
    val sourceReadMBps: Double,
    val writeResults: List<BlockResult>,
    val bestBlock: Int,
    val smallFileLatencyMs: Double,
    val workers: Int,
    val sameVolume: Boolean
)

object StorageAnalyzer {

    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    private val CANDIDATES = intArrayOf(
        64 * 1024,
        256 * 1024,
        1024 * 1024,
        4 * 1024 * 1024,
        8 * 1024 * 1024
    )

    private const val BYTES_PER_TEST = 8L * 1024 * 1024
    private const val SOURCE_SAMPLE = 32L * 1024 * 1024
    private const val SMALL_FILES = 16

    fun volumeIdOf(uri: Uri): String? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e2: Exception) {
                null
            }
        } ?: return null
        val vol = docId.substringBefore(':', "")
        return vol.ifEmpty { null }
    }

    fun describe(context: Context, uri: Uri): VolumeInfo {
        if (uri.authority != EXTERNAL_AUTHORITY) {
            return VolumeInfo("Fournisseur : " + (uri.authority ?: "?"), "Type inconnu", false, 0L, 0L)
        }
        val volId = volumeIdOf(uri) ?: return VolumeInfo("Inconnu", "Type inconnu", false, 0L, 0L)
        if (volId == "primary") {
            val sizes = statFs(Environment.getExternalStorageDirectory().path)
            return VolumeInfo("Mémoire interne", "Flash interne (UFS / eMMC)", false, sizes.first, sizes.second)
        }
        val sm = context.getSystemService(StorageManager::class.java)
        val vol = sm?.storageVolumes?.firstOrNull { it.uuid.equals(volId, ignoreCase = true) }
        val desc = vol?.getDescription(context) ?: volId
        val sizes = if (Build.VERSION.SDK_INT >= 30) {
            val dir = vol?.directory
            if (dir != null) statFs(dir.path) else Pair(0L, 0L)
        } else {
            statFs("/storage/$volId")
        }
        val kind = when {
            desc.contains("USB", ignoreCase = true) -> "Clé / disque USB (OTG)"
            vol?.isRemovable == true -> "Carte SD"
            else -> "Volume externe"
        }
        return VolumeInfo(desc, kind, vol?.isRemovable ?: true, sizes.first, sizes.second)
    }

    private fun statFs(path: String): Pair<Long, Long> = try {
        val s = StatFs(path)
        Pair(s.totalBytes, s.availableBytes)
    } catch (e: Exception) {
        Pair(0L, 0L)
    }

    fun analyze(
        context: Context,
        sources: List<Uri>,
        destTree: Uri,
        onStep: (String) -> Unit
    ): AnalysisResult {
        val cr = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            destTree,
            DocumentsContract.getTreeDocumentId(destTree)
        )

        val writeResults = ArrayList<BlockResult>()
        for (bs in CANDIDATES) {
            onStep("Test d'écriture : bloc de " + formatBytes(bs.toLong()))
            val doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", ".smartcopy_test_$bs")
                ?: continue
            try {
                val buf = ByteArray(bs)
                Random(bs.toLong()).nextBytes(buf)
                val t0 = System.nanoTime()
                val pfd = cr.openFileDescriptor(doc, "w") ?: continue
                try {
                    val out = FileOutputStream(pfd.fileDescriptor)
                    var written = 0L
                    while (written < BYTES_PER_TEST) {
                        out.write(buf)
                        written += bs
                    }
                    out.flush()
                    pfd.fileDescriptor.sync()
                } finally {
                    pfd.close()
                }
                val secs = (System.nanoTime() - t0) / 1e9
                writeResults.add(BlockResult(bs, BYTES_PER_TEST / (1024.0 * 1024.0) / max(secs, 1e-6)))
            } finally {
                try {
                    DocumentsContract.deleteDocument(cr, doc)
                } catch (e: Exception) {
                }
            }
        }

        onStep("Test des petits fichiers (latence)")
        val small = ByteArray(4096)
        var created = 0
        val t1 = System.nanoTime()
        for (i in 0 until SMALL_FILES) {
            val d = DocumentsContract.createDocument(cr, parent, "application/octet-stream", ".smartcopy_small_$i")
                ?: continue
            try {
                cr.openOutputStream(d)?.use { it.write(small) }
                created++
            } finally {
                try {
                    DocumentsContract.deleteDocument(cr, d)
                } catch (e: Exception) {
                }
            }
        }
        val latencyMs = if (created > 0) (System.nanoTime() - t1) / 1e6 / created else 0.0

        var readMBps = 0.0
        val first = sources.firstOrNull()
        if (first != null) {
            onStep("Test de lecture de la source")
            try {
                cr.openInputStream(first)?.use { input ->
                    val buf = ByteArray(1024 * 1024)
                    var total = 0L
                    val t0 = System.nanoTime()
                    while (total < SOURCE_SAMPLE) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        total += r
                    }
                    val secs = (System.nanoTime() - t0) / 1e9
                    if (total > 0 && secs > 0) readMBps = total / (1024.0 * 1024.0) / secs
                }
            } catch (e: Exception) {
            }
        }

        val maxSpeed = writeResults.maxOfOrNull { it.mbPerSec } ?: 0.0
        val best = writeResults
            .filter { it.mbPerSec >= maxSpeed * 0.95 }
            .minByOrNull { it.blockSize }
            ?.blockSize ?: (1024 * 1024)

        val srcVol = first?.let { describe(context, it) }
        val dstVol = describe(context, destTree)
        val sameVolume = srcVol != null && srcVol.label == dstVol.label && srcVol.kind != "Type inconnu"

        val workers = when {
            sameVolume -> 2
            latencyMs >= 25.0 -> 4
            latencyMs >= 8.0 -> 3
            else -> 2
        }

        return AnalysisResult(
            source = srcVol,
            destination = dstVol,
            sourceReadMBps = readMBps,
            writeResults = writeResults,
            bestBlock = best,
            smallFileLatencyMs = latencyMs,
            workers = workers,
            sameVolume = sameVolume
        )
    }
}
