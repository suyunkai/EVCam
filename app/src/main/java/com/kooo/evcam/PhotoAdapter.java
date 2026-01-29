package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * 照片列表适配器
 */
public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
    private final Context context;
    private final List<File> photoFiles;
    private OnPhotoDeleteListener deleteListener;
    private boolean isMultiSelectMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();
    private OnItemSelectedListener itemSelectedListener;

    public interface OnPhotoDeleteListener {
        void onPhotoDeleted();
    }

    public interface OnItemSelectedListener {
        void onItemSelected(int position);
    }

    public PhotoAdapter(Context context, List<File> photoFiles) {
        this.context = context;
        this.photoFiles = photoFiles;
    }

    public void setOnPhotoDeleteListener(OnPhotoDeleteListener listener) {
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
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new PhotoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        File photoFile = photoFiles.get(position);

        holder.photoName.setText(photoFile.getName());

        long sizeInBytes = photoFile.length();
        String sizeStr;
        if (sizeInBytes < 1024) {
            sizeStr = sizeInBytes + " B";
        } else if (sizeInBytes < 1024 * 1024) {
            sizeStr = String.format(Locale.getDefault(), "%.2f KB", sizeInBytes / 1024.0);
        } else {
            sizeStr = String.format(Locale.getDefault(), "%.2f MB", sizeInBytes / (1024.0 * 1024.0));
        }
        holder.photoSize.setText(sizeStr);

        long lastModified = photoFile.lastModified();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(lastModified));
        holder.photoDate.setText(dateStr);

        loadThumbnail(photoFile, holder.photoThumbnail);

        boolean isSelected = selectedPositions.contains(position);
        updateSelectionStyle(holder, isSelected);

        if (isMultiSelectMode) {
            holder.itemView.setOnClickListener(v -> {
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(position);
                }
            });
            holder.btnView.setEnabled(false);
            holder.btnDelete.setEnabled(false);
        } else {
            holder.itemView.setOnClickListener(null);
            holder.btnView.setEnabled(true);
            holder.btnDelete.setEnabled(true);

            holder.btnView.setOnClickListener(v -> {
                Intent intent = new Intent(context, PhotoViewerActivity.class);
                intent.putExtra("photo_path", photoFile.getAbsolutePath());
                context.startActivity(intent);
            });

            holder.btnDelete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle("确认删除")
                        .setMessage("确定要删除 " + photoFile.getName() + " 吗？")
                        .setPositiveButton("删除", (dialog, which) -> {
                            if (photoFile.delete()) {
                                photoFiles.remove(position);
                                notifyItemRemoved(position);
                                notifyItemRangeChanged(position, photoFiles.size());
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();

                                if (deleteListener != null) {
                                    deleteListener.onPhotoDeleted();
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

    private void updateSelectionStyle(PhotoViewHolder holder, boolean isSelected) {
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
        return photoFiles.size();
    }

    /**
     * 异步加载照片缩略图
     */
    private void loadThumbnail(File photoFile, ImageView imageView) {
        // 使用AsyncTask在后台线程加载缩略图
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try {
                    // 检查文件是否存在且大小大于0
                    if (!photoFile.exists() || photoFile.length() == 0) {
                        return null;
                    }

                    // 先获取图片尺寸
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);

                    // 计算缩放比例
                    int targetWidth = 300;
                    int targetHeight = 300;
                    int scaleFactor = Math.min(
                            options.outWidth / targetWidth,
                            options.outHeight / targetHeight
                    );

                    // 加载缩略图
                    options.inJustDecodeBounds = false;
                    options.inSampleSize = scaleFactor > 0 ? scaleFactor : 1;

                    return BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                } else {
                    // 如果无法加载缩略图，显示默认图标
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            }
        }.execute();
    }

    static class PhotoViewHolder extends RecyclerView.ViewHolder {
        ImageView photoThumbnail;
        TextView photoName;
        TextView photoSize;
        TextView photoDate;
        Button btnView;
        Button btnDelete;

        public PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            photoThumbnail = itemView.findViewById(R.id.photo_thumbnail);
            photoName = itemView.findViewById(R.id.photo_name);
            photoSize = itemView.findViewById(R.id.photo_size);
            photoDate = itemView.findViewById(R.id.photo_date);
            btnView = itemView.findViewById(R.id.btn_view);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
