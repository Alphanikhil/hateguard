package com.example.nikkuisgoodboy.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import com.example.nikkuisgoodboy.utils.PreferenceManager

class ContentAccessibilityService : AccessibilityService() {

    private lateinit var preferenceManager: PreferenceManager
    private val potentialNsfwImagesRects = mutableListOf<Rect>()

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        Log.d(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            if (!preferenceManager.isProtectionEnabled()) {
                return
            }

            // Ignore interactions with the app itself
            val packageName = event.packageName?.toString() ?: return
            if (packageName == "com.example.nikkuisgoodboy") {
                Log.d(TAG, "Ignoring events from our own app")
                return
            }

            // Check if the app is whitelisted
            if (preferenceManager.isAppWhitelisted(packageName)) {
                Log.d(TAG, "App is whitelisted, skipping content filtering")
                return
            }

            // Reset list of potentially NSFW images
            potentialNsfwImagesRects.clear()

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val rootNode = rootInActiveWindow ?: return
                    try {
                        // Find all image views in the UI hierarchy
                        findImageViewsRecursively(rootNode)

                        // Log the number of potential NSFW images found
                        Log.d(TAG, "Found ${potentialNsfwImagesRects.size} potential image views")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing accessibility event", e)
                    } finally {
                        rootNode.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onAccessibilityEvent", e)
        }
    }

    private fun findImageViewsRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return

        try {
            // Check if this node is potentially an image view
            if (isLikelyImageView(node)) {
                // Get the node's on-screen bounds
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // Check if it's a reasonably sized image (to ignore small icons)
                if (rect.width() > 100 && rect.height() > 100) {
                    potentialNsfwImagesRects.add(rect)
                }
            }

            // Recursively check all child nodes
            for (i in 0 until node.childCount) {
                findImageViewsRecursively(node.getChild(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while traversing node hierarchy", e)
        }
    }

    private fun isLikelyImageView(node: AccessibilityNodeInfo): Boolean {
        // Check class name for known image view classes
        val className = node.className?.toString() ?: ""
        return className.contains("ImageView") ||
                className.contains("Image") ||
                className.contains("PhotoView") ||
                className.contains("PostImage") ||
                className.contains("Avatar") ||
                className.contains("Thumbnail")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "ContentAccessibilityService"
    }
}
