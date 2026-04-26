package com.calmpad.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

object ImageStorage {
    private const val IMAGES_DIR = "note_images"

    private fun imagesDir(context: Context): File {
        val dir = File(context.filesDir, IMAGES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveImage(context: Context, sourceUri: Uri): String? {
        return try {
            val extension = context.contentResolver.getType(sourceUri)
                ?.substringAfter("/")
                ?.take(4)
                ?: "jpg"
            val filename = "${UUID.randomUUID()}.$extension"
            val destFile = File(imagesDir(context), filename)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            filename
        } catch (e: Exception) {
            null
        }
    }

    fun getImageFile(context: Context, filename: String): File {
        return File(imagesDir(context), filename)
    }

    fun deleteImage(context: Context, filename: String) {
        val file = File(imagesDir(context), filename)
        if (file.exists()) file.delete()
    }
}
