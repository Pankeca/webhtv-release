package com.fongmi.android.tv.ui.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.fongmi.android.tv.databinding.AdapterDanmakuSourceBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 弹幕来源列表。
 *
 * <p>每一行是「开关 + 名称 + 地址 + 测速状态」的卡片，操作按钮放在第二行，
 * 目的是让用户能一眼判断哪个源当前可用——公益源失效很常见，
 * 没有可观测状态时用户只能靠反复试。
 */
public class DanmakuSourceAdapter extends RecyclerView.Adapter<DanmakuSourceAdapter.ViewHolder> {

    private static final int COLOR_OK = Color.parseColor("#7BD88F");
    private static final int COLOR_FAIL = Color.parseColor("#FF7B72");
    private static final int COLOR_IDLE = Color.parseColor("#99FFFFFF");

    private final OnActionListener listener;
    private final List<DanmakuSource> mItems;
    private final Map<String, String> status;
    private final Map<String, Boolean> statusOk;
    private final Set<String> testing;

    public interface OnActionListener {

        void onToggle(DanmakuSource item);

        void onMoveUp(DanmakuSource item);

        void onMoveDown(DanmakuSource item);

        void onEdit(DanmakuSource item);

        void onDelete(DanmakuSource item);

        void onTest(DanmakuSource item);
    }

    public DanmakuSourceAdapter(OnActionListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.status = new HashMap<>();
        this.statusOk = new HashMap<>();
        this.testing = new HashSet<>();
    }

    public void replaceAll(List<DanmakuSource> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public List<DanmakuSource> getItems() {
        return mItems;
    }

    /** 写入某个来源的测速结果。 */
    public void setProbe(String id, String text, boolean ok) {
        status.put(id, text);
        statusOk.put(id, ok);
        testing.remove(id);
        notifyDataSetChanged();
    }

    public void setTesting(String id) {
        testing.add(id);
        notifyDataSetChanged();
    }

    public void clearProbe() {
        status.clear();
        statusOk.clear();
        testing.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDanmakuSourceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DanmakuSource item = mItems.get(position);
        String id = item.getId();
        holder.binding.name.setText(item.getDisplayName());
        holder.binding.url.setText(item.getUrl());
        float alpha = item.isEnabled() ? 1f : 0.45f;
        holder.binding.name.setAlpha(alpha);
        holder.binding.url.setAlpha(alpha);
        holder.binding.enabled.setOnCheckedChangeListener(null);
        holder.binding.enabled.setChecked(item.isEnabled());
        holder.binding.enabled.setOnCheckedChangeListener((button, checked) -> listener.onToggle(item));

        if (testing.contains(id)) {
            holder.binding.status.setText(R.string.danmaku_source_testing);
            holder.binding.status.setTextColor(COLOR_IDLE);
        } else if (status.containsKey(id)) {
            holder.binding.status.setText(status.get(id));
            Boolean ok = statusOk.get(id);
            holder.binding.status.setTextColor(Boolean.TRUE.equals(ok) ? COLOR_OK : COLOR_FAIL);
        } else {
            holder.binding.status.setText(R.string.danmaku_source_untested);
            holder.binding.status.setTextColor(COLOR_IDLE);
        }

        holder.binding.test.setOnClickListener(view -> listener.onTest(item));
        holder.binding.up.setVisibility(position == 0 ? View.INVISIBLE : View.VISIBLE);
        holder.binding.down.setVisibility(position == getItemCount() - 1 ? View.INVISIBLE : View.VISIBLE);
        holder.binding.up.setOnClickListener(view -> listener.onMoveUp(item));
        holder.binding.down.setOnClickListener(view -> listener.onMoveDown(item));
        holder.binding.edit.setOnClickListener(view -> listener.onEdit(item));
        holder.binding.delete.setOnClickListener(view -> listener.onDelete(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterDanmakuSourceBinding binding;

        public ViewHolder(@NonNull AdapterDanmakuSourceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
