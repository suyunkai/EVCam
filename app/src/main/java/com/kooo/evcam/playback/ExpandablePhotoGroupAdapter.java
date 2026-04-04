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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 可展开的图片分组适配器
 * 支持按日期分组显示，点击日期头部可展开/收起
 */
public class ExpandablePhotoGroupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_DATE_HEADER = 0;
    private static final int VIEW_TYPE_PHOTO_GROUP = 1;

    private final Context context;
    private final List<DateSection<PhotoGroup>> dateSections;
    
    /** 扁平化后的列表项（用于 RecyclerView 显示） */
    private final List<Object> flattenedItems = new ArrayList<>();
    
    /** 多选模式下选中的 PhotoGroup */
    private Set<PhotoGroup> selectedGroups = new HashSet<>();
    
    private int selectedPosition = -1;
    private boolean isMultiSelectMode = false;

    private OnItemClickListener itemClickListener;
    private OnItemSelectedListener itemSelectedListener;
    private OnDateHeaderClickListener dateHeaderClickListener;
    private OnItemLongClickListener itemLongClickListener;

    public interface OnItemClickListener {
        void onItemClick(PhotoGroup group, int position);
    }

    public interface OnItemSelectedListener {
        void onItemSelected(PhotoGroup group);
    }

    public interface OnDateHeaderClickListener {
        void onDateHeaderClick(DateSection<PhotoGroup> section, int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(PhotoGroup group, int position);
    }

    public ExpandablePhotoGroupAdapter(Context context, List<DateSection<PhotoGroup>> dateSections) {
        this.context = context;
        this.dateSections = dateSections;
        buildFlattenedList();
    }

    /**
     * 构建扁平化列表
     * 根据展开状态将日期头部和图片项转换为扁平列表
     */
    public void buildFlattenedList() {
        flattenedItems.clear();
        for (DateSection<PhotoGroup> section : dateSections) {
            flattenedItems.add(section);
            if (section.isExpanded()) {
                flattenedItems.addAll(section.getItems());
            }
        }
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        this.itemSelectedListener = listener;
    }

    public void setOnDateHeaderClickListener(OnDateHeaderClickListener listener) {
        this.dateHeaderClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.itemLongClickListener = listener;
    }

    public void setMultiSelectMode(boolean multiSelectMode) {
        this.isMultiSelectMode = multiSelectMode;
        if (!multiSelectMode) {
            selectedGroups.clear();
        }
    }

    public boolean isMultiSelectMode() {
        return isMultiSelectMode;
    }

    public Set<PhotoGroup> getSelectedGroups() {
        return selectedGroups;
    }

    public void setSelectedGroups(Set<PhotoGroup> groups) {
        this.selectedGroups = groups;
    }

    public int getSelectedCount() {
        return selectedGroups.size();
    }

    public void clearSelection() {
        selectedGroups.clear();
    }

    /**
     * 全选所有图片组
     */
    public void selectAll() {
        selectedGroups.clear();
        for (DateSection<PhotoGroup> section : dateSections) {
            selectedGroups.addAll(section.getItems());
        }
    }

    /**
     * 获取所有 PhotoGroup 的总数
     */
    public int getTotalGroupCount() {
        int count = 0;
        for (DateSection<PhotoGroup> section : dateSections) {
            count += section.getItemCount();
        }
        return count;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = flattenedItems.get(position);
        if (item instanceof DateSection) {
            return VIEW_TYPE_DATE_HEADER;
        } else {
            return VIEW_TYPE_PHOTO_GROUP;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_DATE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_date_header, parent, false);
            return new DateHeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_video_group, parent, false);
            return new PhotoGroupViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = flattenedItems.get(position);

        if (holder instanceof DateHeaderViewHolder) {
            @SuppressWarnings("unchecked")
            DateSection<PhotoGroup> section = (DateSection<PhotoGroup>) item;
            bindDateHeader((DateHeaderViewHolder) holder, section, position);
        } else if (holder instanceof PhotoGroupViewHolder) {
            PhotoGroup group = (PhotoGroup) item;
            bindPhotoGroup((PhotoGroupViewHolder) holder, group, position);
        }
    }

    private void bindDateHeader(DateHeaderViewHolder holder, DateSection<PhotoGroup> section, int position) {
        // 设置日期文字
        holder.dateText.setText(section.getFullDateDisplay());
        
        // 设置组数量
        holder.itemCount.setText(section.getItemCount() + "组");
        
        // 设置展开/收起图标
        int iconRes = section.isExpanded() ? R.drawable.ic_expand_less : R.drawable.ic_expand_more;
        holder.expandIcon.setImageResource(iconRes);
        
        // 点击切换展开状态
        holder.itemView.setOnClickListener(v -> {
            section.toggleExpanded();
            buildFlattenedList();
            notifyDataSetChanged();
            
            if (dateHeaderClickListener != null) {
                dateHeaderClickListener.onDateHeaderClick(section, position);
            }
        });
    }

    private void bindPhotoGroup(PhotoGroupViewHolder holder, PhotoGroup group, int position) {
        // 设置日期时间（只显示时间，因为日期已在头部显示）
        holder.videoDate.setVisibility(View.GONE);
        holder.videoTime.setText(group.getFormattedTime());
        holder.videoSize.setText(group.getFormattedSize());

        // 图片数量标签
        int count = group.getPhotoCount();
        holder.videoCountBadge.setText(count + "张");

        // 加载四个位置的缩略图
        loadThumbnail(group.getFrontPhoto(), holder.thumbFront);
        loadThumbnail(group.getBackPhoto(), holder.thumbBack);
        loadThumbnail(group.getLeftPhoto(), holder.thumbLeft);
        loadThumbnail(group.getRightPhoto(), holder.thumbRight);

        // 选中状态样式
        boolean isSelected;
        if (isMultiSelectMode) {
            isSelected = selectedGroups.contains(group);
        } else {
            isSelected = (position == selectedPosition);
        }
        updateSelectionStyle(holder, isSelected);

        // 多选模式的选中指示器
        if (isMultiSelectMode) {
            holder.checkIndicator.setVisibility(View.VISIBLE);
            holder.checkIndicator.setChecked(selectedGroups.contains(group));
        } else {
            holder.checkIndicator.setVisibility(View.GONE);
        }

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                // 多选模式：切换选中状态
                if (selectedGroups.contains(group)) {
                    selectedGroups.remove(group);
                } else {
                    selectedGroups.add(group);
                }
                notifyItemChanged(position);
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(group);
                }
            } else {
                // 单选模式：更新选中位置并显示
                int oldPosition = selectedPosition;
                selectedPosition = position;
                // 刷新旧选中项和新选中项
                if (oldPosition != -1 && oldPosition != position) {
                    notifyItemChanged(oldPosition);
                }
                notifyItemChanged(position);
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(group, position);
                }
            }
        });

        // 长按事件 - 分享图片
        holder.itemView.setOnLongClickListener(v -> {
            if (itemLongClickListener != null) {
                itemLongClickListener.onItemLongClick(group, position);
                return true;
            }
            return false;
        });
    }

    private void updateSelectionStyle(PhotoGroupViewHolder holder, boolean isSelected) {
        if (isSelected) {
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.item_selected_background));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }
    }

    private void loadThumbnail(File photoFile, ImageView imageView) {
        if (photoFile == null || !photoFile.exists()) {
            imageView.setImageDrawable(null);
            imageView.setBackgroundColor(0xFF1A1A1A);
            return;
        }

        RequestOptions options = new RequestOptions()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(photoFile.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);

        Glide.with(context)
                .load(photoFile)
                .apply(options)
                .into(imageView);
    }

    @Override
    public int getItemCount() {
        return flattenedItems.size();
    }

    /**
     * 日期头部 ViewHolder
     */
    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        ImageView expandIcon;
        TextView dateText;
        TextView itemCount;

        DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            expandIcon = itemView.findViewById(R.id.expand_icon);
            dateText = itemView.findViewById(R.id.date_text);
            itemCount = itemView.findViewById(R.id.item_count);
        }
    }

    /**
     * 图片组 ViewHolder
     */
    static class PhotoGroupViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbFront, thumbBack, thumbLeft, thumbRight;
        TextView videoDate, videoTime, videoSize, videoCountBadge;
        android.widget.CheckBox checkIndicator;

        PhotoGroupViewHolder(@NonNull View itemView) {
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
