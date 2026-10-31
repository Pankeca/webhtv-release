package com.fongmi.android.tv.update;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Update;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;

/**
 * 「已下载但还没装」的更新包。
 *
 * <p>为什么需要——下载完是交给系统安装器的，用户完全可能点取消或者在安装器里退出来。
 * 旧实现什么都不记，于是：下次启动不会有任何提示，用户再点「检查更新」又会把整个包重下一遍。
 *
 * <p>这里只记最小必要信息（版本号、版本名、大小），包固定放在 {@code cache/update.apk}。
 * 判断可用时同时校验文件还在、大小一致；只要有一条不满足就自动清掉，避免拿半个包去装。
 *
 * <p>装成功之后本机 versionCode 会变大，{@link #file()} 里 {@code code <= VERSION_CODE}
 * 的分支会顺手把记录清掉，不需要额外挂安装结果回调。
 */
public final class PendingUpdate {

    private static final String KEY_CODE = "update_pending_code";
    private static final String KEY_VERSION = "update_pending_version";
    private static final String KEY_SIZE = "update_pending_size";

    private PendingUpdate() {
    }

    public static void save(Update update) {
        if (update == null || update.code <= 0) return;
        Prefers.put(KEY_CODE, update.code);
        Prefers.put(KEY_VERSION, update.versionName == null ? "" : update.versionName);
        Prefers.put(KEY_SIZE, update.size);
    }

    public static void clear() {
        Prefers.remove(KEY_CODE);
        Prefers.remove(KEY_VERSION);
        Prefers.remove(KEY_SIZE);
    }

    public static String version() {
        return Prefers.getString(KEY_VERSION);
    }

    /** 有可直接安装的包时返回文件，否则返回 null（并顺手清理过期记录）。 */
    public static File file() {
        int code = Prefers.getInt(KEY_CODE);
        if (code <= 0) return null;
        // 已经装上去了（或装了更新的版本），记录作废
        if (code <= BuildConfig.VERSION_CODE) {
            clear();
            return null;
        }
        File file = Path.cache("update.apk");
        long size = Prefers.getLong(KEY_SIZE);
        if (!file.exists() || file.length() <= 0 || size > 0 && file.length() != size) {
            clear();
            return null;
        }
        return file;
    }
}
