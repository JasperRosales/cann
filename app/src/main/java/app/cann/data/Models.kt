package app.cann.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Settings(val apiKey: String = "", val userId: String = "default")

/**
 * One Composio tool call. [args] is a JSON object; strings may hold {{templates}}.
 * [icon] is an image URL or "mi:<name>" for a built-in glyph; null means the app logo.
 */
@Serializable
data class Step(
    val toolSlug: String, val args: String = "{}", val note: String = "", val version: String? = null,
    val toolkit: String = "", val icon: String? = null, val logo: String? = null,
    /** Required argument names, captured from the tool schema when the node is added. */
    val required: List<String> = emptyList(),
) {
    val app: String get() = toolkit.ifBlank { toolSlug.substringBefore('_').lowercase() }
}

@Serializable
sealed interface Schedule {
    /** Only runs when you tap Run. */
    @Serializable @SerialName("manual") data object Manual : Schedule

    /** Runs once at [atMillis], then disables itself. */
    @Serializable @SerialName("once") data class Once(val atMillis: Long) : Schedule

    /** Every [minutes] minutes (Android enforces at least 15). */
    @Serializable @SerialName("interval") data class Interval(val minutes: Int) : Schedule

    /** Every day at [hour]:[minute]; [days] are 1=Mon..7=Sun, empty means every day. */
    @Serializable @SerialName("daily") data class Daily(
        val hour: Int, val minute: Int, val days: Set<Int> = emptySet(),
    ) : Schedule
}

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val schedule: Schedule = Schedule.Manual,
    val enabled: Boolean = true,
    val stopOnError: Boolean = true,
    val nextRunAt: Long? = null,
)

@Serializable
data class StepResult(
    val toolSlug: String, val ok: Boolean, val output: String,
    /** Placeholders that resolved to nothing, a common cause of failures. */
    val unresolved: List<String> = emptyList(),
)

@Serializable
data class Run(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val startedAt: Long,
    val endedAt: Long,
    val ok: Boolean,
    val steps: List<StepResult>,
)

@Serializable
data class Db(
    val settings: Settings = Settings(),
    val workflows: List<Workflow> = emptyList(),
    val runs: List<Run> = emptyList(),
    /** Starred toolkit slugs. */
    val favApps: Set<String> = emptySet(),
    /** User-made workflow templates. */
    val templates: List<Template> = emptyList(),
)

/** A reusable starting point for a workflow. [flow] ids are replaced on use. */
@Serializable
data class Template(
    val id: String, val name: String, val description: String = "",
    val flow: Workflow, val builtIn: Boolean = false,
)
