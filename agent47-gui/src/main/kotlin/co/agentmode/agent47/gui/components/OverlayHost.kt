package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.ui.core.state.InfoOverlayEntry
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.PromptOverlayEntry
import co.agentmode.agent47.ui.core.state.ScrollableTextOverlayEntry
import co.agentmode.agent47.ui.core.state.SelectDialogState
import co.agentmode.agent47.ui.core.state.SelectOverlayEntry
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

@Composable
public fun GuiOverlayHost(
    state: OverlayHostState,
    modifier: Modifier = Modifier,
) {
    if (!state.hasOverlay) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x80000000))
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
            .background(Color(0xFF2B2B2B))
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
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
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

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            items(filteredIndices) { index ->
                val item = entry.items[index]
                val isSelected = index == dialogState.selectedIndex
                val matchedPositions = dialogState.matchedPositions(index)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) Color(0xFF3D5A80) else Color.Transparent)
                        .clickable {
                            dialogState.selectedIndex = index
                            val value = dialogState.selectedValue()
                            if (value != null) {
                                entry.onSubmit(value)
                                if (!entry.keepOpenOnSubmit) {
                                    hostState.dismissTopSilent()
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (matchedPositions.isNotEmpty()) {
                        Text(
                            text = buildAnnotatedString {
                                item.label.forEachIndexed { i, ch ->
                                    if (i in matchedPositions) {
                                        withStyle(SpanStyle(color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)) {
                                            append(ch)
                                        }
                                    } else {
                                        withStyle(SpanStyle(color = if (isSelected) Color.White else Color(0xFFBBBBBB))) {
                                            append(ch)
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        Text(
                            text = item.label,
                            color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptOverlayView(entry: PromptOverlayEntry, hostState: OverlayHostState) {
    val textFieldState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2B2B))
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
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )

        val description = entry.description
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp,
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
    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2B2B))
            .padding(16.dp),
    ) {
        Text(
            text = entry.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        entry.lines.forEach { line ->
            Text(
                text = line,
                color = Color(0xFFAAAAAA),
            )
        }
    }
}

@Composable
private fun ScrollableTextOverlayView(entry: ScrollableTextOverlayEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .heightIn(max = 600.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2B2B))
            .padding(16.dp),
    ) {
        Text(
            text = entry.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
        ) {
            entry.lines.forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFFBBBBBB),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
