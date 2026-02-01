package com.kooo.evcam;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 视频回看Fragment - 全新播放器
 */
public class PlaybackFragment extends Fragment {
    private static final String TAG = "PlaybackFragment";
    
    // 视频列表相关
    private RecyclerView videoList;
    private TextView emptyText;
    private Button btnRefresh, btnMenu, btnMultiSelect;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect;
    private TextView selectedCount, toolbarTitle;
    private View toolbar, multiSelectToolbar;
    private VideoAdapter adapter;
    private List<File> videoFiles = new ArrayList<>();
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();
    
    // 播放器相关
    private VideoView videoPlayer;
    private Button btnPlayPause, btnSpeed, btnFullscreen;
    private SeekBar seekBar;
    private TextView tvCurrentTime, tvDuration;
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private boolean isPlaying = false;
    private boolean isSeeking = false;
    private boolean isFullscreen = false;
    private float playbackSpeed = 1.0f;
    private File currentVideoFile = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);
        
        initViews(view);
        setupListeners();
        updateVideoList();
        
        return view;
    }
    
    private void initViews(View view) {
        // Toolbar
        toolbar = view.findViewById(R.id.toolbar);
        multiSelectToolbar = view.findViewById(R.id.multi_select_toolbar);
        toolbarTitle = view.findViewById(R.id.toolbar_title);
        btnMenu = view.findViewById(R.id.btn_menu);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMultiSelect = view.findViewById(R.id.btn_multi_select);
        btnSelectAll = view.findViewById(R.id.btn_select_all);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelect = view.findViewById(R.id.btn_cancel_select);
        selectedCount = view.findViewById(R.id.selected_count);
        
        Button btnHome = view.findViewById(R.id.btn_home);
        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToRecordingInterface();
                }
            });
        }
        
        // 视频列表（单列布局）
        videoList = view.findViewById(R.id.video_list);
        emptyText = view.findViewById(R.id.empty_text);
        videoList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new VideoAdapter(getContext(), videoFiles);
        adapter.setOnVideoDeleteListener(this::updateVideoList);
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.setSelectedPositions(selectedPositions);
        adapter.setOnItemSelectedListener(this::onItemSelected);
        adapter.setOnVideoClickListener(this::playVideo);
        videoList.setAdapter(adapter);
        
        // 播放器
        videoPlayer = view.findViewById(R.id.video_player);
        btnPlayPause = view.findViewById(R.id.btn_play_pause);
        btnSpeed = view.findViewById(R.id.btn_speed);
        btnFullscreen = view.findViewById(R.id.btn_fullscreen);
        seekBar = view.findViewById(R.id.seek_bar);
        tvCurrentTime = view.findViewById(R.id.tv_current_time);
        tvDuration = view.findViewById(R.id.tv_duration);
        
        // 状态栏适配
        if (toolbar != null) {
            final int originalPaddingTop = toolbar.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbar);
        }
    }
    
    private void setupListeners() {
        // 菜单按钮
        btnMenu.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
        });
        
        btnRefresh.setOnClickListener(v -> updateVideoList());
        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());
        btnSelectAll.setOnClickListener(v -> selectAll());
        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());
        
        // 播放控制
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnSpeed.setOnClickListener(v -> toggleSpeed());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        
        // 进度条
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(formatTime(progress));
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isSeeking = true;
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isSeeking = false;
                if (videoPlayer != null) {
                    videoPlayer.seekTo(seekBar.getProgress());
                }
            }
        });
    }
    
    /**
     * 播放视频
     */
    private void playVideo(File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            AppLog.e(TAG, "Video file not found");
            return;
        }
        
        stopPlayback();
        currentVideoFile = videoFile;
        
        AppLog.d(TAG, "Playing: " + videoFile.getName());
        
        try {
            Uri videoUri = Uri.fromFile(videoFile);
            videoPlayer.setVideoURI(videoUri);
            
            videoPlayer.setOnPreparedListener(mp -> {
                AppLog.d(TAG, "Video prepared");
                int duration = mp.getDuration();
                seekBar.setMax(duration);
                tvDuration.setText(formatTime(duration));
                tvCurrentTime.setText("00:00");
                
                // 设置播放速度
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(playbackSpeed));
                    } catch (Exception e) {
                        AppLog.w(TAG, "Failed to set playback speed: " + e.getMessage());
                    }
                }
                
                // 自动播放
                mp.start();
                isPlaying = true;
                btnPlayPause.setText("暂停");
                startProgressUpdate();
            });
            
            videoPlayer.setOnErrorListener((mp, what, extra) -> {
                AppLog.e(TAG, "Playback error: " + what + ", " + extra);
                if (getContext() != null) {
                    android.widget.Toast.makeText(getContext(), 
                            "播放失败", 
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            
            videoPlayer.setOnCompletionListener(mp -> {
                AppLog.d(TAG, "Playback completed");
                isPlaying = false;
                btnPlayPause.setText("播放");
                stopProgressUpdate();
            });
            
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to play video", e);
        }
    }
    
    private void togglePlayPause() {
        if (videoPlayer == null || currentVideoFile == null) return;
        
        if (isPlaying) {
            videoPlayer.pause();
            isPlaying = false;
            btnPlayPause.setText("播放");
            stopProgressUpdate();
        } else {
            videoPlayer.start();
            isPlaying = true;
            btnPlayPause.setText("暂停");
            startProgressUpdate();
        }
    }
    
    private void toggleSpeed() {
        if (playbackSpeed == 1.0f) {
            playbackSpeed = 1.5f;
        } else if (playbackSpeed == 1.5f) {
            playbackSpeed = 2.0f;
        } else if (playbackSpeed == 2.0f) {
            playbackSpeed = 0.5f;
        } else {
            playbackSpeed = 1.0f;
        }
        
        btnSpeed.setText(playbackSpeed + "x");
        
        // 重新加载视频以应用新速度（VideoView不支持动态改变速度）
        if (currentVideoFile != null) {
            int currentPosition = videoPlayer != null ? videoPlayer.getCurrentPosition() : 0;
            playVideo(currentVideoFile);
            // 跳转到之前的位置
            if (currentPosition > 0) {
                videoPlayer.seekTo(currentPosition);
            }
        }
    }
    
    private void toggleFullscreen() {
        if (getActivity() == null) return;
        
        // 启动全屏播放Activity
        if (currentVideoFile != null) {
            Intent intent = new Intent(getContext(), VideoPlayerActivity.class);
            intent.putExtra("video_path", currentVideoFile.getAbsolutePath());
            startActivity(intent);
        }
    }
    
    private void stopPlayback() {
        if (videoPlayer != null) {
            videoPlayer.stopPlayback();
        }
        isPlaying = false;
        btnPlayPause.setText("播放");
        stopProgressUpdate();
        tvCurrentTime.setText("00:00");
        tvDuration.setText("00:00");
        seekBar.setProgress(0);
    }
    
    private void startProgressUpdate() {
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && !isSeeking && videoPlayer != null) {
                    try {
                        int position = videoPlayer.getCurrentPosition();
                        seekBar.setProgress(position);
                        tvCurrentTime.setText(formatTime(position));
                    } catch (Exception e) {
                        // 忽略异常
                    }
                }
                progressHandler.postDelayed(this, 500);
            }
        };
        progressHandler.post(progressRunnable);
    }
    
    private void stopProgressUpdate() {
        if (progressRunnable != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
    }
    
    private String formatTime(int millis) {
        int seconds = millis / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
    
    private void updateVideoList() {
        videoFiles.clear();
        
        File saveDir = StorageHelper.getVideoDir(getContext());
        if (saveDir.exists() && saveDir.isDirectory()) {
            File[] files = saveDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
            if (files != null && files.length > 0) {
                // 只添加非空文件（过滤掉0字节的损坏文件）
                for (File file : files) {
                    if (file.length() > 0) {
                        videoFiles.add(file);
                    } else {
                        AppLog.w(TAG, "跳过空文件: " + file.getName());
                    }
                }
                
                // 按修改时间倒序排序
                Collections.sort(videoFiles, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f2.lastModified(), f1.lastModified());
                    }
                });
            }
        }
        
        if (videoFiles.isEmpty()) {
            videoList.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            videoList.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
        }
        
        adapter.notifyDataSetChanged();
    }
    
    private void toggleMultiSelectMode() {
        isMultiSelectMode = !isMultiSelectMode;
        selectedPositions.clear();
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.notifyDataSetChanged();
        
        if (isMultiSelectMode) {
            toolbar.setVisibility(View.GONE);
            multiSelectToolbar.setVisibility(View.VISIBLE);
            updateSelectedCount();
        } else {
            toolbar.setVisibility(View.VISIBLE);
            multiSelectToolbar.setVisibility(View.GONE);
        }
    }
    
    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        selectedPositions.clear();
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.notifyDataSetChanged();
        toolbar.setVisibility(View.VISIBLE);
        multiSelectToolbar.setVisibility(View.GONE);
    }
    
    private void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < videoFiles.size(); i++) {
            selectedPositions.add(i);
        }
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }
    
    private void onItemSelected(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }
    
    private void updateSelectedCount() {
        selectedCount.setText("已选择 " + selectedPositions.size() + " 项");
    }
    
    private void deleteSelected() {
        if (selectedPositions.isEmpty()) return;
        
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage("确定要删除选中的 " + selectedPositions.size() + " 个视频吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    int deletedCount = 0;
                    List<Integer> positionsToDelete = new ArrayList<>(selectedPositions);
                    Collections.sort(positionsToDelete, Collections.reverseOrder());
                    
                    for (int position : positionsToDelete) {
                        if (position < videoFiles.size()) {
                            File file = videoFiles.get(position);
                            if (file.delete()) {
                                videoFiles.remove(position);
                                deletedCount++;
                            }
                        }
                    }
                    
                    selectedPositions.clear();
                    adapter.notifyDataSetChanged();
                    updateSelectedCount();
                    
                    if (deletedCount > 0) {
                        updateVideoList();
                    }
                    
                    if (getContext() != null) {
                        android.widget.Toast.makeText(getContext(), 
                                "已删除 " + deletedCount + " 个视频", 
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    
                    if (videoFiles.isEmpty()) {
                        exitMultiSelectMode();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    @Override
    public void onPause() {
        super.onPause();
        if (isPlaying && videoPlayer != null) {
            videoPlayer.pause();
            isPlaying = false;
            btnPlayPause.setText("播放");
            stopProgressUpdate();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
