package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingDanmakuBinding;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.DanmakuSourceSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DanmakuSourceDialog;

public class SettingDanmakuActivity extends BaseActivity {

    private ActivitySettingDanmakuBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingDanmakuActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private String getSourceStatus() {
        return getString(R.string.danmaku_source_status, DanmakuSourceSetting.getSources().size(), DanmakuSourceSetting.countEnabled());
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingDanmakuBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.danmakuLoad.requestFocus();
        mBinding.danmakuSourceText.setText(getSourceStatus());
        mBinding.danmakuAutoText.setText(getSwitch(DanmakuSetting.isAuto()));
        mBinding.danmakuLoadText.setText(getSwitch(DanmakuSetting.isLoad()));
        mBinding.danmakuSpiderText.setText(getSwitch(DanmakuSetting.isSpiderFirst()));
        updateAutoVisibility();
    }

    @Override
    protected void initEvent() {
        mBinding.danmakuSource.setOnClickListener(this::onDanmakuSource);
        mBinding.danmakuAuto.setOnClickListener(this::setDanmakuAuto);
        mBinding.danmakuLoad.setOnClickListener(this::setDanmakuLoad);
        mBinding.danmakuSpider.setOnClickListener(this::setDanmakuSpider);
    }

    private void setDanmakuLoad(View view) {
        DanmakuSetting.putLoad(!DanmakuSetting.isLoad());
        mBinding.danmakuLoadText.setText(getSwitch(DanmakuSetting.isLoad()));
        updateAutoVisibility();
    }

    private boolean hasSource() {
        return !DanmakuSourceSetting.getEnabledSources().isEmpty();
    }

    private void updateAutoVisibility() {
        boolean show = DanmakuSetting.isLoad() && hasSource();
        mBinding.danmakuAuto.setVisibility(show ? View.VISIBLE : View.GONE);
        updateSpiderVisibility();
    }

    private void updateSpiderVisibility() {
        boolean show = DanmakuSetting.isLoad() && hasSource() && DanmakuSetting.isAuto();
        mBinding.danmakuSpider.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void onDanmakuSource(View view) {
        DanmakuSourceDialog.create()
                .onDismissAction(this::refreshSourceStatus)
                .show(this);
    }

    private void refreshSourceStatus() {
        if (mBinding != null) mBinding.danmakuSourceText.setText(getSourceStatus());
        updateAutoVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBinding.danmakuSourceText.setText(getSourceStatus());
    }

    private void setDanmakuAuto(View view) {
        DanmakuSetting.putAuto(!DanmakuSetting.isAuto());
        mBinding.danmakuAutoText.setText(getSwitch(DanmakuSetting.isAuto()));
        updateSpiderVisibility();
    }

    private void setDanmakuSpider(View view) {
        DanmakuSetting.putSpiderFirst(!DanmakuSetting.isSpiderFirst());
        mBinding.danmakuSpiderText.setText(getSwitch(DanmakuSetting.isSpiderFirst()));
    }
}
