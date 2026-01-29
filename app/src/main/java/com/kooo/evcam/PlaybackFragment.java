package com.kooo.evcam;

import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 视频回看Fragment
 */
public class PlaybackFragment extends Fragment {
    private RecyclerView videoList;
    private TextView emptyText;
    private Button btnRefresh;
    private Button btnMenu;
    private Button btnMultiSelect;
    private Button btnSelectAll;
    private Button btnDeleteSelected;
    private Button btnCancelSelect;
    private TextView selectedCount;
    private View toolbar;
    private View multiSelectToolbar;
    private TextView toolbarTitle;
    private VideoAdapter adapter;
    private List<File> videoFiles = new ArrayList<>();
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);

        videoList = view.findViewById(R.id.video_list);
        emptyText = view.findViewById(R.id.empty_text);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMenu = view.findViewById(R.id.btn_menu);
        btnMultiSelect = view.findViewById(R.id.btn_multi_select);
        btnSelectAll = view.findViewById(R.id.btn_select_all);
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected);
        btnCancelSelect = view.findViewById(R.id.btn_cancel_select);
        selectedCount = view.findViewById(R.id.selected_count);
        toolbar = view.findViewById(R.id.toolbar);
        multiSelectToolbar = view.findViewById(R.id.multi_select_toolbar);
        toolbarTitle = view.findViewById(R.id.toolbar_title);
        Button btnHome = view.findViewById(R.id.btn_home);

        videoList.setLayoutManager(new GridLayoutManager(getContext(), 4));
        adapter = new VideoAdapter(getContext(), videoFiles);
        adapter.setOnVideoDeleteListener(this::updateVideoList);
        adapter.setMultiSelectMode(isMultiSelectMode);
        adapter.setSelectedPositions(selectedPositions);
        adapter.setOnItemSelectedListener(this::onItemSelected);
        videoList.setAdapter(adapter);

        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

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

        btnRefresh.setOnClickListener(v -> updateVideoList());

        btnMultiSelect.setOnClickListener(v -> toggleMultiSelectMode());

        btnSelectAll.setOnClickListener(v -> selectAll());

        btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());

        btnDeleteSelected.setOnClickListener(v -> deleteSelected());

        updateVideoList();

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

        return view;
    }

    /**
     * 更新视频列表
     */
    private void updateVideoList() {
        videoFiles.clear();

        // 获取保存目录
        File saveDir = StorageHelper.getVideoDir(getContext());
        if (saveDir.exists() && saveDir.isDirectory()) {
            File[] files = saveDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
            if (files != null && files.length > 0) {
                videoFiles.addAll(Arrays.asList(files));

                // 按修改时间倒序排序（最新的在前）
                Collections.sort(videoFiles, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f2.lastModified(), f1.lastModified());
                    }
                });
            }
        }

        // 更新UI
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
        if (selectedPositions.isEmpty()) {
            return;
        }

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
                                videoFiles.remove((int) position);
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
}
