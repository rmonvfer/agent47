package co.agentmode.agent47.ui.core.util

public data class FuzzyResult(val index: Int, val score: Int, val matchedPositions: List<Int>)

public fun fuzzyMatch(label: String, query: String): FuzzyResult? {
    if (query.isBlank()) return FuzzyResult(0, 0, emptyList())
    val q = query.trim().lowercase()
    val l = label.lowercase()
    val positions = mutableListOf<Int>()
    var qi = 0
    for (li in l.indices) {
        if (qi < q.length && l[li] == q[qi]) {
            positions.add(li)
            qi++
        }
    }
    if (qi < q.length) return null
    var score = positions.first()
    for (i in 1 until positions.size) {
        score += (positions[i] - positions[i - 1] - 1)
    }
    return FuzzyResult(0, score, positions)
}
