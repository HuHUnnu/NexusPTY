package com.chaoxing.eduapp

import androidx.lifecycle.ViewModel

class AppViewModel : ViewModel() {
    val engine = ShellEngine()

    override fun onCleared() {
        engine.destroy()
        super.onCleared()
    }
}
