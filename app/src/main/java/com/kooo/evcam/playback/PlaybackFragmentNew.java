package com.kooo.evcam.playback;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.PopupMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.transfer.QrTransferDialog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 视频回看Fragment（新版）
 * 支持左右分栏、四宫格预览、单路/多路切换、倍速播放
 */
public class PlaybackFragmentNew extends Fragment {
    private static final String TAG = "PlaybackFragmentNew";

    // UI 组件
    private RecyclerView videoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private View noSelectionHint;
    private Button btnMenu, btnRefresh, btnMultiSelect, btnHome;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect, btnShareSelected;
    private TextView selectedCount;
    private View toolbar, multiSelectToolbar;

    // 预览区组件
    private View multiViewLayout, singleViewLayout;
    private VideoView videoFront, videoBack, videoLeft, videoRight, videoSingle;
    private FrameLayout frameFront, frameBack, frameLeft, frameRight;
    private TextView labelFront, labelBack, labelLeft, labelRight, labelSingle;
    private TextView placeholderFront, placeholderBack, placeholderLeft, placeholderRight;

    // 播放控制组件
    private Button btnPlayPause, btnViewMode, btnSpeed;
    private SeekBar seekBar;
    private TextView currentTime, totalTime;

    // 数据
    private List<DateSection<VideoGroup>> dateSections = new ArrayList<>();
    private VideoGroup currentGroup;
    private ExpandableVideoGroupAdapter adapter;
    private MultiVideoPlayerManager playerManager;

