package com.fongmi.android.tv.ui.activity;

final class EpisodeGridLayoutPolicy {

    private EpisodeGridLayoutPolicy() {
    }

    static int getMaxSpan(boolean landscapeLayout, boolean pad) {
        // 平板横屏沿用 6 列（右列本来就宽）。手机横屏改成左右分栏后右列只剩半屏宽，
        // 6 列会把集数按钮挤成一团，降到 4 列。手机竖屏一直是 4 列，不受影响。
        return pad ? 6 : 4;
    }

    static int getAvailableWidth(int measuredWidth, int screenWidth, int screenHeight, int fallbackInset, boolean landscapeLayout, boolean landscapeConfiguration) {
        if (measuredWidth > 0 && landscapeLayout == landscapeConfiguration) return measuredWidth;
        int layoutWidth = landscapeLayout ? Math.max(screenWidth, screenHeight) : Math.min(screenWidth, screenHeight);
        return Math.max(1, layoutWidth - Math.max(0, fallbackInset));
    }
}
