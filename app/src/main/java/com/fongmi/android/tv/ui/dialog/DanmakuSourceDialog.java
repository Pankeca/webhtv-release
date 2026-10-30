package com.fongmi.android.tv.ui.dialog;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.fongmi.android.tv.databinding.DialogDanmakuSourceBinding;
import com.fongmi.android.tv.setting.DanmakuPreset;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.DanmakuSourceSetting;
import com.fongmi.android.tv.ui.adapter.DanmakuSourceAdapter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 多渠道弹幕来源管理：增删、启停、调优先级、测速，以及「合并所有来源」开关。
 *
 * <p>顺序即优先级：自动匹配按列表从上往下试，第一个有结果的来源胜出（合并开关打开时除外）。
 * 界面参照 NewBox 的「弹幕 API」面板：列表里每行都能就地测速，底部是
 * 「内置源 / 全部测速 / 添加」三个动作。
 */
public final class DanmakuSourceDialog extends BaseBottomSheetDialog implements DanmakuSourceAdapter.OnActionListener {

    private DialogDanmakuSourceBinding binding;
    private DanmakuSourceAdapter adapter;
    private Runnable dismissAction;

    public static DanmakuSourceDialog create() {
        return new DanmakuSourceDialog();
    }

    /** 关闭后回调，用于让上层页面刷新「N 个来源」这类汇总信息。 */
    public DanmakuSourceDialog onDismissAction(Runnable action) {
        this.dismissAction = action;
        return this;
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (dismissAction != null) dismissAction.run();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDanmakuSourceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        adapter = new DanmakuSourceAdapter(this);
        binding.recycler.setAdapter(adapter);
        binding.recycler.setItemAnimator(null);
        binding.merge.setChecked(DanmakuSourceSetting.isMergeAll());
        reload();
    }

    @Override
    protected void initEvent() {
        binding.add.setOnClickListener(view -> showEditor(null));
        binding.presets.setOnClickListener(view -> showPresets());
        binding.testAll.setOnClickListener(view -> testAll());
        binding.merge.setOnCheckedChangeListener((button, checked) -> DanmakuSourceSetting.putMergeAll(checked));
    }

    private void reload() {
        List<DanmakuSource> items = new ArrayList<>(DanmakuSourceSetting.getSources());
        adapter.replaceAll(items);
        binding.recycler.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addPresets() {
        int added = DanmakuSourceSetting.addMissingPresets(true);
        reload();
        Notify.show(ResUtil.getString(added > 0 ? R.string.danmaku_source_presets_added : R.string.danmaku_source_presets_none, added));
    }

    /**
     * 列出全部内置公益源，点一条加一条。
     *
     * <p>「内置源」原来的行为是一次性补齐 6 个主力源，但预置表里一共有 15 条，
     * 其余 9 条在界面上没有任何入口（用户没法知道有哪些、也没法单独加）。这里
     * 改成先给完整清单：已添加的标出来，想加哪个点哪个；底部仍保留「添加主力源」
     * 这个一键动作。
     */
    private void showPresets() {
        List<DanmakuPreset.Item> items = DanmakuPreset.all();
        if (items.isEmpty()) return;
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            DanmakuPreset.Item item = items.get(i);
            String host = hostOf(item.url);
            labels[i] = ResUtil.getString(
                    DanmakuSourceSetting.hasPreset(item) ? R.string.danmaku_presets_item_added : R.string.danmaku_presets_item,
                    item.name, host);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(ResUtil.getString(R.string.danmaku_presets_title, items.size()))
                .setItems(labels, (dialog, which) -> {
                    DanmakuPreset.Item item = items.get(which);
                    if (DanmakuSourceSetting.addPreset(item)) {
                        reload();
                        Notify.show(ResUtil.getString(R.string.danmaku_presets_added_one, item.name));
                    } else {
                        Notify.show(ResUtil.getString(R.string.danmaku_presets_exists, item.name));
                    }
                })
                .setPositiveButton(R.string.danmaku_presets_primary, (dialog, which) -> addPresets())
                .setNegativeButton(R.string.dialog_close, null)
                .show();
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private void testAll() {
        for (DanmakuSource item : adapter.getItems()) test(item);
    }

    private void test(DanmakuSource item) {
        adapter.setTesting(item.getId());
        DanmakuApi.probe(item, probe -> App.post(() -> {
            if (binding == null) return;
            adapter.setProbe(item.getId(), probe.text(), probe.ok);
        }));
    }

    @Override
    public void onTest(DanmakuSource item) {
        test(item);
    }

    @Override
    public void onToggle(DanmakuSource item) {
        DanmakuSourceSetting.toggle(item.getId());
        reload();
    }

    @Override
    public void onMoveUp(DanmakuSource item) {
        if (DanmakuSourceSetting.moveUp(item.getId())) reload();
    }

    @Override
    public void onMoveDown(DanmakuSource item) {
        if (DanmakuSourceSetting.moveDown(item.getId())) reload();
    }

    @Override
    public void onEdit(DanmakuSource item) {
        showEditor(item);
    }

    @Override
    public void onDelete(DanmakuSource item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.danmaku_source_delete)
                .setMessage(item.getDisplayName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    DanmakuSourceSetting.remove(item.getId());
                    reload();
                })
                .show();
    }

    private void showEditor(DanmakuSource source) {
        boolean isNew = source == null;
        DanmakuSource target = isNew ? DanmakuSource.create("", "") : source.copy();
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(20);
        layout.setPadding(pad, pad / 2, pad, 0);
        AppCompatEditText nameView = createEdit(layout, R.string.danmaku_source_name_hint, target.getName());
        AppCompatEditText urlView = createEdit(layout, R.string.danmaku_api_hint, target.getUrl());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isNew ? R.string.danmaku_source_add : R.string.danmaku_source_edit)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> save(target, nameView, urlView, isNew))
                .show();
    }

    private AppCompatEditText createEdit(LinearLayout layout, int hint, String text) {
        AppCompatEditText edit = new AppCompatEditText(requireContext());
        edit.setHint(hint);
        edit.setText(text);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setTextColor(ResUtil.getColor(R.color.white));
        edit.setHintTextColor(ResUtil.getColor(R.color.white_70));
        layout.addView(edit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private void save(DanmakuSource target, AppCompatEditText nameView, AppCompatEditText urlView, boolean isNew) {
        String url = urlView.getText() == null ? "" : urlView.getText().toString().trim();
        if (!DanmakuSetting.isValidApiUrl(url)) {
            Notify.show(R.string.danmaku_source_invalid);
            return;
        }
        target.setName(nameView.getText() == null ? "" : nameView.getText().toString().trim());
        target.setUrl(url);
        // IP 直连的源证书基本都对不上，用户手动添加时按地址自动判断一次，省得让用户自己踩 TLS 错误
        target.setInsecure(needsLooseTls(url));
        if (isNew) DanmakuSourceSetting.add(target);
        else DanmakuSourceSetting.update(target);
        reload();
    }

    /** IP 字面量地址的自签证书无法通过主机名校验，直接放宽，其余保持标准校验。 */
    private boolean needsLooseTls(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null) return false;
            return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected boolean stableOverlay() {
        return true;
    }
}
