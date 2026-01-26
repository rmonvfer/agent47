package co.agentmode.agent47.gui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Centralized color tokens for the Agent 47 GUI. Delegates to JewelTheme
 * for standard text and surface colors, and defines semantic colors for
 * application-specific needs (status indicators, selections, chat bubbles).
 */
public object AppColors {

    // --- Text colors (delegate to JewelTheme) ---
    public val textPrimary: Color @Composable get() = JewelTheme.globalColors.text.normal
    public val textSecondary: Color @Composable get() = JewelTheme.globalColors.text.info
    public val textDisabled: Color @Composable get() = JewelTheme.globalColors.text.disabled

    // --- Surface colors ---
    public val panelBackground: Color @Composable get() = JewelTheme.globalColors.panelBackground
    public val surfacePrimary: Color = Color(0xFF2B2D30)
    public val surfaceSecondary: Color = Color(0xFF313335)
    public val surfaceElevated: Color = Color(0xFF3C3F41)

    // --- Borders (delegate to theme) ---
    public val border: Color @Composable get() = JewelTheme.globalColors.borders.normal

    // --- Status colors ---
    public val success: Color = Color(0xFF66BB6A)
    public val warning: Color = Color(0xFFFFA726)
    public val error: Color = Color(0xFFEF5350)
    public val info: Color = Color(0xFF64B5F6)

    // --- Selection and hover ---
    public val selectionBackground: Color = Color(0xFF3D5A80)
    public val hoverBackground: Color = Color(0x18FFFFFF)

    // --- Chat-specific ---
    public val userMessageBackground: Color = Color(0xFF2D5B8A)

    // --- Sidebar ---
    public val sidebarBackground: Color = Color(0xFF18181B)
    public val sectionLabel: Color = Color(0xFF71717A)
    public val sidebarText: Color = Color(0xFFD4D4D8)
    public val sidebarTextMuted: Color = Color(0xFFA1A1AA)
    public val sidebarTextDim: Color = Color(0xFF52525B)
    public val sidebarAccent: Color = Color(0xFF60A5FA)

    // --- Muted text variants ---
    public val textMuted: Color = Color(0xFF9E9E9E)
    public val textDim: Color = Color(0xFF757575)
    public val textLight: Color = Color(0xFFBBBBBB)
    public val textDescription: Color = Color(0xFFAAAAAA)

    // --- Status bar ---
    public val statusBarBackground: Color = Color(0xFF1E1E1E)

    // --- Settings dialog ---
    public val dialogScrim: Color = Color(0x80000000)

    // --- Subtle backgrounds ---
    public val subtleBackground: Color = Color(0x10808080)
    public val thinkingBackground: Color = Color(0x15808080)
    public val taskBarBackground: Color = Color(0x08FFFFFF)

    // --- Provider-specific info highlight ---
    public val infoHighlight: Color = Color(0xFFBBDDFF)
}
