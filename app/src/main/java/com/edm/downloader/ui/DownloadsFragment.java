package com.edm.downloader.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.edm.downloader.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.HashMap;
import java.util.Map;

/** Hosts the three download lists behind a swipeable top tab strip. */
public class DownloadsFragment extends Fragment {

    private static final String STATE_SUB = "sub_tab";
    private static final int[] MODES = {
            TaskListFragment.MODE_ALL,
            TaskListFragment.MODE_ACTIVE,
            TaskListFragment.MODE_DONE
    };
    private static final int[] TITLES = {
            R.string.subtab_all,
            R.string.subtab_active,
            R.string.subtab_done
    };

    private final Map<Integer, TaskListFragment> pages = new HashMap<>();
    private TabLayout tabs;
    private ViewPager2 pager;
    private TabLayoutMediator mediator;
    private int selected;
    private String query = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tabs = view.findViewById(R.id.downloadTabs);
        pager = view.findViewById(R.id.downloadPager);

        pager.setOffscreenPageLimit(MODES.length - 1);
        pager.setAdapter(new PageAdapter());
        mediator = new TabLayoutMediator(tabs, pager,
                (tab, position) -> tab.setText(TITLES[position]));
        mediator.attach();

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (position == selected) {
                    return;
                }
                TaskListFragment previous = pages.get(selected);
                if (previous != null) {
                    previous.cancelSelection();
                }
                selected = position;
            }
        });

        int restore = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_SUB, 0);
        if (restore < 0 || restore >= MODES.length) {
            restore = 0;
        }
        selected = restore;
        if (restore != 0) {
            pager.setCurrentItem(restore, false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SUB, pager == null ? selected : pager.getCurrentItem());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            return;
        }
        TaskListFragment current = pages.get(selected);
        if (current != null) {
            current.cancelSelection();
        }
    }

    @Override
    public void onDestroyView() {
        if (mediator != null) {
            mediator.detach();
            mediator = null;
        }
        if (pager != null) {
            pager.setAdapter(null);
        }
        pages.clear();
        tabs = null;
        pager = null;
        super.onDestroyView();
    }

    /** Switches to the combined list, used when a search starts. */
    public void showAll() {
        if (pager != null && pager.getCurrentItem() != 0) {
            pager.setCurrentItem(0, true);
        }
    }

    public void refresh() {
        for (TaskListFragment page : pages.values()) {
            page.refresh();
        }
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim();
        for (TaskListFragment page : pages.values()) {
            page.setQuery(query);
        }
    }

    /** True when this page is the one currently shown by the pager. */
    public boolean isActivePage(TaskListFragment page) {
        return pager != null && pages.get(pager.getCurrentItem()) == page;
    }

    private class PageAdapter extends FragmentStateAdapter {

        PageAdapter() {
            super(DownloadsFragment.this);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            TaskListFragment fragment = TaskListFragment.newInstance(MODES[position]);
            pages.put(position, fragment);
            // Safe before the view exists: the fragment stores it for its first refresh.
            fragment.setQuery(query);
            return fragment;
        }

        @Override
        public int getItemCount() {
            return MODES.length;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean containsItem(long itemId) {
            return itemId >= 0 && itemId < MODES.length;
        }
    }
}
