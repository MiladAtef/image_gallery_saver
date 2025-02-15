package com.example.imagegallerysaver

import androidx.annotation.NonNull
import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import android.text.TextUtils
import android.webkit.MimeTypeMap
import java.io.OutputStream

class ImageGallerySaverPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private var applicationContext: Context? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "image_gallery_saver")
        methodChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "saveImageToGallery" -> {
                val image = call.argument<ByteArray?>("imageBytes")
                val quality = call.argument<Int?>("quality")
                val name = call.argument<String?>("name")

                result.success(
                    saveImageToGallery(
                        BitmapFactory.decodeByteArray(
                            image ?: ByteArray(0),
                            0,
                            image?.size ?: 0
                        ), quality, name
                    )
                )
            }

            "saveFileToGallery" -> {
                val path = call.argument<String?>("file")
                val name = call.argument<String?>("name")
                result.success(saveFileToGallery(path, name))
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        applicationContext = null
    }

    private fun generateUri(extension: String = "", name: String? = null): Uri? {
        val fileName = name ?: System.currentTimeMillis().toString()
        val mimeType = getMIMEType(extension)
        val isVideo = mimeType?.startsWith("video") == true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                mimeType?.let {
                    put(if (isVideo) MediaStore.Video.Media.MIME_TYPE else MediaStore.Images.Media.MIME_TYPE, it)
                }
            }

            applicationContext?.contentResolver?.insert(uri, values)
        } else {
            val storePath = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            ).absolutePath

            val appDir = File(storePath).apply { if (!exists()) mkdir() }
            val file = File(appDir, if (extension.isNotEmpty()) "$fileName.$extension" else fileName)

            Uri.fromFile(file)
        }
    }

    private fun getMIMEType(extension: String): String? {
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        }
    }

    private fun sendBroadcast(context: Context, fileUri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = fileUri
            context.sendBroadcast(mediaScanIntent)
        }
    }

    private fun saveImageToGallery(bmp: Bitmap?, quality: Int?, name: String?): HashMap<String, Any?> {
        if (bmp == null || quality == null) return SaveResultModel(false, null, "parameters error").toHashMap()
        val context = applicationContext ?: return SaveResultModel(false, null, "applicationContext null").toHashMap()

        var fileUri: Uri? = null
        var fos: OutputStream? = null
        var success = false

        try {
            fileUri = generateUri("jpg", name)
            fos = fileUri?.let { context.contentResolver.openOutputStream(it) }
            if (fos != null) {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                fos.flush()
                success = true
            }
        } catch (e: IOException) {
            return SaveResultModel(false, null, e.toString()).toHashMap()
        } finally {
            fos?.close()
            bmp.recycle()
        }

        return if (success) {
            sendBroadcast(context, fileUri)
            SaveResultModel(true, fileUri.toString(), null).toHashMap()
        } else {
            SaveResultModel(false, null, "saveImageToGallery failed").toHashMap()
        }
    }

    private fun saveFileToGallery(filePath: String?, name: String?): HashMap<String, Any?> {
        if (filePath == null) return SaveResultModel(false, null, "parameters error").toHashMap()
        val context = applicationContext ?: return SaveResultModel(false, null, "applicationContext null").toHashMap()

        var fileUri: Uri? = null
        var outputStream: OutputStream? = null
        var fileInputStream: FileInputStream? = null
        var success = false

        try {
            val originalFile = File(filePath)
            if (!originalFile.exists()) return SaveResultModel(false, null, "$filePath does not exist").toHashMap()

            fileUri = generateUri(originalFile.extension, name)
            outputStream = fileUri?.let { context.contentResolver.openOutputStream(it) }
            fileInputStream = FileInputStream(originalFile)

            if (outputStream != null && fileInputStream != null) {
                val buffer = ByteArray(10240)
                var count: Int
                while (fileInputStream.read(buffer).also { count = it } > 0) {
                    outputStream.write(buffer, 0, count)
                }
                outputStream.flush()
                success = true
            }
        } catch (e: IOException) {
            return SaveResultModel(false, null, e.toString()).toHashMap()
        } finally {
            outputStream?.close()
            fileInputStream?.close()
        }

        return if (success) {
            sendBroadcast(context, fileUri)
            SaveResultModel(true, fileUri.toString(), null).toHashMap()
        } else {
            SaveResultModel(false, null, "saveFileToGallery failed").toHashMap()
        }
    }
}

class SaveResultModel(var isSuccess: Boolean, var filePath: String? = null, var errorMessage: String? = null) {
    fun toHashMap(): HashMap<String, Any?> {
        return hashMapOf(
            "isSuccess" to isSuccess,
            "filePath" to filePath,
            "errorMessage" to errorMessage
        )
    }
}
