package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.coding.core.agents.AgentSource
import co.agentmode.agent47.coding.core.agents.RunningAgent
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.ui.core.state.SelectItem
import kotlinx.coroutines.launch

internal fun OverlayNavigator.openAgentsOverlay() {
    val menu = buildList {
        val runningCount = backgroundAgents?.runningStatus()?.size ?: 0
        add(SelectItem("Running agents ($runningCount)", "running"))
        agentRegistry?.let { add(SelectItem("Agent types (${it.getAll().size})", "types")) }
        add(SelectItem("Settings", "settings"))
        if (scheduler?.isActive() == true) {
            add(SelectItem("Scheduled jobs (${scheduler.list().size})", "scheduled"))
        }
    }
    overlays.push(
        title = "Agents",
        items = menu,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { choice ->
            when (choice) {
                "running" -> openRunningAgentsOverlay()
                "types" -> openAgentTypesOverlay()
                "settings" -> openSubagentSettingsOverlay()
                "scheduled" -> openScheduledJobsOverlay()
            }
        },
    )
}

private fun OverlayNavigator.openRunningAgentsOverlay() {
    val bg = backgroundAgents
    if (bg == null) {
        feed.appendCommandResult("Background agents are unavailable.")
        return
    }
    val agents = bg.runningStatus()
    if (agents.isEmpty()) {
        feed.appendCommandResult("No background agents are running.")
        return
    }
    val options = agents.map { agent ->
        val activity = when {
            agent.status == RunningAgent.Status.QUEUED -> "queued"
            agent.progress?.currentTool != null -> "running ${agent.progress?.currentTool}"
            else -> "working"
        }
        SelectItem(label = "${agent.id} (${agent.agentName}) · $activity", value = agent.id)
    }
    overlays.push(
        title = "Background agents (${agents.size})",
        items = options,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { id -> openAgentActionsOverlay(id) },
    )
}

private fun OverlayNavigator.openAgentActionsOverlay(id: String) {
    if (backgroundAgents == null) return
    val actions = listOf(
        SelectItem(label = "View transcript", value = "view"),
        SelectItem(label = "Steer (send a message)", value = "steer"),
        SelectItem(label = "Stop", value = "stop"),
    )
    overlays.push(
        title = "Agent $id",
        items = actions,
        selectedIndex = 0,
        onSubmit = { action ->
            when (action) {
                "view" -> {
                    // Enter focus mode: the main chat area renders the agent's live transcript.
                    state.viewingAgentId = id
                    overlays.clear()
                }
                "steer" -> {
                    overlays.pushPrompt(
                        title = "Steer $id",
                        placeholder = "Message to inject into the running agent",
                        onSubmit = { msg ->
                            if (msg.isNotBlank()) {
                                agentPanel.steer(id, msg)
                            }
                        },
                    )
                }
                "stop" -> {
                    agentPanel.stop(id)
                }
            }
        },
    )
}

private fun OverlayNavigator.openAgentTypesOverlay() {
    val registry = agentRegistry
    if (registry == null) {
        feed.appendCommandResult("Agent registry is unavailable.")
        return
    }
    val all = registry.getAll()
    val options = all.map { def ->
        val flag = when (def.source) {
            AgentSource.PROJECT -> "•"
            AgentSource.USER -> "◦"
            AgentSource.BUNDLED -> " "
        }
        val disabled = if (!def.enabled) " ✕" else ""
        SelectItem(label = "$flag ${def.label}$disabled — ${def.description}", value = def.name)
    }
    overlays.push(
        title = "Agent types (${all.size})",
        items = options,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { name ->
            val def = registry.getAll().firstOrNull { it.name == name } ?: return@push
            overlays.pushInfo(
                title = def.label,
                lines = buildList {
                    add(def.description)
                    add("")
                    add("name: ${def.name}")
                    add("source: ${def.source}")
                    add("enabled: ${def.enabled}")
                    add("promptMode: ${def.promptMode}")
                    def.model?.let { add("model: ${it.joinToString(", ")}") }
                    def.thinkingLevel?.let { add("thinking: $it") }
                    add("tools: ${def.tools?.joinToString(", ") ?: "all"}")
                    def.memory?.let { add("memory: $it") }
                    def.isolation?.let { add("isolation: $it") }
                    def.skills?.let { add("skills: ${it.joinToString(", ")}") }
                },
            )
        },
    )
}

