package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class KeepAdapter extends BaseDiffAdapter<Keep, KeepAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final Set<Keep> selected = new HashSet<>();
    private int width, height;
    private boolean delete;

    public KeepAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Keep item);

        void onItemSelect(Keep item);

        void onItemDelete(Keep item);

        boolean onLongClick();
    }

    public void setSize(int[] size) {
        this.width = size[0];
        this.height = size[1];
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        if (!delete) selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isSelected(Keep item) {
        return selected.contains(item);
    }

    public void toggleSelection(Keep item) {
        if (!selected.remove(item)) selected.add(item);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setAllSelected(boolean checked) {
        selected.clear();
        if (checked) selected.addAll(getItems());
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isAllSelected() {
        return getItemCount() > 0 && selected.size() >= getItemCount();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void clearSelection() {
        selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    /** 删除当前勾选项：全选是选中状态，只有点删除才真正移除。 */
    public void deleteSelected(Runnable done) {
        List<Keep> remain = new ArrayList<>(getItems());
        remain.removeAll(selected);
        for (Keep item : selected) item.delete();
        selected.clear();
        setItems(remain, done);
    }

    /** 删除模式下点海报上的垃圾桶图标：直接删除单条。 */
    public void deleteItem(Keep item, Runnable done) {
        List<Keep> remain = new ArrayList<>(getItems());
        remain.remove(item);
        selected.remove(item);
        item.delete();
        setItems(remain, done);
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        Keep.deleteAll();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Keep item = getItem(position);
        holder.binding.name.setText(item.getVodName());
        holder.binding.remark.setVisibility(View.GONE);
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.site.setText(item.getSiteName());
        holder.binding.progress.setVisibility(View.GONE);
        holder.binding.historyProgress.setVisibility(View.GONE);
        holder.binding.historyUpdate.setVisibility(View.GONE);
        holder.binding.remark.setVisibility(UpdateTrack.hasUpdate(item.getKey()) ? View.VISIBLE : View.GONE);
        if (holder.binding.remark.getVisibility() == View.VISIBLE) {
            holder.binding.remark.setText(R.string.track_badge);
            holder.binding.remark.setTextColor(0xFFFF5252);
        }
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        if (delete) {
            boolean checked = selected.contains(item);
            // 未勾选时显示垃圾桶（点图标直接删单条），勾选后显示红底白勾，避免残留空心圈
            holder.binding.delete.setImageResource(checked ? R.drawable.ic_vod_check_on : R.drawable.ic_vod_delete);
            holder.binding.delete.setBackgroundColor(checked ? 0x40000000 : 0x80000000);
        } else {
            holder.binding.delete.setImageResource(R.drawable.ic_vod_delete);
            holder.binding.delete.setBackgroundColor(0x80000000);
        }
        holder.binding.delete.setOnClickListener(view -> {
            if (!isDelete()) return;
            if (selected.contains(item)) listener.onItemSelect(item);
            else listener.onItemDelete(item);
        });
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, Keep item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemSelect(item);
            else listener.onItemClick(item);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
