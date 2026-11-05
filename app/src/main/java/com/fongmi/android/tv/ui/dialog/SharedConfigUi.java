package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * 共享配置相关弹窗的四形态尺寸：手机/平板 × 竖屏/横屏。
 *
 * <p>弹窗内容都是代码构建，没有 layout-land / sw600dp 变体可用，因此统一在这里按
 * 屏幕宽度与方向算窗口宽度、滚动区最大高度，并打开 SOFT_INPUT_ADJUST_RESIZE，
 * 避免横屏矮屏被系统栏裁掉、竖屏输入时被键盘遮挡。
 */
final class SharedConfigUi {

    private SharedConfigUi() {
    }

    private static boolean isTablet(Context context) {
        return ResUtil.getScreenWidth(context) >= ResUtil.dp2px(600);
    }

    /** 窗口宽度：手机竖屏接近满宽，手机横屏略收，平板两侧留白并限制最大阅读宽度。 */
    static int dialogWidth(Context context) {
        int screen = ResUtil.getScreenWidth(context);
        boolean land = ResUtil.isLand(context);
        boolean tablet = isTablet(context);
        float ratio = land ? (tablet ? 0.62f : 0.86f) : (tablet ? 0.66f : 0.92f);
        int width = (int) (screen * ratio);
        int cap = ResUtil.dp2px(tablet ? 720 : 560);
        return Math.min(Math.max(ResUtil.dp2px(280), Math.min(width, cap)), screen);
    }

    /**
     * 滚动区最大高度：横屏矮屏按 0.72 屏高并预留标题/按钮/系统栏；
     * 竖屏多给一点内容区。表单类再额外预留键盘高度。
     */
    static int maxContentHeight(Context context, boolean form) {
        int screen = ResUtil.getScreenHeight(context);
        boolean land = ResUtil.isLand(context);
        int reserved = ResUtil.dp2px(land ? 140 : 220) + (form ? ResUtil.dp2px(land ? 40 : 80) : 0);
        int ratio = (int) (screen * (land ? 0.72f : 0.70f));
        return Math.max(ResUtil.dp2px(200), Math.min(ratio, screen - reserved));
    }

    /** 只读/列表弹窗：设置宽度并允许输入法顶起内容。 */
    static void applyWindow(AlertDialog dialog, Context context) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.setLayout(dialogWidth(context), WindowManager.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** 含输入框的表单：额外清掉 ALT_FOCUSABLE_IM，否则部分机型输入法弹不出。 */
    static void applyFormWindow(AlertDialog dialog, Context context) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        applyWindow(dialog, context);
    }
}
