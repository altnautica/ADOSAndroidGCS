package com.altnautica.gcs.core.extensions.data

import javax.inject.Qualifier

/** The application-lifetime scope extension feeds run in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExtensionScope
