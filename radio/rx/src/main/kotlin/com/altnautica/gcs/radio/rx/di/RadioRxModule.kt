package com.altnautica.gcs.radio.rx.di

import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.radio.rx.invite.AgentPhoneInviteApi
import com.altnautica.gcs.radio.rx.invite.InviteCipher
import com.altnautica.gcs.radio.rx.invite.NativeInviteCipher
import com.altnautica.gcs.radio.rx.invite.PhoneInviteApi
import com.altnautica.gcs.radio.rx.link.PhoneRadioTransportProvider
import com.altnautica.gcs.radio.rx.node.PhoneRadioNodeSource
import com.altnautica.gcs.radio.rx.video.PhoneRadioVideoFactory
import dagger.Binds
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
}