private fun OverlayNavigator.openSubagentSettingsOverlay() {
    fun onOff(b: Boolean) = if (b) "on" else "off"
    val s = state.subagentsSettings
    val items = listOf(
        SelectItem("Max concurrency: ${s.maxConcurrent}", "maxConcurrent"),
        SelectItem("Default max turns: ${s.defaultMaxTurns} (0 = unlimited)", "defaultMaxTurns"),
        SelectItem("Grace turns: ${s.graceTurns}", "graceTurns"),
        SelectItem("Join mode: ${s.defaultJoinMode}", "defaultJoinMode"),
        SelectItem("Scheduling: ${onOff(s.schedulingEnabled)}", "schedulingEnabled"),
        SelectItem("Disable default agents: ${onOff(s.disableDefaultAgents)}", "disableDefaultAgents"),
        SelectItem("Output transcript: ${onOff(s.outputTranscript)}", "outputTranscript"),
        SelectItem("Fleet view: ${onOff(s.fleetView)}", "fleetView"),
        SelectItem("Widget: ${s.widgetMode}", "widgetMode"),
        SelectItem("Push notifications: ${onOff(s.pushNotifications)}", "pushNotifications"),
        SelectItem("Tool description: ${s.toolDescriptionMode}", "toolDescriptionMode"),
    )
    overlays.push(
        title = "Subagent settings",
        items = items,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { key -> editSubagentSetting(key) },
    )
}

@Suppress("CyclomaticComplexMethod")
private fun OverlayNavigator.editSubagentSetting(key: String) {
    val cur = state.subagentsSettings
    fun promptInt(title: String, current: Int, min: Int, max: Int, apply: (Int) -> SubagentsSettings) {
        overlays.pushPrompt(
            title = title,
            placeholder = current.toString(),
            onSubmit = { v ->
                v.trim().toIntOrNull()?.let {
                    agentPanel.applySubagentsSettings(apply(it.coerceIn(min, max)))
                }
            },
        )
    }
    fun choose(title: String, values: List<String>, apply: (String) -> SubagentsSettings) {
        overlays.push(
            title = title,
            items = values.map { SelectItem(it, it) },
            selectedIndex = 0,
            onSubmit = { agentPanel.applySubagentsSettings(apply(it)) },
        )
    }
    fun toggle(title: String, apply: (Boolean) -> SubagentsSettings) {
        overlays.push(
            title = title,
            items = listOf(SelectItem("on", true), SelectItem("off", false)),
            selectedIndex = 0,
            onSubmit = { agentPanel.applySubagentsSettings(apply(it)) },
        )
    }
    when (key) {
        "maxConcurrent" -> promptInt("Max concurrency (1–1024)", cur.maxConcurrent, 1, 1024) { cur.copy(maxConcurrent = it) }
        "defaultMaxTurns" -> promptInt("Default max turns (0 = unlimited)", cur.defaultMaxTurns, 0, 10_000) { cur.copy(defaultMaxTurns = it) }
        "graceTurns" -> promptInt("Grace turns (1–1000)", cur.graceTurns, 1, 1_000) { cur.copy(graceTurns = it) }
        "defaultJoinMode" -> choose("Join mode", listOf("smart", "async", "group")) { cur.copy(defaultJoinMode = it) }
        "widgetMode" -> choose("Widget", listOf("background", "all", "off")) { cur.copy(widgetMode = it) }
        "toolDescriptionMode" -> choose("Tool description", listOf("full", "compact", "custom")) { cur.copy(toolDescriptionMode = it) }
        "schedulingEnabled" -> toggle("Scheduling") { cur.copy(schedulingEnabled = it) }
        "disableDefaultAgents" -> toggle("Disable default agents") { cur.copy(disableDefaultAgents = it) }
        "outputTranscript" -> toggle("Output transcript") { cur.copy(outputTranscript = it) }
        "fleetView" -> toggle("Fleet view") { cur.copy(fleetView = it) }
        "pushNotifications" -> toggle("Push notifications") { cur.copy(pushNotifications = it) }
    }
}

private fun OverlayNavigator.openScheduledJobsOverlay() {
    val sched = scheduler
    if (sched == null || !sched.isActive()) {
        feed.appendCommandResult("Scheduling is not active.")
        return
    }
    val jobs = sched.list()
    if (jobs.isEmpty()) {
        feed.appendCommandResult("No scheduled jobs.")
        return
    }
    val options = jobs.map { job ->
        val disabledLabel = if (job.enabled) "" else " (disabled)"
        SelectItem(
            label = "${job.name} · ${job.schedule} [${job.scheduleType}] · runs ${job.runCount}$disabledLabel",
            value = job.id,
        )
    }
    overlays.push(
        title = "Scheduled jobs (${jobs.size})",
        items = options,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { id ->
            overlays.push(
                title = "Cancel this job?",
                items = listOf(SelectItem("Cancel job", true), SelectItem("Keep", false)),
                selectedIndex = 1,
                onSubmit = { cancel ->
                    if (cancel) {
                        scope.launch {
                            sched.removeJob(id)
                            feed.appendCommandResult("Cancelled scheduled job.")
                        }
                    }
                },
            )
        },
    )
}
