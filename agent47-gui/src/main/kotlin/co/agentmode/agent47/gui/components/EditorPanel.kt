package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.ui.core.editor.AutocompleteManager
import co.agentmode.agent47.ui.core.editor.AutocompletePopupModel
import co.agentmode.agent47.ui.core.editor.FileCompletionProvider
import co.agentmode.agent47.ui.core.editor.SlashCommandCompletionProvider
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import java.nio.file.Path

@OptIn(FlowPreview::class)
@Composable
public fun EditorPanel(
    modifier: Modifier = Modifier,
    onSubmit: (String) -> Unit = {},
    slashCommands: List<String> = emptyList(),
    slashCommandDetails: Map<String, String> = emptyMap(),
    cwd: Path = Path.of("."),
    modelLabel: String = "",
    models: List<Model> = emptyList(),
    selectedModelIndex: Int = -1,
    onSelectModel: (Model) -> Unit = {},
    thinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    onSelectThinking: (AgentThinkingLevel) -> Unit = {},
) {
    val textFieldState = rememberTextFieldState()
    val autocompleteManager = remember(slashCommands, cwd) {
        AutocompleteManager(
            listOf(
                SlashCommandCompletionProvider(slashCommands, slashCommandDetails),
                FileCompletionProvider(cwd),
            ),
        )
    }

    var popup by remember { mutableStateOf<AutocompletePopupModel?>(null) }

    // Track text changes for autocomplete (cursor assumed at end of text)
    LaunchedEffect(autocompleteManager) {
        snapshotFlow { textFieldState.text.toString() }
            .debounce(50)
            .collectLatest { text ->
                val (lineText, column) = computeLineAndColumn(text, text.length)
                popup = autocompleteManager.update(lineText, column)
            }
    }

    val currentText = textFieldState.text.toString()
    val hasText = currentText.isNotBlank()
    val bashMode = currentText.trimStart().startsWith("!")

    val cardShape = RoundedCornerShape(12.dp)
    val borderColor = if (bashMode) AppColors.error.copy(alpha = 0.5f) else AppColors.border.copy(alpha = 0.3f)

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Autocomplete popup (above editor card)
        val currentPopup = popup
        if (currentPopup != null && currentPopup.items.isNotEmpty()) {
            AutocompleteDropdown(
                popup = currentPopup,
                onSelect = { index ->
                    applyCompletion(textFieldState, currentPopup, index)
                    autocompleteManager.dismiss()
                    popup = null
                },
            )
        }

        // Card container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(AppColors.surfaceSecondary)
                .border(1.dp, borderColor, cardShape)
                .padding(12.dp)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    val activePopup = popup
                    if (activePopup != null && activePopup.items.isNotEmpty()) {
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                autocompleteManager.selectPrevious()
                                popup = autocompleteManager.popup
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionDown -> {
                                autocompleteManager.selectNext()
                                popup = autocompleteManager.popup
                                return@onPreviewKeyEvent true
                            }
                            Key.Tab, Key.Enter -> {
                                applyCompletion(textFieldState, activePopup, activePopup.selectedIndex)
                                autocompleteManager.dismiss()
                                popup = null
                                return@onPreviewKeyEvent true
                            }
                            Key.Escape -> {
                                autocompleteManager.dismiss()
                                popup = null
                                return@onPreviewKeyEvent true
                            }
                        }
                    }

                    // Enter (no Shift, no Ctrl) submits when no popup
                    if (keyEvent.key == Key.Enter && !keyEvent.isShiftPressed && !keyEvent.isCtrlPressed) {
                        val text = textFieldState.text.toString()
                        if (text.isNotBlank()) {
                            onSubmit(text)
                            textFieldState.edit { replace(0, length, "") }
                        }
                        return@onPreviewKeyEvent true
                    }

                    // Ctrl+Enter or Cmd+Enter also submits
                    if (keyEvent.key == Key.Enter && (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)) {
                        val text = textFieldState.text.toString()
                        if (text.isNotBlank()) {
                            onSubmit(text)
                            textFieldState.edit { replace(0, length, "") }
                        }
                        return@onPreviewKeyEvent true
                    }

                    false
                },
        ) {
            // TextArea
            TextArea(
                state = textFieldState,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 200.dp),
                placeholder = {
                    Text(
                        if (bashMode) "Shell command (! prefix)" else "Message Agent 47... (Enter to send, Shift+Enter for newline)"
                    )
                },
            )

            Spacer(Modifier.height(8.dp))

            // Context bar
            ContextBar(
                modelLabel = modelLabel,
                models = models,
                selectedModelIndex = selectedModelIndex,
                onSelectModel = onSelectModel,
                thinkingLevel = thinkingLevel,
                onSelectThinking = onSelectThinking,
                hasText = hasText,
                onSend = {
                    val text = textFieldState.text.toString()
                    if (text.isNotBlank()) {
                        onSubmit(text)
                        textFieldState.edit { replace(0, length, "") }
                    }
                },
            )
        }
    }
}

