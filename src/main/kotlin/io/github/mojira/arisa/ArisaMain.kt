package io.github.mojira.arisa

import arrow.core.Either
import arrow.core.left
import arrow.syntax.function.partially1
import arrow.syntax.function.partially2
import com.uchuhimo.konf.Config
import io.github.mojira.arisa.infrastructure.addAffectedVersion
import io.github.mojira.arisa.infrastructure.addComment
import io.github.mojira.arisa.infrastructure.config.Arisa
import io.github.mojira.arisa.infrastructure.connectToJira
import io.github.mojira.arisa.infrastructure.deleteAttachment
import io.github.mojira.arisa.infrastructure.getGroups
import io.github.mojira.arisa.infrastructure.link
import io.github.mojira.arisa.infrastructure.removeAffectedVersion
import io.github.mojira.arisa.infrastructure.reopenIssue
import io.github.mojira.arisa.infrastructure.resolveAs
import io.github.mojira.arisa.infrastructure.restrictCommentToGroup
import io.github.mojira.arisa.infrastructure.updateCHK
import io.github.mojira.arisa.infrastructure.updateCommentBody
import io.github.mojira.arisa.infrastructure.updateConfirmation
import io.github.mojira.arisa.infrastructure.updateSecurity
import io.github.mojira.arisa.modules.AttachmentModule
import io.github.mojira.arisa.modules.AttachmentModuleRequest
import io.github.mojira.arisa.modules.CHKModule
import io.github.mojira.arisa.modules.CHKModuleRequest
import io.github.mojira.arisa.modules.CrashModule
import io.github.mojira.arisa.modules.CrashModuleRequest
import io.github.mojira.arisa.modules.EmptyModule
import io.github.mojira.arisa.modules.EmptyModuleRequest
import io.github.mojira.arisa.modules.FailedModuleResponse
import io.github.mojira.arisa.modules.FutureVersionModule
import io.github.mojira.arisa.modules.FutureVersionModuleRequest
import io.github.mojira.arisa.modules.HideImpostorsModule
import io.github.mojira.arisa.modules.HideImpostorsModuleRequest
import io.github.mojira.arisa.modules.KeepPrivateModule
import io.github.mojira.arisa.modules.KeepPrivateModuleRequest
import io.github.mojira.arisa.modules.ModuleError
import io.github.mojira.arisa.modules.ModuleResponse
import io.github.mojira.arisa.modules.OperationNotNeededModuleResponse
import io.github.mojira.arisa.modules.PiracyModule
import io.github.mojira.arisa.modules.PiracyModuleRequest
import io.github.mojira.arisa.modules.RemoveNonStaffMeqsModule
import io.github.mojira.arisa.modules.RemoveNonStaffMeqsModuleRequest
import io.github.mojira.arisa.modules.RemoveTriagedMeqsModule
import io.github.mojira.arisa.modules.RemoveTriagedMeqsModuleRequest
import io.github.mojira.arisa.modules.ReopenAwaitingModule
import io.github.mojira.arisa.modules.ReopenAwaitingModuleRequest
import io.github.mojira.arisa.modules.RevokeConfirmationModule
import io.github.mojira.arisa.modules.RevokeConfirmationModuleRequest
import net.rcarz.jiraclient.Issue
import net.rcarz.jiraclient.JiraClient
import net.sf.json.JSONObject
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

val log = LoggerFactory.getLogger("Arisa")

fun main() {
    val cachedTickets = mutableSetOf<String>()
    val cacheTimer = Timer("RemoveCachedTicket", true)
    val config = Config { addSpec(Arisa) }
        .from.json.watchFile("arisa.json")
        .from.env()
        .from.systemProperties()

    val jiraClient =
        connectToJira(
            config[Arisa.Credentials.username],
            config[Arisa.Credentials.password],
            config[Arisa.Issues.url]
        )

    log.info("Connected to jira")

    val executeModules = initModules(config, jiraClient)
    while (true) {
        val resolutions = listOf("Unresolved", "\"Awaiting Response\"").joinToString(", ")
        val projects = config[Arisa.Issues.projects].joinToString(", ")
        val jql = "project in ($projects) AND resolution in ($resolutions) AND updated >= -5m"

        try {
            jiraClient
                .searchIssues(jql)
                .issues
                .filter { !cachedTickets.contains(it.key) }
                .map { it.key to executeModules(it) }
                .forEach { (issue, responses) ->
                    var successfulModule = false
                    responses.forEach { (module, response) ->
                        when (response) {
                            is Either.Right -> {
                                successfulModule = true
                                log.info("[RESPONSE] [$issue] [$module] Successful")
                            }
                            is Either.Left -> {
                                when (response.a) {
                                    is OperationNotNeededModuleResponse -> log.info("[RESPONSE] [$issue] [$module] Operation not needed")
                                    is FailedModuleResponse -> for (exception in (response.a as FailedModuleResponse).exceptions) {
                                        log.error("[RESPONSE] [$issue] [$module] Failed", exception)
                                    }
                                }
                            }
                        }
                    }
                    if (!successfulModule) {
                        // cache
                        cachedTickets.add(issue)
                        cacheTimer.schedule(290_000) { cachedTickets.remove(issue) }
                    }
                }
        } catch (e: Exception) {
            log.error("Failed to get issues", e)
            continue
        }

        TimeUnit.SECONDS.sleep(config[Arisa.Issues.checkInterval])
    }
}

