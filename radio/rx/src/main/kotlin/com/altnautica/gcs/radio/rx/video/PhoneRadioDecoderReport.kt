package com.altnautica.gcs.radio.rx.video

import com.altnautica.gcs.core.video.codec.DecoderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which decoder the phone radio's video last opened, so the radio screen can
 * say whether the drone's video is decoded in hardware.
 */
@Singleton
class PhoneRadioDecoderReport @Inject constructor() {
    private val _decoder = MutableStateFlow<DecoderInfo?>(null)
    val decoder: StateFlow<DecoderInfo?> = _decoder.asStateFlow()

    fun opened(info: DecoderInfo) {
        _decoder.value = info
    }
}
