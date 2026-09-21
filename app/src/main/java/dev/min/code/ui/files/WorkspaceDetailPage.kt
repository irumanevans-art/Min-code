package dev.min.code.ui.files

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.rootfs.SelectionEstimate
import dev.min.code.core.rootfs.WorkspaceEntity
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.EmptyState
import dev.min.code.ui.components.ImagePreviewDialog
import dev.min.code.ui.components.InkBottomTabs
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTab
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.PaperTone
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.InkMenuItem
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.WorkspaceOpenMode
import dev.min.code.core.rootfs.WorkspaceUsage
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.fileProviderUri
import dev.min.code.util.fileSizeToString
import dev.min.code.util.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.ExternalLink
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Move01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceArchive
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区页：「环境」和「文件」两页横向翻页。
 *
 * 版式是纸上的纸条：每个文件一张 [PaperCard]，目录图标是金的（这是你自己的东西），
 * 文件是石墨；删除一律朱砂。没有卡片阴影——这一页也是同一本日志。
 */
@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    // 默认落在「文件」页：这是这一页的主要用途，「环境」只是偶尔看一眼
    val pagerState = rememberPagerState(initialPage = 1) { 2 }
    val scope = rememberCoroutineScope()
    val bulk by vm.bulk.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var moveTargets by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var openWithTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var largeExport by remember { mutableStateOf<SelectionEstimate?>(null) }
    // 搜索框展不展开。搜索中（query 非空）时一直留着，否则点搜索图标来回切
    var searchBarOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val openFailed = stringResource(R.string.workspace_detail_open_with_failed)
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    // 授权跟着 Activity 走会在转屏时断掉，用 application 的 resolver
    val resolver = remember(context) { context.applicationContext.contentResolver }

    val multiImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // DISPLAY_NAME 查询是一次 IPC，别放在主线程上（旧实现就在这里）
        scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.map { it.toImportSource(resolver) } }
            vm.importFiles(sources)
        }
    }
    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 顶层有两个以上名字，说明这包散着 —— 默认给它套一个文件夹，别炸进当前目录
            val top = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { WorkspaceArchive.peekTopLevelNames(it) }
                }.getOrNull().orEmpty()
            }
            val wrapper = if (top.size > 1) {
                sanitizeFileName(uri.toImportSource(resolver).name.substringBeforeLast('.'))
            } else {
                null
            }
            vm.importArchive(open = { resolver.openInputStream(uri) }, wrapInFolder = wrapper)
        }
    }
    val treeImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importTree(SafTreeReader(resolver, uri))
    }

    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = resolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }
    var archiveBasePath by remember { mutableStateOf("") }
    val archiveExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = resolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportSelectedArchive(outputStream, archiveBasePath)
    }
    val treeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportSelectedToTree(SafTreeWriter(resolver, uri), archiveBasePath)
    }

    /**
     * 按 [mode] 打开一个文件。默认路由和「打开方式」里显式选的走同一条实现 ——
     * 两份拷贝迟早会分叉。
     */
    fun openEntry(entry: WorkspaceFileEntry, mode: WorkspaceOpenMode) {
        when (mode) {
            WorkspaceOpenMode.EDITOR ->
                navController.navigate(Screen.FileEditor(state.area.name, entry.path))

            WorkspaceOpenMode.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                previewImageUri = file.absolutePath
            }

            WorkspaceOpenMode.EXTERNAL -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(context.fileProviderUri(file), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val started = runCatching {
                    context.startActivity(Intent.createChooser(intent, null))
                }.isSuccess
                if (!started) toaster.show(openFailed, ToastType.Error)
            }
        }
    }

    /** 拉起打包导出。选中项的名字决定 zip 叫什么 */
    fun launchArchiveExport() {
        archiveBasePath = archiveBase(state.inSearch, state.path)
        val selected = state.selectedEntries
        archiveExportLauncher.launch(
            archiveName(
                selectedNames = selected.map { it.name },
                selectedIsSingleFolder = selected.size == 1 && selected.first().isDirectory,
                currentFolderName = state.currentFolderName,
                areaLabel = if (state.area == WorkspaceStorageArea.FILES) "workspace" else "rootfs",
                date = today(),
            )
        )
    }

    fun launchTreeExport() {
        archiveBasePath = archiveBase(state.inSearch, state.path)
        treeExportLauncher.launch(null)
    }

    // 返回键：先退出多选，再退出搜索，最后退目录。都没得退时才真的离开这一页
    BackHandler(
        enabled = pagerState.currentPage == 1 &&
            (state.selectionMode || state.inSearch || state.path.isNotBlank()),
    ) {
        when {
            state.selectionMode -> vm.exitSelection()
            state.inSearch -> {
                vm.clearSearch()
                searchBarOpen = false
            }

            else -> vm.goUp()
        }
    }

    val selecting = state.selectionMode && pagerState.currentPage == 1

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast()) },
                label = "files-top-bar",
            ) { inSelection ->
                if (inSelection) {
                    InkTopBar(
                        title = stringResource(R.string.workspace_detail_selected_count, state.selectedCount),
                        navigationIcon = {
                            InkIconButton(
                                icon = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.workspace_detail_exit_selection),
                                onClick = vm::exitSelection,
                            )
                        },
                        actions = {
                            InkIconButton(
                                icon = HugeIcons.Tick02,
                                // 列表本身封顶 500 条（护着 LazyColumn），「全选」也就只能选到这些。
                                // 读屏时至少要说清楚这件事，别让人以为整个目录都选上了
                                contentDescription = when {
                                    state.visibleEntries.size >= LIST_CAP ->
                                        stringResource(R.string.workspace_detail_select_limit, LIST_CAP)

                                    state.allVisibleSelected ->
                                        stringResource(R.string.workspace_detail_select_none)

                                    else -> stringResource(R.string.workspace_detail_select_all)
                                },
                                onClick = {
                                    if (state.allVisibleSelected) vm.clearSelection() else vm.selectAllVisible()
                                },
                            )
                            InkIconButton(
                                icon = HugeIcons.Exchange01,
                                contentDescription = stringResource(R.string.workspace_detail_select_invert),
                                onClick = vm::invertSelection,
                            )
                        },
                    )
                } else {
                    InkTopBar(
                        title = stringResource(R.string.workspace_detail_workspace_info),
                        navigationIcon = { BackButton() },
                        actions = {
                            if (pagerState.currentPage == 1) {
                                InkIconButton(
                                    icon = HugeIcons.Search01,
                                    contentDescription = stringResource(R.string.workspace_detail_search),
                                    onClick = {
                                        if (state.inSearch) vm.clearSearch() else searchBarOpen = !searchBarOpen
                                    },
                                )
                                InkIconButton(
                                    icon = HugeIcons.FolderAdd,
                                    contentDescription = stringResource(R.string.workspace_detail_new_folder),
                                    onClick = { creatingFolder = true },
                                )
                                // rootfs 是系统盘，往里导入太容易把环境写坏 —— 这颗键只在 files/ 区出现
                                if (state.canImport) {
                                    InkIconButton(
                                        icon = HugeIcons.FileImport,
                                        contentDescription = stringResource(R.string.workspace_detail_import),
                                        onClick = { showImportSheet = true },
                                    )
                                }
                            }
                            InkIconButton(
                                icon = HugeIcons.Refresh01,
                                contentDescription = stringResource(R.string.workspace_detail_refresh),
                                onClick = {
                                    vm.refresh()
                                    if (pagerState.currentPage == 0) vm.measureUsage()
                                },
                            )
                            if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                                InkIconButton(
                                    icon = HugeIcons.ComputerTerminal01,
                                    contentDescription = stringResource(R.string.workspace_terminal_title),
                                    onClick = { navController.navigate(Screen.Terminal) },
                                )
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast()) },
                label = "files-bottom-bar",
            ) { inSelection ->
                if (inSelection) {
                    WorkspaceSelectionBar(
                        count = state.selectedCount,
                        onExport = { showExportSheet = true },
                        onMove = { moveTargets = state.selectedEntries },
                        onShare = {
                            val selected = state.selectedEntries
                            val single = selected.singleOrNull()
                            if (single != null && !single.isDirectory) {
                                vm.exportToCacheFile(single, context.cacheDir) { file ->
                                    context.shareFile(file, "application/octet-stream")
                                }
                            } else {
                                vm.exportArchiveToCacheFile(
                                    entries = selected,
                                    base = archiveBase(state.inSearch, state.path),
                                    cacheDir = context.cacheDir,
                                    name = archiveName(
                                        selectedNames = selected.map { it.name },
                                        selectedIsSingleFolder = selected.size == 1 && selected.first().isDirectory,
                                        currentFolderName = state.currentFolderName,
                                        areaLabel = if (state.area == WorkspaceStorageArea.FILES) {
                                            "workspace"
                                        } else {
                                            "rootfs"
                                        },
                                        date = today(),
                                    ),
                                ) { file -> context.shareFile(file, "application/zip") }
                            }
                            vm.exitSelection()
                        },
                        onDelete = { confirmBulkDelete = true },
                    )
                } else {
                    InkBottomTabs(
                        tabs = listOf(
                            InkTab(HugeIcons.Settings03, stringResource(R.string.workspace_detail_tab_basic)),
                            InkTab(HugeIcons.File02, stringResource(R.string.workspace_detail_tab_files)),
                        ),
                        selected = pagerState.currentPage,
                        onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            // 多选时锁住横划：一个横向手势切到「环境」页，会把选择栏晾在一个没有列表的页面上
            userScrollEnabled = !selecting,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    usage = state.usage,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    searchOpen = searchBarOpen || state.inSearch,
                    onQueryChange = vm::search,
                    onCloseSearch = {
                        vm.clearSearch()
                        searchBarOpen = false
                    },
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onOpen = { entry ->
                        when {
                            state.selectionMode -> vm.toggleSelection(entry.path)
                            entry.isDirectory -> vm.open(entry)
                            else -> openEntry(entry, resolveOpenMode(entry, settings.openWithDefaults))
                        }
                    },
                    onLongPress = { entry ->
                        if (state.selectionMode) vm.toggleSelection(entry.path) else vm.enterSelection(entry)
                    },
                    onToggleSelect = { entry -> vm.toggleSelection(entry.path) },
                    onOpenWith = { openWithTarget = it },
                    onDelete = { deleteTarget = it },
                    onRename = { renameTarget = it },
                    onMove = { moveTargets = listOf(it) },
                    onExport = { entry ->
                        if (entry.isDirectory) {
                            // 文件夹没法原样导出一个文件，只能打包
                            vm.enterSelection(entry)
                            showExportSheet = true
                        } else {
                            exportTarget = entry
                            exportLauncher.launch(entry.name)
                        }
                    },
                    onShare = { entry ->
                        if (entry.isDirectory) {
                            vm.enterSelection(entry)
                            vm.exportArchiveToCacheFile(
                                entries = listOf(entry),
                                base = archiveBase(state.inSearch, state.path),
                                cacheDir = context.cacheDir,
                                name = sanitizeFileName(entry.name) + ".zip",
                            ) { file -> context.shareFile(file, "application/zip") }
                            vm.exitSelection()
                        } else {
                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                context.shareFile(file, "application/octet-stream")
                            }
                        }
                    },
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        InkDialog(
            onDismissRequest = vm::dismissInstallError,
            title = stringResource(R.string.workspace_detail_rootfs_install_failed),
            confirmButton = {
                InkTextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }

    renameTarget?.let { entry ->
        NameDialog(
            title = stringResource(R.string.workspace_detail_rename_title),
            initial = entry.name,
            confirmText = stringResource(R.string.common_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.rename(entry, name)
                renameTarget = null
            },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = stringResource(R.string.workspace_detail_new_folder),
            initial = "",
            confirmText = stringResource(R.string.common_create),
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                vm.mkdir(name)
                creatingFolder = false
            },
        )
    }

    moveTargets.takeIf { it.isNotEmpty() }?.let { entries ->
        MoveSheet(
            entries = entries,
            currentPath = state.path,
            onList = vm::listFolders,
            onDismiss = { moveTargets = emptyList() },
            onMove = { dest ->
                if (entries.size == 1 && !state.selectionMode) {
                    vm.moveInto(entries.first(), dest)
                } else {
                    vm.moveSelectedInto(dest)
                }
                moveTargets = emptyList()
            },
        )
    }

    openWithTarget?.let { entry ->
        WorkspaceOpenWithSheet(
            entry = entry,
            onDismiss = { openWithTarget = null },
            onPick = { mode, remember ->
                openWithTarget = null
                if (remember) scope.launch { settingsStore.setOpenWithDefault(entry.extension(), mode) }
                openEntry(entry, mode)
            },
        )
    }

    if (showImportSheet) {
        WorkspaceImportSheet(
            onFiles = {
                showImportSheet = false
                multiImportLauncher.launch(arrayOf("*/*"))
            },
            onFolder = {
                showImportSheet = false
                treeImportLauncher.launch(null)
            },
            onZip = {
                showImportSheet = false
                zipImportLauncher.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "*/*"),
                )
            },
            onDismiss = { showImportSheet = false },
        )
    }

    if (showExportSheet) {
        WorkspaceExportSheet(
            onArchive = {
                showExportSheet = false
                scope.launch {
                    // 大到值得警告一句才拦一下；小批量不该多一次点击
                    val estimate = vm.estimateSelection().getOrNull()
                    if (estimate != null && estimate.isLarge) largeExport = estimate else launchArchiveExport()
                }
            },
            onTree = {
                showExportSheet = false
                scope.launch {
                    val estimate = vm.estimateSelection().getOrNull()
                    if (estimate != null && estimate.isLarge) largeExport = estimate else launchTreeExport()
                }
            },
            onDismiss = { showExportSheet = false },
        )
    }

    largeExport?.let { estimate ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.workspace_detail_export_large_title),
            confirmText = stringResource(R.string.common_export),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = {
                largeExport = null
                launchArchiveExport()
            },
            onDismiss = { largeExport = null },
        ) {
            Text(
                stringResource(
                    R.string.workspace_detail_export_large_warning,
                    estimate.bytes.fileSizeToString(),
                    estimate.files,
                )
            )
        }
    }

    if (confirmBulkDelete) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.common_delete),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                confirmBulkDelete = false
                vm.deleteSelected()
            },
            onDismiss = { confirmBulkDelete = false },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete_many, state.selectedCount))
        }
    }

    bulk?.let { progress ->
        // 小批量不值得占满屏：结束时一句提示就够了
        val worthASheet = progress.total > 3 || progress.bytesTotal > SHEET_BYTES_THRESHOLD
        if (worthASheet) {
            WorkspaceBulkProgressSheet(
                progress = progress,
                onCancel = vm::cancelBulk,
                onDismiss = vm::dismissBulk,
            )
        } else if (progress.finished) {
            val done = stringResource(
                R.string.workspace_detail_bulk_done,
                progress.done,
                progress.bytesDone.fileSizeToString(),
            )
            LaunchedEffect(progress) {
                toaster.show(
                    if (progress.failures.isEmpty()) done else progress.failures.first(),
                    if (progress.failures.isEmpty()) ToastType.Success else ToastType.Error,
                )
                vm.dismissBulk()
            }
        }
    }
}

