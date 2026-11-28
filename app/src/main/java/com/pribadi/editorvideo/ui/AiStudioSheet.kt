package com.pribadi.editorvideo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.ai.AiCandidateBatch
import com.pribadi.editorvideo.ai.AiDurationSplit
import com.pribadi.editorvideo.ai.AiEdgeFrameSource
import com.pribadi.editorvideo.ai.AiExecutionPlan
import com.pribadi.editorvideo.ai.AiGeneratedSegment
import com.pribadi.editorvideo.ai.AiGenerationMode
import com.pribadi.editorvideo.ai.AiInsertPreset
import com.pribadi.editorvideo.ai.AiMultiStrategy
import com.pribadi.editorvideo.ai.AiPlanner
import com.pribadi.editorvideo.ai.AiPromptItem
import com.pribadi.editorvideo.ai.AiReplaceAudioMode
import com.pribadi.editorvideo.ai.AiRunPhase
import com.pribadi.editorvideo.ai.AiRunnerClipboardData
import com.pribadi.editorvideo.ai.AiSpaceOption
import com.pribadi.editorvideo.ai.AiTab
import com.pribadi.editorvideo.ai.AiTimelineJoinStyle
import com.pribadi.editorvideo.ai.AiWorkMode
import com.pribadi.editorvideo.ai.WorkerSyncStatus
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.VideoInfo
import com.pribadi.editorvideo.vm.EditorViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val REPLACE_QUICK_DURS = listOf(3.5, 5.0, 7.0, 10.0)
private val PROMPT_CATEGORIES = listOf("Kamera", "Transisi", "Sinematik", "Aksi", "Kustom")

private fun formatClockSec(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return String.format(Locale.US, "%02d:%02d", m, r)
}

