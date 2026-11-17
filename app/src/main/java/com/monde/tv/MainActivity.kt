
package com.monde.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
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

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val items = mutableListOf<MediaItem>()

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var navigation: LinearLayout

    private val pickFile = 1001

    private var selectedCategory = "Inicio"

    private var xtreamServer = ""
    private var xtreamUser = ""
    private var xtreamPassword = ""

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
            }

            startActivityForResult(intent, pickFile)
        }

        findViewById<Button>(R.id.connectXtream).setOnClickListener {
            connectXtream()
        }

        renderItems()
    }

    // --------------------------------------------------
    // NAVEGACIÓN PRINCIPAL
    // --------------------------------------------------

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

    // --------------------------------------------------
    // IMPORTAR ARCHIVO M3U
    // --------------------------------------------------

    @Deprecated("Use Activity Result API in production")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {

            data?.data?.let { uri ->

                lifecycleScope.launch {

                    try {

                        status.text = "Leyendo archivo M3U..."

                        val text = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: ""
                        }

                        importM3u(text)

                    } catch (e: Exception) {

                        status.text =
                            "Error al leer archivo: ${e.message}"

                    }
                }
            }
        }
    }

    // --------------------------------------------------
    // CARGAR M3U DESDE INTERNET
    // --------------------------------------------------

    private fun loadM3uUrl(url: String) {

        status.text = "Descargando lista M3U..."

        lifecycleScope.launch {

            try {

                val text = withContext(Dispatchers.IO) {

                    val request = Request.Builder()
                        .url(url)
                        .build()

                    client.newCall(request).execute().use {

                        if (!it.isSuccessful) {
                            error("HTTP ${it.code}")
                        }

                        it.body?.string() ?: ""
                    }
                }

                importM3u(text)

            } catch (e: Exception) {

                status.text =
                    "Error M3U: ${e.message}"

            }
        }
    }

    // --------------------------------------------------
    // PROCESAR LISTA M3U
    // --------------------------------------------------

    private fun importM3u(text: String) {

        val imported = mutableListOf<MediaItem>()

        var name = ""
        var group = ""
        var logo = ""

        text.lineSequence().forEach { raw ->

            val line = raw.trim()

            when {

                line.startsWith("#EXTINF", true) -> {

                    name = line.substringAfterLast(",")
                        .trim()
                        .ifBlank { "Canal" }

                    group = getM3uAttribute(line, "group-title")
                    logo = getM3uAttribute(line, "tvg-logo")
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

        if (imported.isEmpty()) {
            status.text = "No se encontraron canales en la lista."
            toast("No se encontraron elementos M3U")
            return
        }

        items.clear()
        items.addAll(imported)

        selectedCategory = "Inicio"
        updateNavigation()
        renderItems()

        toast("${items.size} elementos importados")
    }

    private fun getM3uAttribute(
        line: String,
        key: String
    ): String {

        return Regex(
            "$key=\"([^\"]*)\"",
            RegexOption.IGNORE_CASE
        ).find(line)?.groupValues?.get(1) ?: ""
    }

    // --------------------------------------------------
    // CLASIFICACIÓN DE CONTENIDO
    // --------------------------------------------------

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

    // --------------------------------------------------
    // CONEXIÓN XTREAM CODES
    // --------------------------------------------------

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

        xtreamServer = server
        xtreamUser = user
        xtreamPassword = pass

        status.text = "Conectando con Xtream..."

        lifecycleScope.launch {

            try {

                val catalog = withContext(Dispatchers.IO) {

                    val auth = requestXtream(
                        "player_api.php?username=${enc(user)}&password=${enc(pass)}"
                    )

                    val userInfo = auth.optJSONObject("user_info")

                    if (userInfo?.optInt("auth", 0) != 1) {
                        error("Usuario o contraseña no válidos")
                    }

                    val result = mutableListOf<MediaItem>()

                    // CANALES EN VIVO

                    val live = requestXtream(
                        "player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_live_streams"
                    )

                    val liveArray = live.optJSONArray("data")
                        ?: live.optJSONArray("streams")
                        ?: JSONArray()

                    for (i in 0 until liveArray.length()) {

                        val obj = liveArray.optJSONObject(i) ?: continue

                        val id = obj.optString("stream_id")
                        val name = obj.optString("name", "Canal")
                        val logo = obj.optString("stream_icon")
                        val group = obj.optString("category_name")

                        if (id.isBlank()) continue

                        result.add(
                            MediaItem(
                                name = name,
                                url = "$server/live/$user/$pass/$id.ts",
                                group = group,
                                logo = logo,
                                category = "TV en vivo"
                            )
                        )
                    }

                    // PELÍCULAS

                    val vod = requestXtream(
                        "player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_vod_streams"
                    )

                    val vodArray = vod.optJSONArray("data")
                        ?: vod.optJSONArray("streams")
                        ?: JSONArray()

                    for (i in 0 until vodArray.length()) {

                        val obj = vodArray.optJSONObject(i) ?: continue

                        val id = obj.optString("stream_id")
                        val name = obj.optString("name", "Película")
                        val logo = obj.optString("stream_icon")
                        val extension = obj.optString(
                            "container_extension",
                            "mp4"
                        )

                        if (id.isBlank()) continue

                        result.add(
                            MediaItem(
                                name = name,
                                url = "$server/movie/$user/$pass/$id.$extension",
                                group = obj.optString("category_name"),
                                logo = logo,
                                category = "Películas"
                            )
                        )
                    }

                    // SERIES

                    val series = requestXtream(
                        "player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_series"
                    )

                    val seriesArray = series.optJSONArray("data")
                        ?: series.optJSONArray("series")
                        ?: JSONArray()

                    for (i in 0 until seriesArray.length()) {

                        val obj = seriesArray.optJSONObject(i) ?: continue

                        val id = obj.optString("series_id")
                        val name = obj.optString("name", "Serie")
                        val logo = obj.optString("cover")

                        if (id.isBlank()) continue

                        result.add(
                            MediaItem(
                                name = name,
                                url = "xtream-series:$id",
                                group = obj.optString("category_name"),
                                logo = logo,
                                category = "Series"
                            )
                        )
                    }

                    result
                }

                if (catalog.isEmpty()) {
                    status.text =
                        "Conexión correcta, pero no se encontraron contenidos."
                    return@launch
                }

                items.clear()
                items.addAll(catalog)

                selectedCategory = "Inicio"
                updateNavigation()
                renderItems()

                toast("Catálogo Xtream cargado")

            } catch (e: Exception) {

                status.text =
                    "Error Xtream: ${e.message}"

            }
        }
    }

    private fun requestXtream(path: String): JSONObject {

        val url = "$xtreamServer/$path"

        val request = Request.Builder()
            .url(url)
            .build()

        return client.newCall(request).execute().use {

            if (!it.isSuccessful) {
                error("HTTP ${it.code}")
            }

            JSONObject(it.body?.string() ?: "{}")
        }
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    // --------------------------------------------------
    // ABRIR SERIES Y EPISODIOS
    // --------------------------------------------------

    private fun openSeries(item: MediaItem) {

        val seriesId = item.url.substringAfter("xtream-series:")

        status.text = "Buscando episodios de ${item.name}..."

        lifecycleScope.launch {

            try {

                val episodeUrl = withContext(Dispatchers.IO) {

                    val info = requestXtream(
                        "player_api.php?username=${enc(xtreamUser)}" +
                                "&password=${enc(xtreamPassword)}" +
                                "&action=get_series_info" +
                                "&series_id=${enc(seriesId)}"
                    )

                    val episodes = info.optJSONObject("episodes")
                        ?: error("No se encontraron episodios")

                    var selectedEpisode: JSONObject? = null

                    val keys = episodes.keys()

                    while (keys.hasNext() && selectedEpisode == null) {

                        val key = keys.next()
                        val season = episodes.optJSONArray(key) ?: continue

                        for (i in 0 until season.length()) {

                            val episode = season.optJSONObject(i)
                                ?: continue

                            if (episode.optString("id").isNotBlank()) {
                                selectedEpisode = episode
                                break
                            }
                        }
                    }

                    val episode = selectedEpisode
                        ?: error("La serie no tiene episodios disponibles")

                    val id = episode.optString("id")
                    val extension = episode.optString(
                        "container_extension",
                        "mp4"
                    )

                    "$xtreamServer/series/$xtreamUser/$xtreamPassword/$id.$extension"
                }

                openPlayer(
                    item.name,
                    episodeUrl
                )

            } catch (e: Exception) {

                status.text =
                    "No se pudo abrir la serie: ${e.message}"

            }
        }
    }

    // --------------------------------------------------
    // MOSTRAR CATÁLOGOS Y TARJETAS
    // --------------------------------------------------

    private fun renderItems() {

        list.removeAllViews()

        val visibleItems = if (selectedCategory == "Inicio") {
            items
        } else {
            items.filter {
                it.category.equals(
                    selectedCategory,
                    ignoreCase = true
                )
            }
        }

        status.text =
            "${visibleItems.size} elementos · ${selectedCategory}"

        if (visibleItems.isEmpty()) {

            val empty = TextView(this).apply {
                text = if (items.isEmpty()) {
                    "Importa una lista M3U o conecta tu cuenta Xtream para comenzar."
                } else {
                    "No hay contenido en esta categoría."
                }

                textSize = 16f
                setTextColor(Color.LTGRAY)
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }

            list.addView(empty)
            return
        }

        val displayCategories = if (selectedCategory == "Inicio") {
            listOf("TV en vivo", "Películas", "Series", "Anime")
        } else {
            listOf(selectedCategory)
        }

        displayCategories.forEach { category ->

            val categoryItems = visibleItems.filter {
                it.category.equals(category, ignoreCase = true)
            }

            if (categoryItems.isEmpty()) return@forEach

            val title = TextView(this).apply {

                text = "$category  ›"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(
                    dp(8),
                    dp(18),
                    dp(8),
                    dp(10)
                )
            }

            list.addView(title)

            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFocusable = false
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            categoryItems.take(100).forEach { item ->

                val card = LinearLayout(this).apply {

                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER

                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true

                    background = getDrawable(R.drawable.tv_focus)

                    setPadding(
                        dp(8),
                        dp(10),
                        dp(8),
                        dp(8)
                    )

                    contentDescription = "Abrir ${item.name}"
                }

                val logo = ImageView(this).apply {

                    layoutParams = LinearLayout.LayoutParams(
                        dp(125),
                        dp(82)
                    )

                    scaleType = ImageView.ScaleType.FIT_CENTER

                    setBackgroundColor(
                        Color.rgb(40, 40, 48)
                    )

                    contentDescription = "Imagen de ${item.name}"

                    if (item.logo.isNotBlank()) {

                        load(item.logo) {
                            crossfade(true)
                        }
                    }
                }

                val name = TextView(this).apply {

                    text = item.name
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 2

                    setPadding(
                        dp(3),
                        dp(8),
                        dp(3),
                        dp(3)
                    )
                }

                card.addView(logo)
                card.addView(name)

                card.layoutParams = LinearLayout.LayoutParams(
                    dp(145),
                    dp(150)
                ).apply {
                    setMargins(
                        dp(5),
                        dp(3),
                        dp(5),
                        dp(8)
                    )
                }

                card.setOnClickListener {

                    if (item.url.startsWith("xtream-series:")) {
                        openSeries(item)
                    } else {
                        openPlayer(item.name, item.url)
                    }
                }

                row.addView(card)
            }

            scroll.addView(row)
            list.addView(scroll)
        }
    }

    // --------------------------------------------------
    // ABRIR REPRODUCTOR
    // --------------------------------------------------

    private fun openPlayer(
        name: String,
        url: String
    ) {

        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra("name", name)
                putExtra("url", url)
            }
        )
    }

    // --------------------------------------------------
    // UTILIDADES
    // --------------------------------------------------

    private fun dp(value: Int): Int {

        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun toast(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
