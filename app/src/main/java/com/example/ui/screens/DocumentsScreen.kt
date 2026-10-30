package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ChunkEntity
import com.example.data.local.DocumentEntity
import com.example.ui.RagConfigState

@Composable
fun DocumentsScreen(
    documents: List<DocumentEntity>,
    chunks: List<ChunkEntity>,
    config: RagConfigState,
    onUploadPdf: (Uri, String) -> Unit,
    onRestoreSamplePdfs: () -> Unit,
    onDeleteDocument: (DocumentEntity) -> Unit,
    onUpdateChunkSettings: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedDocFilter by rememberSaveable { mutableStateOf("All") }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = resolveFileName(context, uri)
            onUploadPdf(uri, fileName)
        }
    }

    val filteredChunks = remember(chunks, selectedDocFilter) {
        if (selectedDocFilter == "All") chunks
        else chunks.filter { it.documentName == selectedDocFilter }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("documents_lazy_column"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Upload & Sample Restoration Action Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "1. PDF Document Ingestion & Page Extraction",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Upload any selectable-text PDF from your device or restore the 3 built-in multi-page technical PDFs. Exact 1-based page numbers are preserved on every chunk.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("upload_pdf_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Upload PDF")
                        }

                        OutlinedButton(
                            onClick = onRestoreSamplePdfs,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("restore_samples_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Re-Index Samples")
                        }
                    }
                }
            }
        }

        // Overlapping Chunking Hyperparameters Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesomeMotion,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "2. Overlapping Chunker Configuration",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Text(
                        text = "Target Chunk Size: ${config.chunkSize} chars • Sliding Overlap: ${config.chunkOverlap} chars",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Slider(
                        value = config.chunkSize.toFloat(),
                        onValueChange = { newSize ->
                            onUpdateChunkSettings(newSize.toInt(), config.chunkOverlap)
                        },
                        valueRange = 250f..1000f,
                        steps = 14,
                        modifier = Modifier.testTag("chunk_size_slider")
                    )

                    Slider(
                        value = config.chunkOverlap.toFloat(),
                        onValueChange = { newOverlap ->
                            onUpdateChunkSettings(config.chunkSize, newOverlap.toInt())
                        },
                        valueRange = 40f..300f,
                        steps = 12,
                        modifier = Modifier.testTag("chunk_overlap_slider")
                    )
                }
            }
        }

        // Indexed PDFs List
        item {
            Text(
                text = "Indexed PDF Documents (${documents.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        items(documents, key = { it.id }) { doc ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = doc.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${doc.pageCount} pages • ${doc.chunkCount} overlapping chunks • ${doc.totalChars} chars",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = { onDeleteDocument(doc) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_doc_${doc.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete ${doc.fileName}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Chunk & 384-d Embedding Inspector Header
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Overlapping Chunks & 384-d Vector Inspector (${filteredChunks.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedDocFilter == "All",
                    onClick = { selectedDocFilter = "All" },
                    label = { Text("All (${chunks.size})") }
                )
                documents.take(2).forEach { doc ->
                    val shortName = doc.fileName.substringBefore("_")
                    FilterChip(
                        selected = selectedDocFilter == doc.fileName,
                        onClick = { selectedDocFilter = doc.fileName },
                        label = { Text(shortName) }
                    )
                }
            }
        }

        items(filteredChunks, key = { it.id }) { chunk ->
            ChunkInspectorCard(chunk = chunk)
        }
    }
}

@Composable
private fun ChunkInspectorCard(chunk: ChunkEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chunk.documentName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Page ${chunk.pageNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Chars ${chunk.charStart}..${chunk.charEnd}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Preview first 6 dimensions of the 384-d unit vector
            val previewDims = remember(chunk.embeddingCsv) {
                chunk.embeddingCsv.split(",").take(6).joinToString(", ")
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "384-d L2 Vector: [$previewDims, …] (||v||₂ = 1.00)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private fun resolveFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    result = cursor.getString(idx)
                }
            }
        }
    }
    if (result.isNullOrBlank()) {
        result = uri.lastPathSegment?.substringAfterLast('/') ?: "Uploaded_Document.pdf"
    }
    return if (result!!.endsWith(".pdf", ignoreCase = true)) result!! else "$result.pdf"
}
