package com.tobevpn.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object VpnModule {
    // VpnConnectionManager is @Singleton @Inject constructor — auto-provided by Hilt
}
