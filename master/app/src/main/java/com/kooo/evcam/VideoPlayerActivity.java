package com.kooo.evcam;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/**
 * 内置视频播放器
 * 支持播放、暂停、进度控制等功能
 */
public class VideoPlayerActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView titleText;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private SeekBar seekBar;
    private Button btnPlayPause;
    private View controlsLayout;

    private Handler handler = new Handler();
    private boolean isPlaying = false;
    private boolean isDragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        videoView = findViewById(R.id.video_view);
        titleText = findViewById(R.id.video_title);
        currentTimeText = findViewById(R.id.current_time);
        totalTimeText = findViewById(R.id.total_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        controlsLayout = findViewById(R.id.controls_layout);
        View btnClose = findViewById(R.id.btn_close);

        // 获取视频路径
        String videoPath = getIntent().getStringExtra("video_path");
        if (videoPath == null || videoPath.isEmpty()) {
            Toast.makeText(this, "无效的视频路径", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            Toast.makeText(this, "视频文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 设置标题
        titleText.setText(videoFile.getName());

        // 加载视频
        loadVideo(videoFile);

        // 关闭按钮
        btnClose.setOnClickListener(v -> finish());

        // 播放/暂停按钮
        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseVideo();
            } else {
                playVideo();
            }
        });

        // 进度条拖动
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTimeText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isDragging = false;
                videoView.seekTo(seekBar.getProgress());
            }
        });

        // 点击视频区域显示/隐藏控制栏
        videoView.setOnClickListener(v -> {
            if (controlsLayout.getVisibility() == View.VISIBLE) {
                controlsLayout.setVisibility(View.GONE);
            } else {
                controlsLayout.setVisibility(View.VISIBLE);
                // 3秒后自动隐藏
                handler.removeCallbacks(hideControlsRunnable);
                handler.postDelayed(hideControlsRunnable, 3000);
            }
        });
    }

    /**
     * 加载视频
     */
    private void loadVideo(File videoFile) {
        try {
            Uri videoUri = Uri.fromFile(videoFile);
            videoView.setVideoURI(videoUri);

            // 视频准备完成监听
            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    int duration = videoView.getDuration();
                    seekBar.setMax(duration);
                    totalTimeText.setText(formatTime(duration));
                    currentTimeText.setText(formatTime(0));

                    // 自动开始播放
                    playVideo();

                    // 开始更新进度
                    updateProgress();
                }
            });

            // 播放完成监听
            videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    isPlaying = false;
                    btnPlayPause.setText("播放");
                    seekBar.setProgress(0);
                    currentTimeText.setText(formatTime(0));
                }
            });

            // 错误监听
            videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Toast.makeText(VideoPlayerActivity.this, "播放出错", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "加载视频失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * 播放视频
     */
    private void playVideo() {
        videoView.start();
        isPlaying = true;
        btnPlayPause.setText("暂停");
    }

    /**
     * 暂停视频
     */
    private void pauseVideo() {
        videoView.pause();
        isPlaying = false;
        btnPlayPause.setText("播放");
    }

    /**
     * 更新播放进度
     */
    private void updateProgress() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && !isDragging) {
                    int currentPosition = videoView.getCurrentPosition();
                    seekBar.setProgress(currentPosition);
                    currentTimeText.setText(formatTime(currentPosition));
                }
                if (isPlaying) {
                    handler.postDelayed(this, 100);
                }
            }
        }, 100);
    }

    /**
     * 格式化时间（毫秒转为 mm:ss）
     */
    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    /**
     * 自动隐藏控制栏
     */
    private Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying) {
                controlsLayout.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        if (isPlaying) {
            pauseVideo();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(hideControlsRunnable);
        if (videoView != null) {
            videoView.stopPlayback();
        }
    }
}
