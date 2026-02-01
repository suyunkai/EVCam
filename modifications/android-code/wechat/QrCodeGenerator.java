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
     * 生成带中心Logo的二维码
     * @param content 二维码内容
     * @param size 二维码尺寸（像素）
     * @param logo 中心Logo Bitmap
     * @return 生成的二维码 Bitmap，失败返回 null
     */
    public static Bitmap generateQrCodeWithLogo(String content, int size, Bitmap logo) {
        Bitmap qrCode = generateQrCode(content, size);
        if (qrCode == null || logo == null) {
            return qrCode;
        }

        try {
            // 创建可编辑的 Bitmap
            Bitmap result = qrCode.copy(Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(result);

            // 计算 Logo 位置和大小（Logo 占二维码的 20%）
            int logoSize = size / 5;
            int logoX = (size - logoSize) / 2;
            int logoY = (size - logoSize) / 2;

            // 缩放 Logo
            Bitmap scaledLogo = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true);

            // 绘制白色背景（避免Logo与二维码重叠）
            android.graphics.Paint bgPaint = new android.graphics.Paint();
            bgPaint.setColor(Color.WHITE);
            int padding = logoSize / 10;
            canvas.drawRect(
                    logoX - padding,
                    logoY - padding,
                    logoX + logoSize + padding,
                    logoY + logoSize + padding,
                    bgPaint
            );

            // 绘制 Logo
            canvas.drawBitmap(scaledLogo, logoX, logoY, null);

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return qrCode;
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
     * 生成带 EVCam 标识的设备绑定二维码
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
     * 生成微信小程序绑定二维码
     * 使用 JSON 格式，需要在小程序内扫码识别
     * 
     * @param config 微信小程序配置
     * @param size 二维码尺寸
     * @return 二维码 Bitmap
     */
    public static Bitmap generateMiniProgramQrCode(WechatMiniConfig config, int size) {
        // 直接使用 JSON 格式的绑定数据
        // 用户需要在微信小程序内使用扫码功能
        return generateBindQrCodeWithBranding(config, size);
    }
}
