package com.monde.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import coil.load
class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val items = mutableListOf<MediaItem>()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val pickFile = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        list = findViewById(R.id.channelList)
        findViewById<Button>(R.id.loadM3u).setOnClickListener {
            val url = findViewById<EditText>(R.id.m3uUrl).text.toString().trim()
            if (url.isBlank()) toast("Introduce el enlace M3U") else loadM3uUrl(url)
        }
        findViewById<Button>(R.id.openFile).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
            startActivityForResult(i, pickFile)
        }
        findViewById<Button>(R.id.connectXtream).setOnClickListener { connectXtream() }
    }

    @Deprecated("Use Activity Result API in production")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "" }
                    importM3u(text); toast("Lista importada")
                } catch (e: Exception) { toast("No se pudo leer el archivo: ${e.message}") }
            } }
        }
    }

    private fun loadM3uUrl(url: String) {
        status.text = "Cargando lista..."
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") else it.body?.string() ?: "" }
                }
                importM3u(text)
                toast("Lista M3U cargada")
            } catch (e: Exception) { status.text = "Error al cargar M3U: ${e.message}. Verifica el enlace y la conexión." }
        }
    }

    private fun importM3u(text: String) {
        var name = ""; var group = ""; var logo = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(",").trim().ifBlank { "Canal" }
                    group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: ""
                    logo = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1) ?: ""
                }
                line.isNotBlank() && !line.startsWith("#") && name.isNotBlank() -> {
                    items.add(MediaItem(name, line, group, logo, classify(name, group)))
                    name = ""
                }
            }
        }
        renderItems()
    }

    private fun classify(name: String, group: String): String {
        val s = "$name $group".lowercase()
        return when {
            listOf("anime", "naruto", "one piece", "dragon ball").any { it in s } -> "Anime"
            listOf("movie", "pelicula", "película", "vod", "film").any { it in s } -> "Películas"
            listOf("series", "serie", "season", "temporada").any { it in s } -> "Series"
            else -> "TV en vivo"
        }
    }

    private fun connectXtream() {
        val server = findViewById<EditText>(R.id.server).text.toString().trim().trimEnd('/')
        val user = findViewById<EditText>(R.id.username).text.toString().trim()
        val pass = findViewById<EditText>(R.id.password).text.toString()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) { toast("Completa servidor, usuario y contraseña"); return }
        status.text = "Verificando cuenta Xtream..."
        lifecycleScope.launch {
            try {
                val api = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}"
                val body = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(api).build()).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") else it.body?.string() ?: "{}" }
                }
                val obj = org.json.JSONObject(body)
                if (obj.optJSONObject("user_info")?.optInt("auth", 0) != 1) error("Usuario o contraseña no válidos")
                status.text = "Cuenta Xtream verificada. En esta versión inicial, la carga de catálogos Xtream está pendiente de completar."
                toast("Conexión verificada")
            } catch (e: Exception) { status.text = "Error Xtream: ${e.message}" }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    
private fun renderItems() {
    list.removeAllViews()

    status.text = "${items.size} elementos importados · ${items.count { it.category == "TV en vivo" }} canales"

    val categories = listOf("TV en vivo", "Películas", "Series", "Anime")

    val groups = items.groupBy { it.category }

    val orderedCategories = categories + groups.keys.filter { it !in categories }

    orderedCategories.forEach { category ->
        val categoryItems = groups[category] ?: return@forEach

        if (categoryItems.isEmpty()) return@forEach

        val title = TextView(this).apply {
            text = "$category  ›"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(18), dp(8), dp(10))
        }

        list.addView(title)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        categoryItems.take(100).forEach { item ->

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(8))

                val bg = GradientDrawable().apply {
                    setColor(Color.rgb(29, 29, 38))
                    cornerRadius = dp(10).toFloat()
                }

                background = bg
            }

            val logo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(125), dp(78)
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(40, 40, 48))

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
                setPadding(dp(3), dp(8), dp(3), dp(3))
            }

            card.addView(logo)
            card.addView(name)

            card.layoutParams = LinearLayout.LayoutParams(
                dp(145), dp(140)
            ).apply {
                setMargins(dp(5), dp(3), dp(5), dp(8))
            }

            card.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("name", item.name)
                        putExtra("url", item.url)
                    }
                )
            }

            row.addView(card)
        }

        scroll.addView(row)
        list.addView(scroll)
    }
}

private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