private fun copyPlainText(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun readPlainTextClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

@Composable
fun AiStudioSheet(
    vm: EditorViewModel,
    onExportPromptsFile: () -> Unit,
    onImportPromptsFile: () -> Unit,
    onClose: () -> Unit
) {
    // Baca vm.rev agar perubahan timeline/frame memicu rekomposisi halus
    val _rev = vm.rev

    FloatingSheetPanel(onTapOutside = onClose) {
        // Header Studio Video AI
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
                        text = "Studio Video AI",
                        color = Ev.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    val connOk = vm.aiRunnerConfig.isComplete
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (connOk) Color(0xFF10B981).copy(alpha = 0.20f)
                                else Ev.Yellow.copy(alpha = 0.18f)
                            )
                            .border(
                                1.dp,
                                if (connOk) Color(0xFF10B981) else Ev.Yellow,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { vm.aiActiveTab = AiTab.RUNNER }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (connOk) "✓ Runner Siap" else "⚙ Atur Runner",
                            color = if (connOk) Color(0xFF6EE7B7) else Ev.Yellow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Mode Sisipkan & Replace Scene · Wan 2.2 ZeroGPU · Multi-Hasil",
                    color = Ev.Muted,
                    fontSize = 11.sp
                )
            }
            SmallIconButton(R.drawable.ic_close, onClose)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4 Tab Utama: Buat | Prompt | Status | Runner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (tab in AiTab.entries) {
                val active = vm.aiActiveTab == tab
                val badgeText = when (tab) {
                    AiTab.CREATE -> null
                    AiTab.PROMPTS -> vm.aiPromptLibrary.size.toString()
                    AiTab.STATUS -> when {
                        vm.aiIsRunning -> "●"
                        vm.aiCandidates.size >= 2 -> "${vm.aiCandidates.size}K"
                        vm.aiGeneratedSegments.isNotEmpty() -> vm.aiGeneratedSegments.size.toString()
                        else -> null
                    }
                    AiTab.RUNNER -> "${vm.aiCopiedRunnerIds.size}/4"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Ev.Accent else Color.Transparent)
                        .clickable { vm.aiActiveTab = tab }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tab.label,
                            color = if (active) Color.White else Ev.Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (badgeText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(
                                        if (active) Color.Black.copy(alpha = 0.28f)
                                        else Ev.Ghost
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = if (tab == AiTab.STATUS && vm.aiIsRunning) Ev.Yellow else Color.White,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (vm.aiActiveTab) {
            AiTab.CREATE -> AiCreateTabContent(vm)
            AiTab.PROMPTS -> AiPromptsTabContent(
                vm = vm,
                onExportFile = onExportPromptsFile,
                onImportFile = onImportPromptsFile
            )
            AiTab.STATUS -> AiStatusTabContent(vm)
            AiTab.RUNNER -> AiRunnerTabContent(vm)
        }
    }
}

// =============================================================================================
// TAB 1 — BUAT (Mode Sisipkan + Mode Replace Scene Slider & Shortcut + Multi-Hasil + Prompt)
// =============================================================================================

@Composable
private fun AiCreateTabContent(vm: EditorViewModel) {
    val plan = vm.currentAiExecutionPlan()

    // 1. Pemilih Mode Kerja AI: Mode Sisipkan vs Mode Replace Scene
    AiSectionLabel("MODE KERJA AI")
    Spacer(modifier = Modifier.height(5.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AiModeCard(
            modifier = Modifier.weight(1f),
            active = vm.aiWorkMode == AiWorkMode.INSERT,
            title = "➕ Mode Sisipkan",
            sub = "Belah & selipkan di titik Seek"
        ) {
            vm.selectAiWorkMode(AiWorkMode.INSERT)
        }
        AiModeCard(
            modifier = Modifier.weight(1f),
            active = vm.aiWorkMode == AiWorkMode.REPLACE_SCENE,
            title = "🔄 Replace Scene",
            sub = "Ganti klip terpilih (≤10 dtk)"
        ) {
            vm.selectAiWorkMode(AiWorkMode.REPLACE_SCENE)
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // 1B. Pembagian Durasi Sama: 1 Video / Dobel (maks 5 dtk × 2) / Tripel (maks 3.5 dtk × 3)
    AiSectionLabel("PEMBAGIAN DURASI (SAMA RATA)")
    Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (sp in AiDurationSplit.entries) {
                    val active = vm.aiDurationSplit == sp
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (active) Ev.Accent.copy(alpha = 0.22f) else Ev.Bg)
                            .border(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) Ev.Accent else Ev.Line,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .clickable { vm.selectAiDurationSplit(sp) }
                            .padding(horizontal = 7.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when (sp) {
                                AiDurationSplit.AUTO_1 -> "🎬 1 Video"
                                AiDurationSplit.DOUBLE_5 -> "🎞️ 2 × ≤5s"
                                AiDurationSplit.TRIPLE_35 -> "🎞️ 3 × ≤3.5s"
                                AiDurationSplit.QUINTUPLE_2 -> "🎞️ 5 × ≤2s"
                            },
                            color = if (active) Color.White else Ev.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when (sp) {
                                AiDurationSplit.AUTO_1 -> "maks 10.0s"
                                AiDurationSplit.DOUBLE_5 -> "total maks 10.0s"
                                AiDurationSplit.TRIPLE_35 -> "total maks 10.5s"
                                AiDurationSplit.QUINTUPLE_2 -> "total maks 10.0s"
                            },
                            color = if (active) Ev.Yellow else Ev.Muted,
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = vm.aiDurationSplit.sub,
        color = Ev.Muted,
        fontSize = 10.sp,
        lineHeight = 13.5.sp
    )

    // Total durasi yang dibagi rata (Dobel / Tripel)
    if (vm.aiDurationSplit != AiDurationSplit.AUTO_1) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TOTAL DURASI DIBAGI RATA",
                color = Ev.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${plan.segDurs.joinToString(" + ") { Fmt.one(it) }} = ${Fmt.one(plan.targetTotalSec)} dtk",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = vm.aiSplitTotalSec.toFloat()
                .coerceIn(1.0f, vm.aiDurationSplit.maxTotalSec.toFloat()),
            onValueChange = { vm.updateAiSplitTotalSec(it.toDouble()) },
            valueRange = 1.0f..vm.aiDurationSplit.maxTotalSec.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Ev.Yellow,
                activeTrackColor = Ev.Accent,
                inactiveTrackColor = Ev.Line
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (d in listOf(3.5, 5.0, 7.0, 9.0, 10.0, 10.5)) {
                if (d > vm.aiDurationSplit.maxTotalSec + 0.001) continue
                AiSmallChip(
                    label = "${Fmt.one(d)}s",
                    active = abs(vm.aiSplitTotalSec - d) < 0.06
                ) {
                    vm.updateAiSplitTotalSec(d)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 2. Konten Sumber Frame & Pengaturan Durasi sesuai Mode Kerja
    if (vm.aiWorkMode == AiWorkMode.INSERT) {
        AiInsertModeSection(vm, plan)
    } else {
        AiReplaceSceneSection(vm, plan)
    }

    // 3. Pengatur Multi-Hasil (otomatis tampil bila menghasilkan 2 atau 3 video)
    if (plan.count >= 2) {
        Spacer(modifier = Modifier.height(12.dp))
        AiMultiOutputPreConfigCard(vm, plan)
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 4. Editor Prompt (mendukung Satu Prompt untuk Semua atau Per-Segmen #1..#3)
    AiPromptComposerSection(vm, plan)

    Spacer(modifier = Modifier.height(12.dp))

    // 5. Pilihan Space HuggingFace & Keamanan
    AiSpaceAndSafetySection(vm)

    Spacer(modifier = Modifier.height(12.dp))

    // Info Batas Waktu (Dual Timeout 3 Menit)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "⚡ 1 Workflow tunggal (maks 10 dtk · khusus 3.5 dtk maks 10.5 dtk): setelah Video #1 berhasil, Video berikutnya otomatis digenerate di workflow yang sama · Timeout 3m workflow & 3m/video.",
            color = Ev.Muted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Tombol Utama Generate (menambahkan Kandidat baru bila hasil sebelumnya sudah ada!)
    val nextCandNum = vm.aiCandidates.size + 1
    val candTag = if (vm.aiCandidates.isNotEmpty()) "Kandidat #$nextCandNum · " else ""
    val btnLabel = if (vm.aiWorkMode == AiWorkMode.INSERT) {
        "✨ Generate ${candTag}${plan.count} Video (${Fmt.one(plan.targetTotalSec)} dtk)"
    } else {
        "🔄 Generate ${candTag}Replace (${Fmt.one(plan.targetTotalSec)} dtk · ${plan.count} Video)"
    }
    if (vm.aiCandidates.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Ev.Accent.copy(alpha = 0.14f))
                .border(1.dp, Ev.Accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                .clickable { vm.aiActiveTab = AiTab.STATUS }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🗂 ${vm.aiCandidates.size} kandidat hasil tersimpan — generate lagi akan menambah kandidat baru tanpa menimpa!",
                color = Ev.Text,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Lihat →",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    ActionButton(
        text = if (vm.aiIsRunning) "⏳ Sedang Memproses… Lihat Tab Status" else btnLabel,
        primary = true,
        modifier = Modifier.fillMaxWidth(),
        enabled = true,
        onClick = {
            if (vm.aiIsRunning) {
                vm.aiActiveTab = AiTab.STATUS
            } else {
                vm.startAiGeneration()
            }
        }
    )
}

@Composable
private fun AiInsertModeSection(vm: EditorViewModel, plan: AiExecutionPlan) {
    val seekBmp = vm.aiSeekBitmap
    val lastBmp = vm.aiInsertLastBitmap

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiSectionLabel("FRAME REFERENSI POSISI SEEK")
        if (vm.hasVideo && seekBmp != null) {
            Text(
                text = "✓ ${vm.aiCaptureSourceLabel}",
                color = Ev.Yellow,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    Spacer(modifier = Modifier.height(6.dp))

    // Kotak Frame Awal (100% Identik Preview)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(175.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Ev.Line, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (seekBmp != null && !seekBmp.isRecycled) {
            Image(
                bitmap = seekBmp.asImageBitmap(),
                contentDescription = "Frame Awal posisi seek",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = if (vm.hasVideo) "Menyinkronkan frame presisi…"
                else "Impor video terlebih dahulu untuk mengambil frame posisi seek",
                color = Ev.Muted,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        if (vm.hasVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xD9000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "Titik Sisip ${Fmt.preciseTime(vm.aiSeekTimeSec)} · Klip #${vm.aiSeekClipIndex + 1}",
                    color = Ev.Yellow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xD9000000))
                    .border(1.dp, Ev.Line, RoundedCornerShape(7.dp))
                    .clickable { vm.captureAiSeekFrame(waitSeekSettle = true) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
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

    if (vm.hasVideo) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AiMiniBtn("⏮ -5f", Modifier.weight(1f)) { vm.stepAiFrame(-5) }
            AiMiniBtn("◀ -1f", Modifier.weight(1.15f)) { vm.stepAiFrame(-1) }
            AiMiniBtn("+1f ▶", Modifier.weight(1.15f)) { vm.stepAiFrame(1) }
            AiMiniBtn("+5f ⏭", Modifier.weight(1f)) { vm.stepAiFrame(5) }
        }

        // Frame Akhir Opsional (last_image)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 38.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
                    .border(1.dp, Ev.Line, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (lastBmp != null && !lastBmp.isRecycled) {
                    Image(
                        bitmap = lastBmp.asImageBitmap(),
                        contentDescription = "Frame Akhir",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "Opsional", color = Ev.Muted, fontSize = 8.5.sp)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Frame Akhir Tujuan (last_image)",
                    color = Ev.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (lastBmp != null && vm.aiInsertLastTimeSec >= 0.0) {
                        "Dikunci di ${Fmt.preciseTime(vm.aiInsertLastTimeSec)}"
                    } else {
                        "Opsional · arahkan akhir gerakan ke frame tertentu"
                    },
                    color = Ev.Muted,
                    fontSize = 10.sp
                )
            }
            if (lastBmp != null) {
                AiSmallChip(label = "✕ Hapus", active = false) { vm.clearInsertLastFrame() }
            }
            AiSmallChip(label = "📌 Kunci Seek", active = lastBmp != null) {
                vm.captureInsertLastFrame()
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Ringkasan pembagian durasi (dipakai untuk semua pilihan 1 Video / Dobel / Tripel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HASIL PEMBAGIAN",
                color = Ev.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${plan.count} video · ${Fmt.one(plan.targetTotalSec)} dtk",
                color = Ev.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            plan.segDurs.forEachIndexed { i, d ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ev.Accent.copy(alpha = 0.20f))
                        .border(1.dp, Ev.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Video #${i + 1} · ${Fmt.one(d)}s",
                        color = Ev.Text,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "🎬 ${plan.note}",
            color = Ev.InfoText,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
    }

}

@Composable
private fun AiReplaceSceneSection(vm: EditorViewModel, plan: AiExecutionPlan) {
    val hasScene = vm.hasVideo && vm.core.segs.isNotEmpty()
    val segIdx = if (hasScene) vm.core.sel.coerceIn(0, vm.core.segs.lastIndex) else 0
    val sceneDur = vm.selectedSceneDurationSec()
    val canShortcut = vm.canUseSceneDurationShortcut()
    val shortcutActive = vm.isSceneDurationShortcutActive()

    // Pemilih Scene Aktif di Timeline
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiSectionLabel("SCENE TARGET DI TIMELINE")
        if (hasScene) {
            Text(
                text = "Scene #${segIdx + 1}/${vm.core.segs.size} · Asli ${Fmt.one(sceneDur)} dtk",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    Spacer(modifier = Modifier.height(6.dp))

    if (hasScene && vm.core.segs.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiMiniBtn(
                label = "◀ Scene Sebelumnya",
                modifier = Modifier.weight(1f),
                enabled = segIdx > 0
            ) {
                vm.stepReplaceTargetScene(-1)
            }
            AiMiniBtn(
                label = "Scene Berikutnya ▶",
                modifier = Modifier.weight(1f),
                enabled = segIdx < vm.core.segs.lastIndex
            ) {
                vm.stepReplaceTargetScene(1)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // =========================================================================
    // REPLACE MULTI-SCENE BERURUTAN (item #3) — ganti beberapa scene sekaligus
    // =========================================================================
    if (hasScene && vm.core.segs.size > 1) {
        val multiOn = vm.aiMultiSceneMode
        val maxCount = vm.maxMultiSceneAllowed()
        val multiPlan = vm.currentAiMultiScenePlan()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(if (multiOn) Ev.Accent.copy(alpha = 0.16f) else Ev.Bg)
                .border(
                    width = if (multiOn) 1.5.dp else 1.dp,
                    color = if (multiOn) Ev.Accent else Ev.Line,
                    shape = RoundedCornerShape(11.dp)
                )
                .padding(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔁 Replace Multi-Scene Berurutan",
                        color = Ev.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ganti beberapa scene sekaligus mulai dari scene terpilih (tiap scene 1 workflow)",
                        color = Ev.Muted,
                        fontSize = 9.5.sp,
                        lineHeight = 12.5.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (multiOn) Ev.Accent else Ev.Ghost)
                        .border(1.dp, if (multiOn) Ev.Accent else Ev.Line, RoundedCornerShape(9.dp))
                        .clickable { vm.toggleAiMultiSceneMode() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (multiOn) "ON" else "OFF",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (multiOn) {
                Spacer(modifier = Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiMiniBtn(
                        label = "− Scene",
                        modifier = Modifier.weight(0.8f),
                        enabled = vm.aiMultiSceneCount > 1
                    ) { vm.stepAiMultiSceneCount(-1) }
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Ev.Ghost)
                            .border(1.dp, Ev.Line, RoundedCornerShape(8.dp))
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${vm.aiMultiSceneCount} scene (maks $maxCount)",
                            color = Ev.Yellow,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    AiMiniBtn(
                        label = "+ Scene",
                        modifier = Modifier.weight(0.8f),
                        enabled = vm.aiMultiSceneCount < maxCount
                    ) { vm.stepAiMultiSceneCount(1) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (multiPlan != null) {
                    Text(
                        text = "Target: Scene #${multiPlan.startSegIdx + 1}–#${multiPlan.endSegIdx + 1} · " +
                            multiPlan.perSceneDurs.joinToString(" + ") { Fmt.one(it) } +
                            " = ${Fmt.one(multiPlan.totalSec)} dtk (${plan.count} video AI)",
                        color = Ev.InfoText,
                        fontSize = 10.sp,
                        lineHeight = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        AiSmallChip(
                            label = "◀ Mulai Scene ${multiPlan.startSegIdx + 1}",
                            active = false
                        ) { vm.stepReplaceTargetScene(-1) }
                        AiSmallChip(
                            label = "Scene ${multiPlan.startSegIdx + 1} ▶",
                            active = false
                        ) { vm.stepReplaceTargetScene(1) }
                    }
                } else {
                    Text(
                        text = "Aktifkan untuk membuat ${vm.aiMultiSceneCount} scene sekaligus berurutan.",
                        color = Ev.Muted,
                        fontSize = 10.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

    // =========================================================================
    // OPSI GENERATE BERULANG (item #5) - Generate di posisi sama beberapa kali
    // =========================================================================
    if (hasScene && vm.aiWorkMode == AiWorkMode.REPLACE_SCENE) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(if (vm.aiRepeatGenerate) Ev.Accent.copy(alpha = 0.16f) else Ev.Bg)
                .border(
                    width = if (vm.aiRepeatGenerate) 1.5.dp else 1.dp,
                    color = if (vm.aiRepeatGenerate) Ev.Accent else Ev.Line,
                    shape = RoundedCornerShape(11.dp)
                )
                .padding(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔁 Generate Berulang (Variasi)",
                        color = Ev.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Generate beberapa kali di posisi yang sama untuk mendapatkan variasi berbeda",
                        color = Ev.Muted,
                        fontSize = 9.5.sp,
                        lineHeight = 12.5.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (vm.aiRepeatGenerate) Ev.Accent else Ev.Ghost)
                        .border(1.dp, if (vm.aiRepeatGenerate) Ev.Accent else Ev.Line, RoundedCornerShape(9.dp))
                        .clickable { vm.toggleAiRepeatGenerate() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (vm.aiRepeatGenerate) "ON" else "OFF",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (vm.aiRepeatGenerate) {
                Spacer(modifier = Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiMiniBtn(
                        label = "−",
                        modifier = Modifier.weight(0.8f),
                        enabled = vm.aiRepeatCount > 1
                    ) { vm.stepAiRepeatCount(-1) }
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Ev.Ghost)
                            .border(1.dp, Ev.Line, RoundedCornerShape(8.dp))
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${vm.aiRepeatCount}× generate (${vm.aiDurationSplit.label})",
                            color = Ev.Yellow,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    AiMiniBtn(
                        label = "+",
                        modifier = Modifier.weight(0.8f),
                        enabled = vm.aiRepeatCount < 5
                    ) { vm.stepAiRepeatCount(1) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Akan menghasilkan ${vm.aiRepeatCount} kandidat terpisah dengan durasi ${vm.aiDurationSplit.label} di posisi scene yang sama.",
                    color = Ev.InfoText,
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp
                )
            }
        }
    }

    // =========================================================================
    // OPSI FRAME AWAL & FRAME AKHIR DARI SCENE TETANGGA (item #4)
    // =========================================================================
    if (hasScene) {
        val prevSeg = vm.core.segs.getOrNull(segIdx - 1)
        val nextSeg = vm.core.segs.getOrNull(segIdx + 1)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
                .padding(9.dp)
        ) {
            Text(
                text = "🔗 FRAME DARI SCENE TETANGGA (OPSIONAL)",
                color = Ev.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(text = "Frame Awal AI", color = Ev.Text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AiSmallChip(
                    label = "📸 Frame Sendiri",
                    active = vm.aiFirstFrameSource == AiEdgeFrameSource.FRAME_SENDIRI
                ) { vm.selectAiFirstFrameSource(AiEdgeFrameSource.FRAME_SENDIRI) }
                AiSmallChip(
                    label = "⏮ Akhir Scene ${segIdx}",
                    active = vm.aiFirstFrameSource == AiEdgeFrameSource.SCENE_SEBELUMNYA
                ) { vm.selectAiFirstFrameSource(AiEdgeFrameSource.SCENE_SEBELUMNYA) }
            }
            if (prevSeg == null) {
                Text(
                    text = "Tidak ada scene sebelumnya (scene ini yang pertama).",
                    color = Ev.Muted,
                    fontSize = 9.sp
                )
            }

            Spacer(modifier = Modifier.height(7.dp))
            Text(text = "Frame Akhir AI", color = Ev.Text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AiSmallChip(
                    label = "📸 Frame Sendiri",
                    active = vm.aiLastFrameSource == AiEdgeFrameSource.FRAME_SENDIRI
                ) { vm.selectAiLastFrameSource(AiEdgeFrameSource.FRAME_SENDIRI) }
                AiSmallChip(
                    label = "Awal Scene ${segIdx + 2} ⏭",
                    active = vm.aiLastFrameSource == AiEdgeFrameSource.SCENE_BERIKUTNYA
                ) { vm.selectAiLastFrameSource(AiEdgeFrameSource.SCENE_BERIKUTNYA) }
            }
            if (nextSeg == null) {
                Text(
                    text = "Tidak ada scene berikutnya (scene ini yang terakhir).",
                    color = Ev.Muted,
                    fontSize = 9.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Pratinjau 2 Frame Scene: Frame Awal AI & Frame Akhir AI
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Kartu Frame Awal Scene
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                .padding(7.dp)
        ) {
            Text(
                text = "FRAME AWAL AI",
                color = Ev.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Jika using frame dari scene sebelumnya, tampilkan itu; else frame sendiri
                val firstBmp = when (vm.aiFirstFrameSource) {
                    AiEdgeFrameSource.SCENE_SEBELUMNYA -> vm.aiNeighborFirstBitmap
                    else -> vm.aiReplaceFirstBitmap ?: vm.aiSeekBitmap
                }
                if (firstBmp != null && !firstBmp.isRecycled) {
                    Image(
                        bitmap = firstBmp.asImageBitmap(),
                        contentDescription = "Frame Awal AI",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "Belum ada video", color = Ev.Muted, fontSize = 10.sp)
                }
            }
            // Tampilkan label sumber frame
            if (vm.aiFirstFrameSource != AiEdgeFrameSource.FRAME_SENDIRI) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sumber: ${when (vm.aiFirstFrameSource) {
                        AiEdgeFrameSource.SCENE_SEBELUMNYA -> "Akhir Scene Sebelumnya"
                        AiEdgeFrameSource.SCENE_BERIKUTNYA -> "Awal Scene Berikutnya"
                        else -> "Frame Sendiri"
                    }}",
                    color = Ev.Yellow,
                    fontSize = 9.sp
                )
            }
        }

        // Kartu Frame Akhir AI (jika menggunakan frame dari scene berikutnya)
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                .padding(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FRAME AKHIR AI",
                    color = Ev.Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (vm.aiLastFrameSource != AiEdgeFrameSource.FRAME_SENDIRI) {
                        "Dari Scene Berikutnya"
                    } else if (vm.aiReplaceBridgeLastFrame) "ON" else "OFF",
                    color = if (vm.aiLastFrameSource != AiEdgeFrameSource.FRAME_SENDIRI) {
                        Color(0xFF6EE7B7)
                    } else if (vm.aiReplaceBridgeLastFrame) Color(0xFF6EE7B7) else Ev.Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { vm.toggleReplaceBridgeLastFrame() }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black)
                    .clickable { vm.toggleReplaceBridgeLastFrame() },
                contentAlignment = Alignment.Center
            ) {
                // Jika using frame dari scene berikutnya, tampilkan itu
                val lastBmp = when (vm.aiLastFrameSource) {
                    AiEdgeFrameSource.SCENE_BERIKUTNYA -> vm.aiNeighborLastBitmap
                    else -> vm.aiReplaceLastBitmap
                }
                if (vm.aiReplaceBridgeLastFrame && lastBmp != null && !lastBmp.isRecycled) {
                    Image(
                        bitmap = lastBmp.asImageBitmap(),
                        contentDescription = "Frame Akhir AI",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = if (vm.aiReplaceBridgeLastFrame) "Menyinkronkan…" else "Nonaktif (Gerak Bebas)",
                        color = Ev.Muted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
            // Tampilkan label sumber frame
            if (vm.aiLastFrameSource != AiEdgeFrameSource.FRAME_SENDIRI) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sumber: ${when (vm.aiLastFrameSource) {
                        AiEdgeFrameSource.SCENE_SEBELUMNYA -> "Akhir Scene Sebelumnya"
                        AiEdgeFrameSource.SCENE_BERIKUTNYA -> "Awal Scene Berikutnya"
                        else -> "Frame Sendiri"
                    }}",
                    color = Ev.Yellow,
                    fontSize = 9.sp
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // =========================================================================================
    // INPUT RANGE DURASI REPLACE SCENE (1.0 – 10.0 DTK) + SHORTCUT DURASI SCENE (BILA <= 10 DTK)
    // =========================================================================================
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DURASI REPLACE SCENE (MAKS 10 DTK)",
                    color = Ev.Muted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${Fmt.one(vm.aiReplaceTargetDurSec)} detik",
                    color = Ev.Yellow,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Shortcut Durasi Seperti Scene (aktif jika durasi scene <= 10.0 detik!)
            val shortcutLabel = if (!hasScene) {
                "⚡ Sesuai Scene (-)"
            } else if (canShortcut) {
                "⚡ Sesuai Scene (${Fmt.one(sceneDur)} dtk)"
            } else {
                "Scene ${Fmt.one(sceneDur)}s (> Maks 10s)"
            }
            Box(
                modifier = Modifier
                    .alpha(if (canShortcut) 1f else 0.45f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            shortcutActive -> Ev.Accent
                            canShortcut -> Ev.Accent.copy(alpha = 0.22f)
                            else -> Ev.Ghost
                        }
                    )
                    .border(
                        1.dp,
                        if (canShortcut) Ev.Accent else Ev.Line,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable(enabled = canShortcut) { vm.applySceneDurationShortcut() }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = shortcutLabel,
                    color = if (shortcutActive) Color.White else Ev.Yellow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Input Range Slider 1.0 dtk .. 10.0 dtk (step 0.1 dtk)
        Slider(
            value = vm.aiReplaceTargetDurSec.toFloat().coerceIn(1.0f, 10.0f),
            onValueChange = { vm.updateReplaceTargetDuration(it.toDouble(), refreshEndFrame = false) },
            onValueChangeFinished = { vm.captureReplaceSceneFrames() },
            valueRange = 1.0f..10.0f,
            steps = 89,
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
            Text(text = "Min 1.0 dtk", color = Ev.Muted, fontSize = 10.sp)
            Text(text = "Batas ZeroGPU 10.0 dtk", color = Ev.Muted, fontSize = 10.sp)
        }

        Spacer(modifier = Modifier.height(7.dp))

        // Preset Cepat + Shortcut Durasi Scene
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (canShortcut) {
                AiSmallChip(
                    label = "⚡ Scene (${Fmt.one(sceneDur)}s)",
                    active = shortcutActive
                ) {
                    vm.applySceneDurationShortcut()
                }
            }
            for (d in REPLACE_QUICK_DURS) {
                val on = abs(vm.aiReplaceTargetDurSec - d) < 0.06
                AiSmallChip(
                    label = "${Fmt.one(d)} dtk",
                    active = on
                ) {
                    vm.updateReplaceTargetDuration(d, refreshEndFrame = true)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Penjelasan Dekomposisi ZeroGPU Otomatis
        Text(
            text = "⚙ ${plan.note}",
            color = Ev.InfoText,
            fontSize = 10.5.sp,
            lineHeight = 14.5.sp
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Opsi Audio pada Mode Replace Scene: Video Saja (Pertahankan Audio Asli) vs Video + Audio AI
    AiReplaceAudioSelectorCard(vm)
}

@Composable
private fun AiReplaceAudioSelectorCard(vm: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "OPSI AUDIO SAAT REPLACE SCENE",
            color = Ev.Muted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (am in AiReplaceAudioMode.entries) {
                val active = vm.aiReplaceAudioMode == am
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (active) Ev.Accent.copy(alpha = 0.22f) else Ev.Ghost)
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) Ev.Accent else Ev.Line,
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable { vm.aiReplaceAudioMode = am }
                        .padding(8.dp)
                ) {
                    Text(
                        text = (if (am == AiReplaceAudioMode.VIDEO_ONLY) "🎬 " else "🔊 ") + am.label,
                        color = if (active) Color.White else Ev.Text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = am.sub,
                        color = if (active) Ev.Yellow else Ev.Muted,
                        fontSize = 9.5.sp,
                        lineHeight = 12.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AiMultiOutputPreConfigCard(vm: EditorViewModel, plan: AiExecutionPlan) {
    val forceChain = vm.aiWorkMode == AiWorkMode.REPLACE_SCENE || vm.aiInsertPreset == AiInsertPreset.FULL_10
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PENGATUR ${plan.count} HASIL VIDEO AI",
                color = Ev.Yellow,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = plan.segDurs.joinToString(" + ") { "${Fmt.one(it)}s" },
                color = Ev.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(7.dp))

        if (!forceChain) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (st in AiMultiStrategy.entries) {
                    val active = vm.aiMultiStrategy == st
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) Ev.Accent.copy(alpha = 0.22f) else Ev.Ghost)
                            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(8.dp))
                            .clickable { vm.aiMultiStrategy = st }
                            .padding(7.dp)
                    ) {
                        Text(
                            text = st.label,
                            color = Ev.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = st.sub,
                            color = Ev.Muted,
                            fontSize = 9.5.sp,
                            lineHeight = 12.5.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(7.dp))
        } else {
            Text(
                text = "🔗 Mode Sambung Berantai aktif otomatis: frame terakhir Segmen #1 menjadi frame awal Segmen #2 agar nyambung mulus.",
                color = Ev.Muted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(7.dp))
        }

        // Pilihan Default Cara Masuk ke Timeline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (js in AiTimelineJoinStyle.entries) {
                val active = vm.aiTimelineJoinStyle == js
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Ev.Accent else Ev.Ghost)
                        .clickable { vm.aiTimelineJoinStyle = js }
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = js.label,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AiPromptComposerSection(vm: EditorViewModel, plan: AiExecutionPlan) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiSectionLabel("PROMPT GERAKAN & ADEGAN AI")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "+ Simpan Prompt",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    vm.fillPromptFormFromActiveEditor()
                    vm.aiActiveTab = AiTab.PROMPTS
                }
            )
            if (vm.aiPrompt.isNotEmpty()) {
                Text(
                    text = "Kosongkan",
                    color = Ev.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.updateActiveAiPromptText("") }
                )
            }
        }
    }

    // Bila menghasilkan 2 atau 3 video, izinkan Prompt Sama atau Prompt Berbeda per Segmen (#1..#3)
    if (plan.count >= 2) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiSmallChip(
                label = "Semua Segmen",
                active = !vm.aiPerSegmentPromptMode
            ) {
                vm.selectPromptSegmentSlot(0)
            }
            for (s in 1..plan.count) {
                AiSmallChip(
                    label = "Segmen #$s (${Fmt.one(plan.segDurs[s - 1])}s)",
                    active = vm.aiPerSegmentPromptMode && vm.aiActivePromptSegSlot == s
                ) {
                    vm.selectPromptSegmentSlot(s)
                }
            }
        }
    }

    // Pintasan cepat dari koleksi Management Prompt
    if (vm.aiPromptLibrary.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (item in vm.aiPromptLibrary.take(6)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Ev.Ghost)
                        .border(1.dp, Ev.Line, RoundedCornerShape(99.dp))
                        .clickable { vm.updateActiveAiPromptText(item.body) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = (if (item.favorite) "★ " else "") + item.title,
                        color = Ev.Text,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(Ev.Accent.copy(alpha = 0.2f))
                    .border(1.dp, Ev.Accent, RoundedCornerShape(99.dp))
                    .clickable { vm.aiActiveTab = AiTab.PROMPTS }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Kelola Semua (${vm.aiPromptLibrary.size}) →",
                    color = Ev.Yellow,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    OutlinedTextField(
        value = vm.aiPrompt,
        onValueChange = { vm.updateActiveAiPromptText(it) },
        placeholder = {
            Text(
                text = "Deskripsikan gerakan kamera, subjek, pencahayaan…",
                color = Ev.Muted,
                fontSize = 12.5.sp
            )
        },
        minLines = 3,
        maxLines = 5,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    )
}

@Composable
private fun AiSpaceAndSafetySection(vm: EditorViewModel) {
    AiSectionLabel("MODEL HUGGINGFACE ZEROGPU SPACE")
    Spacer(modifier = Modifier.height(5.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (sp in AiSpaceOption.entries) {
            AiSmallChip(
                label = sp.displayLabel,
                active = vm.aiSpaceOption == sp
            ) {
                vm.selectAiSpace(sp)
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AiSmallChip(
            label = if (vm.aiSafeMode) "✓ Safe Mode Aktif" else "Safe Mode Nonaktif",
            active = vm.aiSafeMode
        ) {
            vm.aiSafeMode = !vm.aiSafeMode
        }
        if (vm.aiSpaceOption.supportsSafetyChecker) {
            AiSmallChip(
                label = if (vm.aiSafetyChecker) "✓ Safety Checker" else "Safety Checker Off",
                active = vm.aiSafetyChecker
            ) {
                vm.aiSafetyChecker = !vm.aiSafetyChecker
            }
        }
    }
}

// =============================================================================================
// TAB 2 — MANAGEMENT PROMPT (Koleksi Prompt, Kategori, Favorit, Pasang ke Segmen, JSON/Clipboard)
// =============================================================================================

@Composable
private fun AiPromptsTabContent(
    vm: EditorViewModel,
    onExportFile: () -> Unit,
    onImportFile: () -> Unit
) {
    val context = LocalContext.current
    val plan = vm.currentAiExecutionPlan()
    val filtered = vm.filteredAiPrompts()

    // Pencarian & Filter Kategori
    OutlinedTextField(
        value = vm.aiPromptSearchQuery,
        onValueChange = { vm.aiPromptSearchQuery = it },
        placeholder = { Text("Cari judul, kategori, atau isi prompt…", color = Ev.Muted, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(7.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AiSmallChip("Semua (${vm.aiPromptLibrary.size})", vm.aiPromptFilterCategory == "ALL") {
            vm.aiPromptFilterCategory = "ALL"
        }
        AiSmallChip("★ Favorit", vm.aiPromptFilterCategory == "FAV") {
            vm.aiPromptFilterCategory = "FAV"
        }
        for (cat in PROMPT_CATEGORIES) {
            AiSmallChip(cat, vm.aiPromptFilterCategory == cat) {
                vm.aiPromptFilterCategory = cat
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Daftar Koleksi Prompt
    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Belum ada prompt yang cocok dengan filter.", color = Ev.Muted, fontSize = 12.sp)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in filtered) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Ev.Bg)
                        .border(1.dp, Ev.Line, RoundedCornerShape(11.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (item.favorite) "★" else "☆",
                                color = if (item.favorite) Ev.Yellow else Ev.Muted,
                                fontSize = 15.sp,
                                modifier = Modifier.clickable { vm.toggleFavoritePrompt(item.id) }
                            )
                            Text(
                                text = item.title,
                                color = Ev.Text,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Ev.Ghost)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = item.category, color = Ev.Yellow, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.body,
                        color = Ev.Muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(7.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AiSmallChip("✓ Pakai", active = true) {
                            vm.applyLibraryPromptToSegment(item, 0)
                        }
                        if (plan.count >= 2) {
                            for (s in 1..plan.count) {
                                AiSmallChip("Seg #$s", active = false) {
                                    vm.applyLibraryPromptToSegment(item, s)
                                }
                            }
                        }
                        AiSmallChip("Edit", active = vm.aiEditingPromptId == item.id) {
                            vm.startEditingPromptItem(item)
                        }
                        AiSmallChip("Hapus", active = false) {
                            vm.deletePromptLibraryEntry(item.id)
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Form Tambah / Edit Prompt
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            Text(
                text = if (vm.aiEditingPromptId != null) "EDIT PROMPT" else "TAMBAH PROMPT BARU",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Salin dari Editor Aktif",
                color = Ev.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { vm.fillPromptFormFromActiveEditor() }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = vm.aiFormPromptTitle,
            onValueChange = { vm.aiFormPromptTitle = it },
            placeholder = { Text("Judul prompt (mis. Drone Orbit Halus)", color = Ev.Muted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (cat in PROMPT_CATEGORIES) {
                AiSmallChip(cat, vm.aiFormPromptCategory == cat) {
                    vm.aiFormPromptCategory = cat
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = vm.aiFormPromptBody,
            onValueChange = { vm.aiFormPromptBody = it },
            placeholder = { Text("Tulis isi prompt lengkap di sini…", color = Ev.Muted, fontSize = 12.sp) },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.aiEditingPromptId != null) {
                ActionButton(
                    text = "Batal",
                    primary = false,
                    modifier = Modifier.weight(0.8f),
                    onClick = { vm.cancelEditingPromptItem() }
                )
            }
            ActionButton(
                text = if (vm.aiEditingPromptId != null) "💾 Perbarui Prompt" else "💾 Simpan ke Koleksi",
                primary = true,
                modifier = Modifier.weight(1.4f),
                onClick = { vm.savePromptLibraryEntry() }
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Eksport & Import Koleksi Prompt (.json File Manager & Clipboard)
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            Text(
                text = "EKSPORT & IMPORT KOLEKSI PROMPT",
                color = Ev.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
            AiSmallChip(
                label = if (vm.aiPromptImportMergeMode) "Mode: Gabungkan" else "Mode: Ganti Semua",
                active = vm.aiPromptImportMergeMode
            ) {
                vm.aiPromptImportMergeMode = !vm.aiPromptImportMergeMode
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiMiniBtn("📤 Eksport .json", Modifier.weight(1f), onClick = onExportFile)
            AiMiniBtn("📋 Salin JSON", Modifier.weight(1f)) {
                copyPlainText(context, "koleksi_prompt_ai", vm.buildPromptsExportJson())
                vm.toast("JSON ${vm.aiPromptLibrary.size} prompt disalin ke Clipboard")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiMiniBtn("📥 Import .json", Modifier.weight(1f), onClick = onImportFile)
            AiMiniBtn("📥 Tempel JSON", Modifier.weight(1f)) {
                val raw = readPlainTextClipboard(context)
                if (raw.isNullOrBlank()) {
                    vm.toast("Clipboard kosong")
                } else {
                    vm.importPromptsFromRawJson(raw)
                }
            }
        }
    }
}

// =============================================================================================
// TAB 3 — STATUS, DUAL WATCHDOG TIMEOUT (3m Workflow + 3m/Video) & PENGATUR MULTI-HASIL
// =============================================================================================

@Composable
private fun AiStatusTabContent(vm: EditorViewModel) {
    val phase = vm.aiRunPhase

    // 1. Dua Kartu Watchdog Timeout Terpisah (3 Menit Menunggu Workflow & 3 Menit per Video)
    AiSectionLabel("MONITOR PROSES & BATAS TIMEOUT (3 MENIT)")
    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Watchdog 1: Menunggu Workflow Aktif (Maks 180 detik / 3 menit)
        val wfFrac = (vm.aiWorkflowWaitSec.toFloat() / AiPlanner.WORKFLOW_WAIT_TIMEOUT_SEC.toFloat())
            .coerceIn(0f, 1f)
        val wfTimedOut = phase == AiRunPhase.WORKFLOW_TIMEOUT
        val wfActiveDone = phase == AiRunPhase.GENERATING_VIDEO ||
            phase == AiRunPhase.DOWNLOADING ||
            phase == AiRunPhase.COMPLETED ||
            phase == AiRunPhase.VIDEO_TIMEOUT

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(11.dp))
                .background(Ev.Bg)
                .border(
                    1.dp,
                    when {
                        wfTimedOut -> Color(0xFFF43F5E)
                        wfActiveDone -> Color(0xFF10B981)
                        phase == AiRunPhase.WAITING_WORKFLOW -> Ev.Yellow
                        else -> Ev.Line
                    },
                    RoundedCornerShape(11.dp)
                )
                .padding(9.dp)
        ) {
            Text(
                text = "MENUNGGU WORKFLOW",
                color = Ev.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${formatClockSec(vm.aiWorkflowWaitSec)} / 03:00",
                color = when {
                    wfTimedOut -> Color(0xFFF43F5E)
                    wfActiveDone -> Color(0xFF6EE7B7)
                    else -> Ev.Text
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { if (wfActiveDone && vm.aiWorkflowWaitSec == 0) 0.08f else wfFrac },
                color = when {
                    wfTimedOut -> Color(0xFFF43F5E)
                    wfActiveDone -> Color(0xFF10B981)
                    else -> Ev.Yellow
                },
                trackColor = Ev.Line,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    wfTimedOut -> "⚠ Timeout > 3 menit"
                    wfActiveDone -> "✓ Runner aktif (${vm.aiWorkflowWaitSec}s)"
                    phase == AiRunPhase.WAITING_WORKFLOW -> "Menunggu GitHub Runner…"
                    else -> "Batas maks 180 detik"
                },
                color = Ev.Muted,
                fontSize = 9.5.sp
            )
        }

        // Watchdog 2: Buat Video (Maks 180 detik / 3 menit per video, di-reset tiap item!)
        val vidFrac = (vm.aiCurrentVideoSec.toFloat() / AiPlanner.VIDEO_GEN_TIMEOUT_SEC.toFloat())
            .coerceIn(0f, 1f)
        val vidTimedOut = phase == AiRunPhase.VIDEO_TIMEOUT
        val vidDone = phase == AiRunPhase.COMPLETED

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(11.dp))
                .background(Ev.Bg)
                .border(
                    1.dp,
                    when {
                        vidTimedOut -> Color(0xFFF43F5E)
                        vidDone -> Color(0xFF10B981)
                        phase == AiRunPhase.GENERATING_VIDEO -> Ev.Accent
                        else -> Ev.Line
                    },
                    RoundedCornerShape(11.dp)
                )
                .padding(9.dp)
        ) {
            val totV = vm.aiTotalVideosInRun.coerceAtLeast(1)
            val curV = vm.aiCurrentVideoNum.coerceIn(1, totV)
            Text(
                text = if (vm.aiTotalVideosInRun > 0) "BUAT VIDEO ($curV/$totV)" else "BUAT VIDEO (PER ITEM)",
                color = Ev.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${formatClockSec(vm.aiCurrentVideoSec)} / 03:00",
                color = when {
                    vidTimedOut -> Color(0xFFF43F5E)
                    vidDone -> Color(0xFF6EE7B7)
                    else -> Ev.Text
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { if (vidDone) 1f else vidFrac },
                color = when {
                    vidTimedOut -> Color(0xFFF43F5E)
                    vidDone -> Color(0xFF10B981)
                    else -> Ev.Accent
                },
                trackColor = Ev.Line,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    vidTimedOut -> "⚠ Timeout > 3 menit/video"
                    vidDone -> "✓ Seluruh item selesai"
                    phase == AiRunPhase.GENERATING_VIDEO -> "Reset otomatis tiap video"
                    else -> "Maks 180 detik / video"
                },
                color = Ev.Muted,
                fontSize = 9.5.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Banner Status Detail + Tombol Batal / Coba Ulang
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = vm.aiRunStatusDetail,
            color = Ev.InfoText,
            fontSize = 11.5.sp,
            lineHeight = 15.5.sp,
            modifier = Modifier.weight(1f)
        )
        if (vm.aiIsRunning) {
            AiSmallChip("✕ Batalkan", active = false) { vm.cancelAiGeneration() }
        } else if (phase == AiRunPhase.WORKFLOW_TIMEOUT || phase == AiRunPhase.FAILED) {
            AiSmallChip("↻ Ulang", active = true) { vm.startAiGeneration() }
        }
    }

    // 2. Sistem Kandidat Hasil AI + Pratinjau Video & Pengatur Hasil
    val candidates = vm.aiCandidates
    val results = vm.aiGeneratedSegments
    if (candidates.isNotEmpty() || results.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))

        // Panel Kandidat Hasil AI: pilih, bandingkan, tandai ⭐ Terbaik, atau generate Kandidat Baru kapan saja!
        AiCandidatesSelectorPanel(vm = vm, candidates = candidates)

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (results.isNotEmpty()) {
        val previewIdx = vm.aiPreviewResultIndex.coerceIn(0, results.lastIndex)
        val activeSeg = results[previewIdx]

        // Pemutar Pratinjau MP4 Hasil AI:
        // - Bila Mode Replace Scene: tampilkan KIRI (Video Scene Asli) vs KANAN (Hasil Video AI)!
        // - Bila Mode Sisipkan     : tampilkan pemutar tunggal Hasil Video AI.
        if (!activeSeg.failed && activeSeg.mp4File != null) {
            val replaceTarget = if (vm.aiSnapshotWorkMode == AiWorkMode.REPLACE_SCENE) {
                vm.resolveSnapshotReplaceTarget()
            } else {
                null
            }
            if (replaceTarget != null) {
                val offsetBeforeSec = results.take(previewIdx)
                    .filter { !it.failed }
                    .sumOf { it.actualDurSec }
                AiSideBySideReplacePreviewCard(
                    vm = vm,
                    origUri = replaceTarget.first,
                    origSeg = replaceTarget.second,
                    origInfo = replaceTarget.third,
                    sceneNumber = vm.aiSnapshotReplaceSegIdx + 1,
                    offsetInSceneSec = offsetBeforeSec,
                    aiSeg = activeSeg
                )
            } else {
                AiInlineMp4PreviewCard(seg = activeSeg)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AiSectionLabel("DAFTAR HASIL VIDEO AI (${results.size} ITEM)")
            val selCount = results.count { it.selected && !it.failed }
            val selDur = results.filter { it.selected && !it.failed }.sumOf { it.actualDurSec }
            Text(
                text = "$selCount dipilih · ${Fmt.one(selDur)} dtk",
                color = Ev.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            for ((idx, sg) in results.withIndex()) {
                AiResultItemCard(
                    vm = vm,
                    index = idx,
                    total = results.size,
                    seg = sg,
                    isPreviewing = idx == previewIdx
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Opsi penggabungan bila lebih dari 1 klip dipilih
        val validSelectedCount = results.count { it.selected && !it.failed && it.mp4File != null }
        if (validSelectedCount >= 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (js in AiTimelineJoinStyle.entries) {
                    val active = vm.aiTimelineJoinStyle == js
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) Ev.Accent else Ev.Ghost)
                            .clickable { vm.aiTimelineJoinStyle = js }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = js.label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Opsi Audio Replace Scene langsung di Tab Status sebelum menekan Ganti Scene
        if (vm.aiSnapshotWorkMode == AiWorkMode.REPLACE_SCENE) {
            AiReplaceAudioSelectorCard(vm)
            Spacer(modifier = Modifier.height(8.dp))
        }

        val activeCandLabel = vm.aiCandidates.getOrNull(vm.aiActiveCandidateIdx)?.let {
            "Kandidat #${it.candidateNumber} · "
        } ?: ""
        val applyLabel = when {
            vm.aiMergingClips -> "⏳ Memproses & Menerapkan ke Timeline…"
            vm.aiSnapshotWorkMode == AiWorkMode.REPLACE_SCENE -> {
                val audTag = if (vm.aiReplaceAudioMode == AiReplaceAudioMode.VIDEO_ONLY) {
                    "Audio Asli"
                } else {
                    "Video+Audio"
                }
                val multiSnap = vm.aiSnapshotMultiScenePlan
                if (multiSnap != null) {
                    val from = multiSnap.startSegIdx + 1
                    val to = multiSnap.endSegIdx + 1
                    "🔄 Terapkan ${activeCandLabel}ke Scene #$from–#$to (${Fmt.one(multiSnap.totalSec)}s · $audTag)"
                } else {
                    val targetSec = vm.aiSnapshotPlan?.targetTotalSec ?: vm.aiReplaceTargetDurSec
                    "🔄 Terapkan ${activeCandLabel}ke Scene #${vm.aiSnapshotReplaceSegIdx + 1} (${Fmt.one(targetSec)}s · $audTag)"
                }
            }
            else -> "➕ Sisipkan ${activeCandLabel}di ${Fmt.preciseTime(vm.aiSnapshotInsertSeekSec)}"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                text = "💾 Simpan Galeri",
                primary = false,
                enabled = validSelectedCount > 0 && !vm.aiMergingClips,
                modifier = Modifier.weight(0.95f),
                onClick = { vm.saveSelectedAiResultsToGallery() }
            )
            ActionButton(
                text = applyLabel,
                primary = true,
                enabled = validSelectedCount > 0 && !vm.aiMergingClips && !vm.aiIsRunning,
                modifier = Modifier.weight(1.55f),
                onClick = { vm.applyAiResultsToTimeline(vm.aiTimelineJoinStyle) }
            )
        }
    }
}

@Composable
private fun AiCandidatesSelectorPanel(
    vm: EditorViewModel,
    candidates: List<AiCandidateBatch>
) {
    val activeIdx = vm.aiActiveCandidateIdx.coerceIn(0, max(0, candidates.lastIndex))
    val activeCand = candidates.getOrNull(activeIdx)
    val nextNum = candidates.size + 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🗂 KANDIDAT HASIL AI (${candidates.size} TERSEDIA)",
                    color = Ev.Yellow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Bandingkan beberapa hasil generate & pilih kandidat terbaik untuk timeline",
                    color = Ev.Muted,
                    fontSize = 9.5.sp
                )
            }
            if (candidates.size >= 2 && !vm.aiIsRunning) {
                Text(
                    text = "Bersihkan",
                    color = Ev.Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.clearAllAiCandidates() }
                )
            }
        }

        if (candidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                for ((idx, cand) in candidates.withIndex()) {
                    val selected = idx == activeIdx
                    val thumb = cand.segments.firstOrNull()?.previewBmp
                    val okCount = cand.segments.count { !it.failed && it.mp4File != null }
                    Column(
                        modifier = Modifier
                            .width(126.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Ev.Accent.copy(alpha = 0.22f) else Ev.Ghost)
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = when {
                                    selected -> Ev.Yellow
                                    cand.isFavorite -> Color(0xFFFACC15).copy(alpha = 0.7f)
                                    else -> Ev.Line
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.selectAiCandidate(idx) }
                            .padding(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null && !thumb.isRecycled) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = "Kandidat #${cand.candidateNumber}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xCC000000))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "#${cand.candidateNumber}" + (if (cand.isFavorite) " ⭐" else ""),
                                    color = if (selected) Ev.Yellow else Color.White,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xCC000000))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$okCount/${cand.segments.size} ok",
                                    color = Color(0xFF6EE7B7),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = cand.summaryLabel,
                            color = Ev.Text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Pukul ${cand.createdAtLabel}",
                            color = Ev.Muted,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        if (activeCand != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiSmallChip(
                    label = if (activeCand.isFavorite) "⭐ Terbaik" else "☆ Tandai Terbaik",
                    active = activeCand.isFavorite
                ) {
                    vm.toggleFavoriteAiCandidate(activeIdx)
                }
                if (!vm.aiIsRunning) {
                    AiSmallChip(
                        label = "🗑 Hapus #${activeCand.candidateNumber}",
                        active = false
                    ) {
                        vm.deleteAiCandidate(activeIdx)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                AiSmallChip(
                    label = "⚙ Ubah Setelan",
                    active = false
                ) {
                    vm.aiActiveTab = AiTab.CREATE
                }
            }
        }

        // Tombol langsung Generate Kandidat Baru dari Tab Status meskipun sudah ada hasil!
        ActionButton(
            text = if (vm.aiIsRunning) {
                "⏳ Sedang Membuat Kandidat…"
            } else {
                "✨ Generate Kandidat Baru (#$nextNum) — Bandingkan Hasil"
            },
            primary = false,
            enabled = !vm.aiIsRunning,
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.startAiGeneration() }
        )
    }
}

@Composable
private fun AiSideBySideReplacePreviewCard(
    vm: EditorViewModel,
    origUri: Uri,
    origSeg: Seg,
    origInfo: VideoInfo,
    sceneNumber: Int,
    offsetInSceneSec: Double,
    aiSeg: AiGeneratedSegment
) {
    val context = LocalContext.current
    val aiFile = aiSeg.mp4File ?: return
    var bothPlaying by remember { mutableStateOf(true) }

    val origStartSec = (origSeg.a + offsetInSceneSec * origSeg.sp)
        .coerceIn(origSeg.a, max(origSeg.a, origSeg.b - 0.05))
    val spanSec = min(aiSeg.actualDurSec, max(0.1, origSeg.len - offsetInSceneSec))
    val origEndSec = (origStartSec + spanSec * origSeg.sp)
        .coerceIn(origStartSec + 0.05, max(origStartSec + 0.05, origSeg.b))

    val leftExo = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    val rightExo = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    // Atur sumber audio sesuai pilihan Video Saja (Audio Asli) vs Video + Audio AI
    val keepOrigAudio = vm.aiReplaceAudioMode == AiReplaceAudioMode.VIDEO_ONLY
    LaunchedEffect(keepOrigAudio) {
        leftExo.volume = if (keepOrigAudio) 1f else 0f
        rightExo.volume = if (keepOrigAudio) 0f else 1f
    }

    LaunchedEffect(origUri, origStartSec, origEndSec, origSeg.sp, origSeg.fz) {
        if (!origSeg.fz) {
            val startMs = max(0L, Math.round(origStartSec * 1000.0))
            val endMs = max(startMs + 80L, Math.round(origEndSec * 1000.0))
            val item = MediaItem.Builder()
                .setUri(origUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            leftExo.setMediaItem(item)
            leftExo.setPlaybackSpeed(origSeg.sp.toFloat().coerceIn(0.25f, 4f))
            leftExo.prepare()
            if (bothPlaying) leftExo.play() else leftExo.pause()
        } else {
            leftExo.pause()
        }
    }

    LaunchedEffect(aiFile.absolutePath) {
        rightExo.setMediaItem(MediaItem.fromUri(Uri.fromFile(aiFile)))
        rightExo.prepare()
        leftExo.seekTo(0L)
        rightExo.seekTo(0L)
        if (bothPlaying) {
            if (!origSeg.fz) leftExo.play()
            rightExo.play()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { leftExo.release() } catch (_: Exception) {}
            try { rightExo.release() } catch (_: Exception) {}
        }
    }

    val origAspect = remember(origInfo, origSeg.rot) {
        val w = if (origInfo.dispW > 0) origInfo.dispW else 9
        val h = if (origInfo.dispH > 0) origInfo.dispH else 16
        if (origSeg.rot % 180 != 0) h.toFloat() / w.toFloat() else w.toFloat() / h.toFloat()
    }.coerceIn(0.35f, 2.6f)

    val aiAspect = remember(aiSeg.info) {
        val inf = aiSeg.info
        val w = if (inf != null && inf.dispW > 0) inf.dispW else 9
        val h = if (inf != null && inf.dispH > 0) inf.dispH else 16
        w.toFloat() / h.toFloat()
    }.coerceIn(0.35f, 2.6f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Accent, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PERBANDINGAN REPLACE SCENE (KIRI ASLI · KANAN AI)",
                color = Ev.Yellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (keepOrigAudio) "🔊 Suara: Asli" else "🔊 Suara: AI",
                color = Color(0xFF6EE7B7),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Tampilan Kiri-Kanan Berdampingan
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // KIRI: Video Scene Asli yang akan di-replace
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(185.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black)
                    .border(1.dp, Ev.Line, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (origSeg.fz) {
                    val fb = vm.aiReplaceFirstBitmap ?: vm.aiSeekBitmap
                    if (fb != null && !fb.isRecycled) {
                        Image(
                            bitmap = fb.asImageBitmap(),
                            contentDescription = "Scene Asli",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .aspectRatio(origAspect, matchHeightConstraintsFirst = true)
                            .graphicsLayer(
                                rotationZ = origSeg.rot.toFloat(),
                                scaleX = if (vm.flip) -1f else 1f
                            )
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).also { tv -> leftExo.setVideoTextureView(tv) }
                            },
                            update = { tv -> leftExo.setVideoTextureView(tv) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "KIRI · Scene #$sceneNumber Asli",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${Fmt.one(origSeg.len)} dtk",
                        color = Ev.Muted,
                        fontSize = 9.sp
                    )
                }
            }

            // KANAN: Hasil Video AI pengganti
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(185.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black)
                    .border(1.dp, Ev.Accent, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.aspectRatio(aiAspect, matchHeightConstraintsFirst = true)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).also { tv -> rightExo.setVideoTextureView(tv) }
                        },
                        update = { tv -> rightExo.setVideoTextureView(tv) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Ev.Accent.copy(alpha = 0.88f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "KANAN · Hasil AI #${aiSeg.segNumber}",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${Fmt.one(aiSeg.actualDurSec)} dtk · Seed ${aiSeg.seed}",
                        color = Ev.Yellow,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        // Kontrol Putar / Jeda & Ulang Serempak Kiri-Kanan
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiMiniBtn(
                label = if (bothPlaying) "⏸ Jeda Berdampingan" else "▶ Putar Berdampingan",
                modifier = Modifier.weight(1f)
            ) {
                val next = !bothPlaying
                bothPlaying = next
                if (next) {
                    if (!origSeg.fz) leftExo.play()
                    rightExo.play()
                } else {
                    leftExo.pause()
                    rightExo.pause()
                }
            }
            AiMiniBtn(
                label = "↺ Ulang Serempak 0:00",
                modifier = Modifier.weight(1f)
            ) {
                leftExo.seekTo(0L)
                rightExo.seekTo(0L)
                bothPlaying = true
                if (!origSeg.fz) leftExo.play()
                rightExo.play()
            }
        }
    }
}

@Composable
private fun AiInlineMp4PreviewCard(seg: AiGeneratedSegment) {
    val context = LocalContext.current
    val mp4File = seg.mp4File ?: return

    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    LaunchedEffect(mp4File.absolutePath) {
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(mp4File)))
        exo.prepare()
        exo.play()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                exo.release()
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(175.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Ev.Accent, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv -> exo.setVideoTextureView(tv) }
            },
            update = { tv -> exo.setVideoTextureView(tv) },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = "▶ Pratinjau Segmen #${seg.segNumber} · ${Fmt.one(seg.actualDurSec)} dtk · Seed ${seg.seed}",
                color = Ev.Yellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AiResultItemCard(
    vm: EditorViewModel,
    index: Int,
    total: Int,
    seg: AiGeneratedSegment,
    isPreviewing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Ev.Bg)
            .border(
                width = if (isPreviewing) 1.5.dp else 1.dp,
                color = when {
                    seg.failed -> Color(0xFFF43F5E)
                    isPreviewing -> Ev.Accent
                    seg.selected -> Ev.Yellow.copy(alpha = 0.6f)
                    else -> Ev.Line
                },
                shape = RoundedCornerShape(11.dp)
            )
            .clickable { vm.aiPreviewResultIndex = index }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Kotak Centang Pilih/Lepas
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        seg.failed -> Color(0xFFF43F5E).copy(alpha = 0.25f)
                        seg.selected -> Ev.Accent
                        else -> Ev.Ghost
                    }
                )
                .border(1.dp, Ev.Line, RoundedCornerShape(6.dp))
                .clickable(enabled = !seg.failed) { vm.toggleSelectAiResult(index) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    seg.failed -> "!"
                    seg.selected -> "✓"
                    else -> ""
                },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Thumbnail Segmen
        Box(
            modifier = Modifier
                .size(width = 62.dp, height = 42.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val bmp = seg.previewBmp
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Hasil #${seg.segNumber}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Info Segmen
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (seg.failed) {
                    "Segmen #${seg.segNumber} · Gagal / Timeout"
                } else {
                    "Segmen #${seg.segNumber} · ${Fmt.one(seg.actualDurSec)} dtk"
                },
                color = if (seg.failed) Color(0xFFFDA4AF) else Ev.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (seg.failed) {
                    seg.errorMessage ?: "Ketuk ↻ untuk membuat ulang segmen ini"
                } else {
                    seg.promptUsed
                },
                color = Ev.Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Kontrol Urutan (↑ / ↓) bila multi-hasil & Regenerate Satuan (↻)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (total >= 2) {
                AiTinyIconLabel("↑", enabled = index > 0) { vm.moveAiResult(index, -1) }
                AiTinyIconLabel("↓", enabled = index < total - 1) { vm.moveAiResult(index, 1) }
            }
            AiTinyIconLabel("↻", enabled = !vm.aiIsRunning) {
                vm.regenerateSingleAiSegment(index)
            }
        }
    }
}

// =============================================================================================
// TAB 4 — RUNNER (Pengaturan Koneksi Firebase & GitHub + 4 Item Repo Runner Clipboards)
// =============================================================================================

@Composable
private fun AiRunnerTabContent(vm: EditorViewModel) {
    val context = LocalContext.current
    val cfg = vm.aiRunnerConfig
    var showToken by remember { mutableStateOf(false) }
    var expandedCodeId by remember { mutableStateOf<String?>(null) }

    AiSectionLabel("KONEKSI FIREBASE RTDB & GITHUB RUNNER")
    Spacer(modifier = Modifier.height(6.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Bg)
            .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        OutlinedTextField(
            value = cfg.firebaseUrl,
            onValueChange = { vm.updateAiRunnerConfigField(firebaseUrl = it) },
            label = { Text("Firebase Realtime Database URL", fontSize = 11.sp) },
            placeholder = { Text("https://nama-db-default-rtdb.firebaseio.com", fontSize = 11.5.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedTextField(
                value = cfg.githubOwner,
                onValueChange = { vm.updateAiRunnerConfigField(githubOwner = it) },
                label = { Text("GitHub Owner", fontSize = 11.sp) },
                placeholder = { Text("username", fontSize = 11.5.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = cfg.githubRepo,
                onValueChange = { vm.updateAiRunnerConfigField(githubRepo = it) },
                label = { Text("Nama Repo Runner", fontSize = 11.sp) },
                placeholder = { Text("wan-runner", fontSize = 11.5.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedTextField(
                value = cfg.githubBranch,
                onValueChange = { vm.updateAiRunnerConfigField(githubBranch = it) },
                label = { Text("Branch", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(0.75f)
            )
            OutlinedTextField(
                value = cfg.workflowFile,
                onValueChange = { vm.updateAiRunnerConfigField(workflowFile = it) },
                label = { Text("File Workflow", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1.25f)
            )
        }

        OutlinedTextField(
            value = cfg.githubToken,
            onValueChange = { vm.updateAiRunnerConfigField(githubToken = it) },
            label = { Text("GitHub Personal Access Token (Actions: Write)", fontSize = 11.sp) },
            placeholder = { Text("ghp_... atau github_pat_...", fontSize = 11.5.sp) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Text(
                    text = if (showToken) "Sembunyi" else "Lihat",
                    color = Ev.Yellow,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { showToken = !showToken }
                        .padding(horizontal = 8.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        val testMsg = vm.aiConnectionTestMessage
        if (testMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (vm.aiConnectionTestOk) Color(0xFF10B981).copy(alpha = 0.16f)
                        else Color(0xFFF43F5E).copy(alpha = 0.16f)
                    )
                    .border(
                        1.dp,
                        if (vm.aiConnectionTestOk) Color(0xFF10B981) else Color(0xFFF43F5E),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(9.dp)
            ) {
                Text(
                    text = testMsg,
                    color = Ev.Text,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                text = "💾 Simpan",
                primary = false,
                modifier = Modifier.weight(0.85f),
                onClick = { vm.saveAiRunnerConfig() }
            )
            ActionButton(
                text = if (vm.aiTestingConnection) "⏳ Mengetes…" else "🔌 Tes Koneksi",
                primary = true,
                enabled = !vm.aiTestingConnection,
                modifier = Modifier.weight(1.15f),
                onClick = { vm.runAiConnectionTest() }
            )
            ActionButton(
                text = if (vm.aiSyncingWorker) "⏳ Sync…" else "🔄 Sync v3",
                primary = false,
                enabled = !vm.aiSyncingWorker,
                modifier = Modifier.weight(0.95f),
                onClick = { vm.syncRunnerWorkerToGitHub() }
            )
        }

        val workerMsg = vm.aiWorkerSyncMessage
        if (!workerMsg.isNullOrEmpty()) {
            val okSync = vm.aiWorkerSyncStatus == WorkerSyncStatus.UP_TO_DATE ||
                vm.aiWorkerSyncStatus == WorkerSyncStatus.AUTO_UPDATED
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (okSync) Color(0xFF10B981).copy(alpha = 0.14f)
                        else Ev.Yellow.copy(alpha = 0.14f)
                    )
                    .border(
                        1.dp,
                        if (okSync) Color(0xFF10B981).copy(alpha = 0.6f) else Ev.Yellow.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = workerMsg,
                    color = Ev.Text,
                    fontSize = 10.5.sp,
                    lineHeight = 14.5.sp
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Repo Runner Clipboards (4 Item Berpasangan: Path + Kode untuk Workflow & Worker)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiSectionLabel("REPO RUNNER CLIPBOARDS (${vm.aiCopiedRunnerIds.size}/4 DISALIN)")
        if (vm.aiCopiedRunnerIds.isNotEmpty()) {
            Text(
                text = "Reset Checklist",
                color = Ev.Accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { vm.resetRunnerClipboardChecklist() }
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Salin 2 pasang path + kode berikut ke repositori GitHub Runner Anda (Add file → Create new file):",
        color = Ev.Muted,
        fontSize = 10.5.sp,
        lineHeight = 14.5.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    val pairs = listOf(
        "workflow" to "1. File Workflow GitHub Actions",
        "worker" to "2. File Worker Python Firebase"
    )
    for ((pairKey, pairTitle) in pairs) {
        val pairItems = AiRunnerClipboardData.ITEMS.filter { it.pair == pairKey }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ev.Bg)
                .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = pairTitle,
                color = Ev.Yellow,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )

            for (clipItem in pairItems) {
                val copied = clipItem.id in vm.aiCopiedRunnerIds
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Ev.Panel)
                        .border(
                            1.dp,
                            if (copied) Color(0xFF10B981) else Ev.Line,
                            RoundedCornerShape(9.dp)
                        )
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${clipItem.label} (${clipItem.language})",
                                color = Ev.Text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (clipItem.isPath) {
                                    clipItem.value
                                } else {
                                    "${clipItem.value.lines().size} baris kode siap pakai"
                                },
                                color = Ev.Muted,
                                fontSize = 10.sp,
                                fontFamily = if (clipItem.isPath) FontFamily.Monospace else FontFamily.Default
                            )
                        }
                        if (!clipItem.isPath) {
                            AiSmallChip(
                                label = if (expandedCodeId == clipItem.id) "Tutup" else "Lihat",
                                active = false
                            ) {
                                expandedCodeId = if (expandedCodeId == clipItem.id) null else clipItem.id
                            }
                        }
                        AiSmallChip(
                            label = if (copied) "✓ Tersalin" else "📋 Salin",
                            active = !copied
                        ) {
                            copyPlainText(context, clipItem.label, clipItem.value)
                            vm.markRunnerClipboardCopied(clipItem.id, clipItem.label)
                        }
                    }

                    if (!clipItem.isPath && expandedCodeId == clipItem.id) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF0A0D12))
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = clipItem.value.trimEnd(),
                                color = Color(0xFFD1FAE5),
                                fontSize = 9.5.sp,
                                lineHeight = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Komponen Kecil Pendukung UI Studio Video AI
// =============================================================================================

@Composable
private fun AiSectionLabel(text: String) {
    Text(
        text = text,
        color = Ev.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun AiModeCard(
    modifier: Modifier,
    active: Boolean,
    title: String,
    sub: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) Ev.Accent.copy(alpha = 0.22f) else Ev.Bg)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) Ev.Accent else Ev.Line,
                shape = RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = title,
            color = if (active) Color.White else Ev.Text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = sub,
            color = if (active) Ev.Yellow else Ev.Muted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun AiMiniBtn(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .background(Ev.Ghost)
            .border(1.dp, Ev.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Ev.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AiSmallChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun AiTinyIconLabel(
    symbol: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(Ev.Ghost)
            .border(1.dp, Ev.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = Ev.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
