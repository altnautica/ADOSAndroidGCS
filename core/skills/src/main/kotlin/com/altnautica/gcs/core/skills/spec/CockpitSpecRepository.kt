package com.altnautica.gcs.core.skills.spec

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Reads spec files from the app's `assets/cockpit-spec/`. */
class AssetSpecSource(private val context: Context) : CockpitSpecSource {
    override fun read(file: CockpitSpecFile): String =
        context.assets.open("${CockpitSpecParser.ASSET_DIR}/${file.fileName}").bufferedReader().use { it.readText() }
}

/**
 * The parsed cockpit spec. Each file is read and parsed once, on first access; a file
 * that fails to parse throws on that access so a broken asset never renders silently.
 */
@Singleton
class CockpitSpecRepository @Inject constructor(private val source: CockpitSpecSource) {
    val builtins: BuiltinsSpec by lazy { CockpitSpecParser.builtins(source.read(CockpitSpecFile.Builtins)) }
    val ds4: Ds4Spec by lazy { CockpitSpecParser.ds4(source.read(CockpitSpecFile.Ds4)) }
    val layout: LayoutSpec by lazy { CockpitSpecParser.layout(source.read(CockpitSpecFile.Layout)) }
    val layers: LayersSpec by lazy { CockpitSpecParser.layers(source.read(CockpitSpecFile.Layers)) }
    val views: ViewsSpec by lazy { CockpitSpecParser.views(source.read(CockpitSpecFile.Views)) }
    val flightState: FlightStateSpec by lazy { CockpitSpecParser.flightState(source.read(CockpitSpecFile.FlightState)) }
    val tokens: TokensSpec by lazy { CockpitSpecParser.tokens(source.read(CockpitSpecFile.Tokens)) }
    val ds4Art: ControllerArtSpec by lazy { CockpitSpecParser.art(source.read(CockpitSpecFile.Ds4Art)) }
    val ds4Hotspots: ControllerHotspotsSpec by lazy { CockpitSpecParser.hotspots(source.read(CockpitSpecFile.Ds4Hotspots)) }
    val genericArt: ControllerArtSpec by lazy { CockpitSpecParser.art(source.read(CockpitSpecFile.GenericArt)) }
    val genericHotspots: ControllerHotspotsSpec by lazy {
        CockpitSpecParser.hotspots(source.read(CockpitSpecFile.GenericHotspots))
    }
    val setupStrings: SetupStringsSpec by lazy { CockpitSpecParser.setupStrings(source.read(CockpitSpecFile.SetupStrings)) }
    val tips: TipsSpec by lazy { CockpitSpecParser.tips(source.read(CockpitSpecFile.Tips)) }
}

@Module
@InstallIn(SingletonComponent::class)
object CockpitSpecModule {
    @Provides
    @Singleton
    fun provideSpecSource(@ApplicationContext context: Context): CockpitSpecSource = AssetSpecSource(context)
}
