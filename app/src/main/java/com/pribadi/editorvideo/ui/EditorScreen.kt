package com.pribadi.editorvideo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.pribadi.editorvideo.vm.EditorViewModel
import com.pribadi.editorvideo.vm.Tool

/**
 * Layar utama editor: bar atas, pratinjau (dengan Readme awal saat kosong), kontrol putar,
 * bar alat, timeline, lalu sheet/dialog (kecepatan, teks overlay, catatan bug/pengembangan,
 * panduan readme, ekspor, konfirmasi), toast, dan mode layar penuh.
 */
@Composable
fun EditorScreen(vm: EditorViewModel, onImport: () -> Unit, onStartExport: () -> Unit) {
    BackHandler(
        enabled = vm.confirmText != null || vm.speedSheet || vm.textSheet ||
            vm.noteSheet || vm.readmeSheet || vm.exportSheet || vm.fullscreen
    ) {
        when {
            vm.confirmText != null -> vm.confirmText = null
            vm.speedSheet -> vm.speedSheet = false
            vm.textSheet -> vm.textSheet = false
            vm.noteSheet -> vm.noteSheet = false
            vm.readmeSheet -> vm.readmeSheet = false
            vm.exportSheet -> vm.closeExport()
            vm.fullscreen -> vm.fullscreen = false
        }
    }
    FullscreenBars(vm.fullscreen)

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
                Stage(vm, onImport, Modifier.weight(1f))
                Controls(vm)
                ToolBar(vm)
                Timeline(vm, onImport)
            }

            if (vm.fullscreen) {
                FullscreenLayer(vm, onImport)
            }

            if (vm.speedSheet) {
                SpeedSheet(vm) { vm.speedSheet = false }
            }

            if (vm.textSheet) {
                OverlayTextSheet(vm) { vm.textSheet = false }
            }

            if (vm.noteSheet) {
                NotesSheet(vm) { vm.noteSheet = false }
            }

            if (vm.readmeSheet) {
                ReadmeSheet(vm, onImport) { vm.readmeSheet = false }
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

    val which = vm.editingText
    if (which != 0) {
        TextEditDialog(
            title = if (which == 1) "Ubah Teks Atas" else "Ubah Teks Bawah",
            initial = if (which == 1) vm.text1 else vm.text2,
            onSave = { v ->
                vm.applyText(which, v)
                vm.editingText = 0
            },
            onDismiss = { vm.editingText = 0 }
        )
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
            .padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopIcon(
                onClick = {
                    if (vm.hasVideo) {
                        vm.resetMedia()
                    } else {
                        vm.toast("Pratinjau sudah di tampilan awal")
                    }
                }
            ) {
                Ico(R.drawable.ic_back, Ev.Text, 24.dp)
            }
            TopIcon(onClick = { vm.readmeSheet = true }) {
                Box {
                    Ico(R.drawable.ic_help, Ev.Text, 24.dp)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-2).dp)
                            .size(8.dp)
                            .background(Ev.Yellow, CircleShape)
                    )
                }
            }
            TopIcon(onClick = { vm.noteSheet = true }) {
                Box {
                    Ico(R.drawable.ic_note, Ev.Text, 24.dp)
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.noteSheet = true }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "Catatan",
                    color = Ev.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
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
                    fontSize = 15.sp
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
// Pratinjau
// ---------------------------------------------------------------------------------------------

/** Rasio lebar/tinggi pratinjau mengikuti video yang sudah diputar (9:16 bila belum ada video). */
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
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (!vm.fullscreen) {
            if (!vm.hasVideo) {
                val w = maxWidth * 0.94f
                val h = maxOf(maxHeight - 8.dp, 1.dp)
                PreviewBox(
                    vm = vm,
                    boxW = w,
                    boxH = h,
                    onEditText = { vm.editingText = it },
                    onImport = onImport
                )
            } else {
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
                    onEditText = { vm.editingText = it },
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
            onEditText = { vm.editingText = it },
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable { vm.togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Ico(if (vm.playing) R.drawable.ic_pause else R.drawable.ic_play, Color.White, 32.dp)
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
// Kontrol putar
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
        CtrlButton(
            if (vm.playing) R.drawable.ic_pause else R.drawable.ic_play,
            30.dp,
            true,
            Modifier.align(Alignment.Center)
        ) { vm.togglePlay() }
        CtrlButton(
            R.drawable.ic_fullscreen,
            24.dp,
            true,
            Modifier.align(Alignment.CenterEnd)
        ) { vm.toggleFullscreen() }
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
            ToolItem("Potong", R.drawable.ic_split, false) { vm.onTool(Tool.SPLIT) }
            ToolItem(
                if (sp == 1.0) "Kecepatan" else Fmt.one(sp) + "x",
                R.drawable.ic_speed,
                sp != 1.0
            ) { vm.onTool(Tool.SPEED) }
            ToolItem("Teks", R.drawable.ic_text, hasText) { vm.onTool(Tool.TEXT) }
            ToolItem("Membalikkan", R.drawable.ic_reverse, vm.selReversed) { vm.onTool(Tool.REVERSE) }
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
            .width(74.dp)
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
                .background(Color(0xF528282C))
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
