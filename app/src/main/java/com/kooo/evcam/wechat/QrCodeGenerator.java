package com.kooo.evcam.wechat;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成工具
 * 用于生成设备绑定二维码
 */
public class QrCodeGenerator {

    /**
     * 生成二维码 Bitmap
     * @param content 二维码内容
     * @param size 二维码尺寸（像素）
     * @return 生成的二维码 Bitmap，失败返回 null
     */
    public static Bitmap generateQrCode(String content, int size) {
        return generateQrCode(content, size, Color.BLACK, Color.WHITE);
    }

    /**
     * 生成二维码 Bitmap（自定义颜色）
     * @param content 二维码内容
     * @param size 二维码尺寸（像素）
     * @param foregroundColor 前景色
     * @param backgroundColor 背景色
     * @return 生成的二维码 Bitmap，失败返回 null
     */
    public static Bitmap generateQrCode(String content, int size, int foregroundColor, int backgroundColor) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            
            // 设置二维码参数
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H); // 高容错级别
            hints.put(EncodeHintType.MARGIN, 1); // 边距

            // 生成二维码矩阵
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            // 将矩阵转换为 Bitmap
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = bitMatrix.get(x, y) ? foregroundColor : backgroundColor;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 生成设备绑定二维码
     * @param config 微信小程序配置
     * @param size 二维码尺寸
     * @return 二维码 Bitmap
     */
    public static Bitmap generateBindQrCode(WechatMiniConfig config, int size) {
        String qrData = config.getQrCodeData();
        return generateQrCode(qrData, size);
    }

    /**
     * 生成带品牌色的设备绑定二维码
     * @param config 微信小程序配置
     * @param size 二维码尺寸
     * @return 二维码 Bitmap
     */
    public static Bitmap generateBindQrCodeWithBranding(WechatMiniConfig config, int size) {
        String qrData = config.getQrCodeData();
        
        // 使用自定义颜色（深蓝色前景）
        int foregroundColor = Color.parseColor("#1a73e8");
        int backgroundColor = Color.WHITE;
        
        return generateQrCode(qrData, size, foregroundColor, backgroundColor);
    }
    
    /**
     * 生成微信小程序绑定二维码（带品牌色）
     * @param config 微信小程序配置
     * @param size 二维码尺寸
     * @return 二维码 Bitmap
     */
    public static Bitmap generateMiniProgramQrCode(WechatMiniConfig config, int size) {
        return generateBindQrCodeWithBranding(config, size);
    }
}
