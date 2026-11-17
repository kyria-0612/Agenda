package com.agenda.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.key
import com.agenda.data.local.*
import com.agenda.app.design.AgendaTheme

class MainActivity : ComponentActivity() {
    private var activeModel:AgendaViewModel?=null
    override fun onResume() {super.onResume();activeModel?.onForeground(true)}
    override fun onPause() {activeModel?.onForeground(false);super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the three-button system bar transparent so the page colour reaches the bottom edge.
        window.isNavigationBarContrastEnforced=false
        val app=application as AgendaApplication
        val model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T:ViewModel> create(modelClass:Class<T>):T {
                return AgendaViewModel(app.repository,app.preferences,app.materials,app.deletions,app.backups,app.features,app.visualAssets) as T
            }
        })[AgendaViewModel::class.java]
        activeModel=model
        setContent { AgendaTheme { key(app.workspaceId) {AgendaApp(model) {
            viewModelStore.clear();app.reloadWorkspace();recreate()
        }} } }
    }
}
