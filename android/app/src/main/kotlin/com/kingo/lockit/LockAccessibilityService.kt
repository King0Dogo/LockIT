package com.kingo.lockit

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class LockAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: LockAccessibilityService? = null

        /**
         * Checks if the Accessibility Service is active.
         */
        fun isServiceRunning(): Boolean {
            return instance != null
        }

        /**
         * Performs a screen lock action using Accessibility API global lock action.
         * Returns true if successful, false if service is offline.
         */
        fun lockScreen(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not inspect events, keeping it fully private and lightweight
    }

    override fun onInterrupt() {
        // No action required
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
