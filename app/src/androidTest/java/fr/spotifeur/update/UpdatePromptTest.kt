package fr.spotifeur.update

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.MainActivity
import fr.spotifeur.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A local release fixture exercises the actual Compose prompt without installing anything. */
@RunWith(AndroidJUnit4::class)
class UpdatePromptTest {
    @Test fun availableReleaseShowsNotesAndCanBeDeferred() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spotifeurUpdateUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val updater = AppUpdater.get(context)
        val preferences = context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
        val previousTag = preferences.getString("dismissed_version", null)
        val previousTime = preferences.getLong("dismissed_at", 0)
        try {
            instrumentation.runOnMainSync {
                updater.dismiss()
                val field = AppUpdater::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = field.get(updater) as MutableStateFlow<UpdateState>
                state.value = UpdateState.Available(GitHubRelease("v99.0.0", "## Added\n- Update fixture", GitHubRelease.LATEST_PAGE,
                    ReleaseApk("Spotifeur-v99.0.0.apk", "https://github.com/jean-voila/Spotifeur/releases/download/v99.0.0/Spotifeur-v99.0.0.apk", 1024, null)))
            }
            // Compose exposes virtual nodes; its provider does not implement the platform's
            // findAccessibilityNodeInfosByText query. Walk the actual accessibility tree.
            fun findText(text: String): AccessibilityNodeInfo? {
                fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString()?.contains(text) == true) return node
                    for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                    return null
                }
                return visit(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun hasText(text: String) = findText(text) != null
            assertTrue("Update prompt did not appear", await { hasText(context.getString(R.string.update_available)) })
            assertTrue("Release notes are missing", hasText("Update fixture"))
            assertTrue("Update action is missing", hasText(context.getString(R.string.update_action)))
            val later = context.getString(R.string.update_later)
            assertTrue("Later action is missing", hasText(later))
            val node = checkNotNull(findText(later))
            var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) clicked = node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            assertTrue("Later action could not be clicked", clicked)
            assertTrue("Later did not dismiss the update", await { updater.state.value == UpdateState.Idle })
            // Read persisted data again: a new process must observe the same deferral.
            val saved = context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
            assertEquals("v99.0.0", saved.getString("dismissed_version", null))
            val dismissedAt = saved.getLong("dismissed_at", 0)
            assertTrue(dismissedAt > 0)
            assertFalse(UpdateReminder.isDue("v99.0.0", saved.getString("dismissed_version", null), dismissedAt, dismissedAt + UpdateReminder.DELAY_MS - 1))
            assertTrue(UpdateReminder.isDue("v99.0.0", saved.getString("dismissed_version", null), dismissedAt, dismissedAt + UpdateReminder.DELAY_MS))
        } finally {
            instrumentation.runOnMainSync {
                updater.dismiss()
                preferences.edit().putString("dismissed_version", previousTag).putLong("dismissed_at", previousTime).commit()
                activity.finish()
            }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }
}
