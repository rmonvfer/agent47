package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import java.nio.file.Path

public data class CoreTools(
    val read: ReadTool?,
    val write: WriteTool?,
    val edit: EditTool?,
    val multiEdit: MultiEditTool?,
    val bash: BashTool?,
    val grep: GrepTool?,
    val find: FindTool?,
    val ls: LsTool?,
    val todoWrite: TodoWriteTool?,
    val todoRead: TodoReadTool?,
    val todoCreate: TodoCreateTool?,
    val todoUpdate: TodoUpdateTool?,
    val batch: BatchTool?,
) {
    public fun all(): List<AgentTool<*>> = listOfNotNull(
        read, write, edit, multiEdit, bash, grep, find, ls,
        todoWrite, todoRead, todoCreate, todoUpdate, batch,
    )
}

public fun createCoreTools(
    cwd: Path,
    enabled: List<String> = DEFAULT_TOOLS,
    skillReader: SkillReader? = null,
    todoState: TodoState? = null,
    commandPrefix: String? = null,
): CoreTools {
    val enabledSet = enabled.toSet()
    val sharedTodoState = todoState ?: TodoState()
    val todoEnabled = "todowrite" in enabledSet || "todoread" in enabledSet
            || "todocreate" in enabledSet || "todoupdate" in enabledSet
    val resolvedTodoState = if (todoEnabled) sharedTodoState else null
    val tools = CoreTools(
        read = if ("read" in enabledSet) ReadTool(cwd, skillReader) else null,
        write = if ("write" in enabledSet) WriteTool(cwd) else null,
        edit = if ("edit" in enabledSet) EditTool(cwd) else null,
        multiEdit = if ("multiedit" in enabledSet) MultiEditTool(cwd) else null,
        bash = if ("bash" in enabledSet) BashTool(cwd, commandPrefix) else null,
        grep = if ("grep" in enabledSet) GrepTool(cwd) else null,
        find = if ("find" in enabledSet) FindTool(cwd) else null,
        ls = if ("ls" in enabledSet) LsTool(cwd) else null,
        todoWrite = if ("todowrite" in enabledSet) TodoWriteTool(resolvedTodoState!!) else null,
        todoRead = if ("todoread" in enabledSet) TodoReadTool(resolvedTodoState!!) else null,
        todoCreate = if ("todocreate" in enabledSet) TodoCreateTool(resolvedTodoState!!) else null,
        todoUpdate = if ("todoupdate" in enabledSet) TodoUpdateTool(resolvedTodoState!!) else null,
        batch = null,
    )

    // BatchTool needs a reference to all other tools, so it's created after them.
    val batchTool = if ("batch" in enabledSet) {
        val toolMap = tools.all().associateBy { it.label }
        BatchTool(toolMap)
    } else {
        null
    }

    return tools.copy(batch = batchTool)
}

public val DEFAULT_TOOLS: List<String> = listOf(
    "read", "bash", "edit", "write", "multiedit",
    "todowrite", "todoread", "todocreate", "todoupdate", "batch",
)
