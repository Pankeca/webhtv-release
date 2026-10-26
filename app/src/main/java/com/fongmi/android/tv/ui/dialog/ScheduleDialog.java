package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogScheduleBinding;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ScheduleDialog extends BaseAlertDialog {

    private DialogScheduleBinding binding;
    private Callback callback;
    private boolean every;
    private boolean none;
    private int weekBits;

    public interface Callback {

        void onApply(int days);
    }

    public static void show(FragmentActivity activity, int days, Callback callback) {
        ScheduleDialog dialog = new ScheduleDialog();
        dialog.every = days == UpdateTrack.DAYS_EVERY;
        dialog.none = days == UpdateTrack.DAYS_NONE;
        dialog.weekBits = days > 0 ? days : 0;
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogScheduleBinding.inflate(getLayoutInflater());
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
        params.width = (int) (screenWidth * (land ? 0.5f : 0.86f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        initView();
        initEvent();
    }

    @Override
    protected void initView() {
        binding.every.setOnClickListener(v -> {
            every = true;
            none = false;
            weekBits = 0;
            render();
        });
        binding.none.setOnClickListener(v -> {
            none = true;
            every = false;
            weekBits = 0;
            render();
        });
        MaterialButton[] week = {binding.mon, binding.tue, binding.wed, binding.thu, binding.fri, binding.sat, binding.sun};
        for (int i = 0; i < week.length; i++) {
            final int bit = 1 << i;
            week[i].setOnClickListener(v -> {
                every = false;
                none = false;
                weekBits = (weekBits & bit) != 0 ? weekBits & ~bit : weekBits | bit;
                render();
            });
        }
        render();
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(view -> dismiss());
        binding.positive.setOnClickListener(view -> {
            int result = none ? UpdateTrack.DAYS_NONE : every ? UpdateTrack.DAYS_EVERY : weekBits == 0 ? UpdateTrack.DAYS_NONE : weekBits;
            if (callback != null) callback.onApply(result);
            dismiss();
        });
    }

    private void render() {
        paint(binding.every, every);
        paint(binding.none, none);
        MaterialButton[] week = {binding.mon, binding.tue, binding.wed, binding.thu, binding.fri, binding.sat, binding.sun};
        for (int i = 0; i < week.length; i++) paint(week[i], (weekBits & (1 << i)) != 0);
        binding.summary.setText(buildSummary());
    }

    private void paint(MaterialButton button, boolean selected) {
        button.setStrokeWidth(ResUtil.dp2px(selected ? 2 : 1));
        button.setStrokeColor(ColorStateList.valueOf(selected ? 0xFF1A73E8 : 0xFFDADCE0));
        button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private String buildSummary() {
        if (none) return getString(R.string.schedule_summary_none);
        if (every) return getString(R.string.schedule_summary_every);
        if (weekBits == 0) return getString(R.string.schedule_summary_pick);
        String[] names = getString(R.string.schedule_week_names).split(",");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 7; i++) if ((weekBits & (1 << i)) != 0) builder.append(builder.length() == 0 ? "" : "、").append(names[i]);
        return getString(R.string.schedule_summary_days, builder.toString());
    }
}
