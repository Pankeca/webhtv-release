package com.fongmi.android.tv.ui.fragment;

import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.RecommendSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.DownloadCenterActivity;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.AccountDialog;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LinkDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.TypeDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.WebHomeChrome;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener, HomeWebController.Listener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private HomeWebController mWeb;
    private TypeAdapter mAdapter;
    private Result mResult;
    private String mChromeMode = WebHomeChrome.NORMAL;
    private int mHomeWebTopMargin;
    private int mToolbarHeight;
    private LinearLayoutCompat mInlineNavigation;

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        mHomeWebTopMargin = ((ViewGroup.MarginLayoutParams) mBinding.homeWeb.getLayoutParams()).topMargin;
        setRecyclerView();
        setWebView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
        updateToolbarMenu();
        // 菜单的 actionView 要等 Toolbar 真正完成布局才拿得到，post 一帧再挂监听
        mBinding.toolbar.post(this::setupPills);
        updateAccountIcon();
        mToolbarHeight = mBinding.toolbar.getLayoutParams().height;
        applySmallScreenLayout();
    }

    /**
     * 手机横屏矮屏适配：把顶栏压扁，给内容留高度。
     * 竖屏的按钮收纳由 menu_vod.xml 的 ifRoom 自动完成。
     */
    private void applySmallScreenLayout() {
        Configuration configuration = getResources().getConfiguration();
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenHeightDp < 480) {
            ViewGroup.LayoutParams params = mBinding.toolbar.getLayoutParams();
            params.height = ResUtil.dp2px(44);
            mBinding.toolbar.setLayoutParams(params);
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), 0, mBinding.type.getPaddingEnd(), ResUtil.dp2px(4));
        }
    }

    /** 手机横屏隐藏底栏后，把「影视 / 直播 / 设置」放到站点名标题后面。 */
    public void setInlineNavigationVisible(boolean visible) {
        if (mInlineNavigation == null) {
            if (!visible || getContext() == null || mBinding == null) return;
            mInlineNavigation = new LinearLayoutCompat(requireContext());
            mInlineNavigation.setOrientation(LinearLayoutCompat.HORIZONTAL);
            mInlineNavigation.setGravity(Gravity.CENTER_VERTICAL);
            mInlineNavigation.setPadding(ResUtil.dp2px(4), 0, ResUtil.dp2px(4), 0);
            mInlineNavigation.setBackground(createInlineNavigationBackground());
            addInlineNavigationItem(R.string.pill_home, R.id.vod);
            addInlineNavigationItem(R.string.nav_live, R.id.live);
            addInlineNavigationItem(R.string.nav_setting, R.id.setting);
            Toolbar.LayoutParams params = new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(40));
            params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            params.setMarginStart(ResUtil.dp2px(4));
            params.setMarginEnd(ResUtil.dp2px(8));
            mBinding.toolbar.addView(mInlineNavigation, params);
        }
        if (mInlineNavigation == null) return;
        mInlineNavigation.setVisibility(visible ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams params = mBinding.toolbar.getLayoutParams();
        params.height = visible ? ResUtil.dp2px(44) : mToolbarHeight;
        mBinding.toolbar.setLayoutParams(params);
        View live = mInlineNavigation.getChildAt(1);
        if (live != null) live.setVisibility(LiveConfig.hasUrl() ? View.VISIBLE : View.GONE);
    }

    private Drawable createInlineNavigationBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0x26FFFFFF);
        drawable.setCornerRadius(ResUtil.dp2px(20));
        return drawable;
    }

    /** 内联导航的一个「文字图标」：白字胶囊，点一下切到对应底栏页签。 */
    private void addInlineNavigationItem(int textRes, int itemId) {
        com.google.android.material.textview.MaterialTextView view = new com.google.android.material.textview.MaterialTextView(requireContext());
        view.setText(textRes);
        view.setTextSize(12);
        view.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setBackgroundResource(R.drawable.shape_home_pill);
        view.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
        view.setOnClickListener(v -> ((HomeActivity) requireActivity()).selectNavigationItem(itemId));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(30));
        params.setMarginStart(ResUtil.dp2px(3));
        params.setMarginEnd(ResUtil.dp2px(3));
        params.gravity = Gravity.CENTER_VERTICAL;
        mInlineNavigation.addView(view, params);
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.title.setOnLongClickListener(this::reloadConfig);
        mBinding.typeMore.setOnTouchListener(this::onTypeMoreTouch);
        mBinding.typeMore.setOnClickListener(this::onTypeMore);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.toolbar.post(() -> {
            MenuItem keep = mBinding.toolbar.getMenu().findItem(R.id.keep);
            if (keep != null && UpdateTrack.hasAnyUpdate()) keep.setTitle(keep.getTitle() + " \u2B24");
            refreshPill(keep);
        });
        mBinding.toolbar.post(this::setSearchAction);
        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            int range = appBarLayout.getTotalScrollRange();
            if (range <= 0) return;
            float factor = Math.abs(verticalOffset * 1f / range);
            int padding = (int) (ResUtil.dp2px(12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
    }

    /** 顶栏这五个入口（刷新/追更/历史/下载/我的）统一用文字胶囊，不再画图标。 */
    private static final int[] PILL_IDS = {R.id.refresh, R.id.keep, R.id.history, R.id.download, R.id.account};

    /**
     * 把菜单 title 填进胶囊，并把点击转回原来的菜单回调。
     *
     * <p>{@code app:actionLayout} 一旦用上，MenuItem 自己的点击回调就不会触发了，
     * 所以必须给 actionView 单独挂监听，否则五个按钮全部变成死的。
     */
    private void setupPills() {
        if (!isAdded() || mBinding == null) return;
        Menu menu = mBinding.toolbar.getMenu();
        for (int id : PILL_IDS) {
            MenuItem item = menu.findItem(id);
            View view = item == null ? null : item.getActionView();
            if (view == null) continue;
            view.setOnClickListener(v -> onMenuItemClick(item));
            refreshPill(item);
        }
    }

    /** 只刷新单个胶囊文字（追更有更新时要加个红点圆），不重建整个菜单。 */
    private void refreshPill(MenuItem item) {
        View view = item == null ? null : item.getActionView();
        if (!(view instanceof TextView)) return;
        CharSequence title = item.getTitle();
        ((TextView) view).setText(title == null ? "" : title.toString());
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setWebView() {
        mWeb = new HomeWebController(requireActivity(), mBinding.homeWeb, this);
        syncWebHomeChrome();
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        if (mWeb != null && mWeb.isVisible()) return;
        mAdapter.addAll(mResult = result);
        notifyPagerAdapter();
        setFabVisible(0);
        mBinding.typeMore.setVisibility(View.GONE);
        mBinding.type.post(this::updateTypeMoreVisible);
        updateToolbarMenu();
        hideProgress();
        showContent();
    }

    private void updateTypeMoreVisible() {
        if (!isAdded() || mBinding == null) return;
        if (mBinding.type.getWidth() == 0 || mBinding.typeBar.getWidth() == 0) {
            mBinding.type.post(this::updateTypeMoreVisible);
            return;
        }
        int typeWidth = mBinding.typeBar.getWidth() - mBinding.typeBar.getPaddingStart() - mBinding.typeBar.getPaddingEnd();
        boolean visible = mAdapter.getItemCount() > 0 && mBinding.type.computeHorizontalScrollRange() > typeWidth;
        mBinding.typeMore.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setFabVisible(int position) {
        if (isNativeChromeHidden()) {
            mBinding.top.setVisibility(View.GONE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
            return;
        }
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.show(this);
        return true;
    }

    private void onTypeMore(View view) {
        if (mAdapter.getItemCount() > 0) TypeDialog.create().items(mAdapter.getItems()).show(this);
    }

    private boolean onTypeMoreTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            view.animate().cancel();
            view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(80).start();
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            view.animate().cancel();
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
        }
        return false;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private void onSite(View view) {
        SiteDialog.create().change().show(this);
    }

    private boolean reloadConfig(View view) {
        VodConfig.get().clear().config(getConfig()).load(new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
            }

            @Override
            public void success() {
                hideProgress();
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                hideProgress();
                showContent();
            }
        });
        return true;
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.refresh) {
            if (mWeb != null && mWeb.isVisible()) mWeb.reload();
            else homeContent();
        } else if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.download) DownloadCenterActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        else if (item.getItemId() == R.id.account) AccountDialog.show(this, this::updateAccountIcon);
        else if (item.getItemId() == R.id.web_home_fullscreen) onWebHomeFullscreen();
        else return false;
        return true;
    }

    private void onWebHomeFullscreen() {
        if (!Setting.isWebHomeFullscreen()) return;
        if (mWeb == null || !mWeb.isVisible()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("mode", WebHomeChrome.EDGE);
        setChrome(payload);
    }

    private void updateToolbarMenu() {
        if (!isAdded() || mBinding == null) return;
        Menu menu = mBinding.toolbar.getMenu();
        MenuItem fullscreen = menu.findItem(R.id.web_home_fullscreen);
        if (fullscreen != null) fullscreen.setVisible(Setting.isWebHomeFullscreen() && mWeb != null && mWeb.isVisible());
        applyCompactToolbar();
        setSearchAction();
        setupPills();
    }

    /** 小屏竖屏空间有限：隐藏站点名标题、搜索框缩成图标，把追更/最近观看/下载中心保留在顶栏。 */
    private void applyCompactToolbar() {
        if (!isAdded() || mBinding == null) return;
        boolean compact = getResources().getConfiguration().screenWidthDp < 600;
        mBinding.title.setVisibility(compact ? View.GONE : View.VISIBLE);
        int logoSize = ResUtil.dp2px(compact ? 28 : 36);
        ViewGroup.LayoutParams logoParams = mBinding.logo.getLayoutParams();
        if (logoParams != null) {
            logoParams.width = logoSize;
            logoParams.height = logoSize;
            mBinding.logo.setLayoutParams(logoParams);
        }
    }

    private void setSearchAction() {
        if (!isAdded() || mBinding == null) return;
        MenuItem item = mBinding.toolbar.getMenu().findItem(R.id.search);
        View search = item == null ? null : item.getActionView();
        if (search == null) return;
        // 小屏（宽 < 600dp）只保留搜索图标，给最近观看/下载中心等按钮留位置
        if (getResources().getConfiguration().screenWidthDp < 600) {
            ViewGroup.LayoutParams params = search.getLayoutParams();
            if (params != null) {
                params.width = ResUtil.dp2px(44);
                search.setLayoutParams(params);
            }
            View label = search.findViewById(R.id.searchText);
            if (label != null) label.setVisibility(View.GONE);
        }
        search.setOnClickListener(view -> SearchActivity.start(requireActivity()));
        search.setOnLongClickListener(view -> {
            SearchActivity.start(requireActivity(), "", getHome().getKey());
            return true;
        });
    }

    /** 「我的」胶囊：未登录用半透明白，登录后变金色，一眼能看出账号状态。 */
    private void updateAccountIcon() {
        if (mBinding == null || !isAdded()) return;
        MenuItem item = mBinding.toolbar.getMenu().findItem(R.id.account);
        View view = item == null ? null : item.getActionView();
        if (!(view instanceof TextView)) return;
        boolean login = AccountStore.isLogin();
        ((TextView) view).setTextColor(ContextCompat.getColor(requireContext(), login ? R.color.account_online : R.color.white_60));
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.typeMore.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        updateTypeMoreVisible();
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        requestNormalChrome();
        showProgress();
        mBinding.homeWeb.setVisibility(View.GONE);
        updateToolbarMenu();
        clearPagerTypes();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
        setFabVisible(0);
        mViewModel.homeContent();
    }

    private void loadHome() {
        Site home = getHome();
        WebHomeChromeStartup.remember(getConfig(), home);
        setTitle();
        // 用户把首页内容换成豆瓣/历史/站点推荐时，网页首页要让位，否则设了也看不到效果
        boolean useWeb = mWeb != null && RecommendSetting.isOff() && mWeb.load(home);
        if (useWeb) {
            clearPagerTypes();
            hideProgress();
            hideNativeContent();
        } else {
            showNativeContent();
            homeContent();
        }
    }

    private void clearPagerTypes() {
        mAdapter.clear();
        mBinding.typeMore.setVisibility(View.GONE);
        notifyPagerAdapter();
    }

    private void notifyPagerAdapter() {
        PagerAdapter adapter = mBinding.pager.getAdapter();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                if (mWeb != null && mWeb.isVisible()) {
                    Site home = getHome();
                    WebHomeChromeStartup.remember(getConfig(), home);
                    requestNormalChrome();
                    setTitle();
                    if (!mWeb.load(home, true)) {
                        showNativeContent();
                        homeContent();
                    }
                } else {
                    loadHome();
                }
                break;
            case SIZE:
                if (mWeb != null && mWeb.isVisible()) return;
                homeContent();
                break;
            case CATEGORY:
                if (mWeb != null && mWeb.isVisible()) return;
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mWeb != null && mWeb.handleBack()) return false;
        if (isNativeChromeHidden()) {
            requestNormalChrome();
            return false;
        }
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        requestNormalChrome();
        if (mWeb != null) mWeb.destroy();
        EventBus.getDefault().unregister(this);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWeb != null) mWeb.onResume();
        updateAccountIcon();
    }

    @Override
    public void onPause() {
        if (mWeb != null) mWeb.onPause();
        super.onPause();
    }

    @Override
    public void onWebLoading() {
        showProgress();
    }

    @Override
    public void onWebReady() {
        hideProgress();
    }

    @Override
    public void onWebError() {
        requestNormalChrome();
        showNativeContent();
        homeContent();
    }

    @Override
    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeLegacyToolbar(visible);
        else applyWebHomeChrome(visible ? WebHomeChrome.NORMAL : WebHomeChrome.IMMERSIVE);
    }

    @Override
    public void applyDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.applyWebHomeDefaultChrome(site);
    }

    @Override
    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeChrome(payload);
    }

    @Override
    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            applyWebHomeChrome(WebHomeChrome.NORMAL);
            return;
        }
        HomeActivity activity = homeActivity();
        if (activity != null) activity.restoreWebHomeChrome();
    }

    @Override
    public WebHomeViewport getViewport() {
        HomeActivity activity = homeActivity();
        return activity == null ? WebHomeViewport.EMPTY : activity.getWebHomeViewport();
    }

    @Override
    public void openVod() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.openVod();
    }

    @Override
    public void openSetting() {
        if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).change(1);
    }

    private void hideNativeContent() {
        mBinding.appBar.setExpanded(true, false);
        boolean hidden = isNativeChromeHidden();
        mBinding.appBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        setHomeWebTopMargin(hidden ? 0 : mHomeWebTopMargin);
        mBinding.type.setVisibility(View.GONE);
        mBinding.typeMore.setVisibility(View.GONE);
        mBinding.pager.setVisibility(View.GONE);
        mBinding.filter.setVisibility(View.GONE);
        mBinding.link.setVisibility(View.GONE);
        mBinding.top.setVisibility(View.GONE);
        updateToolbarMenu();
    }

    private void showNativeContent() {
        requestNormalChrome();
        mBinding.type.setVisibility(View.VISIBLE);
        updateTypeMoreVisible();
        mBinding.pager.setVisibility(View.VISIBLE);
        mBinding.homeWeb.setVisibility(View.GONE);
        updateToolbarMenu();
    }

    public void applyWebHomeChrome(String mode) {
        mChromeMode = WebHomeChrome.normalize(mode, WebHomeChrome.NORMAL);
        boolean hidden = isNativeChromeHidden();
        mBinding.appBar.setExpanded(true, false);
        mBinding.appBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        setHomeWebTopMargin(hidden ? 0 : mHomeWebTopMargin);
        updateToolbarMenu();
        if (hidden) {
            mBinding.type.setVisibility(View.GONE);
            mBinding.typeMore.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.GONE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.top.setVisibility(View.GONE);
        }
    }

    public void applyWebHomeViewport(WebHomeViewport viewport) {
        if (mWeb != null) mWeb.setViewport(viewport);
    }

    public void openVodHome() {
        homeContent();
    }

    private void setHomeWebTopMargin(int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mBinding.homeWeb.getLayoutParams();
        if (params.topMargin == margin) return;
        params.topMargin = margin;
        mBinding.homeWeb.setLayoutParams(params);
    }

    private void requestNormalChrome() {
        HomeActivity activity = homeActivity();
        if (activity != null) activity.setWebHomeLegacyToolbar(true);
        else applyWebHomeChrome(WebHomeChrome.NORMAL);
    }

    private boolean isNativeChromeHidden() {
        return WebHomeChrome.hidesNativeChrome(mChromeMode);
    }

    private void syncWebHomeChrome() {
        HomeActivity activity = homeActivity();
        if (activity == null) return;
        applyWebHomeChrome(activity.getWebHomeChromeMode());
        applyWebHomeViewport(activity.getWebHomeViewport());
    }

    private HomeActivity homeActivity() {
        return getActivity() instanceof HomeActivity ? (HomeActivity) getActivity() : null;
    }


    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
