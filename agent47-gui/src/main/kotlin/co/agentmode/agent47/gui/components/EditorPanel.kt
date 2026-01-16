package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.ui.core.editor.AutocompleteManager
import co.agentmode.agent47.ui.core.editor.AutocompletePopupModel
import co.agentmode.agent47.ui.core.editor.FileCompletionProvider
import co.agentmode.agent47.ui.core.editor.SlashCommandCompletionProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.ui.component.DefaultButton
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
    val bashMode = currentText.trimStart().startsWith("!")
    val borderColor = if (bashMode) Color(0xFFEF5350) else Color(0xFF3C3C3C)

    Column(modifier = modifier) {
        // Autocomplete popup (above editor)
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

        // Editor row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(borderColor.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
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

                    // Ctrl+Enter also submits
                    if (keyEvent.key == Key.Enter && keyEvent.isCtrlPressed) {
                        val text = textFieldState.text.toString()
                        if (text.isNotBlank()) {
                            onSubmit(text)
                            textFieldState.edit { replace(0, length, "") }
                        }
                        return@onPreviewKeyEvent true
                    }

                    false
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            TextArea(
                state = textFieldState,
                modifier = Modifier.weight(1f).height(80.dp),
                placeholder = {
                    Text(
                        if (bashMode) "Shell command (! prefix)" else "Message Agent 47... (Enter to send, Shift+Enter for newline)"
                    )
                },
            )

            DefaultButton(
                modifier = Modifier.padding(start = 8.dp),
                onClick = {
                    val text = textFieldState.text.toString()
                    if (text.isNotBlank()) {
                        onSubmit(text)
                        textFieldState.edit { replace(0, length, "") }
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun AutocompleteDropdown(
    popup: AutocompletePopupModel,
    onSelect: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2B2B2B))
                .padding(4.dp),
        ) {
            popup.items.forEachIndexed { index, item ->
                val isSelected = index == popup.selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) Color(0xFF3D5A80) else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                    val detail = item.detail
                    if (detail != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = detail,
                            color = Color(0xFF757575),
                            fontSize = 11.sp,
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
