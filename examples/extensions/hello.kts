beforeAgent { messages -> messages }

registerCommand("hello", "Display a greeting") { args, context ->
    context.notify("Hello ${args.ifBlank { "world" }}")
}
