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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.gui.theme.AppColors
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun SettingsDialog(
    models: List<Model>,
    selectedModelIndex: Int,
    thinkingLevel: AgentThinkingLevel,
    providers: List<ProviderInfo>,
    onSelectModel: (Model) -> Unit,
    onSelectThinking: (AgentThinkingLevel) -> Unit,
    onConnectProvider: (ProviderInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceElevated = AppColors.surfaceElevated
    val surfaceSecondary = AppColors.surfaceSecondary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.dialogScrim)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(surfaceElevated)
                .clickable(enabled = false, onClick = {})
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Title row with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Settings",
                    color = AppColors.textPrimary,
                    style = JewelTheme.typography.h2TextStyle.copy(fontWeight = FontWeight.Bold),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Tabler.Outline.X,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.textMuted,
                    )
                }
            }

            // Model section
            GroupHeader("Model")
            ModelPicker(
                models = models,
                selectedIndex = selectedModelIndex,
                onSelect = onSelectModel,
                surfaceColor = surfaceSecondary,
            )

            // Thinking section
            GroupHeader("Thinking")
            ThinkingPicker(
                current = thinkingLevel,
                onSelect = onSelectThinking,
            )

            // Providers section
            GroupHeader("Providers")
            ProviderList(
                providers = providers,
                onConnect = onConnectProvider,
                surfaceColor = surfaceSecondary,
            )
        }
    }
}

@OptIn(ExperimentalJewelApi::class)
@Composable
private fun ModelPicker(
    models: List<Model>,
    selectedIndex: Int,
    onSelect: (Model) -> Unit,
    surfaceColor: Color,
) {
    if (models.isEmpty()) {
        Text(
            text = "No models available. Connect a provider first.",
            color = AppColors.sectionLabel,
        )
        return
    }

    ListComboBox(
        items = models,
        selectedIndex = selectedIndex.coerceIn(0, models.lastIndex),
        onSelectedItemChange = { index -> onSelect(models[index]) },
        itemKeys = { index, model -> model.id },
        modifier = Modifier.fillMaxWidth(),
    ) { model, isSelected, _ ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.id,
                color = if (isSelected) Color.White else AppColors.sidebarText,
                style = JewelTheme.editorTextStyle,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = model.provider.value,
                color = if (isSelected) AppColors.infoHighlight else AppColors.sectionLabel,
                style = JewelTheme.typography.small,
            )
        }
    }
}

@Composable
private fun ThinkingPicker(
    current: AgentThinkingLevel,
    onSelect: (AgentThinkingLevel) -> Unit,
) {
    val buttons = AgentThinkingLevel.entries.map { level ->
        SegmentedControlButtonData(
            selected = level == current,
            onSelect = { onSelect(level) },
            content = {
                Text(
                    text = level.name.lowercase(),
                    style = JewelTheme.typography.medium,
                )
            },
        )
    }
    SegmentedControl(buttons = buttons)
}

@Composable
private fun ProviderList(
    providers: List<ProviderInfo>,
    onConnect: (ProviderInfo) -> Unit,
    surfaceColor: Color,
) {
    if (providers.isEmpty()) {
        Text(
            text = "No providers configured.",
            color = AppColors.sectionLabel,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(surfaceColor),
    ) {
        providers.forEach { info ->
            var isHovered by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onHover { isHovered = it }
                    .background(if (isHovered) AppColors.hoverBackground else Color.Transparent)
                    .clickable { if (!info.connected) onConnect(info) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (info.connected) Tabler.Outline.Check else Tabler.Outline.Circle,
                        contentDescription = if (info.connected) "Connected" else "Disconnected",
                        modifier = Modifier.size(14.dp),
                        tint = if (info.connected) AppColors.success else AppColors.sectionLabel,
                    )
                    Text(
                        text = info.name,
                        color = AppColors.sidebarText,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val modelLabel = if (info.modelCount == 1) "1 model" else "${info.modelCount} models"
                    Text(
                        text = modelLabel,
                        color = AppColors.sectionLabel,
                        style = JewelTheme.typography.small,
                    )
                    if (!info.connected) {
                        DefaultButton(onClick = { onConnect(info) }) {
                            Text(text = "Connect", style = JewelTheme.typography.small)
                        }
                    }
                }
            }
        }
    }
}