    // 状态
    private boolean isMultiSelectMode = false;
    private boolean isSingleMode = false;
    private String currentSinglePosition = VideoGroup.POSITION_FRONT;
    private boolean isDraggingSeekBar = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback_new, container, false);
        
        initViews(view);
        initPlayerManager();
        setupListeners();
        setupDoubleTapListeners();
        updateVideoList();
        
        // 应用状态栏适配
        applyStatusBarInsets(view);
        
        return view;
    }

    private void initViews(View view) {
        // 工具栏
        toolbar = view.findViewById(R.id.toolbar);
        multiSelectToolbar = view.findViewById(R.id.multi_select_toolbar);
        btnMenu = view.findViewById(R.id.btn_menu);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMultiSelect = view.findViewById(R.id.btn_multi_select);
        btnHome = view.findViewById(R.id.btn_home);
        currentDatetime = view.findViewById(R.id.current_datetime);

        // 多选工具栏
        btnSelectAll = view.findViewById(R.id.btn_select_all);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelect = view.findViewById(R.id.btn_cancel_select);
        btnShareSelected = view.findViewById(R.id.btn_share_selected);
        selectedCount = view.findViewById(R.id.selected_count);

        // 列表
        videoList = view.findViewById(R.id.video_list);
        emptyText = view.findViewById(R.id.empty_text);
        noSelectionHint = view.findViewById(R.id.no_selection_hint);

        // 四宫格预览
        multiViewLayout = view.findViewById(R.id.multi_view_layout);
        singleViewLayout = view.findViewById(R.id.single_view_layout);
        
        videoFront = view.findViewById(R.id.video_front);
        videoBack = view.findViewById(R.id.video_back);
        videoLeft = view.findViewById(R.id.video_left);
        videoRight = view.findViewById(R.id.video_right);
        videoSingle = view.findViewById(R.id.video_single);

        frameFront = view.findViewById(R.id.frame_front);
        frameBack = view.findViewById(R.id.frame_back);
        frameLeft = view.findViewById(R.id.frame_left);
        frameRight = view.findViewById(R.id.frame_right);

        labelFront = view.findViewById(R.id.label_front);
        labelBack = view.findViewById(R.id.label_back);
        labelLeft = view.findViewById(R.id.label_left);
        labelRight = view.findViewById(R.id.label_right);
        labelSingle = view.findViewById(R.id.label_single);

        placeholderFront = view.findViewById(R.id.placeholder_front);
        placeholderBack = view.findViewById(R.id.placeholder_back);
        placeholderLeft = view.findViewById(R.id.placeholder_left);
        placeholderRight = view.findViewById(R.id.placeholder_right);

        // 播放控制
        btnPlayPause = view.findViewById(R.id.btn_play_pause);
        btnViewMode = view.findViewById(R.id.btn_view_mode);
        btnSpeed = view.findViewById(R.id.btn_speed);
        seekBar = view.findViewById(R.id.seek_bar);
        currentTime = view.findViewById(R.id.current_time);
        totalTime = view.findViewById(R.id.total_time);

        // 设置列表（竖屏2列，横屏1列，日期头部跨越所有列）
        adapter = new ExpandableVideoGroupAdapter(getContext(), dateSections);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // 日期头部占满2列，视频项占1列
                    return adapter.getItemViewType(position) == 0 ? 2 : 1;
                }
            });
            videoList.setLayoutManager(gridLayoutManager);
        } else {
            videoList.setLayoutManager(new LinearLayoutManager(getContext()));
        }
        videoList.setAdapter(adapter);

        // 初始状态：隐藏四宫格，显示提示
        multiViewLayout.setVisibility(View.GONE);
        singleViewLayout.setVisibility(View.GONE);
        noSelectionHint.setVisibility(View.VISIBLE);
    }

    private void initPlayerManager() {
        playerManager = new MultiVideoPlayerManager(getContext());
        playerManager.setVideoViews(videoFront, videoBack, videoLeft, videoRight, videoSingle);
        
        playerManager.setPlaybackListener(new MultiVideoPlayerManager.OnPlaybackListener() {
            @Override
            public void onPrepared(int duration) {
                if (getActivity() == null) return;
                Log.d(TAG, "onPrepared: duration=" + duration);
                getActivity().runOnUiThread(() -> {
                    if (duration > 0) {
                        seekBar.setMax(duration);
                        totalTime.setText(formatTime(duration));
                        currentTime.setText(formatTime(0));
                    } else {
                        Log.w(TAG, "Invalid duration: " + duration);
                        totalTime.setText("--:--");
                        currentTime.setText("00:00");
                    }
                });
            }

            @Override
            public void onProgressUpdate(int currentPosition) {
                if (getActivity() == null || isDraggingSeekBar) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(currentPosition);
                    currentTime.setText(formatTime(currentPosition));
                });
            }

            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    btnPlayPause.setText(isPlaying ? "暂停" : "播放");
                });
            }

            @Override
            public void onCompletion() {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    seekBar.setProgress(0);
                    currentTime.setText(formatTime(0));
                });
            }

            @Override
            public void onError(String message) {
                // 错误处理
            }

            @Override
            public void onSingleVideoPrepared() {
                // 单路视频准备好后显示画面（防止闪烁旧画面）
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (videoSingle != null) {
                        videoSingle.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void setupListeners() {
        // 菜单按钮
        btnMenu.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            }
        });

        // 返回主界面
        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        // 刷新
        btnRefresh.setOnClickListener(v -> updateVideoList());

        // 多选模式
        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());
        btnSelectAll.setOnClickListener(v -> selectAll());
        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        btnDeleteSelected.setOnClickListener(v -> deleteSelected());
        btnShareSelected.setOnClickListener(v -> shareSelected());

        // 列表项点击
        adapter.setOnItemClickListener((group, position) -> {
            loadVideoGroup(group);
        });

        adapter.setOnItemSelectedListener(group -> {
            updateSelectedCount();
        });

        // 列表项长按 - 分享视频
        adapter.setOnItemLongClickListener((group, position) -> {
            if (adapter.isMultiSelectMode()) {
                // 多选模式下，分享所有已选中的视频
                shareSelected();
            } else {
                // 单选模式下，分享当前长按的视频
                showShareDialog(group);
            }
        });

        // 播放控制
        btnPlayPause.setOnClickListener(v -> playerManager.togglePlayPause());

        // 摄像头切换按钮（循环切换）
        btnViewMode.setOnClickListener(v -> cycleViewMode());

        // 倍速
        btnSpeed.setOnClickListener(v -> {
            float newSpeed = playerManager.cycleSpeed();
            btnSpeed.setText(String.format(Locale.getDefault(), "%.1fx", newSpeed));
        });

        // 进度条
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isDraggingSeekBar = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isDraggingSeekBar = false;
                playerManager.seekTo(seekBar.getProgress());
            }
        });
    }

    /**
     * 设置四宫格双击监听（双击放大到单路）
     */
    private void setupDoubleTapListeners() {
        setupDoubleTap(frameFront, VideoGroup.POSITION_FRONT, "前");
        setupDoubleTap(frameBack, VideoGroup.POSITION_BACK, "后");
        setupDoubleTap(frameLeft, VideoGroup.POSITION_LEFT, "左");
        setupDoubleTap(frameRight, VideoGroup.POSITION_RIGHT, "右");

        // 单路模式双击返回多路
        if (singleViewLayout != null) {
            GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (isSingleMode) {
                        switchToMultiMode();
                    }
                    return true;
                }
            });
            singleViewLayout.setOnTouchListener((v, event) -> {
                detector.onTouchEvent(event);
                return true;
            });
        }
    }

    private void setupDoubleTap(View view, String position, String label) {
        if (view == null) return;
        
        GestureDetector detector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isSingleMode && playerManager.hasVideo(position)) {
                    switchToSingleMode(position, label);
                }
                return true;
            }
        });
        
        view.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    /**
     * 切换到单路模式
     */
    private void switchToSingleMode(String position, String label) {
        isSingleMode = true;
        currentSinglePosition = position;
        
        // 先在后台加载视频，延迟后再切换界面显示（防止闪烁旧画面或黑屏）
        labelSingle.setText(label);
        btnViewMode.setText(label + "摄");
        
        // 确保 videoSingle 可见（在切换布局之前）
        if (videoSingle != null) {
            videoSingle.setVisibility(View.VISIBLE);
        }
        
        // 先加载视频（此时 singleViewLayout 还是 GONE，用户看不到）
        playerManager.setSingleMode(true, position);
        
        // 延迟切换界面，等视频加载完成后再显示（无动画，直接切换）
        if (multiViewLayout != null) {
            multiViewLayout.postDelayed(() -> {
                if (isSingleMode) {
                    // 直接切换，不做动画（避免透明过渡时看到十字背景）
                    multiViewLayout.setVisibility(View.GONE);
                    singleViewLayout.setVisibility(View.VISIBLE);
                }
            }, 200);
        }
    }

    /**
     * 切换到多路模式
     */
    private void switchToMultiMode() {
        isSingleMode = false;
        btnViewMode.setText("多路");
        
        playerManager.setSingleMode(false, null);
        
        // 直接切换，不做动画（避免透明过渡时看到十字背景）
        singleViewLayout.setVisibility(View.GONE);
        multiViewLayout.setVisibility(View.VISIBLE);
    }

    /**
     * 循环切换视图模式：多路 → 前摄 → 后摄 → 左摄 → 右摄 → 多路...
     * 只切换到有视频的摄像头
     */
    private void cycleViewMode() {
        if (currentGroup == null) return;
        
        // 构建可用位置列表
        java.util.List<String> availablePositions = new java.util.ArrayList<>();
        availablePositions.add("multi"); // 多路始终可用
        if (currentGroup.hasVideo(VideoGroup.POSITION_FRONT)) availablePositions.add(VideoGroup.POSITION_FRONT);
        if (currentGroup.hasVideo(VideoGroup.POSITION_BACK)) availablePositions.add(VideoGroup.POSITION_BACK);
        if (currentGroup.hasVideo(VideoGroup.POSITION_LEFT)) availablePositions.add(VideoGroup.POSITION_LEFT);
        if (currentGroup.hasVideo(VideoGroup.POSITION_RIGHT)) availablePositions.add(VideoGroup.POSITION_RIGHT);
        
        // 找到当前位置的索引
        String currentPos = isSingleMode ? currentSinglePosition : "multi";
        int currentIndex = availablePositions.indexOf(currentPos);
        if (currentIndex < 0) currentIndex = 0;
        
        // 切换到下一个位置
        int nextIndex = (currentIndex + 1) % availablePositions.size();
        String nextPos = availablePositions.get(nextIndex);
        
        if ("multi".equals(nextPos)) {
            switchToMultiMode();
        } else {
            String label = getPositionLabel(nextPos);
            switchToSingleMode(nextPos, label);
        }
    }
    
    /**
     * 获取位置对应的标签
     */
    private String getPositionLabel(String position) {
        switch (position) {
            case VideoGroup.POSITION_FRONT: return "前";
            case VideoGroup.POSITION_BACK: return "后";
            case VideoGroup.POSITION_LEFT: return "左";
            case VideoGroup.POSITION_RIGHT: return "右";
            default: return "";
        }
    }

    /**
     * 切换单路/多路模式
     */
    private void toggleViewMode() {
        if (isSingleMode) {
            // 当前是单路模式，切换回多路
            switchToMultiMode();
        } else {
            // 当前是多路模式，弹出选项菜单选择单路
            showCameraSelectPopup();
        }
    }

    /**
     * 显示摄像头选择弹出菜单
     */
    private void showCameraSelectPopup() {
        // 构建可选摄像头列表
        List<String> positions = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        
        if (playerManager.hasVideo(VideoGroup.POSITION_FRONT)) {
            positions.add(VideoGroup.POSITION_FRONT);
            labels.add("前摄");
        }
        if (playerManager.hasVideo(VideoGroup.POSITION_BACK)) {
            positions.add(VideoGroup.POSITION_BACK);
            labels.add("后摄");
        }
        if (playerManager.hasVideo(VideoGroup.POSITION_LEFT)) {
            positions.add(VideoGroup.POSITION_LEFT);
            labels.add("左摄");
        }
        if (playerManager.hasVideo(VideoGroup.POSITION_RIGHT)) {
            positions.add(VideoGroup.POSITION_RIGHT);
            labels.add("右摄");
        }

        if (positions.isEmpty()) {
            return; // 没有可选项
        }

        String[] items = labels.toArray(new String[0]);
        
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("选择摄像头")
                .setItems(items, (dialog, which) -> {
                    String position = positions.get(which);
                    String label = labels.get(which).replace("摄", "");
                    switchToSingleMode(position, label);
                })
                .show();
    }

    /**
     * 加载视频组进行播放
     */
    private void loadVideoGroup(VideoGroup group) {
        this.currentGroup = group;
        noSelectionHint.setVisibility(View.GONE);
        
        // 如果在单路模式下，检查当前选择的摄像头是否有视频
        if (isSingleMode) {
            if (!group.hasVideo(currentSinglePosition)) {
                // 当前摄像头在新视频组中没有视频，切回多路模式
                isSingleMode = false;
            }
        }
        
        // 显示四宫格（根据当前模式）
        if (isSingleMode) {
            multiViewLayout.setVisibility(View.GONE);
            singleViewLayout.setVisibility(View.VISIBLE);
        } else {
            multiViewLayout.setVisibility(View.VISIBLE);
            singleViewLayout.setVisibility(View.GONE);
            btnViewMode.setText("多路");
        }
        
        // 更新标题栏日期时间
        currentDatetime.setText(group.getFormattedDateTime());
        
        // 更新四宫格的占位符显示
        updatePlaceholders(group);
        
        // 同步播放器的模式设置（确保 singleModePosition 是最新的）
        playerManager.updateSingleModePosition(isSingleMode, currentSinglePosition);
        
        // 加载视频
        playerManager.loadVideoGroup(group);
    }
    
    /**
     * 查找第一个有视频的摄像头位置
     */
    /**
     * 更新占位符显示（无视频时显示）
     */
    private void updatePlaceholders(VideoGroup group) {
        boolean hasFront = group.hasVideo(VideoGroup.POSITION_FRONT);
        boolean hasBack = group.hasVideo(VideoGroup.POSITION_BACK);
        boolean hasLeft = group.hasVideo(VideoGroup.POSITION_LEFT);
        boolean hasRight = group.hasVideo(VideoGroup.POSITION_RIGHT);

        videoFront.setVisibility(hasFront ? View.VISIBLE : View.GONE);
        placeholderFront.setVisibility(hasFront ? View.GONE : View.VISIBLE);

        videoBack.setVisibility(hasBack ? View.VISIBLE : View.GONE);
        placeholderBack.setVisibility(hasBack ? View.GONE : View.VISIBLE);

        videoLeft.setVisibility(hasLeft ? View.VISIBLE : View.GONE);
        placeholderLeft.setVisibility(hasLeft ? View.GONE : View.VISIBLE);

        videoRight.setVisibility(hasRight ? View.VISIBLE : View.GONE);
        placeholderRight.setVisibility(hasRight ? View.GONE : View.VISIBLE);
    }

    /**
     * 更新视频列表（按日期分组，然后按时间戳分组）
     */
    private void updateVideoList() {
        dateSections.clear();

        File saveDir = StorageHelper.getVideoDir(getContext());
        if (!saveDir.exists() || !saveDir.isDirectory()) {
            showEmptyState();
            return;
        }

        File[] files = saveDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
        if (files == null || files.length == 0) {
            showEmptyState();
            return;
        }

        // 第一步：按时间戳分组（同一秒录制的多路视频）
        Map<String, VideoGroup> groupMap = new HashMap<>();
        for (File file : files) {
            String timestamp = VideoGroup.extractTimestampPrefix(file.getName());
            VideoGroup group = groupMap.get(timestamp);
            if (group == null) {
                group = new VideoGroup(timestamp);
                groupMap.put(timestamp, group);
            }
            group.addFile(file);
        }

        // 转为列表并排序（最新的在前）
        List<VideoGroup> allGroups = new ArrayList<>(groupMap.values());
        Collections.sort(allGroups, (g1, g2) -> g2.getRecordTime().compareTo(g1.getRecordTime()));

        // 第二步：按日期分组
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Map<String, DateSection<VideoGroup>> dateSectionMap = new LinkedHashMap<>();
        
        for (VideoGroup group : allGroups) {
            String dateString = dateFormat.format(group.getRecordTime());
            DateSection<VideoGroup> section = dateSectionMap.get(dateString);
            if (section == null) {
                section = new DateSection<>(dateString, group.getRecordTime());
                dateSectionMap.put(dateString, section);
            }
            section.addItem(group);
        }

        // 日期分组已按日期排序（LinkedHashMap 保持插入顺序，而 allGroups 已排序）
        dateSections.addAll(dateSectionMap.values());

        // 更新UI
        if (dateSections.isEmpty()) {
            showEmptyState();
        } else {
            videoList.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
        }

        adapter.buildFlattenedList();
        adapter.notifyDataSetChanged();
    }

    private void showEmptyState() {
        videoList.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void toggleMultiSelectMode() {
        isMultiSelectMode = !isMultiSelectMode;
        adapter.clearSelection();
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
        adapter.clearSelection();
        adapter.setMultiSelectMode(false);
        adapter.notifyDataSetChanged();
        toolbar.setVisibility(View.VISIBLE);
        multiSelectToolbar.setVisibility(View.GONE);
    }

    private void selectAll() {
        adapter.selectAll();
        adapter.notifyDataSetChanged();
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        selectedCount.setText("已选择 " + adapter.getSelectedCount() + " 项");
    }

    private void deleteSelected() {
        Set<VideoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            return;
        }

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认删除")
                .setMessage("确定要删除选中的 " + selectedGroups.size() + " 组视频吗？（包含所有摄像头录像）")
                .setPositiveButton("删除", (dialog, which) -> {
                    int deletedCount = 0;
                    
                    // 删除选中的视频组
                    for (VideoGroup group : selectedGroups) {
                        deletedCount += group.deleteAll();
                    }
                    
                    // 从日期分组中移除已删除的组
                    for (DateSection<VideoGroup> section : dateSections) {
                        section.getItems().removeAll(selectedGroups);
                    }
                    
                    // 移除空的日期分组
                    dateSections.removeIf(section -> section.getItemCount() == 0);

                    adapter.clearSelection();
                    adapter.buildFlattenedList();
                    adapter.notifyDataSetChanged();
                    updateSelectedCount();

                    if (getContext() != null) {
                        android.widget.Toast.makeText(getContext(),
                                "已删除 " + deletedCount + " 个视频文件",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }

                    if (dateSections.isEmpty()) {
                        exitMultiSelectMode();
                        showEmptyState();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 分享选中的视频
     */
    private void shareSelected() {
        Set<VideoGroup> selectedGroups = adapter.getSelectedGroups();
        if (selectedGroups.isEmpty()) {
            Toast.makeText(getContext(), "请先选择要分享的视频", Toast.LENGTH_SHORT).show();
            return;
        }

        // 收集所有选中的视频文件
        List<File> allVideoFiles = new ArrayList<>();
        String[] positions = {VideoGroup.POSITION_FRONT, VideoGroup.POSITION_BACK, 
                              VideoGroup.POSITION_LEFT, VideoGroup.POSITION_RIGHT};
        
        for (VideoGroup group : selectedGroups) {
            for (String position : positions) {
                File videoFile = group.getVideoFile(position);
                if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                    allVideoFiles.add(videoFile);
                }
            }
        }

        if (allVideoFiles.isEmpty()) {
            Toast.makeText(getContext(), "没有可分享的视频文件", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "准备分享: " + selectedGroups.size() + " 组, " + allVideoFiles.size() + " 个文件");
        for (int i = 0; i < Math.min(allVideoFiles.size(), 5); i++) {
            Log.d(TAG, "文件 " + i + ": " + allVideoFiles.get(i).getAbsolutePath());
        }

        // 显示分享选项对话框
        String[] options = {
            "📱 扫码互传（推荐）",
            "📤 系统分享（" + allVideoFiles.size() + "个文件）"
        };

        Log.d(TAG, "创建分享对话框");
        showShareOptionsDialog("分享选中的视频", 
            "已选择 " + selectedGroups.size() + " 组视频，共 " + allVideoFiles.size() + " 个文件",
            allVideoFiles);
    }

    /**
     * 格式化时间（毫秒 -> mm:ss）
     */
    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void applyStatusBarInsets(View view) {
        View toolbarView = view.findViewById(R.id.toolbar);
        if (toolbarView != null) {
            final int originalPaddingTop = toolbarView.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbarView);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (playerManager != null && playerManager.isPlaying()) {
            playerManager.pause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (playerManager != null) {
            playerManager.release();
        }
    }

    /**
     * 显示分享对话框
     */
    private void showShareDialog(VideoGroup group) {
        if (getContext() == null) return;

        // 获取所有可用的视频文件
        List<File> videoFiles = new ArrayList<>();
        String[] positions = {VideoGroup.POSITION_FRONT, VideoGroup.POSITION_BACK, 
                              VideoGroup.POSITION_LEFT, VideoGroup.POSITION_RIGHT};
        String[] positionNames = {"前视", "后视", "左视", "右视"};
        
        for (int i = 0; i < positions.length; i++) {
            File videoFile = group.getVideoFile(positions[i]);
            if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                videoFiles.add(videoFile);
            }
        }

        if (videoFiles.isEmpty()) {
            Toast.makeText(getContext(), "没有可分享的视频文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建选项
        String[] options = new String[videoFiles.size() + 2];
        options[0] = "📱 扫码互传（推荐）";
        options[1] = "📤 分享所有路视频";
        for (int i = 0; i < videoFiles.size(); i++) {
            File file = videoFiles.get(i);
            String positionName = getPositionName(file.getName());
            options[i + 2] = "📤 仅分享" + positionName + "视频";
        }

        showShareOptionsDialog("分享视频", 
            "共 " + videoFiles.size() + " 个视频文件",
            videoFiles);
    }

    /**
     * 显示分享选项对话框（自定义布局）
     */
    private void showShareOptionsDialog(String title, String message, List<File> videoFiles) {
        if (getContext() == null) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        
        // 加载自定义布局
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_share_options, null);
        TextView titleView = dialogView.findViewById(R.id.dialog_title);
        TextView messageView = dialogView.findViewById(R.id.dialog_message);
        View btnQr = dialogView.findViewById(R.id.btn_qr);
        View btnShare = dialogView.findViewById(R.id.btn_share);
        View btnClose = dialogView.findViewById(R.id.btn_close);
        
        titleView.setText(title);
        messageView.setText(message);
        
        builder.setView(dialogView);
        builder.setCancelable(true);
        
        android.app.AlertDialog dialog = builder.create();
        
        // 设置按钮点击事件
        btnQr.setOnClickListener(v -> {
            Log.d(TAG, "用户选择扫码互传");
            dialog.dismiss();
            showQrTransferDialog(videoFiles);
        });
        
        btnShare.setOnClickListener(v -> {
            Log.d(TAG, "用户选择系统分享");
            dialog.dismiss();
            shareVideos(videoFiles);
        });
        
        btnClose.setOnClickListener(v -> {
            Log.d(TAG, "用户取消分享");
            dialog.dismiss();
        });
        
        dialog.show();
        Log.d(TAG, "分享选项对话框已显示");
    }

    /**
     * 显示扫码互传对话框
     */
    private void showQrTransferDialog(List<File> videoFiles) {
        // 使用 getActivity() 获取 Activity 上下文，避免 Fragment detached 问题
        android.app.Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.e(TAG, "无法显示对话框: activity 不可用");
            return;
        }
        
        if (!isAdded()) {
            Log.e(TAG, "无法显示对话框: Fragment 未 attached");
            return;
        }
        
        Log.d(TAG, "准备显示扫码互传对话框，文件数: " + (videoFiles != null ? videoFiles.size() : 0));
        
        try {
            // 使用 Activity 上下文而不是 Fragment 上下文，确保对话框在 Activity 生命周期内
            QrTransferDialog dialog = new QrTransferDialog(activity, videoFiles);
            dialog.show();
            Log.d(TAG, "扫码互传对话框已显示");
        } catch (Exception e) {
            Log.e(TAG, "显示扫码互传对话框失败", e);
            Toast.makeText(activity, "无法显示分享对话框: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 根据文件名获取位置名称
     */
    private String getPositionName(String fileName) {
        if (fileName.contains("front")) return "前视";
        if (fileName.contains("back")) return "后视";
        if (fileName.contains("left")) return "左视";
        if (fileName.contains("right")) return "右视";
        return "未知";
    }

    /**
     * 分享视频文件
     */
    private void shareVideos(List<File> videoFiles) {
        if (getContext() == null || videoFiles.isEmpty()) return;

        try {
            String authority = getContext().getPackageName() + ".fileprovider";
            
            if (videoFiles.size() == 1) {
                // 分享单个视频
                File videoFile = videoFiles.get(0);
                
                // 检查文件是否存在且可读
                if (!videoFile.exists() || !videoFile.canRead()) {
                    Toast.makeText(getContext(), "文件不存在或无法读取", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                Uri videoUri = FileProvider.getUriForFile(getContext(), authority, videoFile);
                
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("video/mp4");
                shareIntent.putExtra(Intent.EXTRA_STREAM, videoUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "分享视频");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "来自 EVCam 的视频分享");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                // 创建选择器
                Intent chooser = Intent.createChooser(shareIntent, "分享视频到");
                if (chooser.resolveActivity(getContext().getPackageManager()) != null) {
                    startActivity(chooser);
                } else {
                    Toast.makeText(getContext(), "没有找到可以分享的应用", Toast.LENGTH_SHORT).show();
                }
            } else {
                // 分享多个视频
                ArrayList<Uri> videoUris = new ArrayList<>();
                for (File videoFile : videoFiles) {
                    // 检查文件是否存在且可读
                    if (!videoFile.exists() || !videoFile.canRead()) {
                        continue;
                    }
                    Uri videoUri = FileProvider.getUriForFile(getContext(), authority, videoFile);
                    videoUris.add(videoUri);
                }
                
                if (videoUris.isEmpty()) {
                    Toast.makeText(getContext(), "没有可分享的文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("video/mp4");
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, videoUris);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "分享视频");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "来自 EVCam 的视频分享 (" + videoUris.size() + "个视频)");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                
                // 创建选择器
                Intent chooser = Intent.createChooser(shareIntent, "分享视频到");
                if (chooser.resolveActivity(getContext().getPackageManager()) != null) {
                    startActivity(chooser);
                } else {
                    Toast.makeText(getContext(), "没有找到可以分享的应用", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "分享视频失败: FileProvider 无法处理该文件路径", e);
            Toast.makeText(getContext(), "分享失败: 文件路径不受支持，请使用扫码互传功能", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "分享视频失败", e);
            Toast.makeText(getContext(), "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
