package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;

import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 从本地缓存播放页打开的「网络多线路完整版」独立页面。
 *
 * <p>{@link VideoActivity} 在清单里声明为 singleTop，直接从本地页 start 会被复用（走 onNewIntent），
 * 本地选集、线路和播放进度都会被网络详情覆盖，返回时也回不到本地缓存页。
 * 这里用标准启动模式的子类，让网络页叠在本地页之上，返回即可继续看本地视频。
 */
public class NetworkVideoActivity extends VideoActivity {

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        ImgUtil.preload(activity, pic);
        Intent intent = new Intent(activity, NetworkVideoActivity.class);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivity(intent);
    }
}
