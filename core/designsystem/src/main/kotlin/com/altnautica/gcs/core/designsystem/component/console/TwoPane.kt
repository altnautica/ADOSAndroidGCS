package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

private val ListMinWidth = 280.dp
private val ListMaxWidth = 420.dp

/**
 * List/detail layout. Landscape shows both: the list takes [listWeight] of the width
 * (kept between 280 and 420 dp), then a divider, then the detail. Portrait shows one
 * pane: the [detail] when [showDetailInPortrait], otherwise the [list].
 */
@Composable
fun ConsoleTwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    showDetailInPortrait: Boolean,
    modifier: Modifier = Modifier,
    listWeight: Float = 0.38f,
) {
    if (!isLandscape()) {
        Box(modifier.fillMaxSize()) { if (showDetailInPortrait) detail() else list() }
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * listWeight).coerceIn(ListMinWidth, ListMaxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(AdosTheme.colors.borderDefault))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ConsoleTwoPanePreview() = ConsolePreview {
    ConsoleTwoPane(
        list = {
            Column {
                AdosListItem("Calibration", onClick = {}, selected = true)
                AdosListItem("Flight modes", onClick = {})
                AdosListItem("Failsafe", onClick = {})
            }
        },
        detail = {
            Text("Calibration", Modifier.padding(AdosSpacing.S4), style = MaterialTheme.typography.titleLarge)
        },
        showDetailInPortrait = false,
    )
}
