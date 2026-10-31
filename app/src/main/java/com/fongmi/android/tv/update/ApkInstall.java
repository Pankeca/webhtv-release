package com.fongmi.android.tv.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;

import java.io.File;

/**
 * 打开安装器前先看一眼「未知来源应用安装」有没有放开。
 *
 * <p>为什么需要——{@link FileUtil#openFile} 直接 {@code ACTION_VIEW} 一个 apk，
 * 系统在没授权时会弹一个错误提示并把用户丢在安装器外面，看上去像「更新包坏了」。
 * 这里先判断，没授权就跳到本应用对应的授权页，用户回来再点一次即可。
 *
 * <p>Android 8.0（API 26）起才有按应用授权的概念，低版本按老规矩直接放行。
 */
public final class ApkInstall {

    private ApkInstall() {
    }

    public static void open(Context context, File file) {
        if (file == null || !file.exists()) return;
        if (canInstall(context)) {
            FileUtil.openFile(file);
            return;
        }
        Notify.show(com.fongmi.android.tv.R.string.update_install_permission);
        requestPermission(context);
    }

    private static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return context.getPackageManager().canRequestPackageInstalls();
    }

    private static void requestPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }
}
