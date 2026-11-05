package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;
import com.fongmi.android.tv.setting.TvboxConfigSync;

import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        return addAll(type, null);
    }

    public ConfigAdapter addAll(int type, Config current) {
        mItems = Config.getAll(type);
        String currentUrl = current == null ? null : current.getUrl();
        if (!readOnly && !TextUtils.isEmpty(currentUrl)) mItems.removeIf(item -> TextUtils.equals(item.getUrl(), currentUrl));
        return this;
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        // 本地残留的 TVBox 专用协议地址仍保留在列表中，但标记为不可用并弱化显示，
        // 不静默删除用户数据，同时避免它被当作可用源选中
        boolean unusable = !TvboxConfigSync.isSupported(item.getUrl());
        holder.binding.text.setText(unusable ? item.getDesc() + holder.itemView.getContext().getString(R.string.config_unusable) : item.getDesc());
        holder.binding.text.setAlpha(unusable ? 0.55f : 1f);
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
