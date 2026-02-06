package com.kooo.evcam;

import android.app.Dialog;
import android.content.res.AssetManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BlindSpotDisclaimerDialogFragment extends DialogFragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private int secondsLeft = 20;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_blind_spot_disclaimer, null, false);

        TextView tvDisclaimer = view.findViewById(R.id.tv_disclaimer);
        TextView tvCountdown = view.findViewById(R.id.tv_countdown);
        ImageView img = view.findViewById(R.id.img_douyin);
        Button btnAccept = view.findViewById(R.id.btn_accept);

        String markdown = readRawText(R.raw.blind_spot_disclaimer);
        SpannableString styled = buildStyledText(markdown);
        tvDisclaimer.setText(styled);

        loadAssetImage(img, "douyin.jpg");

        btnAccept.setEnabled(false);
        updateCountdownViews(tvCountdown, btnAccept);

        btnAccept.setOnClickListener(v -> {
            new AppConfig(requireContext()).setBlindSpotDisclaimerAccepted(true);
            dismissAllowingStateLoss();
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        setCancelable(false);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        startCountdown();
    }

    @Override
    public void onDestroyView() {
        stopCountdown();
        super.onDestroyView();
    }

    private void startCountdown() {
        stopCountdown();
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                Dialog dialog = getDialog();
                if (dialog == null) return;
                TextView tvCountdown = dialog.findViewById(R.id.tv_countdown);
                Button btnAccept = dialog.findViewById(R.id.btn_accept);
                if (tvCountdown == null || btnAccept == null) return;

                if (secondsLeft <= 0) {
                    btnAccept.setEnabled(true);
                    btnAccept.setText("我已知情并同意");
                    tvCountdown.setText("已阅读完毕，可点击同意继续");
                    return;
                }

                updateCountdownViews(tvCountdown, btnAccept);
                secondsLeft -= 1;
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(countdownRunnable, 0);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) {
            handler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void updateCountdownViews(TextView tvCountdown, Button btnAccept) {
        tvCountdown.setText("请阅读后等待 " + secondsLeft + " 秒");
        btnAccept.setText("我已知情并同意（" + secondsLeft + "s）");
    }

    private String readRawText(int resId) {
        try (InputStream is = requireContext().getResources().openRawResource(resId)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = is.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private SpannableString buildStyledText(String markdown) {
        String text = markdown == null ? "" : markdown.replace("**", "");
        SpannableString ss = new SpannableString(text);

        String[] highlightLines = new String[]{
                "严重安全警告 - 请仔细阅读",
                "可能导致死亡或严重伤害！！！",
                "严禁用于实际驾驶决策"
        };

        for (String needle : highlightLines) {
            int start = text.indexOf(needle);
            if (start >= 0) {
                int end = start + needle.length();
                ss.setSpan(new ForegroundColorSpan(Color.RED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return ss;
    }

    private void loadAssetImage(ImageView imageView, String assetName) {
        try {
            AssetManager am = requireContext().getAssets();
            try (InputStream is = am.open(assetName)) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
        } catch (Exception e) {
            imageView.setVisibility(View.GONE);
        }
    }
}

