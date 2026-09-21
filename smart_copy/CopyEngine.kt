package com.smartcopy.app

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

data class CopyFile(
    val source: File,
    val destination: File,
    var status: CopyStatus = CopyStatus.PENDING,
    var progress: Float = 0f,
    var speed: Double = 0.0,
    var eta: Long = 0
)

enum class CopyStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
}

class CopyEngine(private val metrics: PerformanceMetrics) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(metrics.recommendedThreads)
    private var totalBytesCopied = 0L
    private var startTime = 0L
    private var isCancelled = false
    
    fun addFiles(files: List<CopyFile>) {
        files.forEach { file ->
            executor.submit { copyFileWithOptimization(file) }
        }
    }
    
    fun cancelAll() {
        isCancelled = true
        executor.shutdownNow()
    }
    
    private fun copyFileWithOptimization(copyFile: CopyFile) {
        if (isCancelled) return
        
        try {
            copyFile.status = CopyStatus.IN_PROGRESS
            startTime = System.currentTimeMillis()
            
            val source = copyFile.source
            val destination = copyFile.destination
            val totalSize = source.length()
            val bufferSize = metrics.optimalBufferSize
            
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int
                    var copiedBytes = 0L
                    val startMilis = System.currentTimeMillis()
                    
                    while (input.read(buffer).also { bytesRead = it } > 0 && !isCancelled) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        totalBytesCopied += bytesRead
                        
                        val elapsed = (System.currentTimeMillis() - startMilis) / 1000.0
                        val speedMBps = (copiedBytes / 1024.0 / 1024.0) / elapsed
                        val remainingBytes = totalSize - copiedBytes
                        val etaSeconds = if (speedMBps > 0) remainingBytes / 1024.0 / 1024.0 / speedMBps else 0.0
                        
                        copyFile.progress = (copiedBytes.toFloat() / totalSize) * 100
                        copyFile.speed = speedMBps
                        copyFile.eta = etaSeconds.toLong()
                    }
                    
                    if (!isCancelled) {
                        copyFile.status = CopyStatus.COMPLETED
                    }
                }
            }
        } catch (e: Exception) {
            copyFile.status = CopyStatus.FAILED
        }
    }
    
    fun getGlobalStats(): GlobalCopyStats {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val globalSpeed = if (elapsed > 0) (totalBytesCopied / 1024.0 / 1024.0) / elapsed else 0.0
        
        return GlobalCopyStats(
            totalBytesCopied = totalBytesCopied,
            elapsedSeconds = elapsed.toLong(),
            averageSpeed = globalSpeed
        )
    }
}

data class GlobalCopyStats(
    val totalBytesCopied: Long,
    val elapsedSeconds: Long,
    val averageSpeed: Double
)
