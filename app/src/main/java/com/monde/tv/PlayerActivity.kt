package com.monde.tv

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import android.app.AlertDialog

@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var selector: DefaultTrackSelector
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        val name = intent.getStringExtra("name") ?: "MONDE TV"
        val url = intent.getStringExtra("url") ?: ""
        findViewById<TextView>(R.id.playerTitle).text = name
        info = findViewById(R.id.qualityInfo)
        selector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(selector).build().also { exo ->
            findViewById<PlayerView>(R.id.playerView).player = exo
            exo.setMediaItem(ExoMediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : androidx.media3.common.Player.Listener {
                override fun onTracksChanged(tracks: Tracks) { updateQualityInfo(tracks) }
            })
        }
        findViewById<Button>(R.id.qualityButton).setOnClickListener { showQualityDialog() }
    }

    private fun updateQualityInfo(tracks: Tracks) {
        val heights = tracks.groups.flatMap { g ->
            (0 until g.length).mapNotNull { i -> g.getTrackFormat(i).height.takeIf { it > 0 } }
        }.distinct().sorted()
        info.text = if (heights.isEmpty()) "Calidad: Automática (la fuente no informa variantes)" else "Calidades detectadas: " + heights.joinToString { "${it}p" }
    }

    private fun showQualityDialog() {
        val tracks = player?.currentTracks ?: return
        val videoGroup = tracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.length > 0 }
        val options = arrayOf("Automática", "480p", "720p", "1080p")
        AlertDialog.Builder(this)
            .setTitle("Calidad de imagen")
            .setItems(options) { _, which ->
                if (which == 0) {
                    selector.setParameters(selector.buildUponParameters().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE))
                    info.text = "Calidad: Automática"
                } else if (videoGroup != null) {
                    val target = when (which) { 1 -> 480; 2 -> 720; else -> 1080 }
                    val candidates = (0 until videoGroup.length).map { i -> i to videoGroup.getTrackFormat(i).height }.filter { it.second > 0 }
                    val chosen = candidates.minByOrNull { kotlin.math.abs(it.second - target) }
                    if (chosen != null) {
                        val override = TrackSelectionOverride(videoGroup.mediaTrackGroup, listOf(chosen.first))
                        selector.setParameters(selector.buildUponParameters().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).addOverride(override))
                        info.text = "Calidad seleccionada: ${chosen.second}p"
                    } else info.text = "La fuente no ofrece variantes de video."
                } else info.text = "No hay calidades alternativas disponibles."
            }.show()
    }

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
