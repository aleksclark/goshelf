package com.goshelf.app.ui.bookdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: Int,
    onBack: () -> Unit,
    onAuthorClick: (Int) -> Unit = {},
    onSeriesClick: (String) -> Unit = {},
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.book?.title ?: "Book Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleStar() }) {
                        Icon(
                            if (uiState.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (uiState.isStarred) "Unstar" else "Star",
                            tint = if (uiState.isStarred) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            uiState.book != null -> {
                val book = uiState.book!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Cover image
                    if (book.hasCover) {
                        AsyncImage(
                            model = viewModel.getCoverUrl(bookId),
                            contentDescription = book.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(MaterialTheme.shapes.large),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Title
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Author as clickable chip
                    AssistChip(
                        onClick = { onAuthorClick(book.authorId) },
                        label = { Text(book.author) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    // Series as clickable chip
                    if (!book.series.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val seriesSlug = book.series
                            .replace(Regex("\\s*#\\d+\\.?\\d*\\s*$"), "")
                            .lowercase()
                            .replace(Regex("[^a-z0-9]+"), "-")
                            .trim('-')
                        AssistChip(
                            onClick = { onSeriesClick(seriesSlug) },
                            label = { Text(book.series) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.LibraryBooks,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // File info
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Files",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            book.files.forEach { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatFileSize(file.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Total: ${formatFileSize(book.totalSize)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Overview
                    if (!book.overview.isNullOrBlank()) {
                        Text(
                            text = "Overview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = book.overview,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Downloaded indicator
                    if (uiState.isAlreadyDownloaded) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Downloaded to device",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Download section
                    DownloadSection(
                        uiState = uiState,
                        bookId = bookId,
                        bookTitle = book.title,
                        viewModel = viewModel
                    )

                    // Remove from device button
                    if (uiState.isAlreadyDownloaded && !uiState.isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.removeDownload(bookId) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Remove from device")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSection(
    uiState: BookDetailUiState,
    bookId: Int,
    bookTitle: String,
    viewModel: BookDetailViewModel
) {
    when {
        uiState.isDownloading -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { (uiState.downloadProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (uiState.totalBytes > 0) {
                    Text(
                        text = "${formatFileSize(uiState.bytesDownloaded)} / ${formatFileSize(uiState.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.downloadSpeed > 0) {
                    Text(
                        text = formatSpeed(uiState.downloadSpeed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = uiState.downloadStatus ?: "Downloading...",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.pauseDownload(bookId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause")
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelDownload(bookId) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            }
        }

        uiState.isPaused || uiState.hasPartialDownload -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (uiState.bytesDownloaded > 0) {
                    Text(
                        text = "Paused: ${formatFileSize(uiState.bytesDownloaded)} downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (uiState.downloadStatus != null && uiState.downloadStatus != "Paused") {
                    Text(
                        text = uiState.downloadStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.resumeDownload(bookId, bookTitle) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelDownload(bookId) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            }
        }

        else -> {
            if (uiState.downloadStatus != null) {
                Text(
                    text = uiState.downloadStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.downloadProgress == 100)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.startDownload(bookId, bookTitle) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isAlreadyDownloaded) "Re-download" else "Download & Extract")
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}
