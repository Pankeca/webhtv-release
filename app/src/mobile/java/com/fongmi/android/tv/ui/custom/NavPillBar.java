package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.view.LayoutInflater;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;

import com.fongmi.android.tv.R;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏导航：三个「文字胶囊」（首页 / 直播 / 设置），样式与首页顶栏胶囊同源。
 *
 * <p>原来是 BottomNavigationView + 图标：图标在深色首页上辨识度一般，也不好做胶囊样式
 * （BottomNavigationView 不认 actionLayout，渲染不出自定义 item）。
 * 这里直接用横向 LinearLayout 摆三颗 MaterialTextView，选中态靠 `setSelected` 驱动
 * `selector_nav_pill`（未选中玻璃质感、选中金色实心）。
 */
public class NavPillBar extends LinearLayoutCompat {

    public interface OnSelectListener {

        void onSelect(int itemId);
    }

    /** 一颗胶囊的描述：菜单 id + 文案。 */
    public static final class Entry {

        private final int id;
        private final int titleRes;

        public Entry(int id, int titleRes) {
            this.id = id;
            this.titleRes = titleRes;
        }
    }

    private final List<MaterialTextView> pills = new ArrayList<>();
    private OnSelectListener listener;
    private int selectedId = 0;

    public NavPillBar(@NonNull Context context) {
        super(context);
        init();
    }

    public NavPillBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NavPillBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        // 内边距写在布局 XML 里（android:padding），构造阶段算 dp 容易拿到 0，整行会被压成一条缝
    }

    /** 重建整行胶囊（配置变化、直播源增删时会变）。 */
    public void setEntries(List<Entry> entries) {
        removeAllViews();
        pills.clear();
        if (entries == null) return;
        for (Entry entry : entries) {
            MaterialTextView pill = pill(entry);
            pills.add(pill);
            addView(pill);
        }
        applySelection();
    }

    private MaterialTextView pill(Entry entry) {
        // 用 this 当 root（attachToRoot=false）才能真正套用 XML 里的 layout_height/minWidth，
        // 否则 children 退化成 WRAP_CONTENT，胶囊会矮成一条线
        MaterialTextView pill = (MaterialTextView) LayoutInflater.from(getContext()).inflate(R.layout.view_nav_pill, this, false);
        pill.setId(entry.id);
        pill.setText(entry.titleRes);
        pill.setSelected(entry.id == selectedId);
        pill.setOnClickListener(view -> select(entry.id, true));
        return pill;
    }

    public void setListener(OnSelectListener listener) {
        this.listener = listener;
    }

    /** 单个入口显隐（直播没有源时整颗藏掉）。 */
    public void setEntryVisible(int itemId, boolean visible) {
        View pill = getEntryView(itemId);
        if (pill != null) pill.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public View getEntryView(int itemId) {
        return findViewById(itemId);
    }

    public int getSelectedId() {
        return selectedId;
    }

    /** 切换选中项；notify=false 用于「同步状态」，避免又触发一次跳转。 */
    public void setSelectedId(int itemId, boolean notify) {
        if (selectedId == itemId) {
            if (notify && listener != null) listener.onSelect(itemId);
            return;
        }
        selectedId = itemId;
        applySelection();
        if (notify && listener != null) listener.onSelect(itemId);
    }

    private void select(int itemId, boolean notify) {
        setSelectedId(itemId, notify);
    }

    private void applySelection() {
        for (MaterialTextView pill : pills) pill.setSelected(pill.getId() == selectedId);
    }
}
