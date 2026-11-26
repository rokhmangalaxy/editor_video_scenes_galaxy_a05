package com.pribadi.editorvideo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.export.ExportMath
import com.pribadi.editorvideo.export.ExportMode
import com.pribadi.editorvideo.export.ExportState
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.vm.EditorViewModel
import com.pribadi.editorvideo.vm.NoteItem
import com.pribadi.editorvideo.vm.NoteType
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Bagian kecil yang dipakai bersama
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
        Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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

@Composable
private fun SmallIconButton(id: Int, onClick: () -> Unit) {
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
// Sheet kecepatan
// ---------------------------------------------------------------------------------------------

@Composable
fun SpeedSheet(vm: EditorViewModel, onClose: () -> Unit) {
    Scrim(onTap = onClose, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .absorbTaps()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
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
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 14.dp)
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
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton("−1.0", false, Modifier.weight(1f)) { vm.setSpeed(vm.selSpeed - 1.0, true) }
                ActionButton("Reset 1.0", false, Modifier.weight(1f)) { vm.setSpeed(1.0, true) }
                ActionButton("+1.0", false, Modifier.weight(1f)) { vm.setSpeed(vm.selSpeed + 1.0, true) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                ActionButton("Terapkan ke semua potongan", false, Modifier.weight(1f)) { vm.askApplyAll() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                ActionButton("Selesai", true, Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet teks overlay (default kosong, bisa ditambah/diubah/dikosongkan)
// ---------------------------------------------------------------------------------------------

@Composable
fun OverlayTextSheet(vm: EditorViewModel, onClose: () -> Unit) {
    var topText by remember { mutableStateOf(vm.text1) }
    var bottomText by remember { mutableStateOf(vm.text2) }

    Scrim(onTap = onClose, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .absorbTaps()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Overlay Teks",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                SmallIconButton(R.drawable.ic_close, onClose)
            }

            Text(
                text = "Biarkan kosong bila tidak ingin menampilkan teks. Anda juga bisa mengetuk teks langsung di pratinjau.",
                color = Ev.Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TEKS ATAS",
                    color = Ev.Yellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (topText.isNotEmpty()) {
                    Text(
                        text = "Kosongkan",
                        color = Ev.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { topText = "" }
                    )
                }
            }
            OutlinedTextField(
                value = topText,
                onValueChange = { topText = it },
                placeholder = { Text("Kosong (contoh: Judul / Hook atas)", color = Ev.Muted) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TEKS BAWAH",
                    color = Ev.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (bottomText.isNotEmpty()) {
                    Text(
                        text = "Kosongkan",
                        color = Ev.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { bottomText = "" }
                    )
                }
            }
            OutlinedTextField(
                value = bottomText,
                onValueChange = { bottomText = it },
                placeholder = { Text("Kosong (contoh: @watermark / subjudul)", color = Ev.Muted) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton("Hapus Semua", false, Modifier.weight(1f)) {
                    topText = ""
                    bottomText = ""
                    vm.applyBothTexts("", "")
                    vm.toast("Overlay teks dikosongkan")
                    onClose()
                }
                ActionButton("Simpan Teks", true, Modifier.weight(1f)) {
                    vm.applyBothTexts(topText, bottomText)
                    vm.toast(
                        if (topText.trim().isEmpty() && bottomText.trim().isEmpty()) {
                            "Overlay teks dikosongkan"
                        } else {
                            "Overlay teks diperbarui"
                        }
                    )
                    onClose()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet Catatan (Note untuk Bug & Pengembangan Masa Depan)
// ---------------------------------------------------------------------------------------------

@Composable
fun NotesSheet(vm: EditorViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var selectedType by remember { mutableStateOf(NoteType.BUG) }
    var inputText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }

    Scrim(onTap = onClose, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .absorbTaps()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Catatan Bug & Pengembangan",
                        color = Ev.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Tersimpan otomatis di perangkat untuk catatan bug & ide fitur baru.",
                        color = Ev.Muted,
                        fontSize = 11.5.sp
                    )
                }
                SmallIconButton(R.drawable.ic_close, onClose)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pilihan kategori: Bug vs Pengembangan
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
                            text = if (t == NoteType.BUG) "🐞 Laporan Bug" else "💡 Ide Pengembangan",
                            color = if (active) textColor else Ev.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
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

            // Daftar catatan
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

            Spacer(modifier = Modifier.height(14.dp))
            ActionButton("Tutup", false, Modifier.fillMaxWidth(), onClick = onClose)
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
    val tagBg = if (isBug) Ev.Accent.copy(alpha = 0.2f) else Ev.Yellow.copy(alpha = 0.2f)
    val tagColor = if (isBug) Ev.Accent else Ev.Yellow

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
                    color = Ev.Accent,
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

// ---------------------------------------------------------------------------------------------
// Sheet Readme / Panduan (ketika tombol ? di bar atas diketuk)
// ---------------------------------------------------------------------------------------------

@Composable
fun ReadmeSheet(vm: EditorViewModel, onImport: () -> Unit, onClose: () -> Unit) {
    Scrim(onTap = onClose, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .absorbTaps()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Panduan & Readme Aplikasi",
                    color = Ev.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                SmallIconButton(R.drawable.ic_close, onClose)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .padding(top = 10.dp)
            ) {
                EmptyPreviewReadme(
                    vm = vm,
                    onImport = {
                        onClose()
                        onImport()
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            ActionButton("Tutup", true, Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

/** Dialog konfirmasi "Terapkan ke semua potongan?". */
@Composable
fun ConfirmDialog(text: String, onNo: () -> Unit, onYes: () -> Unit) {
    Scrim(onTap = onNo, alignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(Ev.Panel)
                .absorbTaps()
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
                color = Color(0xFFC4C4CB),
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
// Sheet ekspor
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
            .background(if (on) Ev.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.5.dp, if (on) Ev.Accent else Ev.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(2.dp, if (on) Ev.Accent else Color(0xFF55555B), CircleShape)
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

    Scrim(onTap = null, alignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ev.Panel)
                .absorbTaps()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
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

            SectionLabel("KUALITAS")
            SimpleFlow(8.dp, 8.dp) {
                for (i in ExportMath.QUALITY_LABELS.indices) {
                    Chip(on = vm.qIdx == i, enabled = !running, label = ExportMath.QUALITY_LABELS[i]) {
                        vm.qIdx = i
                    }
                }
            }

            SectionLabel("FPS")
            SimpleFlow(8.dp, 8.dp) {
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

            // Progres / hasil
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

            // Tombol
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
                        ActionButton("Coba lagi", true, Modifier.weight(1f), onClick = onStart)
                    }

                    else -> {
                        ActionButton("Batal", false, Modifier.weight(1f)) { vm.closeExport() }
                        ActionButton("Mulai Ekspor", true, Modifier.weight(1f), onClick = onStart)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialog ubah teks overlay langsung saat diketuk di pratinjau
// ---------------------------------------------------------------------------------------------

@Composable
fun TextEditDialog(
    title: String = "Ubah teks",
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tf by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        try {
            focus.requestFocus()
        } catch (e: Exception) {
            // diabaikan
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = tf,
                    onValueChange = { tf = it },
                    placeholder = { Text("Kosong", color = Ev.Muted) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus)
                )
                if (tf.text.isNotEmpty()) {
                    Text(
                        text = "Kosongkan teks",
                        color = Ev.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { tf = TextFieldValue("") }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(tf.text) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
        containerColor = Ev.Panel,
        titleContentColor = Ev.Text,
        textContentColor = Ev.Text
    )
}
