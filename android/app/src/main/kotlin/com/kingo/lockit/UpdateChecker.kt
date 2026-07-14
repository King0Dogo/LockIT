package com.kingo.lockit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val CURRENT_VERSION = "v1.0.0"
        private const val REPO_PATH = "King0Dogo/LockIT" // Configurable
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface UpdateCallback {
        fun onChecking()
        fun onNoUpdate()
        fun onUpdateAvailable(newVersion: String, downloadUrl: String)
        fun onDownloadProgress(progress: Int)
        fun onError(errorMsg: String)
    }

    fun checkForUpdates(callback: UpdateCallback) {
        callback.onChecking()
        val url = "https://api.github.com/repos/$REPO_PATH/releases/latest"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Remote-Companion-App")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "GitHub release fetch failed", e)
                postToMain { callback.onError("Network error fetching update data.") }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    postToMain { callback.onError("Server returned error code: ${response.code}") }
                    return
                }

                try {
                    val bodyString = response.body?.string() ?: ""
                    val releaseObj = gson.fromJson(bodyString, JsonObject::class.java)
                    
                    val tagName = releaseObj.get("tag_name")?.asString ?: ""
                    
                    if (isNewerVersion(tagName, CURRENT_VERSION)) {
                        val assets = releaseObj.getAsJsonArray("assets")
                        var apkUrl: String? = null
                        
                        for (i in 0 until assets.size()) {
                            val asset = assets.get(i).asJsonObject
                            val name = asset.get("name")?.asString ?: ""
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.get("browser_download_url")?.asString
                                break
                            }
                        }

                        if (apkUrl != null) {
                            val finalApkUrl = apkUrl
                            postToMain { callback.onUpdateAvailable(tagName, finalApkUrl) }
                        } else {
                            postToMain { callback.onError("Release found, but no APK assets available.") }
                        }
                    } else {
                        postToMain { callback.onNoUpdate() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parsing release failed", e)
                    postToMain { callback.onError("Error parsing updates data.") }
                }
            }
        })
    }

    fun downloadAndInstallApk(downloadUrl: String, callback: UpdateCallback) {
        val request = Request.Builder().url(downloadUrl).build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                postToMain { callback.onError("Failed to download update APK.") }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    postToMain { callback.onError("Failed downloading APK file.") }
                    return
                }

                try {
                    val apkFile = File(context.cacheDir, "lockit_update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    val inputStream = response.body?.byteStream()
                    val outputStream = FileOutputStream(apkFile)
                    
                    if (inputStream == null) {
                        postToMain { callback.onError("Response body stream is empty.") }
                        return
                    }

                    val totalBytes = response.body?.contentLength() ?: -1L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = (totalRead * 100 / totalBytes).toInt()
                            postToMain { callback.onDownloadProgress(progress) }
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    postToMain {
                        triggerApkInstall(apkFile)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error writing APK file", e)
                    postToMain { callback.onError("Failed saving update file locally.") }
                }
            }
        })
    }

    private fun triggerApkInstall(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting installation", e)
            Toast.makeText(context, "Could not launch package installer.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isNewerVersion(newTag: String, currentTag: String): Boolean {
        // Basic clean tag comparison: e.g. "v1.1.0" -> "1.1.0"
        val cleanNew = newTag.replace("v", "").split(".")
        val cleanCurrent = currentTag.replace("v", "").split(".")
        
        for (i in 0 until minOf(cleanNew.size, cleanCurrent.size)) {
            val newVal = cleanNew[i].toIntOrNull() ?: 0
            val curVal = cleanCurrent[i].toIntOrNull() ?: 0
            if (newVal > curVal) return true
            if (newVal < curVal) return false
        }
        return cleanNew.size > cleanCurrent.size
    }

    private fun postToMain(runnable: () -> Unit) {
        mainHandler.post(runnable)
    }
}
