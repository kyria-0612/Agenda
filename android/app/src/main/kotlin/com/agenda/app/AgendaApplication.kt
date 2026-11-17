package com.agenda.app

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.agenda.data.local.*
import com.agenda.domain.DELETE_UNDO_MILLIS

private class DatasetServices(context:Context,manager:WorkspaceManager,val location:WorkspaceLocation) {
    private val gate=LocalDataGate()
    val database=AgendaDatabase.open(context,location.databaseName)
    val repository=RoomAgendaRepository(database,gate)
    val preferences=LocalPreferences(context,gate,location.preferencesFile,location.seed)
    val materials=LocalMaterialStore(context,database,gate,location.materials)
    val features=DailyFeatureRepository(database,gate)
    val visualAssets=VisualAssetStore(context,database,gate,location.assets)
    val deletions=RoomDeletionRepository(database,undoDuration={
        context.getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(DELETE_UNDO_MILLIS.toInt(),
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
    },gate=gate)
    val backups=LocalBackupStore(context,database,preferences,gate,location,manager)
}

class AgendaApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        com.agenda.app.design.InterfaceSettings.initialize(this)
    }
    private val manager by lazy {WorkspaceManager(this).also {it.resolve();it.cleanStartupStaging()}}
    private var active:DatasetServices?=null
    @Synchronized private fun services():DatasetServices {
        val selected=manager.resolve()
        val current=active
        if(current!=null && current.location.id==selected.id) return current
        current?.database?.let(::closeLocalDatabase)
        return DatasetServices(this,manager,selected).also {active=it}
    }
    val workspaceId get()=services().location.id
    val repository get()=services().repository
    val preferences get()=services().preferences
    val materials get()=services().materials
    val deletions get()=services().deletions
    val backups get()=services().backups
    val features get()=services().features
    val visualAssets get()=services().visualAssets
    @Synchronized fun reloadWorkspace() {
        val old=active;active=null;old?.database?.let(::closeLocalDatabase)
        services()
    }
}
