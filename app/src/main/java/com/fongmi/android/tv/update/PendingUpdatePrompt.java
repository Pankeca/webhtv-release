package com.fongmi.android.tv.update;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 启动时提示「已经下好但还没装」的更新包。
 *
 * <p>场景：用户点了更新、包也下完校验过了，但在系统安装器里点了取消或者直接退出来。
 * 旧实现什么都不记，用户下次启动完全不知道有个包躺在缓存里，再点检查更新又会重下 150MB。
 *
 * <p>一个进程只提示一次（{@link #shown}），避免每次回到首页都弹。
 * 包如果真的被装上了，本机 versionCode 变大，{@link PendingUpdate#file()} 会自动把记录清掉，
 * 下次就不会再弹。
 */
public final class PendingUpdatePrompt {

    private static boolean shown;

    private PendingUpdatePrompt() {
    }

    public static void showOnce(FragmentActivity activity) {
        if (shown || activity == null || activity.isFinishing()) return;
        File file = PendingUpdate.file();
        if (file == null) return;
        shown = true;
        String version = PendingUpdate.version();
        String message = version.isEmpty() ? ResUtil.getString(R.string.update_pending_message_plain) : ResUtil.getString(R.string.update_pending_message, version);
        new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.update_pending_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_pending_install, (dialog, which) -> ApkInstall.open(activity, file))
                .setNegativeButton(R.string.update_pending_later, null)
                .show();
    }
}
