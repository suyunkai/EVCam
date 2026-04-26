package com.kooo.evcam.v2.ui.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.kooo.evcam.databinding.ActivityV2VideoPlaybackBinding
import java.io.File
import java.util.concurrent.Executors

class V2VideoPlaybackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityV2VideoPlaybackBinding
    private lateinit var adapter: V2VideoPlaybackAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val progressHandler = Handler(Looper.getMainLooper())
    private var selected: V2VideoGroup? = null
    private var pendingVideo: File? = null
    private var userSeeking = false
    @Volatile private var loadGeneration = 0
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!userSeeking) {
                val pos = binding.videoFront.currentPosition.coerceAtLeast(0)
                binding.currentTime.text = formatTime(pos)
                binding.seekBar.progress = pos.coerceAtMost(binding.seekBar.max.coerceAtLeast(1))
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityV2VideoPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.videoList.layoutManager = LinearLayoutManager(this)
        adapter = V2VideoPlaybackAdapter { playVideo(it) }
        binding.videoList.adapter = adapter
        binding.btnHome.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshVideos() }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: 0
                binding.videoFront.seekTo(target)
                binding.currentTime.text = formatTime(target)
                userSeeking = false
            }
        })
        binding.videoFront.setOnErrorListener { _, _, _ -> showPlaybackError("视频无法播放或文件未完成: ${pendingVideo?.name ?: "未知文件"}"); true }
        loadVideos(autoSelect = true)
    }

    override fun onDestroy() {
        stopProgressUpdater()
        binding.videoFront.stopPlayback()
        loadGeneration += 1
        executor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() { pauseAll(); super.onPause() }

    private fun refreshVideos() {
        selected = null
        pendingVideo = null
        stopCurrentPlaybackUi()
        loadVideos(autoSelect = false)
    }

    private fun loadVideos(autoSelect: Boolean) {
        val generation = ++loadGeneration
        adapter.clear()
        binding.emptyText.visibility = View.GONE
        var found = false
        var autoSelected = false
        executor.execute {
            val count = V2VideoScanner.scanGroupsIncremental(
                context = this,
                isCancelled = { generation != loadGeneration }
            ) { group ->
                found = true
                runOnUiThread {
                    if (generation != loadGeneration) return@runOnUiThread
                    adapter.addOrUpdate(group)
                    binding.emptyText.visibility = View.GONE
                    if (autoSelect && !autoSelected) {
                        autoSelected = true
                        playVideo(group)
                    }
                }
                group.composite?.let { video -> loadThumbnailAsync(generation, group.timestamp, video) }
            }
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                binding.emptyText.visibility = if (count == 0 && !found) View.VISIBLE else View.GONE
                if (count == 0 && !found) stopCurrentPlaybackUi()
            }
        }
    }

    private fun loadThumbnailAsync(generation: Int, timestamp: String, video: File) {
        thumbnailExecutor.execute {
            val thumbnail = V2VideoScanner.cachedThumbnail(video) ?: return@execute
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                adapter.updateThumbnail(timestamp, thumbnail)
            }
        }
    }

    private fun stopCurrentPlaybackUi() {
        stopProgressUpdater()
        binding.videoFront.stopPlayback()
        binding.placeholderFront.visibility = View.VISIBLE
        binding.currentDatetime.text = ""
        binding.btnPlayPause.text = "播放"
        binding.currentTime.text = "00:00"
        binding.totalTime.text = "00:00"
        binding.seekBar.progress = 0
        binding.seekBar.max = 100
    }

    private fun playVideo(group: V2VideoGroup) {
        selected = group
        binding.currentDatetime.text = "${group.displayYear}-${group.displayDate} ${group.displayTime}"
        val primary = group.composite
        binding.placeholderFront.visibility = if (primary == null) View.VISIBLE else View.GONE
        playFile(binding.videoFront, primary, true)
    }

    private fun playFile(view: android.widget.VideoView, file: File?, primary: Boolean) {
        if (file == null || !file.isFile || !file.canRead() || file.length() <= 0L) {
            pendingVideo = null
            view.stopPlayback()
            binding.placeholderFront.visibility = View.VISIBLE
            return
        }
        pendingVideo = file
        binding.placeholderFront.visibility = View.GONE
        binding.btnPlayPause.text = "加载中"
        view.setVideoURI(Uri.fromFile(file))
        view.setOnPreparedListener { player ->
            player.isLooping = false
            if (primary) {
                binding.totalTime.text = formatTime(player.duration)
                binding.seekBar.max = player.duration.coerceAtLeast(1)
                binding.seekBar.progress = 0
                binding.currentTime.text = "00:00"
                binding.btnPlayPause.text = "暂停"
                startProgressUpdater()
            }
            player.start()
        }
        view.setOnCompletionListener {
            if (primary) {
                binding.btnPlayPause.text = "播放"
                stopProgressUpdater()
                binding.seekBar.progress = binding.seekBar.max
                binding.currentTime.text = binding.totalTime.text
            }
        }
    }

    private fun showPlaybackError(message: String) {
        binding.videoFront.stopPlayback()
        stopProgressUpdater()
        binding.placeholderFront.visibility = View.VISIBLE
        binding.btnPlayPause.text = "播放"
        binding.currentTime.text = "00:00"
        binding.totalTime.text = "00:00"
        binding.seekBar.progress = 0
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback() {
        if (binding.videoFront.isPlaying) {
            pauseAll()
            binding.btnPlayPause.text = "播放"
        } else {
            startAll()
            binding.btnPlayPause.text = "暂停"
        }
    }

    private fun pauseAll() { if (binding.videoFront.isPlaying) binding.videoFront.pause(); stopProgressUpdater() }
    private fun startAll() { binding.videoFront.start(); startProgressUpdater() }
    private fun startProgressUpdater() { progressHandler.removeCallbacks(progressUpdater); progressHandler.post(progressUpdater) }
    private fun stopProgressUpdater() { progressHandler.removeCallbacks(progressUpdater) }
    private fun formatTime(ms: Int): String = String.format(java.util.Locale.getDefault(), "%02d:%02d", (ms.coerceAtLeast(0) / 1000) / 60, (ms.coerceAtLeast(0) / 1000) % 60)
}
