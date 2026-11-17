package com.agenda.app.design

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App interface preferences stay separate from user-created content and database restores. */
object InterfaceSettings {
    private var store:SharedPreferences?=null
    var blue by mutableStateOf(false)
        private set
    var english by mutableStateOf(false)
        private set
    var bodyTypeface by mutableStateOf("times")
        private set
    fun initialize(context:Context) {
        store=context.getSharedPreferences("interface-settings",Context.MODE_PRIVATE)
        blue=store!!.getBoolean("blue-theme",false)
        english=store!!.getBoolean("english",false)
        bodyTypeface=if(store!!.getString("body-typeface","times")=="lato") "lato" else "times"
    }
    fun selectBlue(value:Boolean) {store?.edit()?.putBoolean("blue-theme",value)?.apply();blue=value}
    fun selectEnglish(value:Boolean) {store?.edit()?.putBoolean("english",value)?.apply();english=value}
    fun selectBodyTypeface(value:String) {
        require(value=="times" || value=="lato")
        store?.edit()?.putString("body-typeface",value)?.apply();bodyTypeface=value
    }
}