fun initModules(config: Config, jiraClient: JiraClient): (Issue) -> Map<String, Either<ModuleError, ModuleResponse>> =
    lambda@{ updateIssue: Issue ->
        // Get issue again to retrieve all fields
        val issue = jiraClient.getIssue(updateIssue.key, "*all", "changelog")

        // Ignore issues where last action was a resolve
        val latestChange = issue.changeLog.entries.lastOrNull()
        if (
            latestChange != null && // There is actually a entry
            latestChange.items.any { it.field == "resolution" } && // It was a transition
            latestChange.author.name != config[Arisa.Credentials.username] && // The transition was not done by the bot
            (issue.comments.isEmpty() || issue.comments.last().updatedDate < latestChange.created) // And there is no comment posted after that
        ) {
            return@lambda emptyMap() // Ignore ticket
        }

        val attachmentModule = AttachmentModule(
            ::deleteAttachment.partially1(jiraClient),
            config[Arisa.Modules.Attachment.extensionBlacklist]
        )
        val chkModule = CHKModule(
            ::updateCHK.partially1(issue).partially1(config[Arisa.CustomFields.chkField])
        )
        val reopenAwaitingModule = ReopenAwaitingModule(
            ::reopenIssue.partially1(issue)
        )
        val piracyModule = PiracyModule(
            ::resolveAs.partially1(issue).partially1("Invalid"),
            ::addComment.partially1(issue).partially1(config[Arisa.Modules.Piracy.piracyMessage]),
            config[Arisa.Modules.Piracy.piracySignatures]
        )
        val removeTriagedMeqsModule = RemoveTriagedMeqsModule(
            ::updateCommentBody,
            config[Arisa.Modules.RemoveTriagedMeqs.meqsTags],
            config[Arisa.Modules.RemoveTriagedMeqs.removalReason]
        )
        val futureVersionModule = FutureVersionModule(
            ::removeAffectedVersion.partially1(issue),
            ::addAffectedVersion.partially1(issue),
            ::addComment.partially1(issue).partially1(config[Arisa.Modules.FutureVersion.futureVersionMessage])
        )
        val removeNonStaffMeqsModule = RemoveNonStaffMeqsModule(
            ::restrictCommentToGroup.partially2("staff"),
            config[Arisa.Modules.RemoveNonStaffMeqs.removalReason]
        )
        val emptyModule = EmptyModule(
            ::resolveAs.partially1(issue).partially1("Incomplete"),
            ::addComment.partially1(issue).partially1(config[Arisa.Modules.Empty.emptyMessage])
        )
        val crashModule = CrashModule(
            ::resolveAs.partially1(issue).partially1("Invalid"),
            ::resolveAs.partially1(issue).partially1("Duplicate"),
            ::link.partially1(issue).partially1("Duplicate"),
            ::addComment.partially1(issue).partially1(config[Arisa.Modules.Crash.moddedMessage]),
            { key -> addComment(issue, config[Arisa.Modules.Crash.duplicateMessage].replace("{DUPLICATE}", key)) },
            config[Arisa.Modules.Crash.crashExtensions],
            config[Arisa.Modules.Crash.duplicates],
            config[Arisa.Modules.Crash.maxAttachmentAge]
        )
        val revokeConfirmationModule = RevokeConfirmationModule(
            ::getGroups.partially1(jiraClient),
            ::updateConfirmation.partially1(issue).partially1(config[Arisa.CustomFields.confirmationField])
        )
        val keepPrivateModule = KeepPrivateModule(
            ::updateSecurity.partially1(issue),
            ::addComment.partially1(issue).partially1(config[Arisa.Modules.KeepPrivate.keepPrivateMessage]),
            config[Arisa.Modules.KeepPrivate.tag]
        )

        val hideImpostorsModule = HideImpostorsModule(
            ::getGroups.partially1(jiraClient),
            ::restrictCommentToGroup.partially2("staff")
        )

        // issue.project doesn't contain full project, which is needed for some modules.
        val project = try {
            jiraClient.getProject(issue.project.key)
        } catch (e: Exception) {
            log.error("Failed to get project of issue", e)
            null
        }

        mapOf(
            "Attachment" to runIfWhitelisted(issue, config[Arisa.Modules.Attachment.whitelist]) {
                attachmentModule(AttachmentModuleRequest(issue.attachments))
            },
            "CHK" to runIfWhitelisted(issue, config[Arisa.Modules.CHK.whitelist]) {
                chkModule(
                    CHKModuleRequest(
                        issue.key,
                        issue.getField(config[Arisa.CustomFields.chkField]) as? String?,
                        ((issue.getField(config[Arisa.CustomFields.confirmationField])) as? JSONObject)?.get("value") as? String?
                    )
                )
            },
            "ReopenAwaiting" to runIfWhitelisted(issue, config[Arisa.Modules.ReopenAwaiting.whitelist]) {
                reopenAwaitingModule(
                    ReopenAwaitingModuleRequest(
                        issue.resolution,
                        (issue.getField("created") as String).toInstant(),
                        (issue.getField("updated") as String).toInstant(),
                        issue.comments
                    )
                )
            },
            "Piracy" to runIfWhitelisted(issue, config[Arisa.Modules.Piracy.whitelist]) {
                piracyModule(
                    PiracyModuleRequest(
                        issue.getField("environment").toNullableString(),
                        issue.summary,
                        issue.description
                    )
                )
            },
            "RemoveTriagedMeqs" to runIfWhitelisted(issue, config[Arisa.Modules.RemoveTriagedMeqs.whitelist]) {
                removeTriagedMeqsModule(
                    RemoveTriagedMeqsModuleRequest(
                        ((issue.getField(config[Arisa.CustomFields.mojangPriorityField])) as? JSONObject)?.get("value") as? String?,
                        issue.getField(config[Arisa.CustomFields.triagedTimeField]) as? String?,
                        issue.comments
                    )
                )
            },
            "FutureVersion" to runIfWhitelisted(issue, config[Arisa.Modules.FutureVersion.whitelist]) {
                futureVersionModule(
                    FutureVersionModuleRequest(
                        issue.versions,
                        project?.versions
                    )
                )
            },
            "RemoveNonStaffMeqs" to runIfWhitelisted(issue, config[Arisa.Modules.RemoveNonStaffMeqs.whitelist]) {
                removeNonStaffMeqsModule(
                    RemoveNonStaffMeqsModuleRequest(issue.comments)
                )
            },
            "Empty" to runIfWhitelisted(issue, config[Arisa.Modules.Empty.whitelist]) {
                emptyModule(
                    EmptyModuleRequest(
                        issue.attachments.size,
                        issue.description,
                        issue.getField("environment") as? String?
                    )
                )
            },
            "Crash" to runIfWhitelisted(issue, config[Arisa.Modules.Crash.whitelist]) {
                crashModule(
                    CrashModuleRequest(
                        issue.attachments,
                        issue.description,
                        issue.createdDate
                    )
                )
            },
            "RevokeConfirmation" to runIfWhitelisted(issue, config[Arisa.Modules.RevokeConfirmation.whitelist]) {
                revokeConfirmationModule(
                    RevokeConfirmationModuleRequest(
                        ((issue.getField(config[Arisa.CustomFields.confirmationField])) as? JSONObject)?.get("value") as? String?
                            ?: "Unconfirmed",
                        issue.changeLog.entries

                    )
                )
            },
            "KeepPrivate" to runIfWhitelisted(issue, config[Arisa.Modules.KeepPrivate.whitelist]) {
                keepPrivateModule(
                    KeepPrivateModuleRequest(
                        issue.security?.id,
                        config[Arisa.PrivateSecurityLevel.special].getOrDefault(
                            project?.key,
                            config[Arisa.PrivateSecurityLevel.default]
                        ),
                        issue.comments
                    )
                )
            },
            "HideImpostors" to runIfWhitelisted(issue, config[Arisa.Modules.HideImpostors.whitelist]) {
                hideImpostorsModule(
                    HideImpostorsModuleRequest(issue.comments)
                )
            }
        )
    }

private fun Any?.toNullableString(): String? = if (this is String) {
    this
} else {
    null
}

val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
private fun String.toInstant() = isoFormat.parse(this).toInstant()

private fun runIfWhitelisted(issue: Issue, projects: List<String>, body: () -> Either<ModuleError, ModuleResponse>) =
    if (isWhitelisted(projects, issue)) {
        body()
    } else {
        OperationNotNeededModuleResponse.left()
    }

fun isWhitelisted(projects: List<String>, issue: Issue) = projects.contains(issue.project.key)
