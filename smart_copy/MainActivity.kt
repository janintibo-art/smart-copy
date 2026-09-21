package com.smartcopy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartCopyTheme {
                SmartCopyApp()
            }
        }
    }
}

@Composable
fun SmartCopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun SmartCopyApp() {
    var copyQueue by remember { mutableStateOf<List<CopyFile>>(emptyList()) }
    var metrics by remember { mutableStateOf<PerformanceMetrics?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var globalStats by remember { mutableStateOf<GlobalCopyStats?>(null) }
    
    val analyzer = remember { FileAnalyzer() }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Smart Copy v1.0",
            fontSize = 28.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        
        if (metrics != null) {
            MetricsCard(metrics!!)
        } else {
            Button(
                onClick = {
                    metrics = analyzer.benchmarkIOPerformance("/storage/emulated/0")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Analyser les performances")
            }
        }
        
        Divider(color = Color.Gray)
        
        if (copyQueue.isNotEmpty()) {
            Text("Queue de copie", color = Color.White, fontSize = 18.sp)
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(copyQueue.size) { index ->
                    CopyFileCard(copyQueue[index])
                }
            }
            
            if (globalStats != null) {
                GlobalStatsCard(globalStats!!)
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { isRunning = !isRunning },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunning) "Pause" else "Démarrer")
                }
                Button(
                    onClick = { copyQueue = emptyList() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Annuler")
                }
            }
        }
    }
}

@Composable
fun MetricsCard(metrics: PerformanceMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Performances détectées", color = Color.White, fontSize = 16.sp)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Vitesse lecture", String.format("%.1f MB/s", metrics.readSpeed))
                StatItem("Vitesse écriture", String.format("%.1f MB/s", metrics.writeSpeed))
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Buffer optimal", String.format("%d MB", metrics.optimalBufferSize / 1024 / 1024))
                StatItem("Threads", metrics.recommendedThreads.toString())
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = Color(0xFF4CAF50), fontSize = 14.sp)
    }
}

@Composable
fun CopyFileCard(file: CopyFile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.source.name, color = Color.White, fontSize = 14.sp)
            
            LinearProgressIndicator(
                progress = file.progress / 100f,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF4CAF50)
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${String.format("%.1f", file.progress)}%", color = Color.Gray, fontSize = 12.sp)
                Text(String.format("%.1f MB/s", file.speed), color = Color.Gray, fontSize = 12.sp)
                Text("ETA: ${formatTime(file.eta)}", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GlobalStatsCard(stats: GlobalCopyStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stats globales", color = Color.White, fontSize = 14.sp)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Total", String.format("%.1f GB", stats.totalBytesCopied / 1024.0 / 1024.0 / 1024.0))
                StatItem("Temps écoulé", formatTime(stats.elapsedSeconds))
                StatItem("Vitesse moyenne", String.format("%.1f MB/s", stats.averageSpeed))
            }
        }
    }
}

fun formatTime(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
