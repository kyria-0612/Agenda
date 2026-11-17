package com.agenda.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import com.agenda.domain.*
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val Context.agendaSettings by preferencesDataStore(name="agenda-settings")
class LocalPreferences(context:Context,private val gate:LocalDataGate=LocalDataGate(),file:File?=null,seed:UserPreferences?=null):PreferencesRepository {
    private val page=intPreferencesKey("page")
    private val board=booleanPreferencesKey("board")
    private val fullDays=booleanPreferencesKey("full-days")
    private val store=if(file==null) context.applicationContext.agendaSettings else stores.computeIfAbsent(file.canonicalPath) {
        PreferenceDataStoreFactory.create(scope=CoroutineScope(SupervisorJob()+Dispatchers.IO),
            migrations=if(seed==null) emptyList() else listOf(object:DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData:Preferences)=currentData.asMap().isEmpty()
                override suspend fun migrate(currentData:Preferences):Preferences=mutablePreferencesOf(page to seed.page,board to seed.board,fullDays to seed.fullDays)
                override suspend fun cleanUp() {}
            }),produceFile={file})
    }
    override val preferences=store.data.map {UserPreferences((it[page] ?: 0).coerceIn(0,2),it[board] ?: false,it[fullDays] ?: false)}
    override suspend fun save(preferences:UserPreferences) {gate.run {store.edit {
        it[page]=preferences.page.coerceIn(0,2);it[board]=preferences.board;it[fullDays]=preferences.fullDays
    }}}
    companion object {private val stores=ConcurrentHashMap<String,DataStore<Preferences>>()}
}
