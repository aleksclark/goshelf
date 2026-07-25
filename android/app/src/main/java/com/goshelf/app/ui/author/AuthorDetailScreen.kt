package com.goshelf.app.ui.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.goshelf.app.data.api.APISeries
import com.goshelf.app.data.api.BookListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorDetailScreen(
    authorId: Int,
    onBack: () -> Unit,
    onBookClick: (Int) -> Unit,
    onSeriesClick: (String) -> Unit,
    viewModel: AuthorDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(authorId) {
        viewModel.loadAuthor(authorId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.authorName.ifEmpty { "Author" }) },
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
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Author info header
                    item {
                        Text(
                            text = uiState.authorName,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${uiState.series.size} series, ${uiState.standaloneBooks.size} standalone books",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Series section
                    if (uiState.series.isNotEmpty()) {
                        item {
                            Text(
                                text = "Series",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(uiState.series) { series ->
                            SeriesCard(
                                series = series,
                                coverUrl = if (series.hasCover) viewModel.getCoverUrl(series.firstBookId) else "",
                                onClick = { onSeriesClick(series.slug) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    // Standalone books section
                    if (uiState.standaloneBooks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Books",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(uiState.standaloneBooks) { book ->
                            BookListCard(
                                book = book,
                                coverUrl = viewModel.getCoverUrl(book.id),
                                onClick = { onBookClick(book.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesCard(
    series: APISeries,
    coverUrl: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (series.hasCover && coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = series.name,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${series.bookCount} book${if (series.bookCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BookListCard(
    book: BookListItem,
    coverUrl: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (book.hasCover) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = book.title,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!book.series.isNullOrEmpty()) {
                    Text(
                        text = book.series,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
