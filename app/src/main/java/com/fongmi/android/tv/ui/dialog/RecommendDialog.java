package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.RecommendSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

/**
 * 首页内容来源选择（单选）。
 *
 * <p>首页只有一个，所以这里是四选一：站点默认 / 豆瓣热播 / 站点推荐 / 最近观看。
 * 点一行立刻落盘并关窗，关窗后通知调用方重建首页——不然用户得手动切一次站点才生效。
 */
public class RecommendDialog extends BaseAlertDialog {

    private Runnable callback;

    public static void show(FragmentActivity activity, Runnable callback) {
        RecommendDialog dialog = new RecommendDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return null;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        LinearLayoutCompat content = new LinearLayoutCompat(requireContext());
        content.setOrientation(LinearLayoutCompat.VERTICAL);
        int padding = ResUtil.dp2px(16);
        content.setPadding(padding, ResUtil.dp2px(4), padding, ResUtil.dp2px(4));
        String source = RecommendSetting.getSource();
        addRow(content, RecommendSetting.OFF, R.string.recommend_source_off, R.string.recommend_source_off_desc, source);
        addRow(content, RecommendSetting.DOUBAN, R.string.recommend_douban, R.string.recommend_douban_desc, source);
        addRow(content, RecommendSetting.SITE, R.string.recommend_site, R.string.recommend_site_desc, source);
        addRow(content, RecommendSetting.HISTORY, R.string.recommend_history, R.string.recommend_history_desc, source);
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.recommend_title)
                .setView(content)
                .setNegativeButton(R.string.dialog_negative, null);
    }

    private void addRow(LinearLayoutCompat parent, String value, int titleRes, int descRes, String selected) {
        boolean checked = value.equals(selected);
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ResUtil.dp2px(4), ResUtil.dp2px(10), ResUtil.dp2px(4), ResUtil.dp2px(10));

        RadioButton radio = new RadioButton(requireContext());
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        row.addView(radio, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayoutCompat texts = new LinearLayoutCompat(requireContext());
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        MaterialTextView title = new MaterialTextView(requireContext());
        title.setText(titleRes);
        title.setTextSize(15);
        title.setTextColor(Color.parseColor("#202124"));
        texts.addView(title);
        MaterialTextView desc = new MaterialTextView(requireContext());
        desc.setText(descRes);
        desc.setTextSize(12);
        desc.setTextColor(Color.parseColor("#80868B"));
        LinearLayoutCompat.LayoutParams descParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = ResUtil.dp2px(2);
        desc.setLayoutParams(descParams);
        texts.addView(desc);
        LinearLayoutCompat.LayoutParams textParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = ResUtil.dp2px(8);
        row.addView(texts, textParams);

        row.setOnClickListener(view -> {
            RecommendSetting.setSource(value);
            dismissAllowingStateLoss();
        });
        parent.addView(row);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (callback != null) callback.run();
        super.onDismiss(dialog);
    }
}
