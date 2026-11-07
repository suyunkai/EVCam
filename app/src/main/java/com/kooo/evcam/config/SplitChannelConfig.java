package com.kooo.evcam.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 单通道分割配置。
 *
 * 部分车机（如 25 款星舰 7）会把 4 路摄像头合成到一路画面中输出，
 * 需要用户在画面上手动框选分割区域，之后按区域独立预览与编码。
 *
 * 区域坐标均为相对整幅画面的归一化值（0~1，左上角为原点）。
 */
public class SplitChannelConfig {

    /** 逻辑画面槽位，与录制文件命名（timestamp_front.mp4）保持一致 */
    public static final String[] SLOT_KEYS = {"front", "back", "left", "right"};

    /** 最多支持 4 个分割区域（与四宫格预览/播放界面保持一致） */
    public static final int MAX_REGIONS = 4;

    public static class Region {
        /** 逻辑槽位：front/back/left/right */
        public String key = "front";
        /** 画面编号（1~9），用于界面显示 */
        public int index = 1;
        /** 备注名称 */
        public String label = "";
        public float x = 0f;
        public float y = 0f;
        public float w = 0.5f;
        public float h = 0.5f;
        /** 编码/预览时的旋转角度：0/90/180/270 */
        public int rotation = 0;
        /** 是否需要左右镜像 */
        public boolean mirror = false;

        public Region() {
        }

        public Region(String key, int index, String label,
                      float x, float y, float w, float h) {
            this.key = key;
            this.index = index;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public Region copy() {
            Region r = new Region(key, index, label, x, y, w, h);
            r.rotation = rotation;
            r.mirror = mirror;
            return r;
        }

        /** 显示名称：有备注显示备注，没有备注显示编号 */
        public String displayName() {
            String name = label == null ? "" : label.trim();
            if (name.isEmpty()) {
                return String.valueOf(index);
            }
            return name;
        }

        /** 计算该区域在给定整幅画面中的像素矩形（宽高保证为偶数） */
        public int[] cropPixels(int frameWidth, int frameHeight) {
            int left = Math.round(x * frameWidth);
            int top = Math.round(y * frameHeight);
            int right = Math.round((x + w) * frameWidth);
            int bottom = Math.round((y + h) * frameHeight);

            left = clamp(left, 0, Math.max(0, frameWidth - 2));
            top = clamp(top, 0, Math.max(0, frameHeight - 2));
            right = clamp(right, left + 2, frameWidth);
            bottom = clamp(bottom, top + 2, frameHeight);

            int cw = ((right - left) / 2) * 2;
            int ch = ((bottom - top) / 2) * 2;
            return new int[]{left, top, Math.max(2, cw), Math.max(2, ch)};
        }

        /**
         * 录制/拍照输出分辨率：目标 1280x800，实际区域达不到时按实际区域分辨率，
         * 等比缩放（不拉伸），宽高保持偶数。
         */
        public int[] encodePixels(int frameWidth, int frameHeight) {
            int[] crop = cropPixels(frameWidth, frameHeight);
            int cropWidth = crop[2];
            int cropHeight = crop[3];
            if (cropWidth <= 0 || cropHeight <= 0) {
                return new int[]{2, 2};
            }
            float scale = Math.min(1f, Math.min(1280f / cropWidth, 800f / cropHeight));
            int outWidth = evenSize(Math.round(cropWidth * scale));
            int outHeight = evenSize(Math.round(cropHeight * scale));
            return new int[]{outWidth, outHeight};
        }

        private static int evenSize(int value) {
            int even = (value / 2) * 2;
            return Math.max(2, even);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("key", key);
                o.put("index", index);
                o.put("label", label == null ? "" : label);
                o.put("x", x);
                o.put("y", y);
                o.put("w", w);
                o.put("h", h);
                o.put("rotation", rotation);
                o.put("mirror", mirror);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Region fromJson(JSONObject o) {
            Region r = new Region();
            r.key = o.optString("key", "front");
            r.index = o.optInt("index", 1);
            r.label = o.optString("label", "");
            // 兼容旧版本的默认名称“画面N”，视为未填写备注
            if (r.label != null && r.label.trim().equals("画面" + r.index)) {
                r.label = "";
            }
            r.x = (float) o.optDouble("x", 0);
            r.y = (float) o.optDouble("y", 0);
            r.w = (float) o.optDouble("w", 0.5);
            r.h = (float) o.optDouble("h", 0.5);
            r.rotation = o.optInt("rotation", 0);
            r.mirror = o.optBoolean("mirror", false);
            return r;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** 物理摄像头 ID（单通道对应的那一路） */
    public String cameraId = "0";
    public final List<Region> regions = new ArrayList<>();

    public boolean isValid() {
        return regions != null && !regions.isEmpty();
    }

    public Region getRegion(String position) {
        if (regions == null || position == null) {
            return null;
        }
        for (Region region : regions) {
            if (position.equals(region.key)) {
                return region;
            }
        }
        return null;
    }

    public String getDisplayName(String position) {
        Region region = getRegion(position);
        if (region == null) {
            return null;
        }
        return region.displayName();
    }

    public String toJsonString() {
        JSONObject root = new JSONObject();
        try {
            root.put("cameraId", cameraId == null ? "0" : cameraId);
            JSONArray array = new JSONArray();
            for (Region region : regions) {
                array.put(region.toJson());
            }
            root.put("regions", array);
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    public static SplitChannelConfig fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            SplitChannelConfig config = new SplitChannelConfig();
            config.cameraId = root.optString("cameraId", "0");
            JSONArray array = root.optJSONArray("regions");
            if (array != null) {
                for (int i = 0; i < array.length() && config.regions.size() < MAX_REGIONS; i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null) {
                        config.regions.add(Region.fromJson(o));
                    }
                }
            }
            return config.isValid() ? config : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 默认四等分（横向长条：4 个画面左右并排，按宽度 4 等分），默认不写备注，主页显示编号 */
    public static SplitChannelConfig defaultQuad() {
        SplitChannelConfig config = new SplitChannelConfig();
        config.cameraId = "0";
        config.regions.add(new Region(SLOT_KEYS[0], 1, "", 0f, 0f, 0.25f, 1f));
        config.regions.add(new Region(SLOT_KEYS[1], 2, "", 0.25f, 0f, 0.25f, 1f));
        config.regions.add(new Region(SLOT_KEYS[2], 3, "", 0.5f, 0f, 0.25f, 1f));
        config.regions.add(new Region(SLOT_KEYS[3], 4, "", 0.75f, 0f, 0.25f, 1f));
        return config;
    }

    /** 按列表顺序重新分配槽位 key，保证最多 4 个且合法 */
    public void normalizeSlots() {
        int count = Math.min(regions.size(), MAX_REGIONS);
        while (regions.size() > MAX_REGIONS) {
            regions.remove(regions.size() - 1);
        }
        for (int i = 0; i < count; i++) {
            regions.get(i).key = SLOT_KEYS[i];
        }
    }
}
