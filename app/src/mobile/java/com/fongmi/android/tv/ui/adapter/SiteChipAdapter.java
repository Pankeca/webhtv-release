package com.fongmi.android.tv.ui.adapter;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.flexbox.FlexboxLayoutManager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterFlagBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 详情页「站点」行：平铺所有同名站点，点击直接换站，样式与线路胶囊一致。 */
public class SiteChipAdapter extends RecyclerView.Adapter<SiteChipAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Vod> mItems;
    private final Map<String, Integer> mEpisodes;
    private String selectedKey = "";

    public SiteChipAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mEpisodes = new HashMap<>();
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    /**
     * 整行替换为给定站点列表，同一个站点只保留一颗胶囊，顺序保持首次出现的位置不变。
     * 快搜结果是分站点逐条回传的，必须整行同步而不是逐批追加，否则只有最后一批站点可见。
     */
    public void setItems(List<Vod> items) {
        mItems.clear();
        if (items != null) {
            Set<String> keys = new HashSet<>();
            for (Vod vod : items) {
                if (vod == null || TextUtils.isEmpty(vod.getSiteKey())) continue;
                if (!keys.add(vod.getSiteKey())) continue;
                mItems.add(vod);
            }
        }
        notifyDataSetChanged();
    }

    /** 每个站点已更新到第几集，取自该站点集数最多的线路。 */
    public void setEpisodeCounts(Map<String, Integer> episodes) {
        mEpisodes.clear();
        if (episodes != null) mEpisodes.putAll(episodes);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setSelectedKey(String key) {
        selectedKey = key == null ? "" : key;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFlagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = mItems.get(position);
        String name = TextUtils.isEmpty(item.getSiteName()) ? item.getName() : item.getSiteName();
        Integer count = mEpisodes.get(item.getSiteKey());
        holder.binding.text.setText(buildText(holder, name, count == null ? 0 : count));
        holder.binding.text.setSelected(TextUtils.equals(selectedKey, item.getSiteKey()));
        holder.binding.text.setOnClickListener(view -> listener.onItemClick(item));
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.binding.text.getLayoutParams();
        int spacing = ResUtil.dp2px(8);
        params.setMargins(0, 0, spacing, spacing);
        // 竖屏单行横滑时 flexbox 默认会把子项压缩进一行，胶囊被压成竖排文字；
        // 明确不收缩 + 自适应宽度，超出的部分交给 RecyclerView 横向滚动。
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params instanceof FlexboxLayoutManager.LayoutParams) {
            FlexboxLayoutManager.LayoutParams flex = (FlexboxLayoutManager.LayoutParams) params;
            flex.setFlexShrink(0f);
            flex.setFlexBasisPercent(-1f);
        }
        holder.binding.text.setLayoutParams(params);
    }

    /** 站点名一行，集数一行且字号略小，和可可影视的站点胶囊一致。 */
    private CharSequence buildText(ViewHolder holder, String name, int count) {
        if (count <= 0) return name;
        SpannableStringBuilder text = new SpannableStringBuilder(name);
        text.append('\n').append(holder.binding.text.getContext().getString(R.string.detail_site_episodes, count));
        text.setSpan(new RelativeSizeSpan(0.82f), name.length() + 1, text.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFlagBinding binding;

        ViewHolder(@NonNull AdapterFlagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
