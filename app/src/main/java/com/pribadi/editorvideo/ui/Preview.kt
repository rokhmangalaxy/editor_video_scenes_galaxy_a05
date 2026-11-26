package com.pribadi.editorvideo.ui

import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.export.OverlayRenderer
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.vm.EditorViewModel
import com.pribadi.editorvideo.vm.GridSortMode
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Warna konsisten untuk lencana/titik Tag di Mode Grid. */
fun tagColorFor(tag: String): Color = when (tag.lowercase()) {
    "favorit" -> Color(0xFFFACC15)
    "utama" -> Color(0xFFF43F5E)
    "highlight" -> Color(0xFFF97316)
    "b-roll" -> Color(0xFF38BDF8)
    "simpan" -> Color(0xFF10B981)
    else -> {
        val palette = listOf(
            Color(0xFFA855F7),
            Color(0xFFEC4899),
            Color(0xFF14B8A6),
            Color(0xFFEAB308),
            Color(0xFF60A5FA)
        )
        palette[(tag.hashCode() and 0x7FFFFFFF) % palette.size]
    }
}

/**
 * Kotak pratinjau tunggal:
 *  - Saat belum ada video (!vm.hasVideo): menampilkan kotak pratinjau bersih tanpa Readme.
 *  - Saat sudah ada video (vm.hasVideo): menampilkan video + teks overlay kaya, indikator Mundur / Loop,
 *    mendukung pinch-to-zoom 2 jari, ketuk teks untuk mengedit, dan seret 1 jari untuk memindah posisi teks.
 */
@Composable
fun PreviewBox(
    vm: EditorViewModel,
    boxW: Dp,
    boxH: Dp,
    onEditText: (Int) -> Unit,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(boxW, boxH)
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds()
            .background(if (vm.hasVideo) Color.Black else Ev.Panel)
    ) {
        if (vm.hasVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = vm.previewZoom
                        scaleY = vm.previewZoom
                        translationX = vm.previewPanX
                        translationY = vm.previewPanY
                    }
            ) {
                VideoSurface(vm, boxW, boxH)
                OverlayLayer(vm, onEditText)
            }

            if (vm.previewZoom > 1.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ev.Bg.copy(alpha = 0.82f))
                        .border(1.dp, Ev.Line, RoundedCornerShape(14.dp))
                        .clickable { vm.resetPreviewZoom() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Zoom " + Fmt.one(vm.previewZoom.toDouble()) + "x · Ketuk untuk Reset",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (vm.selReversed && !vm.reverseRendering) {
                val badgeLabel = if (vm.selRevMode == Seg.REV_LOOP) "🔁 Loop" else "⏪ Mundur"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Accent.copy(alpha = 0.88f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (vm.reverseRendering) {
                ReverseProgressOverlay(vm)
            }
        } else {
            EmptyPreviewState()
            OverlayLayer(vm, onEditText, onEmptyTap = onImport)
        }
    }
}

/**
 * Mode Grid Lengkap:
 *  - Grid menggunakan 100% lebar penuh (tidak terpotong oleh kontrol kolom).
 *  - Simbol kostum kolom men-toggle panel floating input range vertikal (3–9 kolom).
 *  - Fitur Tagging (12 opsi warna), Sort, Filter, serta Hold Multiseleksi & Multiplayer Collage.
 *  - Thumbnail menampilkan frame pertama klip secara utuh sesuai rasio/orientasi video.
 */
