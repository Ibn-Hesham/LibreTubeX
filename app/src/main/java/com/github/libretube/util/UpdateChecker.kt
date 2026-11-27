package com.github.libretube.util

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.IntentData.appUpdateChangelog
import com.github.libretube.constants.IntentData.appUpdateURL
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.ui.dialogs.UpdateAvailableDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Converts a version string ("v32.1.81" or "32.1.72") into a numerically
 * comparable list of components. Missing components count as zero, so a
 * two-part milestone tag ("v33.0") compares correctly against a per-build
 * tag ("v32.1.81").
 */
internal fun parseVersion(version: String): List<Int> =
    version.trim()
        .removePrefix("v")
        .split(".")
        .map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }

/**
 * Whether [latest] is numerically newer than [current], compared component
 * by component with missing components treated as zero.
 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    val latestParts = parseVersion(latest)
    val currentParts = parseVersion(current)
    for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
        val latestPart = latestParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

class UpdateChecker(private val context: Context) {
    suspend fun checkUpdate(isManualCheck: Boolean = false) {
        try {
            val response = RetrofitInstance.externalApi.getLatestRelease()
            // LibreTubeX tags releases as "v<versionName>.<versionCode>" for
            // per-build releases (e.g. v32.1.81) and "v<versionName>" for
            // milestone tags (e.g. v33.0).
            val currentVersion = "${BuildConfig.VERSION_NAME}.${BuildConfig.VERSION_CODE}"

            // Only prompt when the release is actually newer, so an older
            // release being marked latest doesn't offer a "downgrade".
            if (isNewerVersion(response.tagName, currentVersion)) {
                withContext(Dispatchers.Main) {
                    showUpdateAvailableDialog(response.body, response.htmlUrl)
                }
                Log.i(TAG(), response.toString())
            } else if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.app_uptodate)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // a failed manual check must not look like a successful one
            if (isManualCheck) {
                context.toastFromMainDispatcher(R.string.error)
            }
        }
    }

    private fun showUpdateAvailableDialog(
        changelog: String,
        url: String
    ) {
        val dialog = UpdateAvailableDialog()
        val args =
            Bundle().apply {
                putString(appUpdateChangelog, sanitizeChangelog(changelog))
                putString(appUpdateURL, url)
            }
        dialog.arguments = args
        val fragmentManager = (context as? FragmentActivity)?.supportFragmentManager
        fragmentManager?.let {
            dialog.show(it, UpdateAvailableDialog::class.java.simpleName)
        }
    }

    private fun sanitizeChangelog(changelog: String): String {
        return changelog.substringBeforeLast("**Full Changelog**")
            .replace(Regex("in https://github\\.com/\\S+"), "")
            .lines().joinToString("\n") { line ->
                if (line.startsWith("##")) line.uppercase(Locale.ROOT) + " :" else line
            }
            .replace("## ", "")
            .replace(">", "")
            .replace("*", "•")
            .lines()
            .joinToString("\n") { it.trim() }
    }
}
