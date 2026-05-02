package com.tobevpn.app.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.dao.TrafficStat
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.domain.model.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

enum class StatsPeriod { DAY, WEEK, MONTH }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val trafficLogDao: TrafficLogDao,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.DAY)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = authRepository.observeAuthState()
        .map { it is AuthState.Authenticated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val totalBytes: StateFlow<Long> = isAuthenticated.flatMapLatest { auth ->
        trafficLogDao.getTotalBytes(auth)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val stats: StateFlow<List<TrafficStat>> = combine(_period, isAuthenticated) { p, auth -> p to auth }
        .flatMapLatest { (p, auth) ->
            val cal = Calendar.getInstance(TimeZone.getDefault())

            when (p) {
                StatsPeriod.DAY -> {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val dayStart = cal.timeInMillis / 1000
                    val dayEnd = dayStart + 86400
                    trafficLogDao.getHourlyStats(dayStart, dayEnd, auth)
                }
                StatsPeriod.WEEK -> {
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    val weekStart = cal.timeInMillis / 1000
                    val weekEnd = weekStart + 7 * 86400
                    val tzOffsetSec = TimeZone.getDefault().rawOffset.toLong() / 1000
                    trafficLogDao.getDailyStats(weekStart, weekEnd, auth, tzOffsetSec)
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
                    trafficLogDao.getWeeklyStats(monthStart, monthEnd, auth)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPeriod(p: StatsPeriod) {
        _period.value = p
    }
}
