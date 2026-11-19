package com.altnautica.gcs.radio.rx.di

import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.invite.AgentPhoneInviteApi
import com.altnautica.gcs.radio.rx.invite.InviteCipher
import com.altnautica.gcs.radio.rx.invite.NativeInviteCipher
import com.altnautica.gcs.radio.rx.invite.PhoneInviteApi
import com.altnautica.gcs.radio.rx.link.PhoneRadioTransportProvider
import com.altnautica.gcs.radio.rx.node.PhoneRadioNodeSource
import com.altnautica.gcs.radio.rx.video.PhoneRadioVideoFactory
import com.altnautica.gcs.radio.rx.uplink.BoxUplinkApi
import com.altnautica.gcs.radio.rx.uplink.HttpBoxUplinkApi
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class RadioRxModule {

    @Binds
    @IntoSet
    abstract fun nodeSource(source: PhoneRadioNodeSource): NodeSource

    @Binds
    @IntoSet
    abstract fun transport(provider: PhoneRadioTransportProvider): TransportProvider

    @Binds
    @IntoSet
    abstract fun video(factory: PhoneRadioVideoFactory): VideoSourceFactory

    @Binds
    abstract fun inviteApi(api: AgentPhoneInviteApi): PhoneInviteApi

    @Binds
    abstract fun inviteCipher(cipher: NativeInviteCipher): InviteCipher

    @Binds
    abstract fun boxUplinkApi(api: HttpBoxUplinkApi): BoxUplinkApi

    /** Bound only in a debug build (the fleet bench relay). */
    @BindsOptionalOf
    abstract fun alternateRadio(): AlternateRadio
}
