package com.pribadi.editorvideo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.export.ByTagExportStyle
import com.pribadi.editorvideo.export.DetectedScene
import com.pribadi.editorvideo.export.ExportMath
import com.pribadi.editorvideo.export.ExportMode
import com.pribadi.editorvideo.export.ExportState
import com.pribadi.editorvideo.export.OverlayRenderer
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.vm.EditorViewModel
import com.pribadi.editorvideo.vm.GridSortMode
import com.pribadi.editorvideo.vm.NoteItem
import com.pribadi.editorvideo.vm.NoteType
import com.pribadi.editorvideo.vm.UpdateLogEntry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Bagian kecil & Wadah Panel Floating (seluruh panel max-height 80% + scroll-y)
// ---------------------------------------------------------------------------------------------

@Composable
fun ActionButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Ev.Accent else Ev.Ghost)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** Latar gelap penuh layar; ketukan di luar panel memanggil onTap (bila ada). */
@Composable
private fun Scrim(
    onTap: (() -> Unit)?,
    alignment: Alignment,
    content: @Composable BoxWithConstraintsScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Ev.Scrim)
            .pointerInput(onTap) {
                detectTapGestures(onTap = { if (onTap != null) onTap() })
            },
        contentAlignment = alignment
    ) {
        content()
    }
}

/** Menyerap ketukan di dalam panel supaya tidak diteruskan ke latar. */
private fun Modifier.absorbTaps(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

/**
 * Panel floating standar untuk semua menu & fitur:
 * - Tinggi maksimum 80% layar (maxHeight * 0.80f)
 * - Scroll vertikal aktif (verticalScroll / scroll-y)
 */
@Composable
internal fun FloatingSheetPanel(
    onTapOutside: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Scrim(onTap = onTapOutside, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.80f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .border(1.dp, Ev.Line, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .absorbTaps()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
internal fun SmallIconButton(id: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Ico(id, Ev.Text, 24.dp)
    }
}

/** Baris yang membungkus ke baris berikutnya bila tidak muat. */
@Composable
private fun SimpleFlow(hGap: Dp, vGap: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val hg = hGap.roundToPx()
        val vg = vGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in placeables.indices) {
            val p = placeables[i]
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0
                y += rowH + vg
                rowH = 0
            }
            xs[i] = x
            ys[i] = y
            x += p.width + hg
            rowH = max(rowH, p.height)
        }
        val totalH = y + rowH
        layout(constraints.maxWidth, totalH) {
            for (i in placeables.indices) {
                placeables[i].placeRelative(xs[i], ys[i])
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet 10 Pilihan Tema Aplikasi — Grid 2 Kolom dengan Mini Preview UI
// ---------------------------------------------------------------------------------------------

@Composable
fun ThemeSheet(vm: EditorViewModel, onClose: () -> Unit) {
    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pilih Tema (${AppTheme.entries.size} Pilihan)",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Ketuk kartu mini preview di bawah untuk menerapkan tema.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Grid 2 Kolom Mini Preview untuk 10 Tema
        val rows = AppTheme.entries.chunked(2)
        for (pair in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (t in pair) {
                    val active = vm.appTheme == t
                    ThemeMiniPreviewCard(
                        theme = t,
                        active = active,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.selectTheme(t) }
                    )
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        ActionButton("Selesai", true, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

@Composable
private fun ThemeMiniPreviewCard(
    theme: AppTheme,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pal = paletteFor(theme)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Ev.Accent.copy(alpha = 0.14f) else Ev.Bg)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Ev.Accent else Ev.Line,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Mini Preview antarmuka Editor Video menggunakan palet tema terkait
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(pal.bg)
                .border(1.dp, pal.line, RoundedCornerShape(9.dp))
        ) {
            // Mini TopBar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(pal.panel)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(pal.toolIcon)
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(pal.yellow)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(pal.accent)
                )
            }

            // Mini Preview Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(pal.bg)
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(pal.panel)
                        .border(1.dp, pal.line, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(pal.accent)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 3.dp)
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(pal.yellow)
                    )
                }
            }

            // Mini Timeline + Playhead
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(pal.panel)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(pal.accent)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(pal.segBg))
                            .border(1.dp, pal.accent, RoundedCornerShape(3.dp))
                    )
                    Box(
                        modifier = Modifier
                            .weight(0.75f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(pal.segBg))
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = theme.label,
                color = Ev.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Ev.Accent)
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = theme.desc,
            color = Ev.Muted,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Fitur AI (Presisi Tinggi 100% Identik Preview + Step Frame + Prompt + Generate)
// ---------------------------------------------------------------------------------------------

@Composable
fun AiSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val seekBmp = vm.aiSeekBitmap

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Ico(R.drawable.ic_ai, Ev.Yellow, 20.dp)
                    Text(
                        text = "Fitur AI",
                        color = Ev.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    if (vm.hasVideo && seekBmp != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Ev.Accent.copy(alpha = 0.22f))
                                .border(1.dp, Ev.Accent, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "✓ " + vm.aiCaptureSourceLabel,
                                color = Ev.Yellow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Gambar diambil sama persis dari frame Preview pada posisi seek untuk penyisipan presisi.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Kotak Gambar Frame yang 100% sama persis dengan Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, Ev.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (seekBmp != null && !seekBmp.isRecycled) {
                Image(
                    bitmap = seekBmp.asImageBitmap(),
                    contentDescription = "Frame posisi seek",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Ico(R.drawable.ic_ai, Ev.Muted, 28.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (vm.hasVideo) {
                            "Menyinkronkan frame presisi dari Preview…"
                        } else {
                            "Belum ada video · Impor video untuk menampilkan frame posisi seek"
                        },
                        color = Ev.Muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (vm.hasVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xD9000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Seek " + Fmt.preciseTime(vm.aiSeekTimeSec) +
                            " · Klip #" + (vm.aiSeekClipIndex + 1) +
                            " (" + Fmt.preciseTime(vm.aiSeekSourceSec) + ")",
                        color = Ev.Yellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xD9000000))
                        .border(1.dp, Ev.Line, RoundedCornerShape(8.dp))
                        .clickable { vm.captureAiSeekFrame(waitSeekSettle = true) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (vm.aiFrameLoading) "Sinkron…" else "↻ Sinkron",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Kontrol Geser Presisi Per-Frame (agar titik selip hasil AI sangat akurat)
        if (vm.hasVideo) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiStepBtn("⏮ -5f", Modifier.weight(1f)) { vm.stepAiFrame(-5) }
                AiStepBtn("◀ -1 Frame", Modifier.weight(1.25f)) { vm.stepAiFrame(-1) }
                AiStepBtn("+1 Frame ▶", Modifier.weight(1.25f)) { vm.stepAiFrame(1) }
                AiStepBtn("+5f ⏭", Modifier.weight(1f)) { vm.stepAiFrame(5) }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Label + Textarea Prompt
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROMPT AI",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            if (vm.aiPrompt.isNotEmpty()) {
                Text(
                    text = "Kosongkan",
                    color = Ev.Accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        vm.aiPrompt = ""
                        vm.aiPreviewStatus = null
                    }
                )
            }
        }

        OutlinedTextField(
            value = vm.aiPrompt,
            onValueChange = {
                vm.aiPrompt = it
                vm.aiPreviewStatus = null
            },
            placeholder = {
                Text(
                    text = "Tulis prompt AI untuk frame presisi ini (hasil AI nantinya akan diselipkan tepat di posisi seek ini)…",
                    color = Ev.Muted,
                    fontSize = 13.sp
                )
            },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp)
        )

        val status = vm.aiPreviewStatus
        if (status != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Bg)
                    .border(1.dp, Ev.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(11.dp)
            ) {
                Text(
                    text = status,
                    color = Ev.InfoText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                text = "Tutup",
                primary = false,
                modifier = Modifier.weight(0.9f),
                onClick = onClose
            )
            ActionButton(
                text = "✨ Generate",
                primary = true,
                modifier = Modifier.weight(1.4f),
                onClick = { vm.onAiGenerateClicked() }
            )
        }
    }
}

