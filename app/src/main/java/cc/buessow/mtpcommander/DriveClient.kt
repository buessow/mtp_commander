package cc.buessow.mtpcommander

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal data class DriveFolder(val id: String, val name: String)

internal data class DriveFile(val id: String, val name: String, val folder: Boolean, val size: Long,
    val modified: Long, val mimeType: String) {
    /** Google Docs, Sheets, shortcuts etc. have no file content that could be copied. */
    val copyable get() = folder || !mimeType.startsWith("application/vnd.google-apps.")
}

/** Drive access has to be granted, or granted again, in a Google dialog launched from the activity. */
internal class DriveAuthorizationRequired(val intent: PendingIntent) :
    Exception("Google Drive access has to be granted")

/**
 * Minimal Drive v3 REST client; every call blocks and must run off the main thread. Lists, searches and
 * downloads only see files and folders this app created.
 */
internal class DriveClient(private val context: Context) {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    // Play services caches the token and refreshes it before it expires.
    private fun token(retry: Boolean = true): String {
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
        if (result.hasResolution()) throw DriveAuthorizationRequired(result.pendingIntent!!)
        val token = result.accessToken ?: error("Google did not return a Drive access token")
        // Grants add up, so a token from an earlier full Drive grant would still reach every file. Revoking
        // drops the whole grant; the next call then asks for drive.file alone.
        if (retry && FULL_SCOPE in result.grantedScopes) {
            revoke(token)
            GoogleAuthUtil.clearToken(context, token)
            return token(retry = false)
        }
        return token
    }

    private fun revoke(token: String) {
        val connection = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.outputStream.use { it.write("token=${encode(token)}".toByteArray()) }
            checkStatus(connection)
        } finally { connection.disconnect() }
    }

    fun list(folderId: String): List<DriveFile> = query("'${escape(folderId)}' in parents and trashed = false")

    fun find(folderId: String, name: String): List<DriveFile> =
        query("'${escape(folderId)}' in parents and name = '${escape(name)}' and trashed = false")

    fun exists(folderId: String, name: String) = find(folderId, name).isNotEmpty()

    fun download(id: String, to: File, onProgress: (Long, Long) -> Unit) {
        val connection = open("$API/files/${encode(id)}?alt=media&supportsAllDrives=true", "GET")
        try {
            checkStatus(connection)
            val total = connection.contentLengthLong
            connection.inputStream.use { input -> FileOutputStream(to).use { output ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, total)
                }
            } }
        } finally { connection.disconnect() }
    }

    /** Uploads [file] as [name] into [folderId] with a resumable upload session; returns the new file's id. */
    fun upload(folderId: String, file: File, name: String, onProgress: (Long, Long) -> Unit): String {
        val total = file.length()
        val start = open("$UPLOAD/files?uploadType=resumable&supportsAllDrives=true", "POST")
        val location = try {
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            start.setRequestProperty("X-Upload-Content-Length", total.toString())
            start.doOutput = true
            start.outputStream.use {
                it.write(JSONObject().put("name", name).put("parents", JSONArray().put(folderId))
                    .toString().toByteArray())
            }
            checkStatus(start)
            start.getHeaderField("Location") ?: error("Drive did not start the upload")
        } finally { start.disconnect() }
        val connection = URL(location).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(total)
            connection.doOutput = true
            connection.outputStream.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var sent = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    onProgress(sent, total)
                }
            } }
            checkStatus(connection)
            return JSONObject(connection.inputStream.bufferedReader().readText()).getString("id")
        } finally { connection.disconnect() }
    }

    fun createFolder(parentId: String, name: String): String =
        postJson("$API/files?supportsAllDrives=true&fields=id", JSONObject().put("name", name)
            .put("mimeType", FOLDER).put("parents", JSONArray().put(parentId))).getString("id")

    /** Copies a file on the server side; Drive cannot copy folders. */
    fun copy(id: String, parentId: String, name: String): String =
        postJson("$API/files/${encode(id)}/copy?supportsAllDrives=true&fields=id",
            JSONObject().put("name", name).put("parents", JSONArray().put(parentId))).getString("id")

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = open(url, "POST")
        try {
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            checkStatus(connection)
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally { connection.disconnect() }
    }

    /** Moves a file or folder to Drive's trash, where it can still be restored. */
    fun trash(id: String) {
        val connection = open("$API/files/${encode(id)}?supportsAllDrives=true", "POST")
        try {
            // HttpURLConnection has no PATCH; Google APIs accept the override header instead.
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.doOutput = true
            connection.outputStream.use { it.write("""{"trashed":true}""".toByteArray()) }
            checkStatus(connection)
        } finally { connection.disconnect() }
    }

    private fun query(q: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val connection = open("$API/files?q=${encode(q)}&pageSize=1000" +
                "&fields=${encode("nextPageToken,files(id,name,mimeType,size,modifiedTime)")}" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                (pageToken?.let { "&pageToken=${encode(it)}" } ?: ""), "GET")
            val page = try {
                checkStatus(connection)
                JSONObject(connection.inputStream.bufferedReader().readText())
            } finally { connection.disconnect() }
            val items = page.optJSONArray("files") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val mimeType = item.getString("mimeType")
                files += DriveFile(item.getString("id"), item.getString("name"), mimeType == FOLDER,
                    item.optString("size").toLongOrNull() ?: -1,
                    item.optString("modifiedTime").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0, mimeType)
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return files
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${token()}")
        }

    private fun checkStatus(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val message = runCatching {
            JSONObject(connection.errorStream.bufferedReader().readText())
                .getJSONObject("error").getString("message")
        }.getOrNull()
        error("Google Drive error $code${message?.let { ": $it" } ?: ""}")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val ROOT = "root"
        // drive.file only reaches files this app created, but unlike full Drive access it is not a restricted
        // scope, so the app needs no Google security assessment.
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val FULL_SCOPE = "https://www.googleapis.com/auth/drive"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    }
}
