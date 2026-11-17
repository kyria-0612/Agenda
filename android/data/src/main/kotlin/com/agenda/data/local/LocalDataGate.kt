package com.agenda.data.local

import com.agenda.domain.AgendaFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One gate per active dataset: exports freeze writes; switching retires old writers. */
class LocalDataGate {
    private val mutex=Mutex()
    private var retired=false
    suspend fun <T> run(action:suspend ()->T):T=mutex.withLock {
        if(retired) throw AgendaFailure.WorkspaceChanged()
        action()
    }
    internal fun retire() {retired=true}
}
