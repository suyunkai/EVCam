package com.kooo.evcam.transfer;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成器
 */
public class QrCodeGenerator {

    /**
     * 生成二维码位图
     *
     * @param content 二维码内容
     * @param width   宽度
     * @param height  高度
     * @return 二维码位图
     */
    public static Bitmap generateQRCode(String content, int width, int height) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 生成带 Logo 的二维码
     *
     * @param content 二维码内容
     * @param width   宽度
     * @param height  高度
     * @param logo    Logo 位图
     * @return 二维码位图
     */
    public static Bitmap generateQRCodeWithLogo(String content, int width, int height, Bitmap logo) {
        Bitmap qrCode = generateQRCode(content, width, height);
        if (qrCode == null || logo == null) {
            return qrCode;
        }

        // 在二维码中心添加 Logo
        Bitmap result = qrCode.copy(Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(result);

        int logoSize = width / 5;
        Bitmap scaledLogo = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true);

        int left = (width - logoSize) / 2;
        int top = (height - logoSize) / 2;
        canvas.drawBitmap(scaledLogo, left, top, null);

        return result;
    }
}
