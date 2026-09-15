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
 * 图片分组列表适配器
 */
public class PhotoGroupAdapter extends RecyclerView.Adapter<PhotoGroupAdapter.ViewHolder> {

    private final Context context;
    private final List<PhotoGroup> photoGroups;
    private int selectedPosition = -1;
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();

    private OnItemClickListener itemClickListener;
    private OnItemSelectedListener itemSelectedListener;

    public interface OnItemClickListener {
        void onItemClick(PhotoGroup group, int position);
    }

    public interface OnItemSelectedListener {
        void onItemSelected(int position);
    }

    public PhotoGroupAdapter(Context context, List<PhotoGroup> photoGroups) {
        this.context = context;
        this.photoGroups = photoGroups;
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

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video_group, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PhotoGroup group = photoGroups.get(position);

        holder.videoDate.setText(group.getFormattedDate());
        holder.videoTime.setText(group.getFormattedTime());
        holder.videoSize.setText(group.getFormattedSize());

        int count = group.getPhotoCount();
        holder.videoCountBadge.setText(count + " photos");

        loadThumbnail(group.getFrontPhoto(), holder.thumbFront);
        loadThumbnail(group.getBackPhoto(), holder.thumbBack);
        loadThumbnail(group.getLeftPhoto(), holder.thumbLeft);
        loadThumbnail(group.getRightPhoto(), holder.thumbRight);

        boolean isSelected = (position == selectedPosition) || selectedPositions.contains(position);
        updateSelectionStyle(holder, isSelected);

        if (isMultiSelectMode) {
            holder.checkIndicator.setVisibility(selectedPositions.contains(position) ? View.VISIBLE : View.GONE);
        } else {
            holder.checkIndicator.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(position);
                }
            } else {
                setSelectedPosition(position);
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(group, position);
                }
            }
        });
    }

    private void updateSelectionStyle(ViewHolder holder, boolean isSelected) {
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
        return photoGroups.size();
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
