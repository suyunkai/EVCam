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
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 照片列表适配器
 */
public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
    private final Context context;
    private final List<File> photoFiles;
    private OnPhotoDeleteListener deleteListener;

    public interface OnPhotoDeleteListener {
        void onPhotoDeleted();
    }

    public PhotoAdapter(Context context, List<File> photoFiles) {
        this.context = context;
        this.photoFiles = photoFiles;
    }

    public void setOnPhotoDeleteListener(OnPhotoDeleteListener listener) {
        this.deleteListener = listener;
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

        // 设置文件名
        holder.photoName.setText(photoFile.getName());

        // 设置文件大小
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

        // 设置修改日期
        long lastModified = photoFile.lastModified();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(lastModified));
        holder.photoDate.setText(dateStr);

        // 加载照片缩略图（异步）
        loadThumbnail(photoFile, holder.photoThumbnail);

        // 查看按钮 - 使用内置图片查看器
        holder.btnView.setOnClickListener(v -> {
            Intent intent = new Intent(context, PhotoViewerActivity.class);
            intent.putExtra("photo_path", photoFile.getAbsolutePath());
            context.startActivity(intent);
        });

        // 删除按钮
        holder.btnDelete.setOnClickListener(v -> {
            new AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog_Alert)
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
