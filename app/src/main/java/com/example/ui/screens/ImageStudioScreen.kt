package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.GeneratedImageEntry
import com.example.ui.ImageGenUiState

private val CREATE_IMAGE_PROMPTS = listOf(
    "Isometric 3D technical illustration of a glowing neural vector database and PDF documents on a dark obsidian background",
    "Futuristic Apple Silicon microchip with luminous cyan optical data pathways, macro photography",
    "Minimalist editorial book cover illustration for an AI Systems Engineering handbook, geometric Bauhaus style"
)

private val EDIT_IMAGE_PROMPTS = listOf(
    "Transform into a glowing neon cyberpunk blueprint with gold highlights",
    "Convert into a warm watercolor architectural sketch on textured paper",
    "Add dramatic volumetric studio lighting and holographic data nodes"
)

@Composable
fun ImageStudioScreen(
    uiState: ImageGenUiState,
    selectedSourceBitmap: Bitmap?,
    imageHistory: List<GeneratedImageEntry>,
    onSelectSourceBitmap: (Bitmap?) -> Unit,
    onCreateImage: (String) -> Unit,
    onEditImage: (Bitmap, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isEditMode by rememberSaveable { mutableStateOf(false) }
    var promptText by rememberSaveable {
        mutableStateOf(CREATE_IMAGE_PROMPTS.first())
    }

    // Zero-permission Android Photo Picker per Google Play Policy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) {
                        onSelectSourceBitmap(decoded)
                        isEditMode = true
                        promptText = EDIT_IMAGE_PROMPTS.first()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    val isLoading = uiState is ImageGenUiState.Loading

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("image_studio_lazy_column"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header & Mode Switcher (Create vs Edit with gemini-nano-banana-2.1)
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
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "AI Visual Studio • gemini-nano-banana-2.1",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "Generate new diagrams/illustrations from text or edit existing images",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = !isEditMode,
                            onClick = {
                                isEditMode = false
                                promptText = CREATE_IMAGE_PROMPTS.first()
                            },
                            label = { Text("✨ Create from Text") },
                            modifier = Modifier.testTag("mode_create_image_chip")
                        )

                        FilterChip(
                            selected = isEditMode,
                            onClick = {
                                isEditMode = true
                                promptText = EDIT_IMAGE_PROMPTS.first()
                            },
                            label = { Text("🎨 Edit Image") },
                            modifier = Modifier.testTag("mode_edit_image_chip")
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("pick_photo_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Pick Photo to Edit",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Photo")
                        }
                    }

                    // Quick Prompt Presets
                    val presets = if (isEditMode) EDIT_IMAGE_PROMPTS else CREATE_IMAGE_PROMPTS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEachIndexed { idx, preset ->
                            AssistChip(
                                onClick = { promptText = preset },
                                label = {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag("image_preset_chip_$idx")
                            )
                        }
                    }

                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = {
                            Text(
                                if (isEditMode) "Image Edit Instruction" else "Text-to-Image Prompt"
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("image_prompt_input"),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (!isEditMode) {
                                onCreateImage(promptText)
                            } else if (selectedSourceBitmap != null) {
                                onEditImage(selectedSourceBitmap, promptText)
                            }
                        },
                        enabled = !isLoading && promptText.isNotBlank() && (!isEditMode || selectedSourceBitmap != null),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("execute_image_action_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Brush else Icons.Default.AutoFixHigh,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                !isEditMode -> "Generate Image (gemini-nano-banana-2.1)"
                                selectedSourceBitmap == null -> "Pick or Generate an Image First to Edit"
                                else -> "Apply Edit to Selected Image"
                            }
                        )
                    }
                }
            }
        }

        // Canvas / Output Preview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (uiState) {
                        is ImageGenUiState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = uiState.modeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        is ImageGenUiState.Error -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("image_gen_error_box"),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Error: ${uiState.message}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        is ImageGenUiState.Success -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (uiState.isEdited) "Edited Output (1024×1024)" else "Generated Output (1024×1024)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            onSelectSourceBitmap(uiState.bitmap)
                                            isEditMode = true
                                            promptText = EDIT_IMAGE_PROMPTS.first()
                                        },
                                        modifier = Modifier.testTag("edit_this_image_button")
                                    ) {
                                        Text("Edit This Image")
                                    }
                                }

                                Image(
                                    bitmap = uiState.bitmap.asImageBitmap(),
                                    contentDescription = uiState.prompt,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop
                                )

                                Text(
                                    text = "Prompt: ${uiState.prompt}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is ImageGenUiState.Idle -> {
                            if (selectedSourceBitmap != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Source Image Loaded for Editing",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Image(
                                        bitmap = selectedSourceBitmap.asImageBitmap(),
                                        contentDescription = "Selected source image for editing",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(14.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Enter a prompt above to create or edit an image with gemini-nano-banana-2.1",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Session Image Gallery
        if (imageHistory.isNotEmpty()) {
            item {
                Text(
                    text = "Session Visual History (${imageHistory.size}) — Tap any image to edit it",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    imageHistory.forEach { entry ->
                        Image(
                            bitmap = entry.bitmap.asImageBitmap(),
                            contentDescription = entry.prompt,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 2.dp,
                                    color = if (selectedSourceBitmap === entry.bitmap) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    onSelectSourceBitmap(entry.bitmap)
                                    isEditMode = true
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}
