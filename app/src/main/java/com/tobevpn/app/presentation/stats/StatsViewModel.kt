package com.tobevpn.app.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.dao.TrafficStat
import com.tobevpn.app.domain.model.ServerSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

enum class StatsPeriod { DAY, WEEK, MONTH }

enum class StatsServerSource(val databaseValue: String?) {
    ALL(null),
    STANDARD(ServerSource.STANDARD.name),
    BASE_STATION_BYPASS(ServerSource.BASE_STATION_BYPASS.name),
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val trafficLogDao: TrafficLogDao,
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.DAY)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _serverSource = MutableStateFlow(StatsServerSource.ALL)
    val serverSource: StateFlow<StatsServerSource> = _serverSource.asStateFlow()

    val totalBytes: StateFlow<Long> = _serverSource
        .flatMapLatest { source ->
            trafficLogDao.getDeviceTotalBytes(source.databaseValue)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val stats: StateFlow<List<TrafficStat>> = _period
        .combine(_serverSource) { period, source -> period to source }
        .flatMapLatest { (p, source) ->
            val cal = Calendar.getInstance(TimeZone.getDefault())

            when (p) {
                StatsPeriod.DAY -> {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val dayStart = cal.timeInMillis / 1000
                    val dayEnd = dayStart + 86400
                    trafficLogDao.getDeviceHourlyStats(
                        dayStart = dayStart,
                        dayEnd = dayEnd,
                        serverSource = source.databaseValue,
                    )
                }
                StatsPeriod.WEEK -> {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    // set(DAY_OF_WEEK, MONDAY) is relative to the locale's
                    // firstDayOfWeek: in Sunday-first locales it would jump
                    // FORWARD to tomorrow on Sundays, pushing the whole week
                    // window into the future. Pin the week to start on Monday
                    // before resolving the field.
                    cal.firstDayOfWeek = Calendar.MONDAY
                    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    val weekStart = cal.timeInMillis / 1000
                    val weekEnd = weekStart + 7 * 86400
                    // getOffset(now) includes DST, unlike rawOffset which is
                    // off by an hour for half the year in DST zones.
                    val tzOffsetSec = TimeZone.getDefault()
                        .getOffset(System.currentTimeMillis()).toLong() / 1000
                    trafficLogDao.getDeviceDailyStats(
                        weekStart = weekStart,
                        weekEnd = weekEnd,
                        tzOffsetSec = tzOffsetSec,
                        serverSource = source.databaseValue,
                    )
                }
                StatsPeriod.MONTH -> {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    val monthStart = cal.timeInMillis / 1000
                    cal.add(Calendar.MONTH, 1)
                    val monthEnd = cal.timeInMillis / 1000
                    trafficLogDao.getDeviceWeeklyStats(
                        monthStart = monthStart,
                        monthEnd = monthEnd,
                        serverSource = source.databaseValue,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPeriod(p: StatsPeriod) {
        _period.value = p
    }

    fun setServerSource(source: StatsServerSource) {
        _serverSource.value = source
    }
}