@Composable
private fun ContextBar(
    modelLabel: String,
    models: List<Model>,
    selectedModelIndex: Int,
    onSelectModel: (Model) -> Unit,
    thinkingLevel: AgentThinkingLevel,
    onSelectThinking: (AgentThinkingLevel) -> Unit,
    hasText: Boolean,
    onSend: () -> Unit,
) {
    var modelDropdownVisible by remember { mutableStateOf(false) }
    var thinkingPopupVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Add button (placeholder)
        IconButton(
            onClick = { },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Tabler.Outline.Plus,
                contentDescription = "Add",
                modifier = Modifier.size(14.dp),
                tint = AppColors.textMuted,
            )
        }

        // Vertical divider
        Divider(Orientation.Vertical, modifier = Modifier.height(20.dp))

        // Model selector
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { modelDropdownVisible = !modelDropdownVisible }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = modelLabel,
                    color = AppColors.textLight,
                    style = JewelTheme.typography.small,
                )
                Icon(
                    imageVector = Tabler.Outline.ChevronDown,
                    contentDescription = "Select model",
                    modifier = Modifier.size(12.dp),
                    tint = AppColors.textMuted,
                )
            }

            if (modelDropdownVisible) {
                ModelDropdownPopup(
                    models = models,
                    selectedModelIndex = selectedModelIndex,
                    onSelectModel = { model ->
                        onSelectModel(model)
                        modelDropdownVisible = false
                    },
                    onDismiss = { modelDropdownVisible = false },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Thinking level button
        Box {
            val thinkingActive = thinkingLevel != AgentThinkingLevel.OFF
            IconButton(
                onClick = { thinkingPopupVisible = !thinkingPopupVisible },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Tabler.Outline.Bulb,
                    contentDescription = "Thinking level",
                    modifier = Modifier.size(14.dp),
                    tint = if (thinkingActive) AppColors.warning else AppColors.textMuted,
                )
            }

            if (thinkingPopupVisible) {
                ThinkingLevelPopup(
                    currentLevel = thinkingLevel,
                    onSelectLevel = { level ->
                        onSelectThinking(level)
                        thinkingPopupVisible = false
                    },
                    onDismiss = { thinkingPopupVisible = false },
                )
            }
        }

        // Send button
        val sendBackground = if (hasText) AppColors.warning else Color.Transparent
        val sendTextColor = if (hasText) Color.Black else AppColors.textMuted
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(sendBackground)
                .clickable(enabled = hasText) { onSend() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Send",
                color = sendTextColor,
                style = JewelTheme.defaultTextStyle.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                text = "\u2318\u21B5",
                color = if (hasText) Color.Black.copy(alpha = 0.5f) else AppColors.textDim,
                style = JewelTheme.typography.small,
            )
        }
    }
}

@Composable
private fun ModelDropdownPopup(
    models: List<Model>,
    selectedModelIndex: Int,
    onSelectModel: (Model) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.surfaceElevated)
                .border(1.dp, AppColors.border.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(4.dp),
        ) {
            models.forEachIndexed { index, model ->
                val isSelected = index == selectedModelIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) AppColors.selectionBackground else Color.Transparent)
                        .clickable { onSelectModel(model) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = model.id,
                        color = if (isSelected) Color.White else AppColors.textLight,
                        style = JewelTheme.defaultTextStyle,
                    )
                    Text(
                        text = model.provider.value,
                        color = AppColors.textDim,
                        style = JewelTheme.typography.small,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingLevelPopup(
    currentLevel: AgentThinkingLevel,
    onSelectLevel: (AgentThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopEnd,
    ) {
        Column(
            modifier = Modifier
                .width(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.surfaceElevated)
                .border(1.dp, AppColors.border.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(4.dp),
        ) {
            AgentThinkingLevel.entries.forEach { level ->
                val isSelected = level == currentLevel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) AppColors.warning.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onSelectLevel(level) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Tabler.Outline.Bulb,
                        contentDescription = level.name,
                        modifier = Modifier.size(14.dp),
                        tint = if (isSelected) AppColors.warning else AppColors.textMuted,
                    )
                    Text(
                        text = level.name.lowercase(),
                        color = if (isSelected) AppColors.warning else AppColors.textLight,
                        style = JewelTheme.defaultTextStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutocompleteDropdown(
    popup: AutocompletePopupModel,
    onSelect: (Int) -> Unit,
) {
    val surfaceElevated = AppColors.surfaceElevated

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(surfaceElevated)
                .padding(4.dp),
        ) {
            popup.items.forEachIndexed { index, item ->
                val isSelected = index == popup.selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) AppColors.selectionBackground else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Type icon
                    val isSlashCommand = item.label.startsWith("/")
                    Icon(
                        imageVector = if (isSlashCommand) Tabler.Outline.Terminal else Tabler.Outline.File,
                        contentDescription = if (isSlashCommand) "Command" else "File",
                        modifier = Modifier.size(12.dp),
                        tint = if (isSelected) Color.White else AppColors.textDim,
                    )
                    Spacer(Modifier.width(6.dp))

                    Text(
                        text = item.label,
                        color = if (isSelected) Color.White else AppColors.textLight,
                        style = JewelTheme.editorTextStyle.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                    val detail = item.detail
                    if (detail != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = detail,
                            color = AppColors.textDim,
                            style = JewelTheme.typography.small,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun applyCompletion(
    textFieldState: TextFieldState,
    popup: AutocompletePopupModel,
    selectedIndex: Int,
) {
    val selected = popup.items.getOrNull(selectedIndex) ?: return
    val text = textFieldState.text.toString()
    val cursorPos = text.length

    // Find the start of the current line in the flat text
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)).let {
        if (it < 0) 0 else it + 1
    }

    // popup.tokenStart and tokenEnd are line-relative offsets
    val absoluteStart = (lineStart + popup.tokenStart).coerceIn(0, text.length)
    val absoluteEnd = (lineStart + popup.tokenEnd).coerceIn(absoluteStart, text.length)

    textFieldState.edit {
        replace(absoluteStart, absoluteEnd, selected.insertText)
    }
}

private fun computeLineAndColumn(text: String, offset: Int): Pair<String, Int> {
    val safeOffset = offset.coerceIn(0, text.length)
    var lineStart = 0
    for (i in 0 until safeOffset) {
        if (text[i] == '\n') {
            lineStart = i + 1
        }
    }
    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    val lineText = text.substring(lineStart, lineEnd)
    val column = (safeOffset - lineStart).coerceIn(0, lineText.length)
    return lineText to column
}
