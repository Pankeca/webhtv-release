package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogCacheCleanBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;

/**
 * 缓存管理弹窗（口径对齐 TVBox）：当前缓存 / 自动清缓存开关 / 缓存上限 / 立即清理。
 * 自动清理的触发时机在 App 启动时（见 FileUtil#autoClearCacheIfNeeded），这里只负责设置与手动清理。
 */
public final class CacheCleanDialog {

    private CacheCleanDialog() {
    }

    public static void show(FragmentActivity activity, Runnable onChanged) {
        DialogCacheCleanBinding binding = DialogCacheCleanBinding.inflate(LayoutInflater.from(activity));
        Dialog dialog = LightDialog.create(activity, null, binding.getRoot());
        binding.autoValue.setText(Setting.isAutoClearCache() ? R.string.cache_enabled : R.string.cache_disabled);
        binding.limitValue.setText(Setting.formatAutoClearCacheLimit(Setting.getAutoClearCacheLimitMb()));
        refreshSize(activity, binding);

        binding.autoRow.setOnClickListener(view -> {
            boolean enable = !Setting.isAutoClearCache();
            Setting.putAutoClearCache(enable);
            binding.autoValue.setText(enable ? R.string.cache_enabled : R.string.cache_disabled);
            Notify.show(enable ? R.string.cache_auto_on : R.string.cache_auto_off);
        });

        binding.limitRow.setOnClickListener(view -> {
            int[] limits = Setting.AUTO_CLEAR_CACHE_LIMITS;
            CharSequence[] items = new CharSequence[limits.length];
            int selected = 0;
            for (int i = 0; i < limits.length; i++) {
                items[i] = Setting.formatAutoClearCacheLimit(limits[i]);
                if (limits[i] == Setting.getAutoClearCacheLimitMb()) selected = i;
            }
            ChoiceDialog.showSingle(activity, R.string.cache_limit, items, selected, which -> {
                Setting.putAutoClearCacheLimitMb(limits[which]);
                binding.limitValue.setText(Setting.formatAutoClearCacheLimit(limits[which]));
            });
        });

        binding.clear.setOnClickListener(view -> FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                refreshSize(activity, binding);
                Notify.show(R.string.cache_cleared);
                if (onChanged != null) onChanged.run();
            }
        }));

        dialog.setOnDismissListener(d -> {
            if (onChanged != null) onChanged.run();
        });
        dialog.show();
    }

    /** 缓存大小在后台统计，避免弹窗打开时卡主线程。 */
    private static void refreshSize(Context context, DialogCacheCleanBinding binding) {
        binding.size.setText(R.string.cache_current_loading);
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                if (binding.getRoot().isAttachedToWindow()) binding.size.setText(context.getString(R.string.cache_current, result));
            }
        });
    }
}
