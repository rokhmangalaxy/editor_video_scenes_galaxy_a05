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
