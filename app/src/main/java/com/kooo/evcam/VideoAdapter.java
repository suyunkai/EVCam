package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 视频列表适配器
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {
    private final Context context;
    private final List<File> videoFiles;
    private OnVideoDeleteListener deleteListener;
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();
    private OnItemSelectedListener itemSelectedListener;

    public interface OnVideoDeleteListener {
        void onVideoDeleted();
    }

    public interface OnItemSelectedListener {
        void onItemSelected(int position);
    }

    public VideoAdapter(Context context, List<File> videoFiles) {
        this.context = context;
        this.videoFiles = videoFiles;
    }

    public void setOnVideoDeleteListener(OnVideoDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setMultiSelectMode(boolean multiSelectMode) {
        this.isMultiSelectMode = multiSelectMode;
    }

    public void setSelectedPositions(Set<Integer> positions) {
        this.selectedPositions = positions;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        this.itemSelectedListener = listener;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        File videoFile = videoFiles.get(position);

        holder.videoName.setText(videoFile.getName());

        long sizeInBytes = videoFile.length();
        String sizeStr;
        if (sizeInBytes < 1024) {
            sizeStr = sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            sizeStr = String.format(Locale.getDefault(), "%.2f KB", sizeInBytes / 1024.0);
        } else if (sizeInBytes < 1024 * 1024 * 1024) {
            sizeStr = String.format(Locale.getDefault(), "%.2f MB", sizeInBytes / (1024.0 * 1024.0));
        } else {
            sizeStr = String.format(Locale.getDefault(), "%.2f GB", sizeInBytes / (1024.0 * 1024.0 * 1024.0));
        }
        holder.videoSize.setText(sizeStr);

        long lastModified = videoFile.lastModified();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(lastModified));
        holder.videoDate.setText(dateStr);

        loadThumbnail(videoFile, holder.videoThumbnail);

        boolean isSelected = selectedPositions.contains(position);
        updateSelectionStyle(holder, isSelected);

        if (isMultiSelectMode) {
            holder.itemView.setOnClickListener(v -> {
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(position);
                }
            });
            holder.btnPlay.setEnabled(false);
            holder.btnDelete.setEnabled(false);
        } else {
            holder.itemView.setOnClickListener(null);
            holder.btnPlay.setEnabled(true);
            holder.btnDelete.setEnabled(true);

            holder.btnPlay.setOnClickListener(v -> {
                Intent intent = new Intent(context, VideoPlayerActivity.class);
                intent.putExtra("video_path", videoFile.getAbsolutePath());
                context.startActivity(intent);
            });

            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle("确认删除")
                        .setMessage("确定要删除 " + videoFile.getName() + " 吗？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            if (videoFile.delete()) {
                                videoFiles.remove(position);
                                notifyItemRemoved(position);
                                notifyItemRangeChanged(position, videoFiles.size());
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();

                                if (deleteListener != null) {
                                    deleteListener.onVideoDeleted();
                                }
                            } else {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }
    }

    private void updateSelectionStyle(VideoViewHolder holder, boolean isSelected) {
        if (isMultiSelectMode) {
            if (isSelected) {
                holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.item_selected_background));
            } else {
                holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
            }
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }
    }

    @Override
    public int getItemCount() {
        return videoFiles.size();
    }

    /**
     * 异步加载视频缩略图
     */
    private void loadThumbnail(File videoFile, ImageView imageView) {
        // 使用AsyncTask在后台线程加载缩略图
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                MediaMetadataRetriever retriever = null;
                try {
                    // 检查文件是否存在且大小大于0（避免加载正在录制的文件）
                    if (!videoFile.exists() || videoFile.length() == 0) {
                        return null;
                    }

                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(videoFile.getAbsolutePath());

                    // 获取视频第一帧作为缩略图
                    Bitmap bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                    return bitmap;
                } catch (Exception e) {
                    // 静默处理异常（可能是正在录制的文件）
                    // e.printStackTrace();
                    return null;
                } finally {
                    if (retriever != null) {
                        try {
                            retriever.release();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    // 如果无法加载缩略图，显示默认图标
                    imageView.setImageResource(android.R.drawable.ic_media_play);
                }
            }
        }.execute();
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        ImageView videoThumbnail;
        TextView videoName;
        TextView videoSize;
        TextView videoDate;
        Button btnPlay;
        Button btnDelete;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            videoThumbnail = itemView.findViewById(R.id.video_thumbnail);
            videoName = itemView.findViewById(R.id.video_name);
            videoSize = itemView.findViewById(R.id.video_size);
            videoDate = itemView.findViewById(R.id.video_date);
            btnPlay = itemView.findViewById(R.id.btn_play);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
