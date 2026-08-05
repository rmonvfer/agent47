package co.agentmode.agent47.ui.core.util

/**
 * Deterministically maps [name] to one of [paletteSize] slots: the same name always lands on the
 * same slot (stable across runs, JVMs, and process restarts — `String.hashCode()` is specified by
 * the Java platform, not merely "usually stable"), while different names spread across the
 * palette. Framework-agnostic on purpose: the caller supplies its own palette (e.g. a small set of
 * theme colors) and applies the returned index to it.
 */
public fun identityPaletteIndex(name: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "paletteSize must be positive, got $paletteSize" }
    return Math.floorMod(name.hashCode(), paletteSize)
}
