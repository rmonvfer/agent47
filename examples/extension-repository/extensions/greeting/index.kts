registerCommand("greet", "Display a greeting") { arguments, context ->
    context.notify("Hello ${arguments.ifBlank { "world" }}")
}

onSessionStart { _, context ->
    context.ui.setStatus("extension-repository", "ready")
}

onSessionShutdown { _, context ->
    context.ui.setStatus("extension-repository", null)
}
