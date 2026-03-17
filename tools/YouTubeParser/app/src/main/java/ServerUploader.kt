package com.example.youtubeparser

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ServerUploader {

    private const val TAG = "ServerUploader"

    fun uploadJsonFile(context: Context, file: File): Boolean {
        return try {
            val boundary = "Boundary-${UUID.randomUUID()}"
            val lineEnd = "\r\n"
            val uploadUrl = UploadEndpointStore.resolveUploadUrl(context)

            val connection = URL(uploadUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            DataOutputStream(connection.outputStream).use { output ->
                output.writeBytes("--$boundary$lineEnd")
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$lineEnd"
                )
                output.writeBytes("Content-Type: application/json$lineEnd")
                output.writeBytes(lineEnd)

                file.inputStream().use { input ->
                    input.copyTo(output)
                }

                output.writeBytes(lineEnd)
                output.writeBytes("--$boundary--$lineEnd")
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseText = try {
                connection.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                connection.errorStream?.bufferedReader()?.readText().orEmpty()
            }

            Log.d(TAG, "uploadUrl=$uploadUrl responseCode=$responseCode response=$responseText")
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            false
        }
    }
}
