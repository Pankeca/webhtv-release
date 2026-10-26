package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.databinding.DialogListBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadDialog extends BaseAlertDialog implements com.fongmi.android.tv.download.DownloadTask.Listener {

    private static final int COLUMNS = 3;
    private static final int COLOR_SELECT = 0xFF1A73E8;
    private static final int COLOR_DONE_TEXT = 0xFF188038;
    private static final int COLOR_DONE_TINT = 0xFFE6F4EA;

    private DialogListBinding binding;
    private final List<MaterialTextView> chipViews = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private final List<Boolean> done = new ArrayList<>();
    private boolean[] checked;
    private boolean reverse;
    private boolean selectAll;
    private String siteKey;
    private String siteName;
    private String vodId;
    private String vodName;
    private String vodPic;
    private String flagName;
    private CheckBox selectAllBox;
    private MaterialTextView orderButton;

    public static void show(FragmentActivity activity, String siteKey, String siteName, String vodId, String vodName, String vodPic, String flagName, List<Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            Notify.show(R.string.download_empty);
            return;
        }
        DownloadDialog dialog = new DownloadDialog();
        dialog.siteKey = siteKey;
        dialog.siteName = siteName;
        dialog.vodId = vodId;
        dialog.vodName = vodName;
        dialog.vodPic = vodPic;
        dialog.flagName = flagName;
        for (Episode episode : episodes) {
            dialog.names.add(episode.getName());
            dialog.urls.add(episode.getUrl());
            dialog.done.add(DownloadTask.findLocal(vodName, episode.getName()) != null);
        }
        dialog.checked = new boolean[dialog.names.size()];
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogListBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.55f : 0.9f));
        params.height = land ? (int) (ResUtil.getScreenHeight(requireContext()) * 0.85f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        binding.title.setText(R.string.download_title);
        binding.summary.setText(getString(R.string.download_summary, siteName, names.size()));
        binding.close.setOnClickListener(view -> dismiss());
        DownloadManager.get().addListener(this);
        binding.negative.setText(R.string.download_manager_open);
        initView();
        initEvent();
    }

    @Override
    protected void initView() {
        renderGrid();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(view -> {
            int count = 0;
            for (int i = 0; i < names.size(); i++) {
                if (!checked[i]) continue;
                // 已下载且文件仍在的集不再重复下载，避免覆盖本地文件
                if (DownloadTask.findLocal(vodName, names.get(i)) != null) continue;
                DownloadTask task = new DownloadTask(siteKey, siteName, vodId, vodName, vodPic, flagName, names.get(i), urls.get(i));
                if (DownloadManager.get().enqueue(task)) count++;
            }
            Notify.show(count > 0 ? getString(R.string.download_queued, count) : getString(R.string.download_duplicate));
            dismiss();
        });
        binding.negative.setOnClickListener(view -> {
            dismiss();
            com.fongmi.android.tv.ui.activity.DownloadCenterActivity.start(requireActivity());
        });
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        DownloadManager.get().removeListener(this);
        super.onDismiss(dialog);
    }

    @Override
    public void onChange(List<DownloadTask> tasks) {
        if (binding == null || !isAdded()) return;
        binding.content.post(() -> {
            for (int v = 0; v < chipViews.size() && v < names.size(); v++) {
                int dataIndex = reverse ? names.size() - 1 - v : v;
                MaterialTextView view = chipViews.get(v);
                if (view == null) continue;
                // 以磁盘文件为准刷新 ✓，文件被删后勾选标记立即消失
                done.set(dataIndex, DownloadTask.findLocal(vodName, names.get(dataIndex)) != null);
                String label = (done.get(dataIndex) ? "✓ " : "") + names.get(dataIndex);
                DownloadTask task = DownloadManager.get().find(vodName, names.get(dataIndex));
                if (task != null && task.isBusy()) label += task.getState() == DownloadTask.State.RUNNING ? " · " + task.getProgress() + "%" : " · " + getString(R.string.download_state_waiting);
                view.setText(label);
            }
        });
    }

    private void renderGrid() {
        chipViews.clear();
        binding.content.removeAllViews();
        binding.content.addView(orderBar());
        binding.content.addView(grid());
        binding.content.addView(selectAllBar());
        binding.positive.setText(R.string.download_action_now);
        binding.negative.setText(R.string.download_manager_open);
    }

    private View orderBar() {
        LinearLayoutCompat bar = new LinearLayoutCompat(requireContext());
        bar.setOrientation(LinearLayoutCompat.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        orderButton = text(getString(reverse ? R.string.download_order_desc : R.string.download_order_asc), 12, Color.parseColor("#1A73E8"), false);
        orderButton.setGravity(Gravity.CENTER);
        orderButton.setBackground(roundBg(Color.WHITE, 0xFFDADCE0, dp(14)));
        orderButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        orderButton.setOnClickListener(view -> {
            reverse = !reverse;
            renderGrid();
        });
        bar.addView(orderButton, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private View grid() {
        GridLayout grid = new GridLayout(requireContext());
        grid.setColumnCount(COLUMNS);
        int pad = dp(3);
        for (int viewIndex = 0; viewIndex < names.size(); viewIndex++) {
            int dataIndex = reverse ? names.size() - 1 - viewIndex : viewIndex;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.setMargins(pad, pad, pad, pad);
            grid.addView(chip(dataIndex), params);
        }
        return grid;
    }

    private View chip(int dataIndex) {
        boolean selected = checked[dataIndex];
        boolean downloaded = done.get(dataIndex);
        LinearLayoutCompat chip = new LinearLayoutCompat(requireContext());
        chip.setOrientation(LinearLayoutCompat.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(roundBg(selected ? 0xFFE3EDFF : Color.WHITE, selected ? COLOR_SELECT : 0xFFDADCE0, dp(8)));
        chip.setPadding(dp(8), dp(8), dp(8), dp(8));
        CheckBox box = new CheckBox(requireContext());
        box.setChecked(selected);
        box.setClickable(false);
        box.setFocusable(false);
        chip.addView(box, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String label = (downloaded ? "✓ " : "") + names.get(dataIndex);
        MaterialTextView name = text(label, 13, downloaded ? COLOR_DONE_TEXT : Color.BLACK, downloaded);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER);
        while (chipViews.size() <= dataIndex) chipViews.add(null);
        chipViews.set(dataIndex, name);
        chip.setOnClickListener(view -> {
            checked[dataIndex] = !checked[dataIndex];
            syncSelectAll();
            paintChip(chip, box, checked[dataIndex], downloaded, dataIndex);
        });
        name.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chip.addView(name);
        return chip;
    }

    private void paintChip(LinearLayoutCompat chip, CheckBox box, boolean selected, boolean downloaded, int dataIndex) {
        chip.setBackground(roundBg(selected ? 0xFFE3EDFF : Color.WHITE, selected ? COLOR_SELECT : 0xFFDADCE0, dp(8)));
        box.setChecked(selected);
        MaterialTextView name = (MaterialTextView) ((ViewGroup) chip).getChildAt(1);
        name.setText((downloaded ? "✓ " : "") + names.get(dataIndex));
    }

    private View selectAllBar() {
        LinearLayoutCompat bar = new LinearLayoutCompat(requireContext());
        bar.setOrientation(LinearLayoutCompat.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(4), dp(4));
        bar.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        selectAllBox = new CheckBox(requireContext());
        selectAllBox.setText(getString(R.string.download_select_all));
        selectAllBox.setTextSize(14);
        selectAllBox.setOnClickListener(view -> {
            selectAll = selectAllBox.isChecked();
            for (int i = 0; i < checked.length; i++) checked[i] = selectAll;
            renderGrid();
            selectAllBox.setChecked(selectAll);
        });
        bar.addView(selectAllBox);
        return bar;
    }

    private void syncSelectAll() {
        if (selectAllBox == null) return;
        boolean all = true;
        for (boolean value : checked) if (!value) all = false;
        selectAll = all;
        selectAllBox.setChecked(all);
    }

    private GradientDrawable roundBg(int fillColor, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private MaterialTextView text(String value, int sp, int color, boolean bold) {
        MaterialTextView view = new MaterialTextView(requireContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
