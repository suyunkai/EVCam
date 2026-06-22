package com.kooo.evcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.kooo.evcam.config.SplitChannelConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 单通道分割编辑器。
 * 在实时画面上直接拖拽绘制矩形区域，并支持拖动/缩放/编号备注。
 */
public class SplitViewEditorView extends View {

    public interface Listener {
        void onSelectionChanged(SplitChannelConfig.Region region);

        void onRegionsChanged();
    }

    private static final float MIN_SIZE = 0.04f;
    private static final int MAX_REGIONS = SplitChannelConfig.MAX_REGIONS;

    private enum Mode {NONE, MOVE, RESIZE, CREATE}

    private final List<SplitChannelConfig.Region> regions = new ArrayList<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();

    private int selectedIndex = -1;
    private Mode mode = Mode.NONE;
    private int resizeCorner = -1;
    private float lastNormX, lastNormY;
    private boolean panMode = false;

    private Listener listener;

    public SplitViewEditorView(Context context) {
        super(context);
        init();
    }

    public SplitViewEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(3));
        badgePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(sp(15));
        textPaint.setShadowLayer(2, 1, 1, Color.BLACK);
        hintPaint.setColor(0xCCFFFFFF);
        hintPaint.setTextSize(sp(18));
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 平移模式：触摸事件交给外层 HorizontalScrollView，用于左右拖动查看整条长画面
     */
    public void setPanMode(boolean panMode) {
        this.panMode = panMode;
        mode = Mode.NONE;
    }

    public boolean isPanMode() {
        return panMode;
    }

    public void setRegions(List<SplitChannelConfig.Region> source) {
        regions.clear();
        if (source != null) {
            for (SplitChannelConfig.Region region : source) {
                if (regions.size() >= MAX_REGIONS) {
                    break;
                }
                regions.add(region.copy());
            }
        }
        selectedIndex = regions.isEmpty() ? -1 : 0;
        invalidate();
        notifySelection();
    }

    public List<SplitChannelConfig.Region> getRegions() {
        List<SplitChannelConfig.Region> copy = new ArrayList<>();
        for (SplitChannelConfig.Region region : regions) {
            copy.add(region.copy());
        }
        return copy;
    }

    public SplitChannelConfig.Region getSelectedRegion() {
        if (selectedIndex >= 0 && selectedIndex < regions.size()) {
            return regions.get(selectedIndex);
        }
        return null;
    }

    public void addDefaultRegion() {
        if (regions.size() >= MAX_REGIONS) {
            return;
        }
        SplitChannelConfig.Region region = new SplitChannelConfig.Region();
        region.w = 0.32f;
        region.h = 0.32f;
        region.x = clamp(0.5f - region.w / 2f + 0.03f * regions.size(), 0f, 1f - region.w);
        region.y = clamp(0.5f - region.h / 2f + 0.03f * regions.size(), 0f, 1f - region.h);
        region.index = nextIndex();
        region.label = "";
        regions.add(region);
        selectedIndex = regions.size() - 1;
        invalidate();
        notifySelection();
        notifyRegionsChanged();
    }

    public void applyQuadLayout() {
        regions.clear();
        regions.add(newRegion(1, 0f, 0f, 0.25f, 1f));
        regions.add(newRegion(2, 0.25f, 0f, 0.25f, 1f));
        regions.add(newRegion(3, 0.5f, 0f, 0.25f, 1f));
        regions.add(newRegion(4, 0.75f, 0f, 0.25f, 1f));
        selectedIndex = 0;
        invalidate();
        notifySelection();
        notifyRegionsChanged();
    }

    private SplitChannelConfig.Region newRegion(int index, float x, float y, float w, float h) {
        SplitChannelConfig.Region region = new SplitChannelConfig.Region();
        region.index = index;
        region.label = "";
        region.x = x;
        region.y = y;
        region.w = w;
        region.h = h;
        return region;
    }

    public void removeSelectedRegion() {
        if (selectedIndex >= 0 && selectedIndex < regions.size()) {
            regions.remove(selectedIndex);
            selectedIndex = regions.isEmpty() ? -1 : Math.min(selectedIndex, regions.size() - 1);
            invalidate();
            notifySelection();
            notifyRegionsChanged();
        }
    }

    public void setSelectedRegionNumberAndLabel(int number, String label) {
        SplitChannelConfig.Region region = getSelectedRegion();
        if (region == null) {
            return;
        }
        region.index = number;
        region.label = label;
        invalidate();
        notifySelection();
        notifyRegionsChanged();
    }

    private int nextIndex() {
        int max = 0;
        for (SplitChannelConfig.Region region : regions) {
            max = Math.max(max, region.index);
        }
        return Math.min(9, max + 1);
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (regions.isEmpty()) {
            canvas.drawText("在画面上拖动画出第一个分割区域", getWidth() / 2f, getHeight() / 2f, hintPaint);
            return;
        }

        for (int i = 0; i < regions.size(); i++) {
            SplitChannelConfig.Region region = regions.get(i);
            boolean selected = (i == selectedIndex);
            drawRect.set(region.x * getWidth(), region.y * getHeight(),
                    (region.x + region.w) * getWidth(), (region.y + region.h) * getHeight());

            fillPaint.setColor(selected ? 0x33FFB300 : 0x2200E676);
            canvas.drawRect(drawRect, fillPaint);

            strokePaint.setColor(selected ? 0xFFFFB300 : 0xFF00E676);
            canvas.drawRect(drawRect, strokePaint);

            // 编号徽标
            float radius = dp(18);
            float cx = drawRect.left + radius + dp(4);
            float cy = drawRect.top + radius + dp(4);
            badgePaint.setColor(selected ? 0xFFFFB300 : 0xFF00A651);
            canvas.drawCircle(cx, cy, radius, badgePaint);
            Paint numberPaint = textPaint;
            numberPaint.setTextAlign(Paint.Align.CENTER);
            numberPaint.setTextSize(sp(15));
            canvas.drawText(String.valueOf(region.index), cx, cy + sp(5), numberPaint);

            // 备注名称
            String label = region.label == null ? "" : region.label.trim();
            if (!label.isEmpty()) {
                numberPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(label, cx + radius + dp(8), cy + sp(5), numberPaint);
            }

            if (selected) {
                drawHandles(canvas, drawRect);
            }
        }
    }

    private void drawHandles(Canvas canvas, RectF rect) {
        badgePaint.setColor(0xFFFFB300);
        float r = dp(7);
        canvas.drawCircle(rect.left, rect.top, r, badgePaint);
        canvas.drawCircle(rect.right, rect.top, r, badgePaint);
        canvas.drawCircle(rect.left, rect.bottom, r, badgePaint);
        canvas.drawCircle(rect.right, rect.bottom, r, badgePaint);
    }

    // ==================== 触摸 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (panMode) {
            return false;  // 交给外层横向滚动容器处理
        }
        float nx = clamp(event.getX() / Math.max(1f, getWidth()), 0f, 1f);
        float ny = clamp(event.getY() / Math.max(1f, getHeight()), 0f, 1f);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                int corner = findHandle(nx, ny);
                if (corner >= 0) {
                    mode = Mode.RESIZE;
                    resizeCorner = corner;
                    lastNormX = nx;
                    lastNormY = ny;
                    return true;
                }
                int hit = findRegion(nx, ny);
                if (hit >= 0) {
                    selectedIndex = hit;
                    mode = Mode.MOVE;
                    lastNormX = nx;
                    lastNormY = ny;
                    invalidate();
                    notifySelection();
                    return true;
                }
                if (regions.size() < MAX_REGIONS) {
                    SplitChannelConfig.Region region = new SplitChannelConfig.Region();
                    region.x = nx;
                    region.y = ny;
                    region.w = MIN_SIZE;
                    region.h = MIN_SIZE;
                    region.index = nextIndex();
                    region.label = "";
                    regions.add(region);
                    selectedIndex = regions.size() - 1;
                    mode = Mode.CREATE;
                    lastNormX = nx;
                    lastNormY = ny;
                    invalidate();
                    notifySelection();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                SplitChannelConfig.Region region = getSelectedRegion();
                if (region == null) {
                    return false;
                }
                float dx = nx - lastNormX;
                float dy = ny - lastNormY;
                if (mode == Mode.MOVE) {
                    region.x = clamp(region.x + dx, 0f, 1f - region.w);
                    region.y = clamp(region.y + dy, 0f, 1f - region.h);
                } else if (mode == Mode.RESIZE) {
                    resize(region, resizeCorner, nx, ny);
                } else if (mode == Mode.CREATE) {
                    float left = Math.min(lastNormX, nx);
                    float top = Math.min(lastNormY, ny);
                    float right = Math.max(lastNormX, nx);
                    float bottom = Math.max(lastNormY, ny);
                    region.x = left;
                    region.y = top;
                    region.w = Math.max(MIN_SIZE, right - left);
                    region.h = Math.max(MIN_SIZE, bottom - top);
                }
                lastNormX = nx;
                lastNormY = ny;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == Mode.CREATE) {
                    SplitChannelConfig.Region region = getSelectedRegion();
                    if (region != null && (region.w < MIN_SIZE * 1.5f || region.h < MIN_SIZE * 1.5f)) {
                        regions.remove(selectedIndex);
                        selectedIndex = regions.isEmpty() ? -1 : Math.min(selectedIndex, regions.size() - 1);
                    }
                }
                mode = Mode.NONE;
                resizeCorner = -1;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                invalidate();
                notifySelection();
                notifyRegionsChanged();
                return true;
            }
            default:
                return false;
        }
    }

    private void resize(SplitChannelConfig.Region region, int corner, float nx, float ny) {
        float right = region.x + region.w;
        float bottom = region.y + region.h;
        switch (corner) {
            case 0: // left-top
                region.x = clamp(nx, 0f, right - MIN_SIZE);
                region.y = clamp(ny, 0f, bottom - MIN_SIZE);
                break;
            case 1: // right-top
                region.w = clamp(nx, region.x + MIN_SIZE, 1f) - region.x;
                region.y = clamp(ny, 0f, bottom - MIN_SIZE);
                break;
            case 2: // left-bottom
                region.x = clamp(nx, 0f, right - MIN_SIZE);
                region.h = clamp(ny, region.y + MIN_SIZE, 1f) - region.y;
                break;
            case 3: // right-bottom
                region.w = clamp(nx, region.x + MIN_SIZE, 1f) - region.x;
                region.h = clamp(ny, region.y + MIN_SIZE, 1f) - region.y;
                break;
            default:
                break;
        }
    }

    private int findHandle(float nx, float ny) {
        SplitChannelConfig.Region region = getSelectedRegion();
        if (region == null) {
            return -1;
        }
        float radius = dp(28) / Math.max(1f, Math.min(getWidth(), getHeight()));
        float[][] corners = {
                {region.x, region.y},
                {region.x + region.w, region.y},
                {region.x, region.y + region.h},
                {region.x + region.w, region.y + region.h}
        };
        for (int i = 0; i < corners.length; i++) {
            if (Math.abs(nx - corners[i][0]) <= radius && Math.abs(ny - corners[i][1]) <= radius) {
                return i;
            }
        }
        return -1;
    }

    private int findRegion(float nx, float ny) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            SplitChannelConfig.Region region = regions.get(i);
            if (nx >= region.x && nx <= region.x + region.w
                    && ny >= region.y && ny <= region.y + region.h) {
                return i;
            }
        }
        return -1;
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(getSelectedRegion());
        }
    }

    private void notifyRegionsChanged() {
        if (listener != null) {
            listener.onRegionsChanged();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
