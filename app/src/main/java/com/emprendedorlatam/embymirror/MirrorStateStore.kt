package com.emprendedorlatam.embymirror

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorState(
    val running: Boolean = false,
    val message: String = "detenido",
    val m3uUrl: String = ""
)

object MirrorStateStore {
    private val mutable = MutableStateFlow(MirrorState())
    val state = mutable.asStateFlow()

    fun update(newState: MirrorState) {
        mutable.value = newState
    }
}
