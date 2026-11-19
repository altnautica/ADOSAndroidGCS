package com.altnautica.gcs.feature.settings.controller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The stick rate used when the pilot is flown over the phone radio. */
@HiltViewModel
class RadioInputRateViewModel @Inject constructor(private val controls: ControlsRepository) : ViewModel() {
    val hz: StateFlow<Int> = controls.radioInputHz
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ControlsRepository.RADIO_INPUT_DEFAULT_HZ)

    fun set(hz: Int) {
        viewModelScope.launch { controls.setRadioInputHz(hz) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/**
 * The phone-radio stick rate: 50 Hz by default, 20 or 10 Hz to spend less of
 * the uplink's airtime on sticks.
 */
@Composable
fun RadioInputRateRow(vm: RadioInputRateViewModel = hiltViewModel()) {
    val hz by vm.hz.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1), modifier = Modifier.testTag("controls-radio-input-rate")) {
        Text(stringResource(R.string.controls_radio_input_rate), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.controls_radio_input_rate_hint), color = AdosTheme.colors.fgSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            for (rate in ControlsRepository.RADIO_INPUT_RATES_HZ) {
                AdosButton(
                    stringResource(R.string.controls_radio_input_rate_hz, rate),
                    onClick = { vm.set(rate) },
                    variant = if (rate == hz) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
                    modifier = Modifier.testTag("controls-radio-input-rate-$rate"),
                )
            }
        }
    }
}
