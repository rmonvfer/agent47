registerFlag("strict-review", "Request a strict code review")

registerCommand("repository-review", "Ask the agent to review the current repository") { _, context ->
    val strict = getFlag("strict-review") == "true"
    val focus = if (strict) {
        "Treat warnings as actionable findings."
    } else {
        "Prioritize correctness and user-visible regressions."
    }
    context.sendUserMessage("Review the current repository. $focus")
}
