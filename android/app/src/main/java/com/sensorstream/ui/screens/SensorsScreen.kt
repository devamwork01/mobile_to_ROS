package com.sensorstream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        val items = vm.catalog.filter { SignalCatalog.of(it.type).category == cat }
            .sortedBy { SignalCatalog.of(it.type).humanName }
        if (items.isEmpty()) null else cat to items
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = SsDims.screenPad),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gapSm),
    ) {
        item {
            Text("Sensors", color = c.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        grouped.forEach { (cat, items) ->
            item { SectionHeader(cat.title()) }
            items(items, key = { it.handle }) { info ->
                val sig = SignalCatalog.of(info.type)
                val enabled = info.handle in sel.enabled
                val p = vm.periodOf(info.handle)
                val rate = if (p > 0) "${1_000_000 / p} Hz" else "Max"
                SensorCard(
                    info = sig,
                    rateText = rate,
                    enabled = enabled,
                    onToggle = { vm.toggle(info.handle) },
                    onOpen = { nav.go(Screen.Detail(info.handle)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                )
            }
        }
        item { Column(Modifier.fillMaxWidth().padding(8.dp)) {} }
    }
}
