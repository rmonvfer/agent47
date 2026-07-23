import co.agentmode.agent47.ext.core.CompactionHookResult
import co.agentmode.agent47.ext.core.InputHookResult
import co.agentmode.agent47.ext.core.MessageRenderer
import co.agentmode.agent47.ext.core.ToolCallHookResult
import co.agentmode.agent47.ext.core.ToolRenderer
import co.agentmode.agent47.ext.core.ToolResultHookResult

beforeAgent { it }
afterAgent { }
transformContext { it }
on("*") { _, _ -> }
beforeCompaction { _, _ -> CompactionHookResult() }
afterCompaction { _, _ -> }
onToolCall { _, _ -> ToolCallHookResult() }
onToolResult { _, _ -> ToolResultHookResult() }
onInput { _, _ -> InputHookResult.Continue }
onSessionStart { _, _ -> }
onSessionShutdown { _, _ -> }
registerShortcut("ctrl+g", "Example shortcut") { }
registerToolRenderer("example", ToolRenderer { _, _ -> listOf("tool") })
registerMessageRenderer("example", MessageRenderer { _, _ -> listOf("message") })
registerFlag("example", "Example flag")
registerCommand("example", "Example command") { _, _ -> }
