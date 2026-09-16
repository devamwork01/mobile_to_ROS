package com.sensorstream.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.core.SensorInfo
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SensorCard
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.nav.Screen
import com.sensorstream.ui.signal.SensorCategory
import com.sensorstream.ui.signal.SignalCatalog
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.vm.StreamViewModel

private val CATEGORY_ORDER = listOf(
    SensorCategory.MOTION, SensorCategory.ORIENTATION, SensorCategory.MAGNETIC,
    SensorCategory.ENVIRONMENT, SensorCategory.PROXIMITY, SensorCategory.OTHER,
)

private fun SensorCategory.title() = when (this) {
    SensorCategory.MOTION -> "Motion"
    SensorCategory.ORIENTATION -> "Orientation"
    SensorCategory.MAGNETIC -> "Magnetic"
    SensorCategory.ENVIRONMENT -> "Environment"
    SensorCategory.PROXIMITY -> "Proximity"
    SensorCategory.OTHER -> "Other"
}

@Composable
fun SensorsScreen(vm: StreamViewModel, nav: AppNav) {
    val sel by vm.sel.collectAsState()
    val c = Ss.colors

    // Build the ordered, grouped list once per catalog (catalog is stable).
    val grouped: List<Pair<SensorCategory, List<SensorInfo>>> = CATEGORY_ORDER.mapNotNull { cat ->
        val items = vm.catalog.filter { SignalCatalog.of(it.type, it.stringType).category == cat }
            .sortedBy { SignalCatalog.of(it.type, it.stringType).humanName }
        if (items.isEmpty()) null else cat to items
    }

    // Filter chips: null = All, else a single category.
    var filter by remember { mutableStateOf<SensorCategory?>(null) }
    val visible = grouped.filter { filter == null || it.first == filter }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = SsDims.screenPad),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gapSm),
    ) {
        item {
            Text("Sensors", color = c.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("All", filter == null) { filter = null }
                grouped.forEach { (cat, _) ->
                    FilterPill(cat.title(), filter == cat) { filter = cat }
                }
            }
        }
        visible.forEach { (cat, rows) ->
            item { SectionHeader(cat.title()) }
            items(rows, key = { it.handle }) { info ->
                val sig = SignalCatalog.of(info.type, info.stringType)
                val enabled = info.handle in sel.enabled
                val p = vm.periodOf(info.handle)
                val rate = if (p > 0) "${1_000_000 / p} Hz" else "Max"
                SensorCard(
                    info = sig,
                    rateText = rate,
                    enabled = enabled,
                    onToggle = { vm.toggle(info.handle) },
                    onOpen = { nav.go(Screen.Detail(info.handle)) },
                    subtitle = SignalCatalog.typeLabel(info.type, info.stringType),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                )
            }
        }
        item { Column(Modifier.fillMaxWidth().padding(8.dp)) {} }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Ss.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.accent else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) androidx.compose.ui.graphics.Color.White else c.muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
