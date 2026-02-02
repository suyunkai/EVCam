package com.kooo.evcam.playback;

import android.content.res.Configuration;
import android.os.Bundle;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    // UI 组件
    private RecyclerView videoList;
    private TextView emptyText;
    private TextView currentDatetime;
    private View noSelectionHint;
    private Button btnMenu, btnRefresh, btnMultiSelect, btnHome;
    private Button btnSelectAll, btnDeleteSelected, btnCancelSelect;
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
                getActivity().runOnUiThread(() -> {
                    seekBar.setMax(duration);
                    totalTime.setText(formatTime(duration));
                    currentTime.setText(formatTime(0));
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

        // 列表项点击
        adapter.setOnItemClickListener((group, position) -> {
            loadVideoGroup(group);
        });

        adapter.setOnItemSelectedListener(group -> {
            updateSelectedCount();
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
        
        // 延迟切换界面，等视频加载完成后再显示（带动画）
        if (multiViewLayout != null) {
            multiViewLayout.postDelayed(() -> {
                if (isSingleMode) {
                    // 设置初始状态：稍微缩小 + 透明
                    singleViewLayout.setAlpha(0f);
                    singleViewLayout.setScaleX(0.95f);
                    singleViewLayout.setScaleY(0.95f);
                    singleViewLayout.setVisibility(View.VISIBLE);
                    
                    // 淡入 + 放大到正常大小
                    singleViewLayout.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .setListener(new android.animation.AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(android.animation.Animator animation) {
                                    multiViewLayout.setVisibility(View.GONE);
                                }
                            })
                            .start();
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
        
        // 设置初始状态：稍微缩小 + 透明
        multiViewLayout.setAlpha(0f);
        multiViewLayout.setScaleX(0.95f);
        multiViewLayout.setScaleY(0.95f);
        multiViewLayout.setVisibility(View.VISIBLE);
        
        // 淡入 + 放大到正常大小
        multiViewLayout.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        singleViewLayout.setVisibility(View.GONE);
                        // 重置 singleViewLayout 的状态，以便下次使用
                        singleViewLayout.setAlpha(1f);
                        singleViewLayout.setScaleX(1f);
                        singleViewLayout.setScaleY(1f);
                    }
                })
                .start();
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
}