/** 今天，`yyyy-MM-dd`。只用来给导出的包命名 */
private fun today(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** 超过这个体量的批量操作才值得弹一张进度面板 */
private const val SHEET_BYTES_THRESHOLD = 8L * 1024 * 1024

/** 列表一次最多列这么多条（`WorkspaceConfig.maxListEntries`）。「全选」的上限就是它 */
private const val LIST_CAP = 500

private fun Context.shareFile(file: File, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, fileProviderUri(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(Intent.createChooser(intent, null)) }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    usage: WorkspaceUsage?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Linux 环境",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow("状态", workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                    WorkspaceUsageBlock(usage)
                    Text(
                        "/workspace 是文件页里的「文件」区，Claude Code 的工作目录就是它；「Rootfs」区是整个 Ubuntu。" +
                            "Claude 用 apt / pip 装的东西都在 Rootfs 里，占用空间随之增长。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "重装 Linux 环境",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "会清空整个 Ubuntu：Claude Code CLI、Node.js、apt/pip 装过的一切、~/.claude 下的会话记录与记忆。" +
                            "只有环境彻底坏掉时才这么做；「文件」区不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 重装是判定，不是动作：朱砂描边，按下去之前先看清上面那段话
                    InkButton(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        busy = installing,
                        tone = InkButtonTone.Vermilion,
                        icon = HugeIcons.Bash,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(installButtonText)
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetbrainsMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        // 只有下载阶段知道总量；解压阶段是一道来回扫的墨线
        InkLineProgress(
            progress = fraction?.takeIf { progress.stage == RootfsInstallStage.DOWNLOADING },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }
    // 重装不是"更新"：RootfsInstaller 装之前会把整个 linux 目录 deleteRecursively，
    // 里面装的 Claude Code CLI / Node / apt 包和 ~/.claude 下的会话记录一起没。
    // 之前这个弹窗只问一个下载地址，一个字的风险提示都没有。
    val willWipe = workspace.shellStatus == WorkspaceShellStatus.READY.name

    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (willWipe) R.string.workspace_detail_reinstall_rootfs
            else R.string.workspace_detail_install_rootfs
        ),
        confirmButton = {
            InkTextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
                // 会清空环境的那个按钮是朱砂的
                tone = if (willWipe) InkButtonTone.Vermilion else InkButtonTone.Quiet,
            ) {
                Text(
                    stringResource(
                        if (willWipe) R.string.workspace_detail_reinstall_rootfs
                        else R.string.common_install
                    )
                )
            }
        },
        dismissButton = {
            InkTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (willWipe) {
            Notice(
                text = stringResource(R.string.workspace_detail_rootfs_wipe_warning),
                tone = NoticeTone.Error,
            )
        }
        InkTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.workspace_detail_download_url),
            maxLines = 5,
            monospace = true,
        )
    }
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    searchOpen: Boolean,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onLongPress: (WorkspaceFileEntry) -> Unit,
    onToggleSelect: (WorkspaceFileEntry) -> Unit,
    onOpenWith: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onRename: (WorkspaceFileEntry) -> Unit,
    onMove: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    val shown = state.visibleEntries
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (searchOpen) {
            item(key = "search") {
                WorkspaceSearchBar(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onClose = onCloseSearch,
                    modifier = Modifier.animateItem(
                        fadeInSpec = InkMotion.effect(),
                        placementSpec = InkMotion.spatial<IntOffset>(),
                        fadeOutSpec = InkMotion.effectFast(),
                    ),
                )
            }
        }

        // 搜索时不显示区域切换和路径栏：结果本来就跨目录，那两行会误导
        if (!state.inSearch) {
            item(key = "area") {
                WorkspaceAreaSelector(
                    selected = state.area,
                    onSelected = onSelectArea,
                )
            }

            item(key = "path") {
                WorkspacePathBar(
                    path = state.path,
                    canGoUp = state.path.isNotBlank(),
                    onGoUp = onGoUp,
                )
            }
        } else {
            item(key = "search-scope") {
                Text(
                    text = when {
                        state.searching -> stringResource(R.string.workspace_detail_search_running)
                        shown.isEmpty() ->
                            stringResource(R.string.workspace_detail_search_empty, state.query)

                        else -> stringResource(
                            R.string.workspace_detail_search_found,
                            shown.size,
                            state.path.ifBlank { "/" },
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { error ->
            item(key = "error") {
                Notice(
                    text = error,
                    tone = NoticeTone.Error,
                    modifier = Modifier.animateItem(
                        fadeInSpec = InkMotion.effect(),
                        placementSpec = InkMotion.spatial<IntOffset>(),
                        fadeOutSpec = InkMotion.effectFast(),
                    ),
                )
            }
        }

        if (!state.loading && !state.inSearch && shown.isEmpty() && state.error == null) {
            item(key = "empty") {
                EmptyDirectoryState(
                    modifier = Modifier.animateItem(
                        fadeInSpec = InkMotion.effect(),
                        placementSpec = InkMotion.spatial<IntOffset>(),
                        fadeOutSpec = InkMotion.effectFast(),
                    ),
                )
            }
        }

        items(shown, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                selectionMode = state.selectionMode,
                selected = entry.path in state.selected,
                onOpen = { onOpen(entry) },
                onLongPress = { onLongPress(entry) },
                onToggleSelect = { onToggleSelect(entry) },
                onOpenWith = { onOpenWith(entry) },
                onDelete = { onDelete(entry) },
                onRename = { onRename(entry) },
                onMove = { onMove(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
                // 进目录 / 删文件时条目落墨进场、飞白退场，位置变化走弹簧
                modifier = Modifier.animateItem(
                    fadeInSpec = InkMotion.effect(),
                    placementSpec = InkMotion.spatial<IntOffset>(),
                    fadeOutSpec = InkMotion.effectFast(),
                ),
            )
        }
    }
}

/**
 * 文件页的搜索条：一张纸条 + 左边一枚放大镜，右边一个叉。
 * 打开就聚焦 —— 点了搜索图标还要再点一次输入框是多余的一步。
 */
@Composable
private fun WorkspaceSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = stringResource(R.string.workspace_detail_search_hint),
            singleLine = true,
            leading = {
                Icon(
                    HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        InkIconButton(
            icon = HugeIcons.Cancel01,
            contentDescription = stringResource(R.string.workspace_detail_search_close),
            onClick = onClose,
            size = 36.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    InkSegmented(
        options = areas.map { it.second },
        selected = areas.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        onSelect = { onSelected(areas[it].first) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkIconButton(
            icon = HugeIcons.ArrowTurnBackward,
            contentDescription = stringResource(R.string.workspace_detail_go_up),
            onClick = onGoUp,
            enabled = canGoUp,
            size = 36.dp,
            iconSize = 18.dp,
        )
        // 路径是机器产物：等宽
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenWith: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val vermilion = MaterialTheme.sea.vermilion
    val kind = remember(entry.path, entry.isDirectory) { entry.detectFileKind() }

    PaperCard(
        modifier = modifier.fillMaxWidth(),
        onClick = if (selectionMode) onToggleSelect else onOpen,
        // 长按 = 进多选（和系统文件管理器一致）。单项菜单挪到行尾那颗 ⋮ 上，一个不少
        onLongClick = if (selectionMode) onToggleSelect else onLongPress,
        // PaperCard 对 tone 本来就有 animateColorAsState，选中高亮是白捡的
        tone = if (selected) PaperTone.High else PaperTone.Low,
        padding = PaddingValues(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 勾选框和类型图标都是 20 dp，切换时这一行不会抖
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = { fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast()) },
                label = "file-leading",
            ) { selecting ->
                if (selecting) {
                    // onCheckedChange = null：点按由整张卡片接，勾选框只是显示
                    InkCheckbox(checked = selected, onCheckedChange = null)
                } else {
                    Icon(
                        imageVector = kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = kind.tint(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 多选里再开一张单项菜单是歧义：它作用在哪一个？
            AnimatedVisibility(visible = !selectionMode) {
                Box {
                    InkIconButton(
                        icon = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.workspace_detail_more_actions),
                        onClick = { menuExpanded = true },
                        size = 36.dp,
                        iconSize = 18.dp,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        if (!entry.isDirectory) {
                            InkMenuItem(
                                text = stringResource(R.string.workspace_detail_open_with),
                                icon = HugeIcons.ExternalLink,
                                onClick = {
                                    menuExpanded = false
                                    onOpenWith()
                                },
                            )
                        }
                        InkMenuItem(
                            text = stringResource(R.string.common_rename),
                            icon = HugeIcons.Edit02,
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        InkMenuItem(
                            text = stringResource(R.string.common_move),
                            icon = HugeIcons.Move01,
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                        )
                        // 文件夹的导出 / 分享是「打包成 zip」，不再灰掉
                        InkMenuItem(
                            text = stringResource(R.string.common_export),
                            icon = HugeIcons.FileExport,
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        InkMenuItem(
                            text = stringResource(R.string.common_share),
                            icon = HugeIcons.Share08,
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                        InkMenuItem(
                            text = stringResource(R.string.common_delete),
                            icon = HugeIcons.Delete01,
                            tint = vermilion,
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceUsageBlock(usage: WorkspaceUsage?) {
    val usageLabel = stringResource(R.string.workspace_detail_usage)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            usage == null -> WorkspaceInfoRow(usageLabel, "正在扫描…")
            !usage.done -> {
                WorkspaceInfoRow(usageLabel, "${usage.totalBytes.fileSizeToString()} · 已扫 ${usage.scanned} 个文件")
                InkLineProgress(progress = null, modifier = Modifier.fillMaxWidth())
            }
            usage.error != null -> {
                WorkspaceInfoRow(usageLabel, "${usage.totalBytes.fileSizeToString()}（未扫完）")
                Text(
                    usage.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.vermilion,
                )
            }
            else -> {
                WorkspaceInfoRow(usageLabel, usage.totalBytes.fileSizeToString())
                Text(
                    "文件 ${usage.filesBytes.fileSizeToString()} · Rootfs ${usage.linuxBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = CwdPath.folderName(name) != null
    InkDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            InkTextButton(
                onClick = { CwdPath.folderName(name)?.let(onConfirm) },
                enabled = valid,
            ) { Text(confirmText) }
        },
        dismissButton = {
            InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        InkTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.workspace_detail_name_hint),
            singleLine = true,
            monospace = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSheet(
    entries: List<WorkspaceFileEntry>,
    currentPath: String,
    onList: suspend (String) -> Result<List<WorkspaceFileEntry>>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    var relative by remember { mutableStateOf(currentPath) }
    var folders by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(relative) {
        error = null
        onList(relative)
            .onSuccess { listed ->
                // 任何一个被移动的文件夹都不能当落点，也不能落进它自己的子目录
                folders = listed.filter { folder ->
                    entries.none { it.path == folder.path || folder.path.startsWith("${it.path}/") }
                }
            }
            .onFailure { error = it.message ?: "打不开这个目录" }
    }
    InkSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.workspace_detail_move_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                if (entries.size == 1) {
                    "把「${entries.first().name}」移到下面选中的文件夹。点进一层再确认。"
                } else {
                    "把选中的 ${entries.size} 项移到下面选中的文件夹。点进一层再确认。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkIconButton(
                    icon = HugeIcons.ArrowTurnBackward,
                    contentDescription = stringResource(R.string.workspace_detail_go_up),
                    onClick = { relative = relative.substringBeforeLast('/', missingDelimiterValue = "") },
                    enabled = relative.isNotBlank(),
                    size = 36.dp,
                    iconSize = 18.dp,
                )
                Text(
                    text = relative.ifBlank { "/" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error?.let {
                Notice(
                    text = it,
                    tone = NoticeTone.Error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (folders.isEmpty() && error == null) {
                    item {
                        Text(
                            stringResource(R.string.workspace_detail_no_subfolders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    }
                }
                items(folders, key = { it.path }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { relative = folder.path }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.sea.seaDeep,
                        )
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            InkButton(
                onClick = { onMove(relative) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.workspace_detail_move_here))
            }
        }
    }
}

/** 空目录：一小片淡墨，一句楷书。高度定死，别让 LazyColumn 里的空态撑到无限 */
@Composable
private fun EmptyDirectoryState(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(260.dp),
    ) {
        EmptyState(
            title = stringResource(R.string.workspace_detail_empty_directory),
            intensity = 0.35f,
        )
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

private val DEFAULT_ROOTFS_URL: String get() = dev.min.code.core.rootfs.RootfsSources.defaultUrl()
