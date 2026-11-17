package com.agenda.app.design

import androidx.compose.animation.core.spring

/** Shared native container motion for Things, Action and hanging dates. */
object AgendaMotion {
    const val Damping = .86f
    const val Stiffness = 360f
    fun <T> container() = spring<T>(dampingRatio=Damping,stiffness=Stiffness)
}
