package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.ai.configureAppCheck
import com.example.ui.DocuMindViewModel
import com.example.ui.GeminiViewModel
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.DocumentsScreen
import com.example.ui.screens.GeminiChatScreen
import com.example.ui.screens.ImageStudioScreen
import com.example.ui.screens.PipelineLabScreen
import com.example.ui.screens.PythonCodeScreen
import com.example.ui.theme.MyApplicationTheme

private data class NavTabItem(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        // STEP 2: Render Compose UI
        setContent {
            val ragViewModel: DocuMindViewModel = viewModel()
            val geminiViewModel: GeminiViewModel = viewModel()
            val config by ragViewModel.config.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = config.isDarkTheme) {
                DocuMindApp(
                    viewModel = ragViewModel,
                    geminiViewModel = geminiViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocuMindApp(
    viewModel: DocuMindViewModel,
    geminiViewModel: GeminiViewModel
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val chunks by viewModel.chunks.collectAsStateWithLifecycle()
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val ollamaStatus by viewModel.ollamaStatus.collectAsStateWithLifecycle()
    val pipelineLab by viewModel.pipelineLab.collectAsStateWithLifecycle()

    // Gemini AI & Image Studio states
    val geminiMessages by geminiViewModel.messages.collectAsStateWithLifecycle()
    val geminiChatState by geminiViewModel.chatUiState.collectAsStateWithLifecycle()
    val selectedGeminiModel by geminiViewModel.selectedModel.collectAsStateWithLifecycle()
    val selectedPersona by geminiViewModel.selectedPersona.collectAsStateWithLifecycle()
    val useGoogleSearch by geminiViewModel.useGoogleSearch.collectAsStateWithLifecycle()
    val includeLocalPdfContext by geminiViewModel.includeLocalPdfContext.collectAsStateWithLifecycle()

    val imageUiState by geminiViewModel.imageUiState.collectAsStateWithLifecycle()
    val selectedSourceBitmap by geminiViewModel.selectedSourceBitmap.collectAsStateWithLifecycle()
    val imageHistory by geminiViewModel.imageHistory.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var labSubTab by rememberSaveable { mutableIntStateOf(0) } // 0 = RAG Lab, 1 = Mac Python Code

    if (currentTab != 0) {
        BackHandler {
            currentTab = 0
        }
    }

    val navTabs = listOf(
        NavTabItem(R.string.nav_chat, Icons.Filled.Forum, Icons.Outlined.Forum, "nav_tab_chat"),
        NavTabItem(R.string.nav_documents, Icons.Filled.PictureAsPdf, Icons.Outlined.PictureAsPdf, "nav_tab_documents"),
        NavTabItem(R.string.nav_gemini, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_tab_gemini"),
        NavTabItem(R.string.nav_images, Icons.Filled.AutoFixHigh, Icons.Outlined.AutoFixHigh, "nav_tab_images"),
        NavTabItem(R.string.nav_pipeline, Icons.Filled.Hub, Icons.Outlined.Hub, "nav_tab_pipeline")
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DocuMind",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleTheme() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (config.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = stringResource(R.string.toggle_theme),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                navTabs.forEachIndexed { idx, tab ->
                    val selected = currentTab == idx
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = idx },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = stringResource(tab.labelRes)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = statusBanner != null) {
                statusBanner?.let { msg ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.dismissBanner() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss notification",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            when (currentTab) {
                0 -> ChatScreen(
                    chatHistory = chatHistory,
                    documentCount = documents.size,
                    chunkCount = chunks.size,
                    config = config,
                    isBusy = isBusy,
                    onAskQuestion = { viewModel.askQuestion(it) },
                    onClearChat = { viewModel.clearChat() },
                    modifier = Modifier.weight(1f)
                )

                1 -> DocumentsScreen(
                    documents = documents,
                    chunks = chunks,
                    config = config,
                    onUploadPdf = { uri, name -> viewModel.uploadPdfUri(uri, name) },
                    onRestoreSamplePdfs = { viewModel.restoreSamplePdfs() },
                    onDeleteDocument = { viewModel.deleteDocument(it) },
                    onUpdateChunkSettings = { size, overlap ->
                        viewModel.updateChunkSettings(size, overlap)
                    },
                    modifier = Modifier.weight(1f)
                )

                2 -> GeminiChatScreen(
                    messages = geminiMessages,
                    uiState = geminiChatState,
                    selectedModel = selectedGeminiModel,
                    selectedPersona = selectedPersona,
                    useGoogleSearch = useGoogleSearch,
                    includeLocalPdfContext = includeLocalPdfContext,
                    indexedChunks = chunks,
                    onSelectModel = { geminiViewModel.selectModel(it) },
                    onSelectPersona = { geminiViewModel.selectPersona(it) },
                    onToggleGoogleSearch = { geminiViewModel.toggleGoogleSearch(it) },
                    onToggleLocalPdfContext = { geminiViewModel.toggleIncludeLocalPdfContext(it) },
                    onSendMessage = { prompt -> geminiViewModel.sendChatMessage(prompt, chunks) },
                    onClearChat = { geminiViewModel.clearGeminiChat() },
                    modifier = Modifier.weight(1f)
                )

                3 -> ImageStudioScreen(
                    uiState = imageUiState,
                    selectedSourceBitmap = selectedSourceBitmap,
                    imageHistory = imageHistory,
                    onSelectSourceBitmap = { geminiViewModel.setSourceBitmapForEditing(it) },
                    onCreateImage = { geminiViewModel.generateNewImage(it) },
                    onEditImage = { bmp, instruction -> geminiViewModel.editExistingImage(bmp, instruction) },
                    modifier = Modifier.weight(1f)
                )

                4 -> Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = labSubTab == 0,
                            onClick = { labSubTab = 0 },
                            label = { Text("🔬 7-Stage RAG Lab") },
                            modifier = Modifier.testTag("subtab_rag_lab")
                        )
                        FilterChip(
                            selected = labSubTab == 1,
                            onClick = { labSubTab = 1 },
                            label = { Text("💻 Mac Python Code") },
                            modifier = Modifier.testTag("subtab_mac_python")
                        )
                    }

                    if (labSubTab == 0) {
                        PipelineLabScreen(
                            pipelineState = pipelineLab,
                            config = config,
                            ollamaStatus = ollamaStatus,
                            onRunTraceQuery = { viewModel.refreshPipelineLab(it) },
                            onUpdateRetrievalSettings = { k, thresh ->
                                viewModel.updateRetrievalSettings(k, thresh)
                            },
                            onUpdateOllamaSettings = { host, model, temp ->
                                viewModel.updateOllamaSettings(host, model, temp)
                            },
                            onTestOllama = { viewModel.testOllamaConnection() },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        PythonCodeScreen(
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
