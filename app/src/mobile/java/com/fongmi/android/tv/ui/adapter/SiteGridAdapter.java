package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSiteGridBinding;

import java.util.ArrayList;
import java.util.List;

/** 选集面板顶部的「站点」二级菜单：网格样式与选集格子保持一致。 */
public class SiteGridAdapter extends RecyclerView.Adapter<SiteGridAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Vod> items = new ArrayList<>();
    private String selectedKey = "";

    public SiteGridAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    public void setItems(List<Vod> sites, String selectedKey) {
        items.clear();
        if (sites != null) items.addAll(sites);
        this.selectedKey = selectedKey == null ? "" : selectedKey;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSiteGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = items.get(position);
        String name = TextUtils.isEmpty(item.getSiteName()) ? item.getName() : item.getSiteName();
        holder.binding.text.setText(name);
        holder.binding.text.setSelected(TextUtils.equals(selectedKey, item.getSiteKey()));
        holder.binding.text.setOnClickListener(view -> listener.onItemClick(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteGridBinding binding;

        ViewHolder(@NonNull AdapterSiteGridBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
