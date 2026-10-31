package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.track.UpdateTracker;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;
    private CheckBox mSelectAll;
    private boolean updatingSelectAll;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        getKeep();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn(this)));
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mAdapter.setSize(Product.getSpec(this));
    }

    private void getKeep() {
        mAdapter.setItems(Keep.getVod(), () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            syncSelectAll();
            // 先让「有更新」红标跟着列表绑一次（用户要看得见哪几部有更新），再把已看集数对齐，
            // 这样下次进来红标才消失。原来是绑列表之前就 markAllSeen，结果红标永远不显示。
            UpdateTrack.markAllSeen();
        });
    }

    /** 手动更新：拉取每部追更剧的最新集数，并提示本次更新详情。 */
    private void onUpdate() {
        if (mAdapter.getItemCount() == 0) {
            Notify.show(R.string.track_update_none);
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Keep keep : mAdapter.getItems()) {
            if (UpdateTrack.find(keep.getKey()) == null) UpdateTrack.set(keep, UpdateTrack.DAYS_EVERY);
            keys.add(keep.getKey());
        }
        Notify.show(R.string.track_update_checking);
        UpdateTracker.checkKeys(keys, message -> {
            Notify.show(message);
            getKeep();
        });
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            if (mAdapter.getSelectedCount() == 0) {
                Notify.show(R.string.select_required);
                return;
            }
            new MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(getString(R.string.dialog_delete_selected, mAdapter.getSelectedCount())).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.deleteSelected(() -> {
                mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
                syncSelectAll();
            })).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
            syncSelectAll();
        }
    }

    private void syncSelectAll() {
        if (mSelectAll == null) return;
        boolean all = mAdapter.isAllSelected();
        if (mSelectAll.isChecked() != all) {
            updatingSelectAll = true;
            mSelectAll.setChecked(all);
            updatingSelectAll = false;
        }
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.KEEP)) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) SearchActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemSelect(Keep item) {
        mAdapter.toggleSelection(item);
        syncSelectAll();
    }

    /** 删除模式下点海报垃圾桶：直接删除该条。 */
    @Override
    public void onItemDelete(Keep item) {
        mAdapter.deleteItem(item, () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
            syncSelectAll();
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        syncSelectAll();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_keep, menu);
        MenuItem item = menu.findItem(R.id.select_all);
        if (item != null && item.getActionView() instanceof CheckBox) {
            mSelectAll = (CheckBox) item.getActionView();
            mSelectAll.setOnCheckedChangeListener((buttonView, checked) -> {
                if (updatingSelectAll) return;
                if (checked && mAdapter.getItemCount() > 0) {
                    mAdapter.setDelete(true);
                    mAdapter.setAllSelected(true);
                } else if (!checked) {
                    mAdapter.clearSelection();
                }
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.update) onUpdate();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) {
            mAdapter.setDelete(false);
            syncSelectAll();
        } else super.onBackInvoked();
    }
}
