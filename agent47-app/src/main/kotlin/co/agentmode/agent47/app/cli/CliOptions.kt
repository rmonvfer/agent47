package co.agentmode.agent47.app.cli

import java.nio.file.Path

internal data class CliOptions(
    val provider: String?,
    val model: String?,
    val apiKey: String?,
    val systemPrompt: String?,
    val appendSystemPrompt: String?,
    val thinking: String?,
    val printMode: Boolean,
    val continueSession: Boolean,
    val resume: String?,
    val session: Path?,
    val sessionDir: Path?,
    val noSession: Boolean,
    val models: String?,
    val tools: String?,
    val noTools: Boolean,
    val extensionPaths: List<Path>,
    val noExtensions: Boolean,
    val extensionFlags: List<String>,
    val listExtensions: Boolean,
    val listModels: Boolean,
    val listModelsSearch: String?,
    val showVersion: Boolean,
    val promptArgs: List<String>,
)
