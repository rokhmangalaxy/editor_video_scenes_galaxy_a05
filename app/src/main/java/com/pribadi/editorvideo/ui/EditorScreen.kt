package com.pribadi.editorvideo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.vm.EditorViewModel
import com.pribadi.editorvideo.vm.Tool

/**
 * Layar utama editor: bar atas (Reset, Tema 10 Pilihan, Catatan, Ekspor), pratinjau tunggal & Mode Grid 3 Kolom,
 * kontrol putar, bar alat (termasuk Fitur AI), timeline kosong-di-awal dengan label durasi,
 * serta panel-panel floating (max-height 80% scroll-y).
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickGallery: () -> Unit,
    onPickFileManager: () -> Unit,
    onExportTaggingFile: () -> Unit,
    onImportTaggingFile: () -> Unit,
    onExportPromptsFile: () -> Unit,
    onImportPromptsFile: () -> Unit,
    onStartExport: () -> Unit
) {
    val requestImportOptions = { vm.importSourceSheet = true }

    BackHandler(
        enabled = vm.collageOpen || vm.confirmText != null || vm.importSourceSheet ||
            vm.gridTagSheet || vm.gridSortSheet || vm.themeSheet || vm.aiSheet ||
            vm.speedSheet || vm.reverseSheet || vm.textSheet || vm.sceneDetectSheet ||
            vm.noteSheet || vm.exportSheet || vm.fullscreen || vm.gridColSliderOpen || vm.gridMultiSelect
    ) {
        when {
            vm.collageOpen -> vm.closeMultiplayerCollage()
            vm.confirmText != null -> vm.confirmText = null
            vm.importSourceSheet -> vm.importSourceSheet = false
            vm.gridTagSheet -> vm.gridTagSheet = false
            vm.gridSortSheet -> vm.gridSortSheet = false
            vm.themeSheet -> vm.themeSheet = false
            vm.aiSheet -> vm.aiSheet = false
            vm.reverseSheet -> vm.reverseSheet = false
            vm.sceneDetectSheet -> vm.sceneDetectSheet = false
            vm.speedSheet -> vm.speedSheet = false
            vm.textSheet -> vm.textSheet = false
            vm.noteSheet -> vm.noteSheet = false
            vm.exportSheet -> vm.closeExport()
            vm.fullscreen -> vm.fullscreen = false
            vm.gridColSliderOpen -> vm.gridColSliderOpen = false
            vm.gridMultiSelect -> vm.clearGridMultiSelect()
        }
    }
    FullscreenBars(vm.fullscreen || vm.collageOpen)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(Ev.Bg)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                TopBar(vm)
                Stage(vm, requestImportOptions, Modifier.weight(1f))
                Controls(vm)
                ToolBar(vm)
                Timeline(vm, requestImportOptions)
            }

            if (vm.fullscreen) {
                FullscreenLayer(vm, requestImportOptions)
            }

            if (vm.importSourceSheet) {
                ImportSourceSheet(
                    onPickGallery = onPickGallery,
                    onPickFileManager = onPickFileManager,
                    onClose = { vm.importSourceSheet = false }
                )
            }

            if (vm.themeSheet) {
                ThemeSheet(vm) { vm.themeSheet = false }
            }

            if (vm.aiSheet) {
                AiStudioSheet(
                    vm = vm,
                    onExportPromptsFile = onExportPromptsFile,
                    onImportPromptsFile = onImportPromptsFile,
                    onClose = { vm.aiSheet = false }
                )
            }

            if (vm.gridTagSheet) {
                GridTagSheet(
                    vm = vm,
                    onExportFile = onExportTaggingFile,
                    onImportFile = onImportTaggingFile,
                    onClose = { vm.gridTagSheet = false }
                )
            }

            if (vm.gridSortSheet) {
                GridSortSheet(vm) { vm.gridSortSheet = false }
            }

            if (vm.collageOpen && vm.collageItems.isNotEmpty()) {
                MultiplayerCollageOverlay(
                    items = vm.collageItems,
                    onClose = { vm.closeMultiplayerCollage() }
                )
            }

            if (vm.speedSheet) {
                SpeedSheet(vm) { vm.speedSheet = false }
            }

            if (vm.reverseSheet) {
                ReverseSheet(vm) { vm.reverseSheet = false }
            }

            if (vm.sceneDetectSheet) {
                SceneDetectSheet(vm) { vm.sceneDetectSheet = false }
            }

            if (vm.textSheet) {
                OverlayTextSheet(vm) { vm.textSheet = false }
            }

            if (vm.noteSheet) {
                NotesSheet(vm) { vm.noteSheet = false }
            }

            val confirm = vm.confirmText
            if (confirm != null) {
                ConfirmDialog(
                    text = confirm,
                    onNo = { vm.confirmText = null },
                    onYes = { vm.confirmApplyAll() }
                )
            }

            if (vm.exportSheet) {
                ExportSheet(vm, onStartExport)
            }

            ToastHost(
                msg = vm.toastMsg,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 180.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bar atas
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopBar(vm: EditorViewModel) {
    val openNotesCount = vm.notes.count { !it.done }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopIcon(
                onClick = {
                    if (vm.hasVideo) {
                        vm.resetMedia()
                    } else {
                        vm.toast("Pratinjau & timeline sudah kosong")
                    }
                }
            ) {
                Ico(R.drawable.ic_back, Ev.Text, 23.dp)
            }
            TopIcon(onClick = { vm.themeSheet = true }) {
                Ico(R.drawable.ic_palette, Ev.Yellow, 23.dp)
            }
            TopIcon(
                onClick = {
                    vm.noteTab = 0
                    vm.noteSheet = true
                }
            ) {
                Box {
                    Ico(R.drawable.ic_note, Ev.Text, 23.dp)
                    if (openNotesCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-3).dp)
                                .clip(CircleShape)
                                .background(Ev.Accent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = openNotesCount.toString(),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.themeSheet = true }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Tema",
                    color = Ev.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.noteSheet = true }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Catatan",
                    color = Ev.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ev.Accent)
                    .clickable { vm.openExport() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Ekspor",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun TopIcon(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        content = content
    )
}

// ---------------------------------------------------------------------------------------------
// Pratinjau (Tunggal & Mode Grid 3 Kolom)
// ---------------------------------------------------------------------------------------------

private fun aspectOf(vm: EditorViewModel): Float {
    val inf = vm.info
    if (!vm.hasVideo || inf == null || inf.dispW <= 0 || inf.dispH <= 0) return 9f / 16f
    return if (vm.rot % 180 != 0) {
        inf.dispH.toFloat() / inf.dispW.toFloat()
    } else {
        inf.dispW.toFloat() / inf.dispH.toFloat()
    }
}

@Composable
private fun Stage(vm: EditorViewModel, onImport: () -> Unit, modifier: Modifier) {
    if (vm.gridMode && !vm.fullscreen) {
        VideoGridStage(
            vm = vm,
            onImport = onImport,
            modifier = modifier
        )
    } else {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (!vm.fullscreen) {
                val ar = aspectOf(vm)
                val maxW = maxWidth * 0.94f
                val maxH = maxOf(maxHeight - 8.dp, 1.dp)
                var w: Dp = maxW
                var h: Dp = w / ar
                if (h > maxH) {
                    h = maxH
                    w = h * ar
                }
                PreviewBox(
                    vm = vm,
                    boxW = w,
                    boxH = h,
                    onEditText = { layer -> vm.openTextEditor(layer) },
                    onImport = onImport
                )
            }
        }
    }
}

@Composable
private fun FullscreenLayer(vm: EditorViewModel, onImport: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        val ar = aspectOf(vm)
        val maxW = maxWidth
        val maxH = maxHeight
        var w: Dp = maxW
        var h: Dp = w / ar
        if (h > maxH) {
            h = maxH
            w = h * ar
        }
        PreviewBox(
            vm = vm,
            boxW = w,
            boxH = h,
            onEditText = { layer -> vm.openTextEditor(layer) },
            onImport = onImport,
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable { vm.fullscreen = false },
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_close, Color.White, 24.dp)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Ico(if (vm.playing) R.drawable.ic_pause else R.drawable.ic_play, Color.White, 32.dp)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (vm.previewLoop) Ev.Accent else Color(0x66000000))
                    .border(
                        1.dp,
                        if (vm.previewLoop) Ev.Accent else Color(0x55FFFFFF),
                        CircleShape
                    )
                    .clickable { vm.togglePreviewLoop() },
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_loop, Color.White, 22.dp)
            }
        }
    }
}

@Composable
private fun FullscreenBars(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        val window = view.context.findActivity()?.window
        if (window != null && active) {
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null && active) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---------------------------------------------------------------------------------------------
// Kontrol putar & tombol Mode Grid 3 Kolom
// ---------------------------------------------------------------------------------------------

@Composable
private fun Controls(vm: EditorViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 10.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtrlButton(R.drawable.ic_undo, 24.dp, vm.canUndo, Modifier) { vm.undo() }
            CtrlButton(R.drawable.ic_redo, 24.dp, vm.canRedo, Modifier) { vm.redo() }
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtrlButton(
                if (vm.playing) R.drawable.ic_pause else R.drawable.ic_play,
                30.dp,
                vm.hasVideo,
                Modifier
            ) { vm.togglePlay() }
            // Toggle Loop pratinjau (mengaktifkan/mematikan mode loop tanpa langsung memutar video)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (vm.previewLoop) Ev.Accent else Ev.Ghost)
                    .border(
                        1.dp,
                        if (vm.previewLoop) Ev.Accent else Ev.Line,
                        CircleShape
                    )
                    .clickable { vm.togglePreviewLoop() },
                contentAlignment = Alignment.Center
            ) {
                Ico(
                    id = R.drawable.ic_loop,
                    tint = if (vm.previewLoop) Color.White else Ev.Text,
                    size = 19.dp
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tombol Mode Grid 3 Kolom
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (vm.gridMode) Ev.Accent else Ev.Ghost)
                    .border(
                        1.dp,
                        if (vm.gridMode) Ev.Accent else Ev.Line,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { vm.toggleGridMode() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ico(
                        id = R.drawable.ic_grid,
                        tint = if (vm.gridMode) Color.White else Ev.Text,
                        size = 16.dp
                    )
                    Text(
                        text = if (vm.gridMode) "Grid ${vm.gridColumns}" else "Grid",
                        color = if (vm.gridMode) Color.White else Ev.Text,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            CtrlButton(
                R.drawable.ic_fullscreen,
                24.dp,
                !vm.gridMode,
                Modifier
            ) { vm.toggleFullscreen() }
        }
    }
}

@Composable
private fun CtrlButton(
    @DrawableRes id: Int,
    size: Dp,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.3f)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        Ico(id, Ev.Text, size)
    }
}

// ---------------------------------------------------------------------------------------------
// Bar alat
// ---------------------------------------------------------------------------------------------

@Composable
private fun ToolBar(vm: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ev.Panel)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ev.Line)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            val sp = vm.selSpeed
            val hasText = vm.text1.isNotEmpty() || vm.text2.isNotEmpty()
            val revLabel = when (vm.selRevMode) {
                Seg.REV_MUNDUR -> "Mundur"
                Seg.REV_LOOP -> "Loop"
                else -> "Membalikkan"
            }
            ToolItem("Potong", R.drawable.ic_split, false) { vm.onTool(Tool.SPLIT) }
            ToolItem("Scene", R.drawable.ic_scene_detect, vm.sceneDetectSheet) { vm.onTool(Tool.SCENE_DETECT) }
            ToolItem("AI", R.drawable.ic_ai, vm.aiSheet) { vm.onTool(Tool.AI) }
            ToolItem(
                if (sp == 1.0) "Kecepatan" else Fmt.one(sp) + "x",
                R.drawable.ic_speed,
                sp != 1.0
            ) { vm.onTool(Tool.SPEED) }
            ToolItem("Teks", R.drawable.ic_text, hasText) { vm.onTool(Tool.TEXT) }
            ToolItem(
                revLabel,
                R.drawable.ic_reverse,
                vm.selReversed || vm.reverseRendering || vm.reverseSheet
            ) { vm.onTool(Tool.REVERSE) }
            ToolItem("Duplikat", R.drawable.ic_dup, false) { vm.onTool(Tool.DUP) }
            ToolItem("Hapus", R.drawable.ic_del, false) { vm.onTool(Tool.DEL) }
            ToolItem("Putar", R.drawable.ic_rotate, vm.rot != 0) { vm.onTool(Tool.ROTATE) }
            ToolItem("Cermin", R.drawable.ic_mirror, vm.flip) { vm.onTool(Tool.MIRROR) }
            ToolItem("Beku", R.drawable.ic_freeze, false) { vm.onTool(Tool.FREEZE) }
        }
    }
}

@Composable
private fun ToolItem(label: String, @DrawableRes icon: Int, on: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(70.dp)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Ico(icon, if (on) Ev.Accent else Ev.ToolIcon, 24.dp)
        Text(
            text = label,
            color = if (on) Ev.Accent else Ev.Muted,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Toast
// ---------------------------------------------------------------------------------------------

private class ToastHolder {
    var text: String = ""
}

@Composable
private fun ToastHost(msg: String?, modifier: Modifier) {
    val holder = remember { ToastHolder() }
    if (msg != null) holder.text = msg
    val a by animateFloatAsState(
        targetValue = if (msg != null) 1f else 0f,
        animationSpec = tween(250),
        label = "toast"
    )
    if (a > 0.01f) {
        Box(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .offset(y = (10f * (1f - a)).dp)
                .alpha(a)
                .clip(RoundedCornerShape(20.dp))
                .background(Ev.Panel.copy(alpha = 0.96f))
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Text(
                text = holder.text,
                color = Ev.Text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
