package com.tobevpn.app.data.repository

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only dependency bridge for the on-device server probe benchmark. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PingBenchmarkEntryPoint {
    fun vpnRepository(): VpnRepository
    fun baseStationBypassRepository(): BaseStationBypassRepository
    fun baseStationBypassProfileProbeRepository(): BaseStationBypassProfileProbeRepository
}
