package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ComputerDesk01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Minimize01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.getActivity
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceDesktopCatalog
import me.rerere.workspace.WorkspaceDesktop
import org.koin.compose.koinInject

/**
 * 工作区桌面页: 把 rootfs 里跑着的 code-server / noVNC / JupyterLab / 自定义服务直接内嵌到 App 里。
 *
 * WebView 只允许停在回环地址: 页面背后是一个能读写 /workspace 的本机服务, 一旦跟随链接跳到公网,
 * 等于把一个可执行的浏览器交给外部站点, 所以外链一律转交系统浏览器。
 */
@Composable
fun WorkspaceDesktopPage(id: String) {
    val manager: WorkspaceDesktopManager = koinInject()
    val repository: WorkspaceRepository = koinInject()
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current
    val state by manager.observe(id).collectAsStateWithLifecycle(
        initialValue = WorkspaceDesktopState(workspaceId = id),
    )
    val settings = LocalSettings.current

    val noBrowserMessage = stringResource(R.string.wp5_desktop_page_no_browser)

    var workspaceName by remember(id) { mutableStateOf<String?>(null) }
    var webView by remember(id) { mutableStateOf<WebView?>(null) }
    var loading by remember(id) { mutableStateOf(false) }
    var loadFailed by remember(id) { mutableStateOf(false) }
    var fullscreen by remember(id) {
        mutableStateOf(settings.displaySetting.desktopFullscreenByDefault)
    }

    // 全屏时返回键先退出全屏, 而不是直接离开页面
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // 全屏 = 连系统栏一起藏起来; 退出全屏或离开页面时都要放回来
    val view = LocalView.current
    val activity = view.context.getActivity()
    DisposableEffect(view, activity, fullscreen) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 进页面先把自己的状态对一遍; 页面在安装/启动完成前被打开时, 探测结果会自行刷新
    LaunchedEffect(id) {
        workspaceName = runCatching { repository.getById(id)?.name }.getOrNull()
        manager.refresh(id)
    }

    // 点击即开：桌面已安装但没在运行时，进页面自动启动一次
    var autoStarted by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, state.loaded, state.installed, state.running, state.busy, state.desktop) {
        if (!autoStarted && state.loaded && state.desktop != WorkspaceDesktop.NONE &&
            state.installed != false && !state.running && state.busy == null
        ) {
            autoStarted = true
            manager.start(id)
        }
    }

    fun openExternal(target: String) {
        runCatching { uriHandler.openUri(target) }.onFailure {
            toaster.show(noBrowserMessage, type = ToastType.Warning)
        }
    }

    val webUrl = state.plan?.url?.takeIf { state.loaded && state.running }

    Scaffold(
        topBar = {
            // 全屏时连应用栏一起让位, 桌面视图铺满整个页面
            if (!fullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = workspaceName ?: stringResource(R.string.wp5_desktop_page_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                    actions = {
                        if (webUrl != null) {
                            IconButton(
                                onClick = {
                                    loadFailed = false
                                    webView?.reload()
                                }
                            ) {
                                Icon(
                                    HugeIcons.Refresh01,
                                    contentDescription = stringResource(R.string.wp5_desktop_page_reload),
                                )
                            }
                            IconButton(onClick = { openExternal(webUrl) }) {
                                Icon(
                                    HugeIcons.Earth,
                                    contentDescription = stringResource(R.string.wp5_desktop_page_open_browser),
                                )
                            }
                            // 停掉桌面进程: 复用工作区详情页同一条停止流程 (发信号 + 等端口不再响应)
                            IconButton(
                                onClick = { manager.stop(id) },
                                enabled = state.busy == null,
                            ) {
                                Icon(
                                    HugeIcons.Stop,
                                    contentDescription = stringResource(R.string.wp5_desktop_action_stop),
                                )
                            }
                            IconButton(onClick = { fullscreen = true }) {
                                Icon(
                                    HugeIcons.Fullscreen,
                                    contentDescription = stringResource(R.string.wp5_desktop_page_fullscreen),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    loadFailed = false
                                    manager.refresh(id)
                                }
                            ) {
                                Icon(
                                    HugeIcons.Refresh01,
                                    contentDescription = stringResource(R.string.wp5_desktop_page_refresh_state),
                                )
                            }
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            webUrl?.let { url ->
                DesktopWebView(
                    url = url,
                    onWebViewReady = { webView = it },
                    onWebViewReleased = { released -> if (webView === released) webView = null },
                    onLoadingChange = { loading = it },
                    onLoadFailed = { loadFailed = true },
                    onExternalUrl = ::openExternal,
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }

            if (webUrl == null || loadFailed) {
                DesktopPlaceholder(
                    state = state,
                    failedToLoad = loadFailed,
                    onStart = { manager.start(id) },
                    onReload = {
                        loadFailed = false
                        webView?.reload()
                    },
                    onRefresh = {
                        loadFailed = false
                        manager.refresh(id)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CustomColors.topBarColors.containerColor),
                )
            }

            // 全屏里没有顶栏, 留一个退出全屏的悬浮按钮 (返回键同样先退全屏)
            if (fullscreen) {
                FilledTonalIconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        HugeIcons.Minimize01,
                        contentDescription = stringResource(R.string.wp5_desktop_page_exit_fullscreen),
                    )
                }
            }
        }
    }

    DesktopFailureDialog(
        failure = state.failure,
        onDismiss = { manager.dismissFailure(id) },
    )
}

/**
 * 桌面没在运行 / 页面打不开时的占位。
 *
 * 文案按「先解决哪一步」排序: 没探测完 → 页面加载失败 → 缺 rootfs → 没配方案 → 没启动。
 */
@Composable
private fun DesktopPlaceholder(
    state: WorkspaceDesktopState,
    failedToLoad: Boolean,
    onStart: () -> Unit,
    onReload: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val probing = !state.loaded
    val busy = state.busy
    val plan = state.plan
    val notConfigured = plan == null || plan.url == null || plan.startScript == null

    val icon: ImageVector
    val title: String
    val description: String?
    when {
        probing -> {
            icon = HugeIcons.ComputerDesk01
            title = stringResource(R.string.wp5_desktop_page_probing)
            description = null
        }

        failedToLoad -> {
            icon = HugeIcons.Alert02
            title = stringResource(R.string.wp5_desktop_page_load_failed_title)
            description = stringResource(R.string.wp5_desktop_page_load_failed_desc)
        }

        !state.rootfsReady -> {
            icon = HugeIcons.ComputerDesk01
            title = stringResource(R.string.wp5_desktop_page_rootfs_missing)
            description = stringResource(R.string.wp5_desktop_page_rootfs_missing_desc)
        }

        notConfigured -> {
            icon = HugeIcons.ComputerDesk01
            title = stringResource(R.string.wp5_desktop_page_not_configured)
            description = stringResource(R.string.wp5_desktop_page_not_configured_desc)
        }

        else -> {
            icon = HugeIcons.ComputerDesk01
            title = stringResource(R.string.wp5_desktop_page_not_running)
            description = stringResource(R.string.wp5_desktop_page_not_running_desc)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        when {
            busy != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(desktopBusyLabel(busy), style = MaterialTheme.typography.bodyMedium)
                }
                // 安装的下载阶段把百分比也摊开, 不然看不出是不是卡住了
                if (busy == WorkspaceDesktopAction.INSTALL) {
                    state.download?.let { download ->
                        DesktopDownloadProgress(
                            progress = download,
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                    }
                }
            }

            probing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 页面打不开多半是桌面进程已经退了, 重新拉起来才是有效的下一步
                val canStart = state.rootfsReady && plan?.startScript != null
                if (canStart) {
                    Button(onClick = onStart) {
                        Text(stringResource(R.string.wp5_desktop_action_start))
                    }
                }
                if (failedToLoad && state.running) {
                    Button(onClick = onReload) {
                        Text(stringResource(R.string.wp5_desktop_page_reload))
                    }
                }
                FilledTonalButton(onClick = onRefresh) {
                    Text(stringResource(R.string.wp5_desktop_page_refresh_state))
                }
            }
        }
    }
}

/**
 * 只认回环地址的内嵌浏览器。
 *
 * - JS / DOM storage 打开: code-server 和 JupyterLab 都依赖它们;
 * - 不允许读本地文件: 页面来自本机服务, 没有理由让它能碰 App 沙箱;
 * - 外链转交系统浏览器, 内嵌视图不跟着离开 127.0.0.1。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DesktopWebView(
    url: String,
    onWebViewReady: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onLoadFailed: () -> Unit,
    onExternalUrl: (String) -> Unit,
) {
    val currentOnLoadingChange by rememberUpdatedState(onLoadingChange)
    val currentOnLoadFailed by rememberUpdatedState(onLoadFailed)
    val currentOnExternalUrl by rememberUpdatedState(onExternalUrl)

    val client = remember {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (WorkspaceDesktopCatalog.isLocalUrl(target)) return false
                currentOnExternalUrl(target)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentOnLoadingChange(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentOnLoadingChange(false)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 字体/图标之类的子资源失败不该把整页判死, 只有主文档打不开才算失败
                if (request?.isForMainFrame == true) {
                    currentOnLoadingChange(false)
                    currentOnLoadFailed()
                }
            }
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = client
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = onWebViewReady,
        onRelease = { view ->
            onWebViewReleased(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeAllViews()
            view.destroy()
        },
    )
}
