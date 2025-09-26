package com.example.musick.utils

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

class SafeHandler<T>(target: T) where T : Any {
    private val targetRef = WeakReference(target)
    private val handler = Handler(Looper.getMainLooper())

    fun post(delay: Long = 0, action: T.() -> Unit) {
        handler.postDelayed({
            targetRef.get()?.action()
        }, delay)
    }

    fun removeCallbacks() {
        handler.removeCallbacksAndMessages(null)
    }
}
