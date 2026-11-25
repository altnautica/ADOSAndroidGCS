package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.model.ParamDoc
import com.altnautica.gcs.feature.cockpit.R

/**
 * One parameter: what it is (the firmware's title over the raw name), current → recommended with
 * the values' labels, and, when [expandable] and there is something to say, a tap that opens the
 * firmware's description, why ADOS wants the value and whether the FC must restart. [dim] greys a
 * row that needs no change. The alert cards and the apply review both draw it.
 */
@Composable
internal fun ParamRow(name: String, have: Double?, want: String, doc: ParamDoc?, dim: Boolean = false, expandable: Boolean = true) {
    val c = LocalChrome.current
    val strong = if (dim) c.textDim else c.text
    val why = ParamText.why(name)
    val canOpen = expandable && (doc != null || why != null)
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val s = pressScale(interaction)
    val wantNumber = ParamText.wantValue(want)
    val haveText = have?.let { ParamText.value(it, doc) } ?: stringResource(R.string.alerts_param_not_reported)
    val wantText = wantNumber?.let { ParamText.value(it, doc) } ?: want
    val haveLabel = ParamText.label(have, doc)
    val wantLabel = ParamText.label(wantNumber, doc)
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = s; scaleY = s }
            .let { m ->
                if (canOpen) m.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button) { open = !open } else m
            }
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
            .testTag("status-param-$name"),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                val titleStyle = c.labelText.copy(color = strong, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
                if (doc != null) {
                    Text(doc.title, style = titleStyle, maxLines = 2)
                    Text(name, style = c.labelText.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                } else {
                    Text(name, style = titleStyle.copy(fontFamily = FontFamily.Monospace))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(haveText, style = c.labelText.copy(color = c.textDim))
                Text("→", style = c.labelText.copy(color = c.textDim), modifier = Modifier.padding(horizontal = 6.dp))
                Box(
                    Modifier.background(c.text.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(wantText, style = c.labelText.copy(color = strong, fontWeight = FontWeight.SemiBold))
                }
            }
            if (canOpen) {
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = c.textDim,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(chevronAngle(open, opened = 180f, closed = 0f)),
                )
            }
        }
        // The values' meanings take the row's full width, so a long label never squeezes the name.
        if (haveLabel != null || wantLabel != null) {
            Text(
                "${haveLabel ?: haveText} → ${wantLabel ?: wantText}",
                style = c.labelText.copy(fontSize = 11.sp),
                maxLines = 2,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = if (canOpen) 24.dp else 0.dp),
            )
        }
        if (canOpen) {
            AnimatedVisibility(open, enter = ExpandEnter, exit = ExpandExit) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    doc?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = c.labelText.copy(color = c.text.copy(alpha = 0.85f)))
                    }
                    why?.let {
                        Text(stringResource(R.string.alerts_param_why_label), style = c.labelText.copy(fontWeight = FontWeight.SemiBold))
                        Text(stringResource(it), style = c.labelText.copy(color = c.text.copy(alpha = 0.85f)))
                    }
                    if (doc?.rebootRequired == true) {
                        Text(stringResource(R.string.alerts_param_restart), style = c.labelText.copy(color = c.warning, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}
