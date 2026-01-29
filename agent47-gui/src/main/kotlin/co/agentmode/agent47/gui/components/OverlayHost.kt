package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.ui.core.state.InfoOverlayEntry
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.PromptOverlayEntry
import co.agentmode.agent47.ui.core.state.ScrollableTextOverlayEntry
import co.agentmode.agent47.ui.core.state.SelectDialogState
import co.agentmode.agent47.ui.core.state.SelectOverlayEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.ui.component.SimpleListItem
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

@Composable
public fun GuiOverlayHost(
    state: OverlayHostState,
    modifier: Modifier = Modifier,
) {
    if (!state.hasOverlay) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.dialogScrim)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                    state.dismissTop()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val topEntry = state.stack.lastOrNull() ?: return

        when (topEntry) {
            is SelectOverlayEntry<*> -> SelectOverlayView(topEntry, state)
            is PromptOverlayEntry -> PromptOverlayView(topEntry, state)
            is InfoOverlayEntry -> InfoOverlayView(topEntry)
            is ScrollableTextOverlayEntry -> ScrollableTextOverlayView(topEntry)
        }
    }
}

@Composable
private fun <T> SelectOverlayView(entry: SelectOverlayEntry<T>, hostState: OverlayHostState) {
    val surfaceElevated = AppColors.surfaceElevated

    val dialogState = remember(entry.id) {
        SelectDialogState(entry.items, entry.initialSelectedIndex).also {
            entry.dialogState = it
        }
    }

    var filterText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .heightIn(max = 500.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceElevated)
            .padding(16.dp)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when {
                    keyEvent.key == Key.Escape -> {
                        hostState.dismissTop()
                        true
                    }
                    keyEvent.key == Key.DirectionUp -> {
                        dialogState.moveUp()
                        entry.onSelectionChanged?.let { callback ->
                            dialogState.selectedValue()?.let { callback(it) }
                        }
                        true
                    }
                    keyEvent.key == Key.DirectionDown -> {
                        dialogState.moveDown()
                        entry.onSelectionChanged?.let { callback ->
                            dialogState.selectedValue()?.let { callback(it) }
                        }
                        true
                    }
                    keyEvent.key == Key.Enter -> {
                        val value = dialogState.selectedValue()
                        if (value != null) {
                            entry.onSubmit(value)
                            if (!entry.keepOpenOnSubmit) {
                                hostState.dismissTopSilent()
                            }
                        }
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.U -> {
                        dialogState.clearFilter()
                        filterText = ""
                        true
                    }
                    keyEvent.key == Key.Backspace -> {
                        dialogState.deleteChar()
                        filterText = dialogState.query
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Title
        Text(
            text = entry.title,
            color = AppColors.textPrimary,
            style = JewelTheme.typography.h4TextStyle,
        )

        Spacer(Modifier.height(8.dp))

        // Search field
        if (entry.items.size > 5) {
            val textFieldState = rememberTextFieldState()
            val focusRequester = remember { FocusRequester() }

            TextField(
                state = textFieldState,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val newText = textFieldState.text.toString()
                        if (newText != dialogState.query) {
                            dialogState.query = newText
                            val refreshed = dialogState.filteredIndices()
                            if (refreshed.isNotEmpty() && dialogState.selectedIndex !in refreshed) {
                                dialogState.selectedIndex = refreshed.first()
                            }
                        }
                        false
                    },
                placeholder = { Text("Filter...") },
            )

            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Spacer(Modifier.height(8.dp))
        }

        // Items list
        val filteredIndices = dialogState.filteredIndices()
        val listState = rememberLazyListState()

        LaunchedEffect(dialogState.selectedIndex) {
            val pos = filteredIndices.indexOf(dialogState.selectedIndex)
            if (pos >= 0) {
                listState.animateScrollToItem(pos)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(filteredIndices) { index ->
                val item = entry.items[index]
                val isSelected = index == dialogState.selectedIndex
                val matchedPositions = dialogState.matchedPositions(index)

                val clickModifier = Modifier.clickable {
                    dialogState.selectedIndex = index
                    val value = dialogState.selectedValue()
                    if (value != null) {
                        entry.onSubmit(value)
                        if (!entry.keepOpenOnSubmit) {
                            hostState.dismissTopSilent()
                        }
                    }
                }

                if (matchedPositions.isNotEmpty()) {
                    val annotated = buildAnnotatedString {
                        item.label.forEachIndexed { i, ch ->
                            if (i in matchedPositions) {
                                withStyle(SpanStyle(color = AppColors.info, fontWeight = FontWeight.Bold)) {
                                    append(ch)
                                }
                            } else {
                                withStyle(SpanStyle(color = if (isSelected) Color.White else AppColors.textLight)) {
                                    append(ch)
                                }
                            }
                        }
                    }
                    SimpleListItem(
                        text = annotated,
                        selected = isSelected,
                        modifier = clickModifier.fillMaxWidth(),
                    )
                } else {
                    SimpleListItem(
                        text = item.label,
                        selected = isSelected,
                        modifier = clickModifier.fillMaxWidth(),
                    )
                }
            }
        }

            VerticalScrollbar(
                scrollState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PromptOverlayView(entry: PromptOverlayEntry, hostState: OverlayHostState) {
    val surfaceElevated = AppColors.surfaceElevated
    val textFieldState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceElevated)
            .padding(16.dp)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    keyEvent.key == Key.Escape -> {
                        hostState.dismissTop()
                        true
                    }
                    keyEvent.key == Key.Enter -> {
                        val value = textFieldState.text.toString()
                        entry.onSubmit(value)
                        hostState.dismissTopSilent()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Text(
            text = entry.title,
            color = AppColors.textPrimary,
            style = JewelTheme.typography.h4TextStyle,
        )

        val description = entry.description
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = AppColors.textDescription,
                style = JewelTheme.typography.medium,
            )
        }

        Spacer(Modifier.height(8.dp))

        TextField(
            state = textFieldState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = { Text(entry.placeholder) },
        )

        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

@Composable
private fun InfoOverlayView(entry: InfoOverlayEntry) {
    val surfaceElevated = AppColors.surfaceElevated

    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceElevated)
            .padding(16.dp),
    ) {
        Text(
            text = entry.title,
            color = AppColors.textPrimary,
            style = JewelTheme.typography.h4TextStyle,
        )
        Spacer(Modifier.height(8.dp))
        entry.lines.forEach { line ->
            Text(
                text = line,
                color = AppColors.textDescription,
            )
        }
    }
}

@Composable
private fun ScrollableTextOverlayView(entry: ScrollableTextOverlayEntry) {
    val surfaceElevated = AppColors.surfaceElevated

    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceElevated)
            .padding(16.dp),
    ) {
        Text(
            text = entry.title,
            color = AppColors.textPrimary,
            style = JewelTheme.typography.h4TextStyle,
        )
        Spacer(Modifier.height(8.dp))

        VerticallyScrollableContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            Column {
                entry.lines.forEach { line ->
                    Text(
                        text = line,
                        color = AppColors.textLight,
                        style = JewelTheme.consoleTextStyle,
                    )
                }
            }
        }
    }
}
