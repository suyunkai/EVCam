package com.kooo.evcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * 内置图片查看器
 * 支持缩放、拖动等手势操作
 */
public class PhotoViewerActivity extends AppCompatActivity {
    private ImageView imageView;
    private TextView titleText;

    // 手势相关
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;
    private float minScale = 0.5f;
    private float maxScale = 4.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_viewer);

        imageView = findViewById(R.id.photo_image);
        titleText = findViewById(R.id.photo_title);
        View btnClose = findViewById(R.id.btn_close);

        // 获取图片路径
        String photoPath = getIntent().getStringExtra("photo_path");
        if (photoPath == null || photoPath.isEmpty()) {
            Toast.makeText(this, "无效的图片路径", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File photoFile = new File(photoPath);
        if (!photoFile.exists()) {
            Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 设置标题
        titleText.setText(photoFile.getName());

        // 加载图片
        loadPhoto(photoFile);

        // 关闭按钮
        btnClose.setOnClickListener(v -> finish());

        // 设置触摸监听器
        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ImageView view = (ImageView) v;

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        savedMatrix.set(matrix);
                        start.set(event.getX(), event.getY());
                        mode = DRAG;
                        break;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDist = spacing(event);
                        if (oldDist > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(mid, event);
                            mode = ZOOM;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        mode = NONE;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (mode == DRAG) {
                            matrix.set(savedMatrix);
                            matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                        } else if (mode == ZOOM) {
                            float newDist = spacing(event);
                            if (newDist > 10f) {
                                matrix.set(savedMatrix);
                                float scale = newDist / oldDist;

                                // 限制缩放范围
                                float[] values = new float[9];
                                matrix.getValues(values);
                                float currentScale = values[Matrix.MSCALE_X];

                                if (currentScale * scale < minScale) {
                                    scale = minScale / currentScale;
                                } else if (currentScale * scale > maxScale) {
                                    scale = maxScale / currentScale;
                                }

                                matrix.postScale(scale, scale, mid.x, mid.y);
                            }
                        }
                        break;
                }

                view.setImageMatrix(matrix);
                return true;
            }
        });
    }

    /**
     * 加载图片
     */
    private void loadPhoto(File photoFile) {
        try {
            // 先获取图片尺寸
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);

            // 计算缩放比例（避免加载过大的图片导致OOM）
            int maxSize = 2048;
            int scaleFactor = 1;
            if (options.outWidth > maxSize || options.outHeight > maxSize) {
                scaleFactor = Math.max(
                    options.outWidth / maxSize,
                    options.outHeight / maxSize
                );
            }

            // 加载图片
            options.inJustDecodeBounds = false;
            options.inSampleSize = scaleFactor;
            Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
                imageView.setScaleType(ImageView.ScaleType.MATRIX);

                // 初始化矩阵，使图片居中显示
                imageView.post(() -> {
                    int viewWidth = imageView.getWidth();
                    int viewHeight = imageView.getHeight();
                    int bitmapWidth = bitmap.getWidth();
                    int bitmapHeight = bitmap.getHeight();

                    float scale = Math.min(
                        (float) viewWidth / bitmapWidth,
                        (float) viewHeight / bitmapHeight
                    );

                    matrix.setScale(scale, scale);
                    matrix.postTranslate(
                        (viewWidth - bitmapWidth * scale) / 2,
                        (viewHeight - bitmapHeight * scale) / 2
                    );
                    imageView.setImageMatrix(matrix);
                });
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            Toast.makeText(this, "加载图片失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * 计算两点间距离
     */
    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    /**
     * 计算两点中点
     */
    private void midPoint(PointF point, MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }
}
