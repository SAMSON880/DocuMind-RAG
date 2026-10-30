package com.example.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.rag.OllamaStatus
import com.example.ui.PipelineLabState
import com.example.ui.RagConfigState
import java.util.Locale
import kotlin.math.abs

private val AVAILABLE_LOCAL_MODELS = listOf("llama3.2", "llama3.2:1b", "mistral", "phi3", "gemma2:2b")

@Composable
fun PipelineLabScreen(
    pipelineState: PipelineLabState,
    config: RagConfigState,
    ollamaStatus: OllamaStatus,
    onRunTraceQuery: (String) -> Unit,
    onUpdateRetrievalSettings: (Int, Float) -> Unit,
    onUpdateOllamaSettings: (String, String, Float) -> Unit,
    onTestOllama: () -> Unit,
    modifier: Modifier = Modifier
) {
    var traceQueryInput by rememberSaveable(pipelineState.sampleQuery) {
        mutableStateOf(pipelineState.sampleQuery)
    }
    var hostInput by rememberSaveable(config.ollamaHost) {
        mutableStateOf(config.ollamaHost)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("pipeline_lab_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Interactive Beginner AI Engineer RAG Tracer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Interactive 7-Stage RAG Pipeline Debugger",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Text(
                        text = "For Beginner AI Engineers: Enter any test question below to inspect how DocuMind tokenizes, embeds into 384 dimensions, computes Cosine Similarity against FAISS/local chunks, and builds the grounded Ollama prompt.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = traceQueryInput,
                        onValueChange = { traceQueryInput = it },
                        label = { Text("Test Query for Live RAG Trace") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lab_trace_query_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = { onRunTraceQuery(traceQueryInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_rag_trace_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Trace Query Across All RAG Stages")
                    }
                }
            }
        }

        // Stage A: Query Tokenization & 384-d Embedding Visualizer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Stage 3 & 5: Query Embedding (384-d Unit Vector)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Extracted Stemmed Concepts: ${pipelineState.extractedTokens.joinToString(", ").ifBlank { "(none)" }}",
                        style = MaterialTheme.typography.labelLarge
                    )

                    val primaryColor = MaterialTheme.colorScheme.primary
                    val secondaryColor = MaterialTheme.colorScheme.secondary
                    val bgBar = MaterialTheme.colorScheme.surfaceVariant

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgBar)
                            .padding(8.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dims = pipelineState.queryVectorPreview
                            if (dims.isNotEmpty()) {
                                val barWidth = size.width / dims.size
                                val midY = size.height / 2f
                                dims.forEachIndexed { idx, value ->
                                    val scaledH = (abs(value) * size.height * 2.2f).coerceAtMost(midY)
                                    val topY = if (value >= 0f) midY - scaledH else midY
                                    drawRect(
                                        color = if (value >= 0f) primaryColor else secondaryColor,
                                        topLeft = Offset(idx * barWidth + 2f, topY),
                                        size = Size((barWidth - 4f).coerceAtLeast(2f), scaledH.coerceAtLeast(3f))
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "First 24 of 384 normalized dimensions shown (Cyan = positive activation, Emerald = negative activation).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Stage B: Top-K Cosine Similarity Retrieval & Threshold Cutoff
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Stage 6: Vector Search & Similarity Cutoff",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Top-K = ${config.topK} chunks • Min Similarity Threshold = ${String.format(Locale.US, "%.2f", config.similarityThreshold)}",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Slider(
                        value = config.similarityThreshold,
                        onValueChange = { newThresh ->
                            onUpdateRetrievalSettings(config.topK, newThresh)
                        },
                        valueRange = 0.05f..0.65f,
                        modifier = Modifier.testTag("similarity_threshold_slider")
                    )

                    if (pipelineState.retrievedResults.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "0 chunks passed the threshold (${String.format(Locale.US, "%.2f", config.similarityThreshold)}). Guardrail triggered: DocuMind will return 'Information Not Found' without hallucinating!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        pipelineState.retrievedResults.forEachIndexed { idx, res ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "#${idx + 1} ${res.documentName} (Page ${res.pageNumber})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Matched terms: ${res.matchedTerms.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = String.format(Locale.US, "cos(θ) = %.3f", res.cosineSimilarity),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stage C: Local Ollama LLM Server Configuration
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Stage 7: Local Ollama LLM Endpoint & Model",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = {
                            hostInput = it
                            onUpdateOllamaSettings(it, config.selectedModel, config.temperature)
                        },
                        label = { Text("Ollama Server URL (Mac localhost or LAN IP)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ollama_host_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AVAILABLE_LOCAL_MODELS.take(3).forEach { model ->
                            FilterChip(
                                selected = config.selectedModel == model,
                                onClick = {
                                    onUpdateOllamaSettings(hostInput, model, config.temperature)
                                },
                                label = { Text(model) }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onTestOllama,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_ollama_connection_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Ollama Connection (/api/tags)")
                    }

                    Text(
                        text = ollamaStatus.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ollamaStatus.isReachable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Stage D: Grounded Prompt Inspector
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
                    Text(
                        text = "Constructed Grounded Prompt Sent to Ollama",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "${pipelineState.augmentedSystemPrompt}\n\n${pipelineState.augmentedUserPrompt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}
