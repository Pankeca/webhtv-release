package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.databinding.DialogAboutBinding;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.ResUtil;

public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(FragmentActivity activity, Runnable updateAction) {
        DialogAboutBinding binding = DialogAboutBinding.inflate(LayoutInflater.from(activity));
        binding.version.setText(activity.getString(R.string.about_version, AppVersion.fullName(), BuildConfig.FLAVOR_mode, BuildConfig.FLAVOR_abi));
        configureContentHeight(activity, binding);

        Dialog dialog = LightDialog.create(activity, null, binding.getRoot());
        binding.confirm.setOnClickListener(v -> dialog.dismiss());
        binding.checkUpdate.setOnClickListener(v -> {
            // 正在下载时不要重新检查、更不要重下：直接把进度条接回来
            if (Updater.create().isDownloading()) {
                showProgress(activity, dialog, binding);
                return;
            }
            binding.checkUpdate.setEnabled(false);
            binding.checkUpdate.setText(R.string.update_check);
            binding.updateHint.setVisibility(android.view.View.GONE);
            Updater.create().check(activity, update -> {
                if (!dialog.isShowing()) return;
                binding.checkUpdate.setEnabled(true);
                binding.checkUpdate.setText(R.string.about_check_update);
                // 已是最新 / 检查失败时 doInBackground 已经弹过提示，这里不再重复
                if (update == null || !update.hasUpdate()) return;
                // 优先展示清单里的 versionName，缺失时回退到资产名（仅兜底）
                String version = update.getVersionLabel();
                binding.updateHint.setText(activity.getString(R.string.about_update_available, version));
                binding.updateHint.setVisibility(android.view.View.VISIBLE);
                binding.updateHint.setOnClickListener(v2 -> showProgress(activity, dialog, binding));
            });
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        configureWindow(activity, dialog);
        binding.confirm.requestFocus();
    }

    /**
     * 切到「下载中」并接上进度。
     *
     * <p>已有下载在跑时走 {@link Updater#attachProgress}：只回放最近一次进度快照，
     * 不重新拉清单、不重开文件、不会把已经下了一半的包清掉重下。
     */
    private static void showProgress(FragmentActivity activity, Dialog dialog, DialogAboutBinding binding) {
        binding.updateHint.setEnabled(false);
        binding.updateHint.setVisibility(android.view.View.GONE);
        binding.updateProgressWrap.setVisibility(android.view.View.VISIBLE);
        binding.updateProgress.setVisibility(android.view.View.VISIBLE);
        binding.updateProgress.setProgress(0);
        binding.updateStatus.setText(R.string.update_downloading_start);
        Updater.DownloadListener listener = new Updater.DownloadListener() {
            @Override
            public void onProgress(int progress, long bytes, long total, long speed, long elapsed) {
                if (!dialog.isShowing()) return;
                binding.updateProgress.setProgress(Math.max(0, Math.min(100, progress)));
                if (total > 0) {
                    String size = android.text.format.Formatter.formatFileSize(activity, bytes) + " / " + android.text.format.Formatter.formatFileSize(activity, total);
                    binding.updateStatus.setText(activity.getString(R.string.update_downloading_progress, progress, size));
                } else {
                    binding.updateStatus.setText(activity.getString(R.string.update_downloading_percent, progress));
                }
            }

            @Override
            public void onComplete() {
                if (dialog.isShowing()) dialog.dismiss();
            }

            @Override
            public void onError(String message) {
                if (!dialog.isShowing()) return;
                binding.updateProgress.setVisibility(android.view.View.GONE);
                binding.updateStatus.setText(message);
                binding.updateHint.setEnabled(true);
                binding.updateHint.setVisibility(android.view.View.VISIBLE);
            }
        };
        if (Updater.create().attachProgress(listener)) return;
        Updater.create().downloadStable(activity, listener);
    }

    private static void configureContentHeight(FragmentActivity activity, DialogAboutBinding binding) {
        int screenHeight = ResUtil.getScreenHeight(activity);
        int maxHeight = (int) (screenHeight * (ResUtil.isLand(activity) ? 0.42f : 0.38f));
        int minHeight = ResUtil.dp2px(220);
        ViewGroup.LayoutParams params = binding.contentScroll.getLayoutParams();
        params.height = Math.max(minHeight, Math.min(maxHeight, ResUtil.dp2px(420)));
        binding.contentScroll.setLayoutParams(params);
    }

    private static boolean configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return false;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
        return true;
    }
}
