
package com.monde.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val items = mutableListOf<MediaItem>()

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var navigation: LinearLayout

    private val pickFile = 1001

    private var selectedCategory = "Inicio"

    private var xtreamAccount: SecureStore.XtreamAccount? = null

    private val categories = listOf(
        "Inicio",
        "TV en vivo",
        "Películas",
        "Series",
        "Anime"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        list = findViewById(R.id.channelList)

        createNavigation()
        configureButtons()
        restoreSavedData()
    }

    // =====================================================
    // BOTONES
    // =====================================================

    private fun configureButtons() {

        findViewById<Button>(R.id.loadM3u).setOnClickListener {
            val url = findViewById<EditText>(R.id.m3uUrl)
                .text.toString().trim()

            if (url.isBlank()) {
                toast("Introduce el enlace M3U")
            } else {
                loadM3uUrl(url)
            }
        }

        findViewById<Button>(R.id.openFile).setOnClickListener {

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

            startActivityForResult(intent, pickFile)
        }

        findViewById<Button>(R.id.connectXtream).setOnClickListener {
            connectXtream()
        }
    }

    // =====================================================
    // RECUPERAR DATOS GUARDADOS
    // =====================================================

    private fun restoreSavedData() {

        try {
            xtreamAccount = SecureStore.getXtreamAccount(this)

            xtreamAccount?.let { account ->
                findViewById<EditText>(R.id.server).setText(account.server)
                findViewById<EditText>(R.id.username).setText(account.username)
                findViewById<EditText>(R.id.password).setText(account.password)
            }

            val savedSources = SecureStore.getM3uSources(this)

            if (savedSources.isEmpty()) {
                if (xtreamAccount != null) {
                    loadXtreamCatalog(xtreamAccount!!, false)
                } else {
                    renderItems()
                }
                return
            }

            status.text = "Recuperando listas guardadas..."

            lifecycleScope.launch {

                val restored = mutableListOf<MediaItem>()
                var successfulSources = 0

                for (source in savedSources) {
                    try {
                        val content = withContext(Dispatchers.IO) {
                            if (source.isFile) {
                                contentResolver.openInputStream(
                                    Uri.parse(source.uri)
                                )?.bufferedReader()?.use {
                                    it.readText()
                                } ?: ""
                            } else {
                                downloadText(source.url)
                            }
                        }

                        val parsed = parseM3u(content)
                        if (parsed.isNotEmpty()) {
                            restored.addAll(parsed)
                            successfulSources++
                        }

                    } catch (_: Exception) {
                        // Una lista que ya no esté disponible
                        // no impide recuperar las demás.
                    }
                }

                items.clear()
                items.addAll(restored)

                if (restored.isNotEmpty()) {
                    selectedCategory = "Inicio"
                    updateNavigation()
                    renderItems()
                    status.text =
                        "$successfulSources listas recuperadas · ${items.size} elementos"
                } else if (xtreamAccount != null) {
                    loadXtreamCatalog(xtreamAccount!!, false)
                } else {
                    status.text = "No se pudieron recuperar las listas guardadas."
                    renderItems()
                }
            }

        } catch (e: Exception) {
            status.text = "Error al recuperar datos: ${e.message}"
            renderItems()
        }
    }

    // =====================================================
    // NAVEGACIÓN
    // =====================================================

    private fun createNavigation() {

        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }

        val parent = status.parent as? LinearLayout

        if (parent != null) {
            val index = parent.indexOfChild(status)
            parent.addView(navigation, index)
        }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(navigation)
        }

        if (parent != null) {
            parent.removeView(navigation)
            val index = parent.indexOfChild(status)
            parent.addView(scroll, index)
        }

        categories.forEach { category ->

            val button = TextView(this).apply {
                text = category
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = getDrawable(R.drawable.tv_focus)

                setOnClickListener {
                    selectedCategory = category
                    updateNavigation()
                    renderItems()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            )

            params.setMargins(dp(4), dp(8), dp(4), dp(8))
            navigation.addView(button, params)
        }

        updateNavigation()
    }

    private fun updateNavigation() {

        if (!::navigation.isInitialized) return

        for (i in 0 until navigation.childCount) {

            val button = navigation.getChildAt(i) as TextView
            val active = button.text.toString() == selectedCategory

            button.setTextColor(
                if (active) Color.rgb(255, 70, 80)
                else Color.WHITE
            )

            button.setTypeface(
                null,
                if (active) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    // =====================================================
    // ARCHIVO M3U
    // =====================================================

    @Deprecated("Se conserva para compatibilidad con el proyecto actual")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != pickFile || resultCode != Activity.RESULT_OK) {
            return
        }

        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Algunos proveedores de documentos no permiten
            // conservar permisos persistentes.
        }

        lifecycleScope.launch {

            try {
                status.text = "Leyendo archivo M3U..."

                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("No se pudo abrir el archivo")
                }

                val parsed = parseM3u(content)

                if (parsed.isEmpty()) {
                    error("El archivo no contiene elementos M3U válidos")
                }

                val name = getDocumentName(uri)

                SecureStore.addM3uSource(
                    this@MainActivity,
                    SecureStore.M3uSource(
                        id = "file_${uri}",
                        name = name,
                        uri = uri.toString(),
                        isFile = true
                    )
                )

                items.addAll(parsed)
                selectedCategory = "Inicio"
                updateNavigation()
                renderItems()

                status.text = "${parsed.size} elementos importados"
                toast("Lista guardada correctamente")

            } catch (e: Exception) {
                status.text = "Error al leer M3U: ${e.message}"
            }
        }
    }

    private fun getDocumentName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )

                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index) ?: "Lista M3U"
                } else {
                    "Lista M3U"
                }
            } ?: "Lista M3U"
        } catch (_: Exception) {
            "Lista M3U"
        }
    }

    // =====================================================
    // CARGAR M3U POR INTERNET
    // =====================================================

    private fun loadM3uUrl(url: String) {

        status.text = "Descargando lista M3U..."

        lifecycleScope.launch {

            try {
                val content = withContext(Dispatchers.IO) {
                    downloadText(url)
                }

                val parsed = parseM3u(content)

                if (parsed.isEmpty()) {
                    error("No se encontraron canales en la lista")
                }

                SecureStore.addM3uSource(
                    this@MainActivity,
                    SecureStore.M3uSource(
                        id = "url_${url.hashCode()}",
                        name = "Lista M3U ${SecureStore.getM3uSources(this@MainActivity).size + 1}",
                        url = url,
                        isFile = false
                    )
                )

                items.addAll(parsed)

                selectedCategory = "Inicio"
                updateNavigation()
                renderItems()

                status.text = "${parsed.size} elementos importados"
                toast("Lista M3U guardada correctamente")

            } catch (e: Exception) {
                status.text = "Error M3U: ${e.message}"
            }
        }
    }

    private fun downloadText(url: String): String {

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MONDE-TV/1.0")
            .build()

        return client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                error("Error HTTP ${response.code}")
            }

            response.body?.string()
                ?: error("Respuesta vacía")
        }
    }

    // =====================================================
    // PROCESADOR M3U
    // =====================================================

    private fun parseM3u(text: String): List<MediaItem> {

        val imported = mutableListOf<MediaItem>()

        var name = ""
        var group = ""
        var logo = ""

        text.lineSequence().forEach { raw ->

            val line = raw.trim()

            when {

                line.startsWith("#EXTINF", true) -> {

                    val comma = line.lastIndexOf(',')

                    name = if (comma >= 0) {
                        line.substring(comma + 1).trim()
                    } else {
                        "Canal"
                    }

                    if (name.isBlank()) name = "Canal"

                    group = getM3uAttribute(line, "group-title")
                    logo = getM3uAttribute(line, "tvg-logo")
                }

                line.startsWith("#EXTGRP:", true) -> {
                    group = line.substringAfter(":").trim()
                }

                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    name.isNotBlank() -> {

                    imported.add(
                        MediaItem(
                            name = name,
                            url = line,
                            group = group,
                            logo = logo,
                            category = classify(name, group)
                        )
                    )

                    name = ""
                    group = ""
                    logo = ""
                }
            }
        }

        return imported
    }

    private fun getM3uAttribute(
        line: String,
        key: String
    ): String {

        val regex = Regex(
            """(?:^|\s)$key\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(line) ?: return ""

        return match.groupValues.drop(1)
            .firstOrNull { it.isNotEmpty() }
            ?: ""
    }

    private fun classify(
        name: String,
        group: String
    ): String {

        val value = "$name $group".lowercase()

        return when {

            listOf(
                "anime",
                "naruto",
                "one piece",
                "dragon ball"
            ).any { it in value } -> "Anime"

            listOf(
                "movie",
                "pelicula",
                "película",
                "vod",
                "film"
            ).any { it in value } -> "Películas"

            listOf(
                "series",
                "serie",
                "season",
                "temporada"
            ).any { it in value } -> "Series"

            else -> "TV en vivo"
        }
    }

    // =====================================================
    // XTREAM CODES
    // =====================================================

    private fun connectXtream() {

        val server = findViewById<EditText>(R.id.server)
            .text.toString().trim().trimEnd('/')

        val user = findViewById<EditText>(R.id.username)
            .text.toString().trim()

        val pass = findViewById<EditText>(R.id.password)
            .text.toString()

        if (
            server.isBlank() ||
            user.isBlank() ||
            pass.isBlank()
        ) {
            toast("Completa servidor, usuario y contraseña")
            return
        }

        val account = SecureStore.XtreamAccount(
            server = server,
            username = user,
            password = pass
        )

        loadXtreamCatalog(account, true)
    }

    private fun loadXtreamCatalog(
        account: SecureStore.XtreamAccount,
        saveAccount: Boolean
    ) {

        status.text = "Conectando con Xtream..."

        lifecycleScope.launch {

            try {

                val catalog = withContext(Dispatchers.IO) {

                    val auth = requestXtream(
                        account,
                        "player_api.php?username=${enc(account.username)}" +
                            "&password=${enc(account.password)}"
                    )

                    val userInfo = auth.optJSONObject("user_info")
                        ?: error("Respuesta de autenticación no válida")

                    val authValue = userInfo.optInt("auth", 0)
                    val accountStatus = userInfo.optString("status")

                    if (
                        authValue != 1 &&
                        !accountStatus.equals("Active", true)
                    ) {
                        error("Usuario o contraseña no válidos")
                    }

                    val result = mutableListOf<MediaItem>()

                    val liveCategories = getCategoryNames(
                        account,
                        "get_live_categories"
                    )

                    val vodCategories = getCategoryNames(
                        account,
                        "get_vod_categories"
                    )

                    val seriesCategories = getCategoryNames(
                        account,
                        "get_series_categories"
                    )

                    // TV EN VIVO

                    val live = requestXtream(
                        account,
                        playerPath(account, "get_live_streams")
                    )

                    val liveArray = extractArray(
                        live,
                        "data",
                        "streams"
                    )

                    for (i in 0 until liveArray.length()) {

                        val obj = liveArray.optJSONObject(i) ?: continue

                        val id = obj.optString("stream_id")
                        if (id.isBlank()) continue

                        val name = obj.optString("name", "Canal")
                        val logo = obj.optString("stream_icon")
                        val categoryId = obj.optString("category_id")
                        val group = liveCategories[categoryId] ?: ""

                        result.add(
                            MediaItem(
                                name = name,
                                url = "${account.server}/live/" +
                                    "${enc(account.username)}/" +
                                    "${enc(account.password)}/$id.ts",
                                group = group,
                                logo = logo,
                                category = "TV en vivo"
                            )
                        )
                    }

                    // PELÍCULAS

                    val vod = requestXtream(
                        account,
                        playerPath(account, "get_vod_streams")
                    )

                    val vodArray = extractArray(
                        vod,
                        "data",
                        "streams"
                    )

                    for (i in 0 unti
