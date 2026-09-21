package com.smartcopy.app

import java.io.File
import kotlin.system.measureTimeMillis

data class StorageInfo(
    val totalCapacity: Long,
    val availableCapacity: Long,
    val isSSD: Boolean,
    val isInternal: Boolean
)

data class PerformanceMetrics(
    val readSpeed: Double,
    val writeSpeed: Double,
    val optimalBufferSize: Int,
    val recommendedThreads: Int
)

class FileAnalyzer {
    fun analyzeStorage(path: String): StorageInfo {
        val file = File(path)
        val stat = file.totalSpace
        val available = file.freeSpace
        val isSSD = detectSSD(path)
        
        return StorageInfo(
            totalCapacity = stat,
            availableCapacity = available,
            isSSD = isSSD,
            isInternal = isInternalStorage(path)
        )
    }
    
    fun benchmarkIOPerformance(path: String, testSize: Long = 10 * 1024 * 1024): PerformanceMetrics {
        val testFile = File(path, ".smartcopy_benchmark_${System.nanoTime()}")
        val testData = ByteArray(1024 * 1024)
        java.util.Random().nextBytes(testData)
        
        var writeTime = 0L
        var readTime = 0L
        
        try {
            writeTime = measureTimeMillis {
                testFile.outputStream().use { out ->
                    repeat((testSize / testData.size).toInt()) {
                        out.write(testData)
                    }
                }
            }
            
            readTime = measureTimeMillis {
                testFile.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (input.read(buffer) != -1) {}
                }
            }
        } finally {
            testFile.delete()
        }
        
        val writeSpeedMBps = (testSize / 1024 / 1024) / (writeTime / 1000.0)
        val readSpeedMBps = (testSize / 1024 / 1024) / (readTime / 1000.0)
        
        val bufferSize = when {
            writeSpeedMBps > 200 -> 16 * 1024 * 1024
            writeSpeedMBps > 100 -> 8 * 1024 * 1024
            writeSpeedMBps > 50 -> 4 * 1024 * 1024
            else -> 2 * 1024 * 1024
        }
        
        val threads = when {
            readSpeedMBps > 300 -> Runtime.getRuntime().availableProcessors()
            readSpeedMBps > 100 -> Runtime.getRuntime().availableProcessors() / 2
            else -> 2
        }
        
        return PerformanceMetrics(
            readSpeed = readSpeedMBps,
            writeSpeed = writeSpeedMBps,
            optimalBufferSize = bufferSize,
            recommendedThreads = maxOf(1, threads)
        )
    }
    
    private fun detectSSD(path: String): Boolean {
        return !path.contains("sdcard", ignoreCase = true)
    }
    
    private fun isInternalStorage(path: String): Boolean {
        return path.contains("/data") || path.contains("/storage/emulated")
    }
}
