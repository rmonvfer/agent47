package com.jakewharton.mosaic;

import com.jakewharton.mosaic.layout.KeyEvent;
import com.jakewharton.mosaic.terminal.KeyboardEvent;

/**
 * Patched replacement for Mosaic's compat.kt.
 *
 * Mosaic 0.18.0's toKeyEventOrNull throws UnsupportedOperationException for
 * any keyboard codepoint outside ASCII 32-126 and the known special keys.
 * This breaks input of non-ASCII characters like ñ (codepoint 241), é, ü, etc.
 *
 * This class shadows Mosaic's CompatKt on the classpath (project classes load
 * before dependency JARs) and handles non-ASCII Unicode gracefully by converting
 * the codepoint to its string representation instead of throwing.
 *
 * Remove this file when Mosaic fixes the bug upstream.
 */
public final class CompatKt {

    private CompatKt() {
    }

    public static final KeyEvent toKeyEventOrNull(KeyboardEvent event) {
        if (event.getEventType() != 1) {
            return null;
        }

        int codepoint = event.getCodepoint();
        String key;

        if (codepoint == 9) {
            key = "Tab";
        } else if (codepoint == 13) {
            key = "Enter";
        } else if (codepoint == 27) {
            key = "Escape";
        } else if (codepoint >= 32 && codepoint < 127) {
            key = String.valueOf((char) codepoint);
        } else if (codepoint == 127) {
            key = "Backspace";
        } else if (codepoint == 57350) {
            key = "ArrowLeft";
        } else if (codepoint == 57351) {
            key = "ArrowRight";
        } else if (codepoint == 57352) {
            key = "ArrowUp";
        } else if (codepoint == 57353) {
            key = "ArrowDown";
        } else if (codepoint == 57348) {
            key = "Insert";
        } else if (codepoint == 57349) {
            key = "Delete";
        } else if (codepoint == 57354) {
            key = "PageUp";
        } else if (codepoint == 57355) {
            key = "PageDown";
        } else if (codepoint == 57356) {
            key = "Home";
        } else if (codepoint == 57357) {
            key = "End";
        } else if (codepoint >= 57364 && codepoint < 57399) {
            key = "F" + (codepoint - 57363);
        } else if (codepoint > 0 && Character.isValidCodePoint(codepoint)) {
            // Non-ASCII Unicode characters (ñ, é, ü, CJK, emoji, etc.)
            key = new String(Character.toChars(codepoint));
        } else {
            return null;
        }

        return new KeyEvent(key, event.getAlt(), event.getCtrl(), event.getShift());
    }
}
