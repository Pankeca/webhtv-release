package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

public final class AppVersion {

    private AppVersion() {
    }

    public static String fullName() {
        String tag = BuildConfig.BUILD_TAG == null ? "" : BuildConfig.BUILD_TAG.trim();
        if (tag.isEmpty()) tag = BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILD_TIME;
        return stripPrefix(tag);
    }

    public static boolean isCurrent(String name) {
        return stripPrefix(name).equals(stripPrefix(fullName()));
    }

    /**
     * 点分数字版本号比较。
     *
     * @return 大于 0 表示 a 比 b 新，0 表示相同，小于 0 表示更旧
     */
    public static int compare(String a, String b) {
        String[] left = stripPrefix(a).split("[^0-9]+");
        String[] right = stripPrefix(b).split("[^0-9]+");
        int size = Math.max(left.length, right.length);
        for (int i = 0; i < size; i++) {
            long l = number(left, i);
            long r = number(right, i);
            if (l != r) return l > r ? 1 : -1;
        }
        return 0;
    }

    private static long number(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index];
        if (part.isEmpty()) return 0;
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String stripPrefix(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") && value.length() > 1 && Character.isDigit(value.charAt(1)) ? value.substring(1) : value;
    }
}