@Composable
fun VideoGridStage(
    vm: EditorViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val _rev = vm.rev
    val cols = vm.gridColumns.coerceIn(3, 9)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (!vm.hasVideo || vm.core.segs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                EmptyGridState(cols = cols, onImport = onImport)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ev.Ghost)
                            .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                            .clickable { vm.openGridTagManager() }
                            .padding(horizontal = 8.dp, vertical = 3.5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Ico(R.drawable.ic_tag, Ev.Yellow, 12.dp)
                            Text("Tag", color = Ev.Text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    GridColToggleSymbol(
                        cols = cols,
                        active = vm.gridColSliderOpen,
                        onClick = { vm.toggleGridColSlider() }
                    )
                }
            }
        } else {
            val segs = vm.core.segs
            val useSegGrid = when (vm.gridSubMode) {
                1 -> true
                2 -> false
                else -> segs.size >= 2
            }

            // Hitung daftar indeks potongan setelah Filter & Sort
            val filteredSegIndices: List<Int> = if (useSegGrid) {
                val base = segs.indices.filter { idx ->
                    val g = segs[idx]
                    when (vm.gridFilter) {
                        EditorViewModel.GRID_FILTER_ALL -> true
                        EditorViewModel.GRID_FILTER_TAGGED -> g.tags.isNotEmpty()
                        EditorViewModel.GRID_FILTER_UNTAGGED -> g.tags.isEmpty()
                        else -> vm.gridFilter in g.tags
                    }
                }
                when (vm.gridSortMode) {
                    GridSortMode.TIMELINE_ASC -> base
                    GridSortMode.TIMELINE_DESC -> base.reversed()
                    GridSortMode.DUR_DESC -> base.sortedByDescending { segs[it].len }
                    GridSortMode.DUR_ASC -> base.sortedBy { segs[it].len }
                    GridSortMode.TAG_AZ -> base.sortedWith(
                        Comparator { a, b ->
                            val emptyA = segs[a].tags.isEmpty()
                            val emptyB = segs[b].tags.isEmpty()
                            if (emptyA != emptyB) {
                                if (emptyA) 1 else -1
                            } else {
                                val tagA = segs[a].tags.firstOrNull()?.lowercase() ?: ""
                                val tagB = segs[b].tags.firstOrNull()?.lowercase() ?: ""
                                val cmp = tagA.compareTo(tagB)
                                if (cmp != 0) cmp else a.compareTo(b)
                            }
                        }
                    )
                }
            } else {
                emptyList()
            }

            val sliceCount = max(9, cols * 3)
            val allVisibleKeys: List<Int> = if (useSegGrid) {
                filteredSegIndices
            } else {
                (0 until sliceCount).map { EditorViewModel.SLICE_KEY_OFFSET + it }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Baris 1: Mode, Tagging, Sort (kiri) + Simbol Toggle Kostum Kolom Floating (kanan)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        GridFilterPill(
                            label = "Klip (${segs.size})",
                            active = useSegGrid
                        ) {
                            vm.clearGridMultiSelect()
                            vm.gridSubMode = 1
                        }
                        GridFilterPill(
                            label = "Waktu ($sliceCount)",
                            active = !useSegGrid
                        ) {
                            vm.clearGridMultiSelect()
                            vm.gridSubMode = 2
                        }
                        // Tombol Tagging
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ev.Ghost)
                                .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                                .clickable { vm.openGridTagManager() }
                                .padding(horizontal = 8.dp, vertical = 3.5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Ico(R.drawable.ic_tag, Ev.Yellow, 12.dp)
                                Text(
                                    text = "Tag",
                                    color = Ev.Text,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // Tombol Sort (Urutkan)
                        if (useSegGrid) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (vm.gridSortMode != GridSortMode.TIMELINE_ASC) {
                                            Ev.Accent.copy(alpha = 0.22f)
                                        } else {
                                            Ev.Ghost
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (vm.gridSortMode != GridSortMode.TIMELINE_ASC) Ev.Accent else Ev.Line,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { vm.gridSortSheet = true }
                                    .padding(horizontal = 8.dp, vertical = 3.5.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Ico(R.drawable.ic_sort, Ev.InfoText, 12.dp)
                                    Text(
                                        text = vm.gridSortMode.shortLabel,
                                        color = Ev.Text,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Simbol Kostum Kolom (toggle panel floating range vertikal 3..9)
                    GridColToggleSymbol(
                        cols = cols,
                        active = vm.gridColSliderOpen,
                        onClick = { vm.toggleGridColSlider() }
                    )
                }

                    // Baris 2: Filter per Tag + Aksi cepat bagi klip bila masih 1 klip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 5.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (segs.size == 1) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Ev.Accent.copy(alpha = 0.22f))
                                    .border(1.dp, Ev.Accent, RoundedCornerShape(10.dp))
                                    .clickable { vm.splitCurrentClipForGrid(max(6, cols * 2)) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "✂ Bagi ${max(6, cols * 2)} Klip",
                                    color = Ev.Yellow,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (useSegGrid) {
                            val taggedCount = segs.count { it.tags.isNotEmpty() }
                            val untaggedCount = segs.size - taggedCount
                            FilterTagChip(
                                label = "Semua (${segs.size})",
                                active = vm.gridFilter == EditorViewModel.GRID_FILTER_ALL,
                                dotColor = null
                            ) { vm.selectGridFilter(EditorViewModel.GRID_FILTER_ALL) }

                            if (taggedCount > 0) {
                                FilterTagChip(
                                    label = "Bertag ($taggedCount)",
                                    active = vm.gridFilter == EditorViewModel.GRID_FILTER_TAGGED,
                                    dotColor = Ev.Yellow
                                ) { vm.selectGridFilter(EditorViewModel.GRID_FILTER_TAGGED) }

                                FilterTagChip(
                                    label = "Tanpa Tag ($untaggedCount)",
                                    active = vm.gridFilter == EditorViewModel.GRID_FILTER_UNTAGGED,
                                    dotColor = null
                                ) { vm.selectGridFilter(EditorViewModel.GRID_FILTER_UNTAGGED) }
                            }

                        for (tag in vm.availableTags) {
                            val cnt = segs.count { tag in it.tags }
                            if (cnt > 0 || vm.gridFilter == tag) {
                                FilterTagChip(
                                    label = "$tag ($cnt)",
                                    active = vm.gridFilter == tag,
                                    dotColor = vm.tagColor(tag)
                                ) { vm.selectGridFilter(tag) }
                            }
                        }
                        } else {
                            Text(
                                text = "Tips: Hold (tekan tahan) sel untuk Multiseleksi & Multiplayer Collage",
                                color = Ev.Muted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Baris 3 (Kondisional): Bar Multiseleksi (Hold) + Tombol Multiplayer Collage
                    if (vm.gridMultiSelect) {
                        val selCount = vm.gridSelectedKeys.size
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Ev.Panel)
                                .border(1.dp, Ev.Accent, RoundedCornerShape(10.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$selCount dipilih",
                                color = Ev.Yellow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            MultiBarBtn(
                                label = if (vm.gridSelectedKeys.containsAll(allVisibleKeys) && allVisibleKeys.isNotEmpty()) {
                                    "Lepas"
                                } else {
                                    "Semua"
                                },
                                primary = false
                            ) {
                                vm.selectAllGridKeys(allVisibleKeys)
                            }
                            MultiBarBtn(
                                label = "🎬 Collage",
                                primary = true
                            ) {
                                vm.openMultiplayerCollage()
                            }
                            if (useSegGrid) {
                                MultiBarBtn(
                                    label = "🏷 Tag",
                                    primary = false
                                ) {
                                    vm.openGridTagManager()
                                }
                                if (segs.size > 1) {
                                    MultiBarBtn(
                                        label = "Hapus",
                                        primary = false
                                    ) {
                                        vm.deleteGridSelectedSegments()
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Ev.Ghost)
                                    .clickable { vm.clearGridMultiSelect() },
                                contentAlignment = Alignment.Center
                            ) {
                                Ico(R.drawable.ic_close, Ev.Text, 13.dp)
                            }
                        }
                    }

                    // Daftar Sel Grid (3 sampai 9 Kolom)
                    val gapDp = when {
                        cols <= 4 -> 6.dp
                        cols <= 6 -> 4.dp
                        else -> 2.5.dp
                    }

                    if (useSegGrid && filteredSegIndices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Ev.Panel)
                                .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Tidak ada klip yang cocok dengan filter \"${vm.gridFilter}\"",
                                    color = Ev.Muted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                GridFilterPill("Tampilkan Semua", true) {
                                    vm.selectGridFilter(EditorViewModel.GRID_FILTER_ALL)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(gapDp)
                        ) {
                            val rowCellAspect = vm.aspectOfSource(0).coerceIn(0.45f, 2.25f)
                            if (useSegGrid) {
                                val rows = filteredSegIndices.chunked(cols)
                                for (rowIndices in rows) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(gapDp)
                                    ) {
                                        for (idx in rowIndices) {
                                            val g = segs[idx]
                                            val isActive = !vm.gridMultiSelect && idx == vm.selIndex
                                            val selOrder = vm.gridSelectedKeys.indexOf(idx)
                                            // Gunakan tepat frame pertama (g.a) dari setiap klip!
                                            val firstSrcTime = g.a
                                            val statusPrefix = when {
                                                cols >= 6 -> ""
                                                g.fz -> "❄"
                                                g.revMode == Seg.REV_LOOP -> "🔁"
                                                g.rev -> "⏪"
                                                else -> ""
                                            }
                                            GridVideoCell(
                                                vm = vm,
                                                cols = cols,
                                                cellKey = idx,
                                                isActive = isActive,
                                                selectionOrder = selOrder,
                                                srcIndex = g.src,
                                                thumbTimeSec = firstSrcTime,
                                                topBadge = "$statusPrefix#${idx + 1}",
                                                bottomBadge = Fmt.durLabel(g.len),
                                                tags = g.tags,
                                                cellAspect = rowCellAspect,
                                                modifier = Modifier.weight(1f),
                                                onTap = {
                                                    if (vm.gridMultiSelect) {
                                                        vm.toggleGridSelectKey(idx)
                                                    } else if (idx == vm.selIndex) {
                                                        vm.togglePlay()
                                                    } else {
                                                        vm.selectSegmentFromGrid(idx, autoPlay = true)
                                                    }
                                                },
                                                onHold = {
                                                    vm.startGridMultiSelect(idx)
                                                }
                                            )
                                        }
                                        repeat(cols - rowIndices.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                val total = vm.core.total.coerceAtLeast(0.1)
                                val sliceDur = total / sliceCount.toDouble()
                                val activeSlice = ((vm.core.t / sliceDur).toInt()).coerceIn(0, sliceCount - 1)
                                val rows = (0 until sliceCount).toList().chunked(cols)
                                for (rowSlices in rows) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(gapDp)
                                    ) {
                                        for (sIdx in rowSlices) {
                                            val key = EditorViewModel.SLICE_KEY_OFFSET + sIdx
                                            val startT = sIdx * sliceDur
                                            // Gunakan frame pertama (startT) dari setiap bagian waktu!
                                            val locStart = vm.core.locate(startT)
                                            val g = segs.getOrElse(locStart.i) { segs.first() }
                                            val isActive = !vm.gridMultiSelect && sIdx == activeSlice
                                            val selOrder = vm.gridSelectedKeys.indexOf(key)
                                            GridVideoCell(
                                                vm = vm,
                                                cols = cols,
                                                cellKey = key,
                                                isActive = isActive,
                                                selectionOrder = selOrder,
                                                srcIndex = g.src,
                                                thumbTimeSec = locStart.s,
                                                topBadge = "#${sIdx + 1}",
                                                bottomBadge = Fmt.time(startT),
                                                tags = g.tags,
                                                cellAspect = rowCellAspect,
                                                modifier = Modifier.weight(1f),
                                                onTap = {
                                                    if (vm.gridMultiSelect) {
                                                        vm.toggleGridSelectKey(key)
                                                    } else if (sIdx == activeSlice) {
                                                        vm.togglePlay()
                                                    } else {
                                                        vm.selectSliceFromGrid(startT)
                                                    }
                                                },
                                                onHold = {
                                                    vm.startGridMultiSelect(key)
                                                }
                                            )
                                        }
                                        repeat(cols - rowSlices.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (vm.reverseRendering) {
                    ReverseProgressOverlay(vm)
                }
            }

        // Overlay Floating Input Range Vertikal 3..9 Kolom (tampil saat simbol kostum kolom di-toggle)
        if (vm.gridColSliderOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { vm.gridColSliderOpen = false }
                    }
            )
            VerticalGridColumnsRange(
                columns = cols,
                onColumnsChange = { vm.updateGridColumns(it) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 31.dp, end = 2.dp, bottom = 6.dp)
            )
        }
    }
}

/** Simbol tombol toggle kostum kolom pada toolbar Mode Grid. */
@Composable
private fun GridColToggleSymbol(
    cols: Int,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Yellow else Ev.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Ico(
                id = R.drawable.ic_grid_cols,
                tint = if (active) Color.White else Ev.Yellow,
                size = 13.dp
            )
            Text(
                text = "${cols}×",
                color = if (active) Color.White else Ev.Text,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Kontrol Input Range Vertikal Floating untuk mengatur jumlah kolom Grid dari 3 sampai 9 kolom.
 * Tampil mengambang di atas Grid ketika simbol kostum kolom di-toggle tanpa memakan ruang Grid.
 */
@Composable
private fun VerticalGridColumnsRange(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(38.dp)
            .fillMaxHeight(0.84f)
            .heightIn(min = 175.dp, max = 265.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Ev.Panel.copy(alpha = 0.94f))
            .border(1.5.dp, Ev.Accent.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "KOL",
            color = Ev.Muted,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Ev.Accent)
                .padding(horizontal = 6.dp, vertical = 1.5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = columns.toString(),
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Tombol kurangi kolom (menuju 3)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Ev.Ghost)
                .clickable(enabled = columns > 3) { onColumnsChange(columns - 1) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "−",
                color = if (columns > 3) Ev.Text else Ev.Muted.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Rel Range Vertikal 3 .. 9
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val trackH = size.height.toFloat().coerceAtLeast(1f)
                        fun mapYToCol(y: Float): Int {
                            val frac = (y / trackH).coerceIn(0f, 1f)
                            return (3f + frac * 6f).roundToInt().coerceIn(3, 9)
                        }
                        onColumnsChange(mapYToCol(down.position.y))
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.firstOrNull { it.pressed } ?: break
                            onColumnsChange(mapYToCol(pressed.position.y))
                            pressed.consume()
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            val thumbSize = 18.dp
            val usableH = (maxHeight - thumbSize).coerceAtLeast(0.dp)
            val frac = ((columns - 3).toFloat() / 6f).coerceIn(0f, 1f)
            val thumbOffsetDp = usableH * frac

            // Garis rel tengah
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ev.Ghost)
            )

            // Bagian aktif rel dari atas ke posisi thumb
            val activeHeightDp = thumbOffsetDp + (thumbSize * 0.5f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(4.dp)
                    .height(activeHeightDp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ev.Accent)
            )

            // Titik skala 3..9
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (c in 3..9) {
                    Text(
                        text = c.toString(),
                        color = if (c == columns) Color.Transparent else Ev.Muted.copy(alpha = 0.65f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Kenop geser (thumb)
            Box(
                modifier = Modifier
                    .offset(y = thumbOffsetDp)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Ev.Yellow)
                    .border(2.dp, Ev.Accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = columns.toString(),
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Tombol tambah kolom (menuju 9)
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Ev.Ghost)
                .clickable(enabled = columns < 9) { onColumnsChange(columns + 1) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                color = if (columns < 9) Ev.Text else Ev.Muted.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyGridState(cols: Int, onImport: () -> Unit) {
    val gap = if (cols <= 5) 6.dp else 3.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Ev.Panel)
            .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onImport)
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val midCol = cols / 2
        for (r in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                for (c in 0 until cols) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.85f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Ev.Bg)
                            .border(1.dp, Ev.Line, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (r == 0 && c == midCol) {
                            Box(
                                modifier = Modifier
                                    .size(if (cols <= 5) 28.dp else 18.dp)
                                    .clip(CircleShape)
                                    .background(Ev.Accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Ico(R.drawable.ic_plus, Color.White, if (cols <= 5) 16.dp else 11.dp)
                            }
                        } else if (cols <= 6) {
                            Text(
                                text = "#${r * cols + c + 1}",
                                color = Ev.Muted.copy(alpha = 0.45f),
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
            }
            if (r == 0) Spacer(modifier = Modifier.height(gap))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Mode Grid ($cols Kolom) · Ketuk untuk Impor Video",
            color = Ev.Muted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun GridFilterPill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.5.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Ev.InfoText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FilterTagChip(
    label: String,
    active: Boolean,
    dotColor: Color?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Ev.Accent else Ev.Panel)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (active) Color.White else dotColor)
                )
            }
            Text(
                text = label,
                color = if (active) Color.White else Ev.Muted,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MultiBarBtn(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) Ev.Accent else Ev.Ghost)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GridVideoCell(
    vm: EditorViewModel,
    cols: Int,
    cellKey: Int,
    isActive: Boolean,
    selectionOrder: Int,
    srcIndex: Int,
    thumbTimeSec: Double,
    topBadge: String,
    bottomBadge: String,
    tags: List<String>,
    cellAspect: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onHold: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestTap = rememberUpdatedState(onTap)
    val latestHold = rememberUpdatedState(onHold)
    val isSelected = selectionOrder >= 0
    val corner = when {
        cols <= 4 -> 8.dp
        cols <= 6 -> 6.dp
        else -> 4.dp
    }
    val borderW = if (isSelected || isActive) 2.dp else 1.dp
    val borderCol = when {
        isSelected -> Ev.Yellow
        isActive -> Ev.Accent
        else -> Ev.Line
    }

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(cellAspect)
            .clip(RoundedCornerShape(corner))
            .background(Color.Black)
            .border(borderW, borderCol, RoundedCornerShape(corner))
            .pointerInput(cellKey) {
                detectTapGestures(
                    onTap = { latestTap.value() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestHold.value()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val cellW = maxWidth
        val cellH = maxHeight
        val firstFrameBucket = Math.round(max(0.0, thumbTimeSec) * 1000.0) / 10L
        LaunchedEffect(srcIndex, firstFrameBucket, vm.rot, vm.flip) {
            vm.ensureGridFirstFrame(srcIndex, thumbTimeSec)
        }

        val firstBmp = vm.gridFirstFrameAt(srcIndex, thumbTimeSec)
        if (firstBmp != null && !firstBmp.isRecycled) {
            Image(
                bitmap = firstBmp.asImageBitmap(),
                contentDescription = topBadge,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!isActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Ev.Panel),
                contentAlignment = Alignment.Center
            ) {
                if (cols <= 6) {
                    Text(text = topBadge, color = Ev.Muted, fontSize = 9.sp)
                }
            }
        }

        if (isActive) {
            val videoAr = vm.aspectOfSource(srcIndex)
            var vw = cellW
            var vh = vw / videoAr
            if (vh > cellH) {
                vh = cellH
                vw = vh * videoAr
            }
            LaunchedEffect(cellKey) {
                delay(90)
                vm.refreshFrame()
            }
            Box(modifier = Modifier.size(vw, vh)) {
                VideoSurface(vm, vw, vh)
            }
        }

        // Lencana kiri atas (# nomor klip) — diberi transparansi agar gambar frame pertama tetap terlihat
        val padEdge = if (cols >= 7) 2.dp else 3.5.dp
        val badgeFont = when {
            cols <= 4 -> 9.sp
            cols <= 6 -> 8.sp
            else -> 7.sp
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(padEdge)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isActive) {
                        Ev.Accent.copy(alpha = 0.48f)
                    } else {
                        Color.Black.copy(alpha = 0.36f)
                    }
                )
                .padding(horizontal = if (cols >= 7) 3.dp else 4.5.dp, vertical = 1.dp)
        ) {
            Text(
                text = topBadge,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = badgeFont,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }

        // Indikator Multiseleksi (kanan atas) atau Label Tag transparan dengan warna dari 12 opsi warna
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(padEdge)
                    .size(if (cols >= 7) 14.dp else 18.dp)
                    .clip(CircleShape)
                    .background(Ev.Yellow.copy(alpha = 0.74f))
                    .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (selectionOrder + 1).toString(),
                    color = Color.Black,
                    fontSize = if (cols >= 7) 8.sp else 9.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        } else if (tags.isNotEmpty()) {
            if (cols <= 5) {
                val firstTag = tags.first()
                val tagCol = vm.tagColor(firstTag)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(padEdge)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.28f))
                        .background(tagCol.copy(alpha = 0.36f))
                        .border(0.8.dp, tagCol.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.5.dp, vertical = 1.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(tagCol.copy(alpha = 0.92f))
                        )
                        Text(
                            text = if (tags.size > 1) "$firstTag +${tags.size - 1}" else firstTag,
                            color = Color.White.copy(alpha = 0.94f),
                            fontSize = if (cols == 5) 7.5.sp else 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(padEdge),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (t in tags.take(2)) {
                        val tagCol = vm.tagColor(t)
                        Box(
                            modifier = Modifier
                                .size(if (cols >= 7) 5.5.dp else 7.dp)
                                .clip(CircleShape)
                                .background(tagCol.copy(alpha = 0.68f))
                                .border(0.5.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
            }
        }

        // Lencana durasi/waktu kanan bawah transparan (disembunyikan pada 8-9 kolom agar gambar tetap bersih)
        if (cols <= 7) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(padEdge)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.36f))
                    .padding(horizontal = if (cols >= 6) 3.dp else 4.5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = bottomBadge,
                    color = Ev.Yellow.copy(alpha = 0.92f),
                    fontSize = badgeFont,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ReverseProgressOverlay(vm: EditorViewModel) {
    val pct = (vm.reverseProgress * 100f).toInt().coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBF000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Ev.Panel)
                .border(1.dp, Ev.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Merender ${vm.reverseRenderingLabel}… $pct%",
                color = Ev.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { vm.reverseProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .width(190.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Ev.Accent,
                trackColor = Ev.Ghost
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.cancelReverseRender() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Batalkan",
                    color = Ev.InfoText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Tampilan placeholder pratinjau awal ketika belum ada video:
 * jika diklik akan menampilkan opsi upload dari Galeri atau File Manager.
 */
@Composable
private fun EmptyPreviewState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ev.Panel)
            .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Ev.Accent),
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_plus, Color.White, 28.dp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Impor Video",
            color = Ev.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ketuk untuk pilih dari Galeri atau File Manager",
            color = Ev.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Ghost)
                    .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Ico(R.drawable.ic_gallery, Ev.Yellow, 14.dp)
                Text("Galeri", color = Ev.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Ghost)
                    .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Ico(R.drawable.ic_folder, Ev.InfoText, 14.dp)
                Text("File Manager", color = Ev.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * TextureView dibuat seukuran video sebelum diputar, lalu diputar/dicermin lewat properti View.
 * Juga mendaftarkan TextureView aktif ke ViewModel agar panel AI dapat membaca buffer piksel yang 100% sama persis.
 */
@Composable
private fun VideoSurface(vm: EditorViewModel, boxW: Dp, boxH: Dp) {
    val rot = vm.rot
    val flip = vm.flip
    val r90 = rot % 180 != 0
    val preW = if (r90) boxH else boxW
    val preH = if (r90) boxW else boxH
    val density = LocalDensity.current
    val wPx = with(density) { preW.roundToPx() }
    val hPx = with(density) { preH.roundToPx() }

    LaunchedEffect(Unit) {
        delay(200)
        vm.refreshFrame()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            frame.clipChildren = false
            frame.clipToPadding = false
            val tv = TextureView(ctx)
            frame.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            vm.player.setVideoTextureView(tv)
            vm.attachPreviewTextureView(tv)
            frame
        },
        update = { frame ->
            val tv = frame.getChildAt(0) as TextureView
            vm.attachPreviewTextureView(tv)
            val lp = tv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != wPx || lp.height != hPx) {
                lp.width = wPx
                lp.height = hPx
                tv.layoutParams = lp
            }
            tv.rotation = rot.toFloat()
            tv.scaleX = if (flip && !r90) -1f else 1f
            tv.scaleY = if (flip && r90) -1f else 1f
        },
        onRelease = { frame ->
            val tv = frame.getChildAt(0) as TextureView
            vm.detachPreviewTextureView(tv)
            vm.player.clearVideoTextureView(tv)
        }
    )
}

/**
 * Dua layer teks overlay digambar dengan kode yang sama persis seperti saat ekspor.
 */
@Composable
private fun OverlayLayer(
    vm: EditorViewModel,
    onEditText: (Int) -> Unit,
    onEmptyTap: (() -> Unit)? = null
) {
    val latestEmptyTap = rememberUpdatedState(onEmptyTap)
    val slop = LocalDensity.current.run { 8.dp.toPx() }
    val hitPad = LocalDensity.current.run { 14.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(vm.hasVideo) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var movedMuch = false
                    val hitOnDown = OverlayRenderer.hitTest(
                        size.width.toFloat(),
                        size.height.toFloat(),
                        vm.style1,
                        vm.style2,
                        down.position.x,
                        down.position.y,
                        hitPad
                    )
                    var prevPos = down.position

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2 && vm.hasVideo) {
                            multiTouch = true
                            val zoomChange = ev.calculateZoom()
                            val panChange = ev.calculatePan()
                            val centroidSize = ev.calculateCentroidSize(useCurrent = false)
                            if (centroidSize > 0f && (abs(zoomChange - 1f) > 0.002f || panChange.getDistance() > 0.5f)) {
                                vm.updatePreviewTransform(zoomChange, panChange.x, panChange.y)
                            }
                            ev.changes.forEach { it.consume() }
                        } else if (!multiTouch && pressed.size == 1) {
                            val cur = pressed[0]
                            val d = (cur.position - down.position).getDistance()
                            if (d > slop) {
                                movedMuch = true
                            }
                            if (movedMuch) {
                                val dx = cur.position.x - prevPos.x
                                val dy = cur.position.y - prevPos.y
                                prevPos = cur.position
                                if (hitOnDown != 0 && size.width > 0 && size.height > 0) {
                                    vm.moveOverlayText(
                                        hitOnDown,
                                        dx / size.width.toFloat(),
                                        dy / size.height.toFloat()
                                    )
                                    cur.consume()
                                } else if (vm.hasVideo && vm.previewZoom > 1.02f) {
                                    vm.updatePreviewTransform(1f, dx, dy)
                                    cur.consume()
                                }
                            }
                        }
                        if (pressed.isEmpty()) {
                            if (!multiTouch && !movedMuch) {
                                if (hitOnDown != 0) {
                                    onEditText(hitOnDown)
                                } else if (vm.hasVideo) {
                                    vm.togglePlay()
                                } else {
                                    latestEmptyTap.value?.invoke()
                                }
                            }
                            break
                        }
                    }
                }
            }
    ) {
        val revTrigger = vm.rev
        val s1 = vm.style1
        val s2 = vm.style2
        if (revTrigger >= 0 && (s1.isNotEmpty || s2.isNotEmpty)) {
            drawIntoCanvas { c ->
                OverlayRenderer.draw(c.nativeCanvas, size.width, size.height, s1, s2)
            }
        }
    }
}