@Composable
private fun AiStepBtn(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Ev.Ghost)
            .border(1.dp, Ev.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Ev.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Pilihan Sumber Impor Video (Galeri atau File Manager)
// ---------------------------------------------------------------------------------------------

@Composable
fun ImportSourceSheet(
    onPickGallery: () -> Unit,
    onPickFileManager: () -> Unit,
    onClose: () -> Unit
) {
    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pilih Sumber Video",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Pilih satu atau beberapa video dari Galeri maupun File Manager.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Opsi 1: Upload dari Galeri (Photo Picker)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ev.Accent.copy(alpha = 0.16f))
                .border(1.5.dp, Ev.Accent, RoundedCornerShape(14.dp))
                .clickable {
                    onClose()
                    onPickGallery()
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Ev.Accent),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_gallery, Color.White, 22.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Upload dari Galeri",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Buka Galeri Foto & Video untuk memilih cepat (dukung multiseleksi)",
                    color = Ev.Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Opsi 2: Pilih dari File Manager (SAF)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(14.dp))
                .clickable {
                    onClose()
                    onPickFileManager()
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Ev.Ghost)
                    .border(1.dp, Ev.Line, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_folder, Ev.Yellow, 22.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pilih dari File Manager",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Telusuri folder Penyimpanan Internal, Kartu SD, atau Download",
                    color = Ev.Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        ActionButton("Batal", false, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Tagging & Sheet Sort untuk Mode Grid
// ---------------------------------------------------------------------------------------------

@Composable
fun GridTagSheet(
    vm: EditorViewModel,
    onExportFile: () -> Unit,
    onImportFile: () -> Unit,
    onClose: () -> Unit
) {
    val _rev = vm.rev
    val ctx = LocalContext.current
    var newTagInput by remember { mutableStateOf("") }
    var selectedColorIdx by remember { mutableIntStateOf(5) } // default Ungu untuk tag kustom pertama
    val palette = EditorViewModel.TAG_COLOR_PALETTE
    val selectedOpt = palette.getOrElse(selectedColorIdx) { palette.first() }
    val targets = vm.targetTagSegmentIndices()
    val segs = vm.core.segs
    val targetTitle = when {
        targets.size > 1 -> "${targets.size} Potongan Terpilih"
        targets.size == 1 -> "Potongan #${targets.first() + 1}"
        else -> "Belum ada potongan"
    }

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Ico(R.drawable.ic_tag, Ev.Yellow, 20.dp)
                    Text(
                        text = "Kelola Tag · $targetTitle",
                        color = Ev.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Ketuk kartu untuk pasang/lepas tag. Ketuk titik warna pada kartu untuk mengganti warnanya.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Daftar Tag tersedia (grid 2 kolom rapi)
        val rows = vm.availableTags.chunked(2)
        for (pair in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (tag in pair) {
                    val activeCount = targets.count { idx ->
                        idx in segs.indices && tag in segs[idx].tags
                    }
                    val allActive = targets.isNotEmpty() && activeCount == targets.size
                    val partial = activeCount > 0 && !allActive
                    val dotCol = vm.tagColor(tag)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                when {
                                    allActive -> dotCol.copy(alpha = 0.20f)
                                    partial -> Ev.Ghost
                                    else -> Ev.Bg
                                }
                            )
                            .border(
                                width = if (allActive) 2.dp else 1.dp,
                                color = if (allActive) dotCol else if (partial) Ev.Yellow else Ev.Line,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable { vm.toggleTagForCurrentTarget(tag) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            // Titik warna: ketuk untuk menerapkan warna terpilih dari 12 opsi warna
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(dotCol)
                                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                                    .clickable { vm.assignTagColor(tag, selectedColorIdx) }
                            )
                            Text(
                                text = tag,
                                color = Ev.Text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (allActive || partial) {
                            Text(
                                text = if (allActive) "✓" else "$activeCount/${targets.size}",
                                color = if (allActive) Ev.Yellow else Ev.Muted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Header + Pratinjau Label Transparan dari 12 Opsi Warna Custom Tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BUAT CUSTOM TAG · 12 OPSI WARNA",
                color = Ev.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            // Mini preview tampilan label transparan di item Grid
            val previewLabel = newTagInput.trim().ifEmpty { selectedOpt.name }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.30f))
                    .background(selectedOpt.color.copy(alpha = 0.36f))
                    .border(1.dp, selectedOpt.color.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(selectedOpt.color)
                    )
                    Text(
                        text = previewLabel,
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Palet 12 Opsi Warna (2 Baris × 6 Kolom)
        val colorRows = palette.chunked(6)
        for (cRow in colorRows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (opt in cRow) {
                    val isChosen = opt.index == selectedColorIdx
                    val useDarkCheck = opt.index == 0 || opt.index == 8 || opt.index == 10 || opt.index == 11
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(opt.color.copy(alpha = if (isChosen) 0.95f else 0.75f))
                            .border(
                                width = if (isChosen) 2.5.dp else 1.dp,
                                color = if (isChosen) Color.White else Color.Black.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(9.dp)
                            )
                            .clickable { selectedColorIdx = opt.index },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isChosen) {
                            Text(
                                text = "✓",
                                color = if (useDarkCheck) Color.Black else Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newTagInput,
                onValueChange = { newTagInput = it },
                placeholder = {
                    Text("Nama custom tag (${selectedOpt.name})…", color = Ev.Muted, fontSize = 12.5.sp)
                },
                minLines = 1,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                text = "+ Tambah",
                primary = true,
                enabled = newTagInput.trim().isNotEmpty(),
                onClick = {
                    vm.addCustomTag(newTagInput, selectedColorIdx)
                    newTagInput = ""
                    selectedColorIdx = (selectedColorIdx + 1) % palette.size
                }
            )
        }

        // Bagian Eksport & Import Tagging (.json File Manager & Clipboard)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                .padding(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Ico(R.drawable.ic_import_export, Ev.Yellow, 16.dp)
                    Text(
                        text = "EKSPORT & IMPORT TAGGING",
                        color = Ev.Text,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // Toggle Mode Import: Gabung vs Timpa
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (!vm.tagImportReplaceMode) Ev.Accent else Ev.Ghost)
                            .clickable { vm.tagImportReplaceMode = false }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Gabung",
                            color = if (!vm.tagImportReplaceMode) Color.White else Ev.Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (vm.tagImportReplaceMode) Ev.Accent else Ev.Ghost)
                            .clickable { vm.tagImportReplaceMode = true }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Timpa",
                            color = if (vm.tagImportReplaceMode) Color.White else Ev.Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Simpan daftar custom tag, warna, dan tag klip ke berkas .json atau muat kembali kapan saja.",
                color = Ev.Muted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(9.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButton(
                    text = "⬇ Eksport File (.json)",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onExportFile
                )
                ActionButton(
                    text = "⬆ Import File (.json)",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onImportFile
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Ev.Ghost)
                        .border(1.dp, Ev.Line, RoundedCornerShape(9.dp))
                        .clickable {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val json = vm.buildTaggingExportJson()
                            cm?.setPrimaryClip(ClipData.newPlainText("Tagging Video JSON", json))
                            vm.toast("JSON Tagging disalin ke clipboard")
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Salin JSON",
                        color = Ev.InfoText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Ev.Ghost)
                        .border(1.dp, Ev.Line, RoundedCornerShape(9.dp))
                        .clickable {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (clipText.isNullOrBlank()) {
                                vm.toast("Clipboard masih kosong")
                            } else {
                                vm.importTaggingFromJson(clipText, vm.tagImportReplaceMode)
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tempel JSON",
                        color = Ev.InfoText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                text = "Bersihkan Tag",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = { vm.clearTagsForCurrentTarget() }
            )
            ActionButton(
                text = "Selesai",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = onClose
            )
        }
    }
}

@Composable
fun GridSortSheet(vm: EditorViewModel, onClose: () -> Unit) {
    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Urutkan Mode Grid (Sort)",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Pilih urutan tampilan klip pada Mode Grid.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(12.dp))

        for (mode in GridSortMode.entries) {
            val active = vm.gridSortMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Ev.Accent.copy(alpha = 0.18f) else Ev.Bg)
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = if (active) Ev.Accent else Ev.Line,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { vm.selectGridSortMode(mode) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = mode.label,
                    color = Ev.Text,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp
                )
                if (active) {
                    Text(
                        text = "✓ Aktif",
                        color = Ev.Yellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        ActionButton("Tutup", false, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Membalikkan: Opsi Mundur & Loop (Normal + Mundur)
// ---------------------------------------------------------------------------------------------

@Composable
fun ReverseSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val curMode = vm.selRevMode

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Membalikkan · Potongan " + (vm.selIndex + 1),
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Pilih mode Mundur atau Loop (Normal + Mundur digabung).",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Opsi 1: Mundur (fitur reverse urutan frame)
        ReverseOptionCard(
            title = "⏪ Mundur",
            subtitle = "Membalik urutan frame potongan video dari akhir ke awal.",
            active = curMode == Seg.REV_MUNDUR
        ) {
            vm.applyReverseMode(Seg.REV_MUNDUR)
        }

        // Opsi 2: Loop (video Normal + Mundur digabung)
        ReverseOptionCard(
            title = "🔁 Loop (Normal + Mundur)",
            subtitle = "Menggabungkan video normal diikuti video mundur menjadi satu potongan utuh (bolak-balik).",
            active = curMode == Seg.REV_LOOP
        ) {
            vm.applyReverseMode(Seg.REV_LOOP)
        }

        // Opsi 3: Kembalikan ke Normal (bila sedang Mundur / Loop)
        if (curMode != Seg.REV_NONE) {
            ReverseOptionCard(
                title = "▶ Kembalikan ke Normal",
                subtitle = "Mengembalikan potongan ke video asli sebelum dibalik atau di-loop.",
                active = false
            ) {
                vm.applyReverseMode(Seg.REV_NONE)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        ActionButton("Batal", false, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

@Composable
private fun ReverseOptionCard(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Ev.Accent.copy(alpha = 0.14f) else Ev.Bg)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Ev.Accent else Ev.Line,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
                if (active) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ev.Accent)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Aktif",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = Ev.Muted,
                fontSize = 12.sp,
                lineHeight = 16.5.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet kecepatan
// ---------------------------------------------------------------------------------------------

@Composable
fun SpeedSheet(vm: EditorViewModel, onClose: () -> Unit) {
    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Kecepatan · Potongan " + (vm.selIndex + 1),
                color = Ev.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Text(
            text = Fmt.one(vm.selSpeed) + "x",
            style = TextStyle(
                color = Ev.Text,
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFeatureSettings = "tnum",
                textAlign = TextAlign.Center
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp)
        )

        Slider(
            value = vm.selSpeed.toFloat().coerceIn(0.1f, 9f),
            onValueChange = { vm.setSpeed(it.toDouble(), false) },
            onValueChangeFinished = { vm.commitSpeed() },
            valueRange = 0.1f..9f,
            colors = SliderDefaults.colors(
                thumbColor = Ev.Accent,
                activeTrackColor = Ev.Accent,
                inactiveTrackColor = Ev.Line
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "0.1x", color = Ev.Muted, fontSize = 12.sp)
            Text(text = "9.0x", color = Ev.Muted, fontSize = 12.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton("−1.0", false, Modifier.weight(1f)) { vm.setSpeed(vm.selSpeed - 1.0, true) }
            ActionButton("Reset 1.0", false, Modifier.weight(1f)) { vm.setSpeed(1.0, true) }
            ActionButton("+1.0", false, Modifier.weight(1f)) { vm.setSpeed(vm.selSpeed + 1.0, true) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            ActionButton("Terapkan ke semua potongan", false, Modifier.weight(1f)) { vm.askApplyAll() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            ActionButton("Selesai", true, Modifier.weight(1f), onClick = onClose)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Split Pendeteksi Scene + Deteksi Ulang + Min 0.1 + Simpan Default + Grid Preview
// ---------------------------------------------------------------------------------------------

private val MIN_SCENE_DUR_PRESETS = listOf(0.1, 0.3, 0.5, 1.0, 1.5, 2.0, 3.0)

@Composable
fun SceneDetectSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val scenes = vm.detectedScenes
    val selectedCount = scenes.count { it.selected }

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Deteksi Scene · Potongan " + (vm.selIndex + 1),
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Nilai terakhir diingat otomatis sebagai default (min 0.1).",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tombol Deteksi Ulang (Rescan) agar bila hasil saat ini belum sesuai bisa dipindai ulang
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Ghost)
                    .border(1.dp, Ev.Accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .alpha(if (vm.sceneScanning) 0.45f else 1f)
                    .clickable(enabled = !vm.sceneScanning) { vm.rescanScenes() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "🔄 Deteksi Ulang",
                    color = Ev.Text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (vm.sceneScanCount > 0) {
                Text(
                    text = "Pindaian ke-${vm.sceneScanCount + 1}",
                    color = Ev.InfoText,
                    fontSize = 11.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Kontrol 1: Sensitivitas Deteksi (min 0.1 .. 1.0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SENSITIVITAS DETEKSI",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = String.format(Locale.US, "%.2f (%d%%)", vm.sceneSensitivity, (vm.sceneSensitivity * 100f).roundToInt()),
                color = Ev.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = vm.sceneSensitivity.coerceIn(0.1f, 1.0f),
            onValueChange = { vm.updateSceneSettings(it, vm.sceneMinDur) },
            enabled = !vm.sceneScanning,
            valueRange = 0.1f..1.0f,
            colors = SliderDefaults.colors(
                thumbColor = Ev.Accent,
                activeTrackColor = Ev.Accent,
                inactiveTrackColor = Ev.Line
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Min 0.1", color = Ev.Muted, fontSize = 11.sp)
            Text(text = "Maks 1.0", color = Ev.Muted, fontSize = 11.sp)
        }

        // Kontrol 2: Durasi Minimal Tiap Scene (min 0.1 detik .. 5.0 detik)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DURASI MINIMAL SCENE",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${Fmt.one(vm.sceneMinDur)} detik",
                color = Ev.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = vm.sceneMinDur.toFloat().coerceIn(0.1f, 5.0f),
            onValueChange = { vm.updateSceneSettings(vm.sceneSensitivity, it.toDouble()) },
            enabled = !vm.sceneScanning,
            valueRange = 0.1f..5.0f,
            colors = SliderDefaults.colors(
                thumbColor = Ev.Accent,
                activeTrackColor = Ev.Accent,
                inactiveTrackColor = Ev.Line
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (dOpt in MIN_SCENE_DUR_PRESETS) {
                val active = abs(vm.sceneMinDur - dOpt) < 0.04
                Chip(
                    on = active,
                    enabled = !vm.sceneScanning,
                    label = "${Fmt.one(dOpt)}s"
                ) {
                    vm.updateSceneSettings(newSensitivity = vm.sceneSensitivity, newMinDur = dOpt)
                }
            }
        }

        // Kontrol 3: Opsi Durasi Maksimal Tiap Scene (Default OFF)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DURASI MAKSIMAL SCENE",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (vm.sceneMaxDurEnabled) {
                    Text(
                        text = "Maks ${Fmt.one(vm.sceneMaxDur)} dtk",
                        color = Ev.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .alpha(if (vm.sceneScanning) 0.45f else 1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (vm.sceneMaxDurEnabled) Ev.Accent else Ev.Ghost)
                        .border(
                            1.dp,
                            if (vm.sceneMaxDurEnabled) Ev.Accent else Ev.Line,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !vm.sceneScanning) {
                            vm.updateSceneSettings(newMaxEnabled = !vm.sceneMaxDurEnabled)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (vm.sceneMaxDurEnabled) "ON" else "OFF",
                        color = if (vm.sceneMaxDurEnabled) Color.White else Ev.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (vm.sceneMaxDurEnabled) {
            val minBound = max(0.5f, vm.sceneMinDur.toFloat())
            Slider(
                value = vm.sceneMaxDur.toFloat().coerceIn(minBound, 20.0f),
                onValueChange = {
                    vm.updateSceneSettings(
                        newMaxEnabled = true,
                        newMaxDur = it.toDouble()
                    )
                },
                enabled = !vm.sceneScanning,
                valueRange = minBound..20.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Ev.Accent,
                    activeTrackColor = Ev.Accent,
                    inactiveTrackColor = Ev.Line
                )
            )
            val maxPresets = listOf(1.5, 2.5, 3.5, 5.0, 7.0, 10.0, 15.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip(
                    on = false,
                    enabled = !vm.sceneScanning,
                    label = "Off"
                ) {
                    vm.updateSceneSettings(newMaxEnabled = false)
                }
                for (mOpt in maxPresets) {
                    if (mOpt >= vm.sceneMinDur - 0.01) {
                        val active = abs(vm.sceneMaxDur - mOpt) < 0.05
                        Chip(
                            on = active,
                            enabled = !vm.sceneScanning,
                            label = "${Fmt.one(mOpt)}s"
                        ) {
                            vm.updateSceneSettings(newMaxEnabled = true, newMaxDur = mOpt)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (vm.sceneScanning) {
            val pct = (vm.sceneScanProgress * 100f).roundToInt().coerceIn(0, 100)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ev.Bg)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Memindai frame untuk deteksi scene… $pct%",
                    color = Ev.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { vm.sceneScanProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Ev.Accent,
                    trackColor = Ev.Ghost
                )
            }
        } else {
            // Header Grid Preview Deteksi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GRID PREVIEW DETEKSI (${scenes.size} SCENE)",
                    color = Ev.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (scenes.isNotEmpty()) {
                    val allSelected = selectedCount == scenes.size
                    Text(
                        text = if (allSelected) "Batal Pilih Semua" else "Pilih Semua",
                        color = Ev.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { vm.selectAllDetectedScenes(!allSelected) }
                    )
                }
            }
            Text(
                text = "Ketuk gambar untuk melompat ke scene di pratinjau. Ketuk tanda centang untuk memilih bagian yang disertakan.",
                color = Ev.Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            // Grid 2 Kolom
            if (scenes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Bg)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Belum ada scene terdeteksi.",
                        color = Ev.Muted,
                        fontSize = 13.sp
                    )
                }
            } else {
                val rows = scenes.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (rowItems in rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (sc in rowItems) {
                                DetectedSceneGridCard(
                                    scene = sc,
                                    modifier = Modifier.weight(1f),
                                    onPreview = { vm.previewDetectedScene(sc) },
                                    onToggleSelect = { vm.toggleDetectedScene(sc.index) }
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton("Batal", false, Modifier.weight(1f), onClick = onClose)
                ActionButton(
                    text = "Split Scene ($selectedCount)",
                    primary = true,
                    modifier = Modifier.weight(1.4f),
                    enabled = selectedCount > 0
                ) {
                    vm.applyDetectedScenes(onlySelected = true)
                }
            }
        }
    }
}

@Composable
private fun DetectedSceneGridCard(
    scene: DetectedScene,
    modifier: Modifier = Modifier,
    onPreview: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val borderCol = if (scene.selected) Ev.Accent else Ev.Line
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(Color(0xFF222228))
                .clickable(onClick = onPreview)
        ) {
            val bmp = scene.thumb
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Scene ${scene.index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Scene #${scene.index + 1}",
                        color = Ev.Muted,
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xBF000000))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "#${scene.index + 1}",
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (scene.selected) Ev.Accent else Color(0x99000000))
                    .border(1.5.dp, Color.White, CircleShape)
                    .clickable(onClick = onToggleSelect),
                contentAlignment = Alignment.Center
            ) {
                if (scene.selected) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xBF000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = Fmt.durLabel(scene.durationSec),
                    color = Ev.Yellow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${Fmt.time(scene.startSec)} – ${Fmt.time(scene.endSec)}",
                color = if (scene.selected) Ev.Text else Ev.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Teks Overlay dengan Kustomisasi Kaya
// ---------------------------------------------------------------------------------------------

private val TEXT_FILL_COLORS: List<Int> = listOf(
    0xFFFFFFFF.toInt(), // Putih
    0xFFFFE45C.toInt(), // Kuning Emas
    0xFFB03060.toInt(), // Medium Maroon
    0xFFFF3B5C.toInt(), // Merah Koral
    0xFF38BDF8.toInt(), // Biru Langit
    0xFF4ADE80.toInt(), // Hijau Neon
    0xFFFB923C.toInt(), // Oranye
    0xFFC084FC.toInt(), // Ungu Muda
    0xFF2DD4BF.toInt(), // Toska
    0xFF111114.toInt()  // Hitam
)

private val TEXT_STROKE_COLORS: List<Int> = listOf(
    0x00000000,         // Tanpa Outline
    0xFF000000.toInt(), // Hitam
    0xFF4A2FB0.toInt(), // Ungu Klasik
    0xFFB03060.toInt(), // Medium Maroon
    0xFFFFFFFF.toInt(), // Putih
    0xFFF2C22A.toInt(), // Emas
    0xFFFF3B5C.toInt(), // Merah Koral
    0xFF1E3A8A.toInt()  // Biru Tua
)

private val TEXT_BG_COLORS: List<Int> = listOf(
    0x00000000,         // Tanpa Kotak Latar
    0xB3000000.toInt(), // Hitam Semi-Transparan
    0xEB141418.toInt(), // Hitam Pekat
    0xD9B03060.toInt(), // Kotak Medium Maroon
    0xD9FF3B5C.toInt(), // Kotak Merah Koral
    0xD94A2FB0.toInt(), // Kotak Ungu
    0xD9FFE45C.toInt(), // Kotak Kuning
    0xD9FFFFFF.toInt()  // Kotak Putih
)

private val ALIGN_LABELS = listOf("Kiri", "Tengah", "Kanan")

@Composable
fun OverlayTextSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val tab = vm.activeTextTab
    val layerNum = if (tab == 1) 2 else 1
    val cfg = if (layerNum == 1) vm.style1 else vm.style2

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Kustomisasi Teks Overlay",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Teks juga dapat digeser langsung (drag) di layar pratinjau.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LayerTabButton(
                title = "Teks 1 (Utama)",
                hasContent = vm.style1.isNotEmpty,
                active = tab == 0,
                modifier = Modifier.weight(1f)
            ) { vm.activeTextTab = 0 }
            LayerTabButton(
                title = "Teks 2 (Subjudul)",
                hasContent = vm.style2.isNotEmpty,
                active = tab == 1,
                modifier = Modifier.weight(1f)
            ) { vm.activeTextTab = 1 }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (layerNum == 1) "ISI TEKS 1" else "ISI TEKS 2",
                color = Ev.Yellow,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            if (cfg.text.isNotEmpty()) {
                Text(
                    text = "Kosongkan Layer Ini",
                    color = Ev.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        vm.updateTextConfig(layerNum, cfg.copy(text = ""))
                    }
                )
            }
        }
        OutlinedTextField(
            value = cfg.text,
            onValueChange = { vm.updateTextConfig(layerNum, cfg.copy(text = it)) },
            placeholder = {
                Text(
                    text = "Kosong (ketik teks, bisa lebih dari 1 baris)",
                    color = Ev.Muted
                )
            },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp)
        )

        SmallSectionTitle("GAYA HURUF (FONT)")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in OverlayRenderer.FONT_LABELS.indices) {
                Chip(
                    on = cfg.fontIndex == i,
                    enabled = true,
                    label = OverlayRenderer.FONT_LABELS[i]
                ) {
                    vm.updateTextConfig(layerNum, cfg.copy(fontIndex = i))
                }
            }
        }

        SmallSectionTitle("PERATAAN & EFEK")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in ALIGN_LABELS.indices) {
                Chip(
                    on = cfg.alignIndex == i,
                    enabled = true,
                    label = "Rata " + ALIGN_LABELS[i]
                ) {
                    vm.updateTextConfig(layerNum, cfg.copy(alignIndex = i))
                }
            }
            Chip(
                on = cfg.shadow,
                enabled = true,
                label = if (cfg.shadow) "Bayangan: Aktif" else "Bayangan: Mati"
            ) {
                vm.updateTextConfig(layerNum, cfg.copy(shadow = !cfg.shadow))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "UKURAN TEKS (${(cfg.sizeScale * 100f).roundToInt()}%)",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Reset 100%",
                color = Ev.InfoText,
                fontSize = 11.5.sp,
                modifier = Modifier.clickable {
                    vm.updateTextConfig(layerNum, cfg.copy(sizeScale = 1.0f))
                }
            )
        }
        Slider(
            value = cfg.sizeScale.coerceIn(0.4f, 2.2f),
            onValueChange = { vm.updateTextConfig(layerNum, cfg.copy(sizeScale = it)) },
            valueRange = 0.4f..2.2f,
            colors = SliderDefaults.colors(
                thumbColor = Ev.Accent,
                activeTrackColor = Ev.Accent,
                inactiveTrackColor = Ev.Line
            )
        )

        SmallSectionTitle("WARNA TEKS")
        ColorPaletteRow(
            colors = TEXT_FILL_COLORS,
            selectedColor = cfg.fillColor,
            onSelect = { vm.updateTextConfig(layerNum, cfg.copy(fillColor = it)) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OUTLINE / GARIS TEPI",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (cfg.strokeColor == 0 || cfg.strokeScale <= 0.02f) {
                    "Tanpa Outline"
                } else {
                    "Tebal ${(cfg.strokeScale * 100f).roundToInt()}%"
                },
                color = Ev.InfoText,
                fontSize = 11.5.sp
            )
        }
        ColorPaletteRow(
            colors = TEXT_STROKE_COLORS,
            selectedColor = cfg.strokeColor,
            onSelect = { chosen ->
                val nextScale = if (chosen != 0 && cfg.strokeScale <= 0.05f) 1.0f else cfg.strokeScale
                vm.updateTextConfig(layerNum, cfg.copy(strokeColor = chosen, strokeScale = nextScale))
            }
        )
        if (cfg.strokeColor != 0) {
            Slider(
                value = cfg.strokeScale.coerceIn(0f, 2.0f),
                onValueChange = { vm.updateTextConfig(layerNum, cfg.copy(strokeScale = it)) },
                valueRange = 0f..2.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Ev.Accent,
                    activeTrackColor = Ev.Accent,
                    inactiveTrackColor = Ev.Line
                )
            )
        }

        SmallSectionTitle("KOTAK LATAR BELAKANG")
        ColorPaletteRow(
            colors = TEXT_BG_COLORS,
            selectedColor = cfg.bgColor,
            onSelect = { vm.updateTextConfig(layerNum, cfg.copy(bgColor = it)) }
        )

        SmallSectionTitle("POSISI TEKS (ATAU SERET DI PRATINJAU)")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(
                on = abs(cfg.posY - 0.12f) < 0.04f && abs(cfg.posX - 0.5f) < 0.04f,
                enabled = true,
                label = "Atas"
            ) {
                vm.updateTextConfig(layerNum, cfg.copy(posX = 0.5f, posY = 0.12f))
            }
            Chip(
                on = abs(cfg.posY - 0.50f) < 0.04f && abs(cfg.posX - 0.5f) < 0.04f,
                enabled = true,
                label = "Tengah"
            ) {
                vm.updateTextConfig(layerNum, cfg.copy(posX = 0.5f, posY = 0.50f))
            }
            Chip(
                on = abs(cfg.posY - 0.88f) < 0.04f && abs(cfg.posX - 0.5f) < 0.04f,
                enabled = true,
                label = "Bawah"
            ) {
                vm.updateTextConfig(layerNum, cfg.copy(posX = 0.5f, posY = 0.88f))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("X", color = Ev.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = cfg.posX.coerceIn(0.06f, 0.94f),
                onValueChange = { vm.updateTextConfig(layerNum, cfg.copy(posX = it)) },
                valueRange = 0.06f..0.94f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Ev.Accent,
                    activeTrackColor = Ev.Accent,
                    inactiveTrackColor = Ev.Line
                )
            )
            Text("Y", color = Ev.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = cfg.posY.coerceIn(0.06f, 0.94f),
                onValueChange = { vm.updateTextConfig(layerNum, cfg.copy(posY = it)) },
                valueRange = 0.06f..0.94f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Ev.Accent,
                    activeTrackColor = Ev.Accent,
                    inactiveTrackColor = Ev.Line
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton("Reset Semua", false, Modifier.weight(1f)) {
                vm.clearAllTexts()
                vm.toast("Semua overlay teks dikosongkan")
            }
            ActionButton("Selesai", true, Modifier.weight(1f), onClick = onClose)
        }
    }
}

@Composable
private fun SmallSectionTitle(text: String) {
    Text(
        text = text,
        color = Ev.Muted,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun LayerTabButton(
    title: String,
    hasContent: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        if (hasContent) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (active) Ev.Yellow else Ev.Accent, CircleShape)
            )
        }
    }
}

@Composable
private fun ColorPaletteRow(
    colors: List<Int>,
    selectedColor: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (c in colors) {
            val isNone = c == 0
            val selected = selectedColor == c
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isNone) Ev.Bg else Color(c))
                    .border(
                        width = if (selected) 2.5.dp else 1.dp,
                        color = if (selected) Ev.Yellow else Color(0x55FFFFFF),
                        shape = CircleShape
                    )
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center
            ) {
                if (isNone) {
                    Text(
                        text = "∅",
                        color = Ev.Muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Catatan (Tab 1: Catatan Bug & Pengembangan | Tab 2: Updated & Readme)
// ---------------------------------------------------------------------------------------------

@Composable
fun NotesSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var selectedType by remember { mutableStateOf(NoteType.BUG) }
    var inputText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    val currentTab = vm.noteTab

    FloatingSheetPanel(onTapOutside = onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Catatan & Pembaruan Aplikasi",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = "Catatan bug/pengembangan serta info Updated & Readme aplikasi.",
                    color = Ev.Muted,
                    fontSize = 11.5.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (currentTab == 0) Ev.Accent else Ev.Ghost)
                    .border(1.dp, if (currentTab == 0) Ev.Accent else Ev.Line, RoundedCornerShape(10.dp))
                    .clickable { vm.noteTab = 0 }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📝 Catatan (${vm.notes.size})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (currentTab == 1) Ev.Accent else Ev.Ghost)
                    .border(1.dp, if (currentTab == 1) Ev.Accent else Ev.Line, RoundedCornerShape(10.dp))
                    .clickable { vm.noteTab = 1 }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🚀 Updated (Readme)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (currentTab == 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (t in NoteType.entries) {
                    val active = selectedType == t
                    val badgeColor = if (t == NoteType.BUG) Ev.Accent else Ev.Yellow
                    val textColor = if (active && t == NoteType.DEV) Color.Black else Color.White
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) badgeColor else Ev.Ghost)
                            .border(
                                1.dp,
                                if (active) badgeColor else Ev.Line,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { selectedType = t }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (t == NoteType.BUG) "🐞 Laporan Bug" else "💡 Pengembangan",
                            color = if (active) textColor else Ev.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (selectedType == NoteType.BUG) {
                            "Tulis detail bug yang ditemukan..."
                        } else {
                            "Tulis ide fitur atau pengembangan di masa depan..."
                        },
                        color = Ev.Muted
                    )
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 10.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editingId != null) {
                    ActionButton("Batal Edit", false, Modifier.weight(1f)) {
                        editingId = null
                        inputText = ""
                    }
                }
                ActionButton(
                    text = if (editingId != null) "Simpan Perubahan" else "+ Tambah Catatan",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    enabled = inputText.trim().isNotEmpty()
                ) {
                    val id = editingId
                    if (id != null) {
                        vm.updateNote(id, selectedType, inputText)
                        editingId = null
                    } else {
                        vm.addNote(selectedType, inputText)
                    }
                    inputText = ""
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DAFTAR CATATAN (" + vm.notes.size + ")",
                    color = Ev.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (vm.notes.isNotEmpty()) {
                    Text(
                        text = "Salin Semua",
                        color = Ev.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            copyNotesToClipboard(ctx, vm.notes)
                            vm.toast("Semua catatan disalin ke clipboard")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (vm.notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Bg)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Belum ada catatan bug atau rencana pengembangan.",
                        color = Ev.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in vm.notes) {
                        NoteRow(
                            item = item,
                            onToggleDone = { vm.toggleNoteDone(item.id) },
                            onEdit = {
                                editingId = item.id
                                selectedType = item.type
                                inputText = item.text
                            },
                            onDelete = {
                                if (editingId == item.id) {
                                    editingId = null
                                    inputText = ""
                                }
                                vm.deleteNote(item.id)
                            }
                        )
                    }
                }
            }
        } else {
            UpdatedAndReadmeSection(updateHistory = vm.updateHistory)
        }

        Spacer(modifier = Modifier.height(14.dp))
        ActionButton("Tutup", false, Modifier.fillMaxWidth(), onClick = onClose)
    }
}

@Composable
private fun UpdatedAndReadmeSection(updateHistory: List<UpdateLogEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "RIWAYAT UPDATED",
            color = Ev.Yellow,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        for (entry in updateHistory) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ev.Bg)
                    .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ev.Accent.copy(alpha = 0.25f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = entry.version,
                            color = Ev.Yellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = entry.date,
                        color = Ev.Muted,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = entry.title,
                    color = Ev.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                for (line in entry.items) {
                    Text(
                        text = line,
                        color = Ev.InfoText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "README & PANDUAN FITUR",
            color = Ev.Yellow,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Editor Video · Pemecah Bagian (Scenes)",
                color = Ev.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Aplikasi editor video untuk memecah video menjadi beberapa bagian (potongan/scenes) secara manual maupun deteksi otomatis, mengedit tiap potongan, dan mengekspornya sekaligus atau terpisah.",
                color = Ev.InfoText,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            ReadmeBullet(
                num = "1",
                title = "Impor & Multiseleksi (+)",
                desc = "Ketuk tombol + untuk memilih 1 atau beberapa video sekaligus ke dalam timeline."
            )
            ReadmeBullet(
                num = "2",
                title = "Potong Manual & Deteksi Scene Otomatis",
                desc = "Ketuk Scene untuk memindai pergantian adegan otomatis (min 0.1, mendukung Deteksi Ulang & Grid Preview Deteksi)."
            )
            ReadmeBullet(
                num = "3",
                title = "Membalikkan: Mundur & Loop",
                desc = "Ketuk Membalikkan untuk merender potongan menjadi video Mundur atau Loop (gabungan video Normal + Mundur)."
            )
            ReadmeBullet(
                num = "4",
                title = "Kustomisasi Overlay Teks Kaya",
                desc = "Atur font, ukuran, warna isi, warna & ketebalan outline, kotak latar, bayangan, serta geser langsung posisi teks di layar pratinjau."
            )
            ReadmeBullet(
                num = "5",
                title = "Tema Aplikasi (Medium Maroon & Gelap)",
                desc = "Ketuk tombol Tema di bar atas untuk memilih tema Medium Maroon atau Gelap Standar."
            )
            ReadmeBullet(
                num = "6",
                title = "Ekspor Fleksibel",
                desc = "Simpan sebagai 1 Video Utuh, Semua Potongan (berkas MP4 terpisah per bagian), atau Potongan Saat Ini."
            )
        }
    }
}

@Composable
private fun ReadmeBullet(num: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Ev.Line),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = num,
                color = Ev.Yellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Ev.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                color = Ev.Muted,
                fontSize = 11.sp,
                lineHeight = 15.5.sp
            )
        }
    }
}

@Composable
private fun NoteRow(
    item: NoteItem,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isBug = item.type == NoteType.BUG
    val tagBg = if (isBug) Ev.Accent.copy(alpha = 0.25f) else Ev.Yellow.copy(alpha = 0.2f)
    val tagColor = if (isBug) Ev.Text else Ev.Yellow

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
            .clickable { onEdit() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tagBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.type.label,
                        color = tagColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (item.createdAt.isNotEmpty()) {
                    Text(
                        text = item.createdAt,
                        color = Ev.Muted,
                        fontSize = 10.5.sp
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.done) "✓ Selesai" else "Tandai Selesai",
                    color = if (item.done) Ev.Yellow else Ev.InfoText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onToggleDone() }
                )
                Text(
                    text = "Hapus",
                    color = Ev.Yellow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDelete() }
                )
            }
        }

        Text(
            text = item.text,
            color = if (item.done) Ev.Muted else Ev.Text,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            style = TextStyle(
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
            )
        )
    }
}

