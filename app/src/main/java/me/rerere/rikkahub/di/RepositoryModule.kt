package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.recommend.RecommendIndexRepository
import me.rerere.rikkahub.data.recommend.RecommendInstaller
import me.rerere.rikkahub.data.recommend.RecommendSourceStore
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceResidentShellRunner
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        // AI 命令打进驻留的「AI」终端会话，省掉每条命令一次 proot 冷启动（1~3 秒）；
        // 会话拿不到时退回原来的 proot 行为，见 WorkspaceResidentShellRunner 的类注释
        val prootShellRunner = ProotShellRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
        WorkspaceManager(
            baseDir = File(context.filesDir, WorkspaceManager.BASE_DIR_NAME),
            // socket 短路径绑定用 (见 WorkspaceRuntimeBinds), cacheDir 宿主路径最短
            runtimeBaseDir = context.cacheDir,
            shellRunner = WorkspaceResidentShellRunner(
                sessionManager = get(),
                fallback = prootShellRunner,
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    // Air-Ko 插件：filesDir/plugins 下的 JSON 提示词包，启用状态存 Settings
    single {
        PluginManager(
            context = get(),
            client = get(),
            settingsStore = get(),
            appScope = get(),
        )
    }

    // 推荐商店：源配置、index 拉取（带缓存兜底）、安装器
    single {
        RecommendSourceStore(context = get(), appScope = get())
    }

    single {
        RecommendIndexRepository(
            context = get(),
            client = get(),
            sourceStore = get(),
            appScope = get(),
        )
    }

    single {
        RecommendInstaller(
            client = get(),
            skillManager = get(),
            settingsStore = get(),
            pluginManager = get(),
        )
    }
}
