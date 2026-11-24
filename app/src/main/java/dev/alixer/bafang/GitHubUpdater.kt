package dev.alixer.bafang

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * First-party signed GitHub Release update channel (public anonymously, private with PAT).
 * - No privileged/silent installation: Android presents its normal package installer.
 * - Only a private repository needs a read-only fine-grained GitHub PAT, encrypted by Android Keystore.
 * - VersionCode, package name, SHA-256 and signing certificate are checked before installing.
 * - The PAT is sent ONLY to api.github.com, never through any download redirect.
 */
class GitHubUpdater(private val activity: Activity, private val notify: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bafang-github-updates").apply { isDaemon = true }
    }
    private val prefs = activity.getSharedPreferences("bafang_update", Context.MODE_PRIVATE)
    private val api = "https://api.github.com/repos/AlixerVolkov/Bafang-Companion-Public"
    private val releasePrefix = "bafang-companion-v"
    private val keyAlias = "bafang-companion-github-token-v1"
    private var pendingApk: File? = null
    private var busy = false
    // Empty for public Releases; used only as fallback for private repository access.
    private var releaseAuthToken: String = ""

    data class Release(val code: Long, val name: String, val assetId: Long,
                       val size: Long, val sha256: String, val notes: String)

    private fun onUi(action: () -> Unit) = main.post(action)
    private fun report(message: String) = onUi { notify(message) }

    fun hasToken(): Boolean = prefs.contains("pat_v1")

    fun configure() {
        val input = EditText(activity).apply {
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(40, 28, 40, 28)
        }
        AlertDialog.Builder(activity)
            .setTitle("GitHub · token opcional")
            .setMessage("Para un repositorio público no se necesita clave. Si el repositorio es privado, usa un token de solo lectura (Contents: Read), cifrado en este teléfono.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val candidate = input.text.toString().trim()
                if (candidate.isEmpty() || candidate.length < 16 || candidate.any { it.isWhitespace() }) {
                    report("Token vacío o inválido; no se guardó")
                } else background.execute {
                    try {
                        // Verify the credential BEFORE storing it: 404 means no repo access.
                        val response = getJson("$api", candidate)
                        if (response.optString("full_name") != "AlixerVolkov/Bafang-Companion")
                            throw IOException("El token no tiene acceso al repositorio esperado")
                        saveToken(candidate)
                        report("GitHub configurado: acceso privado de solo lectura correcto")
                        check(manual = true)
                    } catch (e: Exception) { report("No se guardó el token: ${explain(e)}") }
                }
            }
            .setNeutralButton("Borrar clave") { _, _ ->
                prefs.edit().remove("pat_v1").apply()
                report("Credencial de GitHub borrada de la app")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    fun check(manual: Boolean = true) {
        synchronized(this) { if (busy) return; busy = true }
        background.execute {
            try {
                // Always use the public GitHub Releases API anonymously.
                val releases = getArray("$api/releases?per_page=30", "")
                releaseAuthToken = ""
                val latest = findLatest(releases)
                val current = installedVersion()
                if (latest == null) {
                    report("GitHub accesible, pero no se encontró un APK publicado con versión y SHA-256 verificables")
                } else if (latest.code <= current) {
                    report("Bafang Companion actualizado (v${appVersion()}, build $current)")
                } else {
                    report("Nueva versión ${latest.name} disponible (build ${latest.code})")
                    onUi { confirmDownload(latest) }
                }
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                report("Actualizaciones: ${explain(e)}")
            } finally { synchronized(this) { busy = false } }
        }
    }

    fun checkOnOpen() {
        val elapsed = System.currentTimeMillis() - prefs.getLong("last_check", 0)
        if (elapsed > 12 * 60 * 60 * 1000L) check(manual = false)
    }

    private fun findLatest(items: JSONArray): Release? {
        var newest: Release? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optBoolean("draft") || item.optBoolean("prerelease")) continue
            val tag = item.optString("tag_name")
            if (!tag.startsWith(releasePrefix)) continue
            // The release body carries a versionCode generated from the Android build file.
            val code = Regex("(?m)^VersionCode:\\s*(\\d+)\\s*$")
                .find(item.optString("body"))?.groupValues?.getOrNull(1)?.toLongOrNull() ?: continue
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val versionName = tag.removePrefix(releasePrefix)
                if (!versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) continue
                if (asset.optString("name") != "Bafang-Companion-$versionName.apk") continue
                val githubDigest = asset.optString("digest").removePrefix("sha256:")
                // Prefer GitHub's asset digest; older release responses may omit
                // this field. Our own release pipeline also publishes a SHA256 in
                // the release notes, bound to the exact APK filename.
                val noteDigest = Regex("(?m)^APK-SHA256:\\s*([0-9a-fA-F]{64})\\s*$")
                    .find(item.optString("body"))?.groupValues?.getOrNull(1).orEmpty()
                val digest = if (githubDigest.matches(Regex("[0-9a-fA-F]{64}"))) githubDigest else noteDigest
                if (!digest.matches(Regex("[0-9a-fA-F]{64}"))) continue
                val candidate = Release(code, tag.removePrefix(releasePrefix), asset.optLong("id"),
                    asset.optLong("size"), digest.lowercase(), item.optString("body").take(2000))
                if (candidate.assetId > 0 && candidate.size in 100_000..80_000_000 &&
                    (newest == null || candidate.code > newest.code)) newest = candidate
            }
        }
        return newest
    }

    private fun confirmDownload(release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Actualizar a ${release.name}")
            .setMessage("Versión ${release.name} (build ${release.code}) desde GitHub Releases. Se comprueban SHA-256 y firma Android. El instalador del sistema solicitará tu autorización.\n\n${release.notes.take(500)}")
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Descargar e instalar") { _, _ -> download(release) }.show()
    }

    private fun download(release: Release) {
        synchronized(this) { if (busy) { report("Actualizador ocupado"); return }; busy = true }
        background.execute {
            var temp: File? = null
            try {
                val token = releaseAuthToken
                val dir = File(activity.cacheDir, "updates")
                if (!dir.exists() && !dir.mkdirs()) throw IOException("No se pudo crear carpeta temporal")
                temp = File(dir, "bafang-${release.code}.apk")
                report("Descargando versión ${release.name}…")
                val sha = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val url = if (token.isNotBlank()) "$api/releases/assets/${release.assetId}"
                    else "https://github.com/AlixerVolkov/Bafang-Companion-Public/releases/download/bafang-companion-v${release.name}/Bafang-Companion-${release.name}.apk"
                val conn = connect(url, token, "application/octet-stream")
                try {
                    if (conn.responseCode != 200) throw IOException("Descarga HTTP ${conn.responseCode}")
                    val mime = conn.contentType.orEmpty().lowercase()
                    if (mime.contains("json") || mime.contains("html"))
                        throw IOException("GitHub devolvió metadatos, no el APK")
                    conn.inputStream.use { source -> FileOutputStream(temp).use { out ->
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            val n = source.read(chunk)
                            if (n < 0) break
                            bytes += n
                            if (bytes > 80_000_000) throw IOException("Archivo excesivamente grande")
                            sha.update(chunk, 0, n)
                            out.write(chunk, 0, n)
                        }
                        out.fd.sync()
                    } }
                } finally { conn.disconnect() }
                if (bytes != release.size) throw IOException("Tamaño APK inesperado: $bytes bytes")
                val actualHash = sha.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(release.sha256, ignoreCase = true))
                    throw IOException("SHA-256 distinto al publicado en GitHub")
                verifyPackage(temp, release.code)
                pendingApk = temp
                report("APK verificado: ${release.name}. Preparando instalador…")
                onUi { beginInstall() }
            } catch (e: Exception) {
                temp?.delete()
                report("Descarga cancelada: ${explain(e)}")
            } finally { synchronized(this) { busy = false } }
        }
    }

    /** Only OS package installer can install an APK; no unattended installs. */
    fun beginInstall() {
        val apk = pendingApk ?: return
        if (!apk.isFile) { report("APK temporal no encontrado"); return }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("Permitir instalación de actualizaciones")
                .setMessage("Android debe autorizar a Bafang Companion a instalar la versión descargada. Habilítalo únicamente para esta aplicación.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    val uri = Uri.parse("package:${activity.packageName}")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
                }.setNegativeButton("Cancelar", null).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            pendingApk = null // Do not relaunch after installer is dismissed.
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) { report("Android no dispone de instalador: ${explain(e)}") }
          catch (e: Exception) { report("No se pudo abrir el instalador: ${explain(e)}") }
    }

    fun onResume() {
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())) {
            // User can always tap Install again after returning from settings.
            beginInstall()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, version: Long) {
        val pm = activity.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flag)
            ?: throw IOException("El archivo no es un APK Android válido")
        val installed = pm.getPackageInfo(activity.packageName, flag)
        if (candidate.packageName != activity.packageName) throw IOException("El identificador de paquete no coincide")
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (code != version || code <= installedVersion()) throw IOException("Versión APK incorrecta o no superior")
        val expected = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val actual = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
        if (expected.isNullOrEmpty() || actual.isNullOrEmpty()) throw IOException("APK sin certificados comprobables")
        if (expected.size != 1 || actual.size != 1 || !MessageDigest.isEqual(expected[0].toByteArray(), actual[0].toByteArray()))
            throw IOException("Firma distinta a la app instalada. Desinstala una vez la versión antigua si cambió la firma y vuelve a instalar la versión actual desde GitHub")
    }

    private fun connect(url: String, token: String, accept: String): HttpURLConnection {
        var location = url
        repeat(6) { redirects ->
            val link = URL(location)
            if (link.protocol != "https") throw IOException("Redirección no HTTPS")
            val hostname = link.host.lowercase()
            val apiHost = hostname == "api.github.com"
            if (!apiHost && !hostname.endsWith(".githubusercontent.com") && hostname != "github.com")
                throw IOException("Descarga redirigida fuera de GitHub")
            // Public releases have stable github.com download URLs; private assets use api.github.com.
            if (!apiHost && redirects == 0 && !location.startsWith(
                    "https://github.com/AlixerVolkov/Bafang-Companion-Public/releases/download/"))
                throw IOException("Origen de descarga inesperado")
            val conn = (link.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 35_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "BafangCompanion/0.19.0")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (apiHost && token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            when (val status = conn.responseCode) {
                301,302,303,307,308 -> {
                    val next = conn.getHeaderField("Location") ?: throw IOException("Redirección sin destino")
                    location = URL(link, next).toExternalForm()
                    conn.disconnect()
                }
                401,403 -> { conn.disconnect(); throw IOException("GitHub ha rechazado el acceso (HTTP $status)") }
                404 -> { conn.disconnect(); throw IOException("Repositorio o versión no accesible (404)") }
                else -> return conn
            }
        }
        throw IOException("Demasiadas redirecciones de GitHub")
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = connect(url, token, "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IOException("Respuesta GitHub HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(2_000_000) })
        } finally { conn.disconnect() }
    }

    private fun getArray(url: String, token: String): JSONArray {
        val conn = connect(url, token, "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IOException("Respuesta GitHub HTTP ${conn.responseCode}")
            return JSONArray(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(3_000_000) })
        } finally { conn.disconnect() }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + bytes
        prefs.edit().putString("pat_v1", Base64.getEncoder().encodeToString(packed)).apply()
    }

    private fun loadToken(): String {
        val packed = prefs.getString("pat_v1", null) ?: throw IOException("Configura tu token en Actualizaciones")
        val raw = Base64.getDecoder().decode(packed)
        if (raw.size < 28) throw IOException("Token cifrado incompleto")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }

    private fun explain(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> "Tiempo de espera agotado"
        else -> e.message?.take(240) ?: e.javaClass.simpleName
    }
}