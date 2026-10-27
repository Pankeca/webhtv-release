package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogThemeBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.ThemeAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ThemeDialog extends BaseAlertDialog implements ThemeAdapter.OnClickListener {

    /** 柔和护眼色板：低饱和、低亮度，避免长时间观看刺眼。 */
    private static final int[] COLORS = {-1, 0,
            0xFF7E9B8E, 0xFF7F94AE, 0xFF8E86A8, 0xFFB08A8A,
            0xFFA8917A, 0xFF6E8F92, 0xFF9A8FA6, 0xFF8A9A7B,
            0xFF7B8FA6, 0xFFA29A86, 0xFF97899B, 0xFF6F8F7F};
    private DialogThemeBinding binding;

    public static void show(Fragment fragment) {
        new ThemeDialog().show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogThemeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.setting_theme_color).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.recycler.setAdapter(new ThemeAdapter(this, COLORS, Setting.getThemeColor()));
    }

    @Override
    public void onItemClick(int color) {
        ((Listener) requireParentFragment()).setTheme(color);
        dismiss();
    }

    public interface Listener {

        void setTheme(int color);
    }
}

