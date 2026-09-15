package com.kooo.evcam.playback;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.kooo.evcam.R;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 视频分组列表适配器
 * 显示按时间戳分组的视频组
 */
public class VideoGroupAdapter extends RecyclerView.Adapter<VideoGroupAdapter.ViewHolder> {

    private final Context context;
    private final List<VideoGroup> videoGroups;
    private int selectedPosition = -1;
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();

    private OnItemClickListener itemClickListener;
    private OnItemSelectedListener itemSelectedListener;

    public interface OnItemClickListener {
        void onItemClick(VideoGroup group, int position);
    }

    public interface OnItemSelectedListener {
        void onItemSelected(int position);
    }

    public VideoGroupAdapter(Context context, List<VideoGroup> videoGroups) {
        this.context = context;
        this.videoGroups = videoGroups;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        this.itemSelectedListener = listener;
    }

    public void setMultiSelectMode(boolean multiSelectMode) {
        this.isMultiSelectMode = multiSelectMode;
        if (!multiSelectMode) {
            selectedPositions.clear();
        }
    }

    public void setSelectedPositions(Set<Integer> positions) {
        this.selectedPositions = positions;
    }

    public Set<Integer> getSelectedPositions() {
        return selectedPositions;
    }

    public void setSelectedPosition(int position) {
        int oldPosition = this.selectedPosition;
        this.selectedPosition = position;
        if (oldPosition >= 0 && oldPosition < getItemCount()) {
            notifyItemChanged(oldPosition);
        }
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video_group, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VideoGroup group = videoGroups.get(position);

        // 设置日期时间
        holder.videoDate.setText(group.getFormattedDate());
        holder.videoTime.setText(group.getFormattedTime());
        holder.videoSize.setText(group.getFormattedSize());

        // 视频路数标签
        int count = group.getVideoCount();
        holder.videoCountBadge.setText(count + " channels");

        // 加载四个位置的缩略图
        loadThumbnail(group.getFrontVideo(), holder.thumbFront);
        loadThumbnail(group.getBackVideo(), holder.thumbBack);
        loadThumbnail(group.getLeftVideo(), holder.thumbLeft);
        loadThumbnail(group.getRightVideo(), holder.thumbRight);

        // 选中状态样式
        boolean isSelected = (position == selectedPosition) || selectedPositions.contains(position);
        updateSelectionStyle(holder, isSelected, position == selectedPosition);

        // 多选模式的选中指示器
        if (isMultiSelectMode) {
            holder.checkIndicator.setVisibility(selectedPositions.contains(position) ? View.VISIBLE : View.GONE);
        } else {
            holder.checkIndicator.setVisibility(View.GONE);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                // 多选模式：切换选中状态
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(position);
                }
            } else {
                // 单选模式：选中并播放
                setSelectedPosition(position);
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(group, position);
                }
            }
        });
    }

    private void updateSelectionStyle(ViewHolder holder, boolean isSelected, boolean isCurrentlyPlaying) {
        if (isSelected) {
            // 选中状态：高亮背景
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.item_selected_background));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }
    }

    /**
     * 加载视频缩略图
     */
    private void loadThumbnail(File videoFile, ImageView imageView) {
        if (videoFile == null || !videoFile.exists() || videoFile.length() == 0) {
            imageView.setImageDrawable(null);
            imageView.setBackgroundColor(0xFF1A1A1A);
            return;
        }

        RequestOptions options = new RequestOptions()
                .frame(0)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(videoFile.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);

        Glide.with(context)
                .asBitmap()
                .load(videoFile)
                .apply(options)
                .into(imageView);
    }

    @Override
    public int getItemCount() {
        return videoGroups.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbFront, thumbBack, thumbLeft, thumbRight;
        TextView videoDate, videoTime, videoSize, videoCountBadge;
        android.widget.CheckBox checkIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbFront = itemView.findViewById(R.id.thumb_front);
            thumbBack = itemView.findViewById(R.id.thumb_back);
            thumbLeft = itemView.findViewById(R.id.thumb_left);
            thumbRight = itemView.findViewById(R.id.thumb_right);
            videoDate = itemView.findViewById(R.id.video_date);
            videoTime = itemView.findViewById(R.id.video_time);
            videoSize = itemView.findViewById(R.id.video_size);
            videoCountBadge = itemView.findViewById(R.id.video_count_badge);
            checkIndicator = itemView.findViewById(R.id.check_indicator);
        }
    }
}
