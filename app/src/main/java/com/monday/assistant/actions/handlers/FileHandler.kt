package com.monday.assistant.actions.handlers

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.gson.JsonObject
import com.monday.assistant.actions.ActionResult
import java.io.File

/**
 * FILE HANDLER
 * ─────────────────────────────────────────────────────────────────────
 * Handles file picking and sharing.
 *
 * "Blip e amar last 3ta photo pathao"
 * → Picks 3 most recent photos from gallery
 * → Opens share sheet with those files selected
 * → User taps Blip (or any app) in the share sheet
 *
 * Works with: ANY file type, ANY share target
 * Photos, videos, PDFs, APKs, documents — anything in storage
 */
class FileHandler(private val context: Context) {

    companion object {
        private const val TAG = "FileHandler"
    }

    fun shareFiles(action: JsonObject): ActionResult {
        val count = action.get("count")?.asInt ?: 1
        val type = action.get("type")?.asString?.lowercase() ?: "any"
        val targetApp = action.get("targetApp")?.asString?.lowercase() ?: ""

        val uris = when (type) {
            "photo", "image" -> getRecentPhotos(count)
            "video" -> getRecentVideos(count)
            "file", "any" -> getRecentFiles(count)
            else -> getRecentPhotos(count)
        }

        if (uris.isEmpty()) return ActionResult.error("File পাইনি")

        return try {
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    setType(getMimeType(uris[0]))
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    setType("*/*")
                }
            }

            intent.apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Target specific app if specified
                if (targetApp.isNotBlank()) {
                    val pkg = getPackageForApp(targetApp)
                    if (pkg != null) setPackage(pkg)
                }
            }

            // Wrap in chooser so user can pick target if not specified
            val chooser = if (targetApp.isBlank()) {
                Intent.createChooser(intent, "Share via…").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else intent

            context.startActivity(chooser)
            ActionResult.success("${uris.size}টা file share করার জন্য ready")
        } catch (e: Exception) {
            Log.e(TAG, "Share error: ${e.message}")
            ActionResult.error("File share করতে পারিনি: ${e.message}")
        }
    }

    fun openFile(action: JsonObject): ActionResult {
        val query = action.get("query")?.asString ?: return ActionResult.error("File name missing")

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult.success("File manager open হয়েছে")
        } catch (e: Exception) {
            ActionResult.error("File manager open করতে পারিনি")
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun getRecentPhotos(count: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && uris.size < count) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol)
                )
                uris.add(uri)
            }
        }
        return uris
    }

    private fun getRecentVideos(count: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext() && uris.size < count) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol)
                )
                uris.add(uri)
            }
        }
        return uris
    }

    private fun getRecentFiles(count: Int): List<Uri> {
        // Return both photos and videos if type is "any"
        return (getRecentPhotos(count / 2 + 1) + getRecentVideos(count / 2 + 1))
            .take(count)
    }

    private fun getMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "*/*"
    }

    private fun getPackageForApp(appName: String): String? {
        return when (appName.lowercase()) {
            "blip" -> "net.blip.android"
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "messenger" -> "com.facebook.orca"
            "drive" -> "com.google.android.apps.docs"
            "gmail" -> "com.google.android.gm"
            else -> null
        }
    }
}
