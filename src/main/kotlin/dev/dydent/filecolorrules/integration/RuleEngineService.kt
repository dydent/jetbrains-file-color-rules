package dev.dydent.filecolorrules.integration

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.dydent.filecolorrules.config.AtomicConfigWriter
import dev.dydent.filecolorrules.config.CONFIG_FILE_NAME
import dev.dydent.filecolorrules.config.ConfigException
import dev.dydent.filecolorrules.config.ConfigParser
import dev.dydent.filecolorrules.config.ConfigProblem
import dev.dydent.filecolorrules.config.ConfigSerializer
import dev.dydent.filecolorrules.config.FileColorConfig
import dev.dydent.filecolorrules.engine.CompiledConfig
import dev.dydent.filecolorrules.engine.FileFacts
import dev.dydent.filecolorrules.engine.MatchResult
import dev.dydent.filecolorrules.engine.RuleCompiler
import dev.dydent.filecolorrules.engine.RuleExplanation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.UIManager

data class RuleEngineSnapshot(
    val revision: Long,
    val config: FileColorConfig,
    val source: String,
    val problem: ConfigProblem?,
    val exists: Boolean,
)

private data class EngineState(
    val snapshot: RuleEngineSnapshot,
    val compiled: CompiledConfig,
)

private data class CachedMatch(val result: MatchResult?)

@Service(Service.Level.PROJECT)
class RuleEngineService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val revision = AtomicLong()
    private val rootPath: Path? = project.basePath?.let(Path::of)
    private val configurationPath: Path? = rootPath?.resolve(CONFIG_FILE_NAME)
    private val state = AtomicReference(
        EngineState(
            RuleEngineSnapshot(0, FileColorConfig.empty(), "", null, false),
            CompiledConfig.EMPTY,
        ),
    )
    private val cache: Cache<VirtualFile, CachedMatch> = Caffeine.newBuilder()
        .maximumSize(20_000)
        .build()
    private val refreshLock = Any()
    private var refreshJob: Job? = null
    @Volatile
    private var lastNotifiedProblem: String? = null

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    onVfsEvents(events)
                }
            },
        )
        reloadFromDisk()
    }

    fun snapshot(): RuleEngineSnapshot = state.get().snapshot

    fun configPath(): Path? = configurationPath

    fun colorFor(file: VirtualFile): Color? {
        val facts = factsFor(file) ?: return null
        val match = cache.get(file) { CachedMatch(state.get().compiled.match(facts)) }.result ?: return null
        val light = Color.decode(match.color.light)
        val dark = Color.decode(match.color.dark)
        return com.intellij.ui.JBColor(light, dark)
    }

    fun explain(path: String, isDirectory: Boolean = false): RuleExplanation {
        val normalized = ConfigParser.normalizeConfiguredPath(path)
        val name = normalized.substringAfterLast('/')
        val extension = if (isDirectory) "" else name.substringAfterLast('.', "")
        return state.get().compiled.explain(FileFacts(normalized, name, extension, isDirectory))
    }

    fun relativePath(file: VirtualFile): String? = factsFor(file)?.path

    fun reloadFromDisk() {
        val path = configurationPath ?: return
        coroutineScope.launch(Dispatchers.IO) {
            load(path)
        }
    }

    fun replaceConfig(config: FileColorConfig, onFailure: (String) -> Unit = {}) {
        val path = configurationPath ?: run {
            onFailure("The project has no base directory.")
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val serialized = ConfigSerializer.serialize(config)
                ConfigParser.parse(serialized)
                AtomicConfigWriter.write(path, serialized)
                refreshConfigurationFile()
                load(path)
            } catch (exception: Exception) {
                val message = exception.message ?: "Could not write File Color Rules configuration"
                ApplicationManager.getApplication().invokeLater { onFailure(message) }
            }
        }
    }

    private fun load(path: Path) {
        val exists = Files.exists(path)
        val source = if (exists) {
            val size = Files.size(path)
            if (size > ConfigParser.MAX_BYTES) {
                publishProblem("", true, ConfigProblem("Configuration exceeds the 1 MiB limit"))
                return
            }
            Files.readString(path, StandardCharsets.UTF_8)
        } else {
            ""
        }

        try {
            val config = if (exists) ConfigParser.parse(source) else FileColorConfig.empty()
            val compiled = RuleCompiler.compile(config, SystemInfoRt.isFileSystemCaseSensitive)
            val snapshot = RuleEngineSnapshot(revision.incrementAndGet(), config, source, null, exists)
            state.set(EngineState(snapshot, compiled))
            lastNotifiedProblem = null
            cache.invalidateAll()
            refreshProjectViewSoon()
        } catch (exception: ConfigException) {
            publishProblem(source, exists, exception.problem)
        } catch (exception: Exception) {
            publishProblem(source, exists, ConfigProblem(exception.message ?: "Could not load configuration"))
        }
    }

    private fun publishProblem(source: String, exists: Boolean, problem: ConfigProblem) {
        val previous = state.get()
        state.set(
            EngineState(
                RuleEngineSnapshot(
                    revision.incrementAndGet(),
                    previous.snapshot.config,
                    source,
                    problem,
                    exists,
                ),
                previous.compiled,
            ),
        )
        if (lastNotifiedProblem != problem.toString()) {
            lastNotifiedProblem = problem.toString()
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("File Color Rules")
                        .createNotification(problem.toString(), NotificationType.ERROR)
                        .notify(project)
                }
            }
        }
    }

    private fun onVfsEvents(events: List<VFileEvent>) {
        val configPathString = configurationPath?.toString() ?: return
        val root = rootPath?.toString()?.trimEnd('/') ?: return
        var configurationChanged = false
        var structureChanged = false
        for (event in events) {
            val path = event.path
            if (path == configPathString || path.endsWith("/$CONFIG_FILE_NAME") && path.startsWith(root)) {
                configurationChanged = true
            } else if (path == root || path.startsWith("$root/")) {
                if (event !is VFileContentChangeEvent) structureChanged = true
            }
        }
        if (structureChanged) {
            cache.invalidateAll()
            refreshProjectViewSoon()
        }
        if (configurationChanged) reloadFromDisk()
    }

    private fun factsFor(file: VirtualFile): FileFacts? {
        if (!file.isValid || file.fileSystem.protocol != "file") return null
        val root = rootPath?.toString()?.replace('\\', '/')?.trimEnd('/') ?: return null
        val path = file.path.replace('\\', '/')
        val relative = when {
            path == root -> "."
            path.startsWith("$root/") -> path.removePrefix("$root/")
            else -> return null
        }
        return FileFacts(
            path = relative,
            name = file.name,
            extension = if (file.isDirectory) "" else file.extension.orEmpty(),
            isDirectory = file.isDirectory,
        )
    }

    private fun refreshProjectViewSoon() {
        synchronized(refreshLock) {
            refreshJob?.cancel()
            refreshJob = coroutineScope.launch {
                delay(100)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) ProjectView.getInstance(project).refresh()
                }
            }
        }
    }

    private fun refreshConfigurationFile() {
        val path = configurationPath ?: return
        VirtualFileManager.getInstance().asyncRefresh {
            VirtualFileManager.getInstance().findFileByNioPath(path)?.refresh(false, false)
        }
    }

    override fun dispose() {
        cache.invalidateAll()
        synchronized(refreshLock) {
            refreshJob?.cancel()
        }
    }

    companion object {
        fun getInstance(project: Project): RuleEngineService = project.service()
    }
}
