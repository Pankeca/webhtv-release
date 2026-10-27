package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogThemeBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.ThemeAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ThemeDialog extends BaseAlertDialog implements ThemeAdapter.OnClickListener {

    /** 柔和护眼色板：低饱和、低亮度，避免长时间观看刺眼。 */
    private static final int[] COLORS = {-1, 0,
            0xFF7E9B8E, 0xFF7F94AE, 0xFF8E86A8, 0xFFB08A8A,
            0xFFA8917A, 0xFF6E8F92, 0xFF9A8FA6, 0xFF8A9A7B,
            0xFF7B8FA6, 0xFFA29A86, 0xFF97899B, 0xFF6F8F7F};
    /** 护眼纯色皮肤：切换后壁纸与主题色同步为对应纯色。 */
    private static final int[] SKINS = {
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_CHARCOAL),
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_NIGHT_BLUE),
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_MOSS),
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_PLUM),
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_COCOA),
            Setting.getBuiltInWallColor(Setting.WALL_SOLID_SLATE)};
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
        binding.themeRecycler.setAdapter(new ThemeAdapter(this, COLORS, Setting.getThemeColor()));
        binding.skinRecycler.setAdapter(new ThemeAdapter(this::onSkinClick, SKINS, selectedSkin()));
        binding.reset.setOnClickListener(view -> onReset());
    }

    private int selectedSkin() {
        int wall = Setting.getWall();
        return Setting.getWallType() == 0 && Setting.isSolidWall(wall) ? Setting.getBuiltInWallColor(wall) : -1;
    }

    @Override
    public void onItemClick(int color) {
        ((Listener) requireParentFragment()).setTheme(color);
        dismiss();
    }

    private void onSkinClick(int color) {
        int wall = Setting.getSolidWallByColor(color);
        if (wall == -1) return;
        Setting.putWall(wall);
        Setting.putWallType(0);
        Setting.putWallColor(color);
        ConfigEvent.wall();
        ((Listener) requireParentFragment()).setTheme(0);
        dismiss();
    }

    private void onReset() {
        Setting.putWall(Setting.WALL_DREAM_PURPLE);
        Setting.putWallType(0);
        Setting.putWallColor(0);
        ConfigEvent.wall();
        ((Listener) requireParentFragment()).setTheme(-1);
        dismiss();
    }

    public interface Listener {

        void setTheme(int color);
    }
}
