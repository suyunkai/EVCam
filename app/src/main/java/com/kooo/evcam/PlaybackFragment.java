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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 视频回看Fragment
 */
public class PlaybackFragment extends Fragment {
    private RecyclerView videoList;
    private TextView emptyText;
    private Button btnRefresh;
    private Button btnMenu;
    private VideoAdapter adapter;
    private List<File> videoFiles = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);

        videoList = view.findViewById(R.id.video_list);
        emptyText = view.findViewById(R.id.empty_text);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        btnMenu = view.findViewById(R.id.btn_menu);
        Button btnHome = view.findViewById(R.id.btn_home);

        // 设置RecyclerView为网格布局（4列）
        videoList.setLayoutManager(new GridLayoutManager(getContext(), 4));
        adapter = new VideoAdapter(getContext(), videoFiles);
        adapter.setOnVideoDeleteListener(this::updateVideoList);
        videoList.setAdapter(adapter);

        // 主页按钮 - 返回预览界面
        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

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

        // 刷新按钮
        btnRefresh.setOnClickListener(v -> updateVideoList());

        // 加载视频列表
        updateVideoList();

        // 沉浸式状态栏兼容
        View toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            final int originalPaddingTop = toolbar.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbar);
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
}