private fun copyNotesToClipboard(context: Context, notes: List<NoteItem>) {
    val sb = StringBuilder()
    sb.appendLine("# Catatan Editor Video (Bug & Pengembangan)")
    for ((idx, n) in notes.withIndex()) {
        val status = if (n.done) "[x]" else "[ ]"
        sb.appendLine("${idx + 1}. $status [${n.type.label}] ${n.text} (${n.createdAt})")
    }
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Catatan Editor Video", sb.toString().trim()))
    } catch (e: Exception) {
        // diabaikan
    }
}

/** Dialog konfirmasi "Terapkan ke semua potongan?" (max-height 80% + scroll-y). */
@Composable
fun ConfirmDialog(text: String, onNo: () -> Unit, onYes: () -> Unit) {
    Scrim(onTap = onNo, alignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.86f)
                .heightIn(max = maxHeight * 0.80f)
                .clip(RoundedCornerShape(16.dp))
                .background(Ev.Panel)
                .border(1.dp, Ev.Line, RoundedCornerShape(16.dp))
                .absorbTaps()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Text(
                text = "Terapkan ke semua potongan?",
                color = Ev.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = text,
                color = Ev.InfoText,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton("Batal", false, Modifier.weight(1f), onClick = onNo)
                ActionButton("Terapkan", true, Modifier.weight(1f), onClick = onYes)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet ekspor (max-height 80% + scroll-y)
// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Ev.Muted,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun ModeRow(on: Boolean, enabled: Boolean, title: String, sub: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(if (on) Ev.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.5.dp, if (on) Ev.Accent else Ev.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(2.dp, if (on) Ev.Accent else Ev.Muted, CircleShape)
        ) {
            if (on) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .background(Ev.Accent, CircleShape)
                )
            }
        }
        Column {
            Text(text = title, color = Ev.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = sub, color = Ev.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Chip(on: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (on) Ev.Accent else Color.Transparent)
            .border(1.5.dp, if (on) Ev.Accent else Ev.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (on) Color.White else Ev.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ExportSheet(vm: EditorViewModel, onStart: () -> Unit) {
    val st by vm.exportState.collectAsState()
    val running = st is ExportState.Running
    val info = vm.exportInfo()
    val plan = info.plan

    FloatingSheetPanel(onTapOutside = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Ekspor", color = Ev.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            SmallIconButton(R.drawable.ic_close) { vm.closeExport() }
        }

        SectionLabel("MODE EKSPOR")
        ModeRow(
            on = vm.exportMode == ExportMode.VIDEO,
            enabled = !running,
            title = "Ekspor Video",
            sub = info.subVideo
        ) { vm.exportMode = ExportMode.VIDEO }
        ModeRow(
            on = vm.exportMode == ExportMode.ALL,
            enabled = !running,
            title = "Ekspor Semua Potongan",
            sub = info.subAll
        ) { vm.exportMode = ExportMode.ALL }
        ModeRow(
            on = vm.exportMode == ExportMode.CURRENT,
            enabled = !running,
            title = "Ekspor Potongan Saat Ini",
            sub = info.subCurrent
        ) { vm.exportMode = ExportMode.CURRENT }
        ModeRow(
            on = vm.exportMode == ExportMode.BY_TAG,
            enabled = !running,
            title = "Ekspor Berdasarkan Tag",
            sub = info.subByTag
        ) { vm.selectExportByTagMode() }

        if (vm.exportMode == ExportMode.BY_TAG) {
            val usedTags = vm.usedTagsInTimeline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ev.Bg)
                    .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PILIH TAG KLIP",
                        color = Ev.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    if (usedTags.isNotEmpty()) {
                        val allSelected = vm.exportSelectedTags.size >= usedTags.size
                        Text(
                            text = if (allSelected) "Kosongkan" else "Pilih Semua",
                            color = Ev.Accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !running) { vm.toggleAllExportTags() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (usedTags.isEmpty()) {
                    Text(
                        text = "Belum ada klip yang diberi Tag di timeline. Buka Mode Grid untuk menandai klip terlebih dahulu.",
                        color = Ev.Yellow,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else {
                    SimpleFlow(8.dp, 8.dp) {
                        for ((tagName, count) in usedTags) {
                            val selected = vm.exportSelectedTags.any {
                                it.equals(tagName, ignoreCase = true)
                            }
                            val tColor = vm.tagColor(tagName)
                            val pillShape = RoundedCornerShape(16.dp)
                            Row(
                                modifier = Modifier
                                    .clip(pillShape)
                                    .background(
                                        if (selected) tColor.copy(alpha = 0.24f) else Ev.Ghost
                                    )
                                    .border(
                                        1.5.dp,
                                        if (selected) tColor else Ev.Line,
                                        pillShape
                                    )
                                    .clickable(enabled = !running) { vm.toggleExportTag(tagName) }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(tColor)
                                )
                                Text(
                                    text = "#$tagName ($count)",
                                    color = if (selected) Ev.Text else Ev.Muted,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "FORMAT KELUARAN TAG",
                        color = Ev.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SimpleFlow(8.dp, 8.dp) {
                        for (style in ByTagExportStyle.entries) {
                            Chip(
                                on = vm.exportByTagStyle == style,
                                enabled = !running,
                                label = style.label
                            ) {
                                vm.exportByTagStyle = style
                            }
                        }
                    }
                    Text(
                        text = vm.exportByTagStyle.sub,
                        color = Ev.Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // Shortcut bernama untuk menyamakan Kualitas & FPS dengan video sumber asli
        Spacer(modifier = Modifier.height(6.dp))
        val origActive = vm.isOriginalExportSpec
        val shortcutShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shortcutShape)
                .background(if (origActive) Ev.Accent.copy(alpha = 0.16f) else Ev.Ghost)
                .border(1.5.dp, if (origActive) Ev.Accent else Ev.Line, shortcutShape)
                .clickable(enabled = !running) { vm.applyOriginalExportSpec() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (origActive) Ev.Accent else Ev.Bg),
                    contentAlignment = Alignment.Center
                ) {
                    Ico(
                        id = R.drawable.ic_spark,
                        tint = if (origActive) Color.White else Ev.Accent,
                        size = 17.dp
                    )
                }
                Column {
                    Text(
                        text = "Sesuai Video Asli",
                        color = Ev.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = "Setel Kualitas & FPS asli (${vm.originalSpecLabel()})",
                        color = Ev.Muted,
                        fontSize = 11.5.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (origActive) Ev.Accent else Ev.Bg)
                    .border(1.dp, if (origActive) Ev.Accent else Ev.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (origActive) "Aktif ✓" else "Gunakan",
                    color = if (origActive) Color.White else Ev.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        SectionLabel("KUALITAS")
        SimpleFlow(8.dp, 8.dp) {
            Chip(
                on = vm.qIdx == ExportMath.ORIGINAL_IDX,
                enabled = !running,
                label = "Asli"
            ) {
                vm.qIdx = ExportMath.ORIGINAL_IDX
            }
            for (i in ExportMath.QUALITY_LABELS.indices) {
                Chip(on = vm.qIdx == i, enabled = !running, label = ExportMath.QUALITY_LABELS[i]) {
                    vm.qIdx = i
                }
            }
        }

        SectionLabel("FPS")
        SimpleFlow(8.dp, 8.dp) {
            Chip(
                on = vm.fIdx == ExportMath.ORIGINAL_IDX,
                enabled = !running,
                label = "Asli"
            ) {
                vm.fIdx = ExportMath.ORIGINAL_IDX
            }
            for (i in ExportMath.FPS_VALUES.indices) {
                Chip(on = vm.fIdx == i, enabled = !running, label = ExportMath.FPS_VALUES[i].toString() + " fps") {
                    vm.fIdx = i
                }
            }
        }

        Text(
            text = plan.width.toString() + "×" + plan.height + " · " + Fmt.fps(plan.fps) +
                " fps · MP4 · ±" + Fmt.one(info.mb) + " MB",
            color = Ev.InfoText,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
        for (note in plan.notes) {
            Text(
                text = note,
                color = Ev.Yellow,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        when (val s = st) {
            is ExportState.Running -> {
                val frac = ((s.fileIndex + s.fraction) / max(1, s.fileCount).toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Ev.Ghost)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(frac)
                            .background(Ev.Accent)
                    )
                }
                val label = if (s.fileIndex == 0 && s.fraction <= 0f) {
                    "Menyiapkan…"
                } else {
                    "Mengekspor " + min(s.fileCount, s.fileIndex + 1) + "/" + s.fileCount +
                        " — " + Math.round(frac * 100f) + "%"
                }
                Text(
                    text = label,
                    color = Ev.InfoText,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            is ExportState.Done -> {
                Text(
                    text = "Ekspor selesai",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                for (f in s.files) {
                    Text(
                        text = f,
                        color = Ev.InfoText,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                for (n in s.notes) {
                    Text(
                        text = n,
                        color = Ev.Yellow,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            is ExportState.Failed -> {
                Text(
                    text = s.message,
                    color = Color(0xFFFF7A7A),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            else -> {}
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (st) {
                is ExportState.Running -> {
                    ActionButton("Batalkan", false, Modifier.weight(1f)) { vm.cancelExport() }
                    ActionButton("Mulai Ekspor", true, Modifier.weight(1f), enabled = false) { }
                }

                is ExportState.Done -> {
                    ActionButton("Tutup", true, Modifier.weight(1f)) { vm.closeExport() }
                }

                is ExportState.Failed -> {
                    ActionButton("Tutup", false, Modifier.weight(1f)) { vm.closeExport() }
                    ActionButton("Coba lagi", true, Modifier.weight(1f), enabled = info.canStart, onClick = onStart)
                }

                else -> {
                    ActionButton("Batal", false, Modifier.weight(1f)) { vm.closeExport() }
                    ActionButton("Mulai Ekspor", true, Modifier.weight(1f), enabled = info.canStart, onClick = onStart)
                }
            }
        }
    }
}
