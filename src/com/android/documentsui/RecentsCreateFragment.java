/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.documentsui.Shared.TAG;
import static com.android.documentsui.State.ACTION_CREATE;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils.TruncateAt;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.documentsui.Events.MotionInputEvent;
import com.android.documentsui.RecentsProvider.RecentColumns;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import libcore.io.IoUtils;

/**
 * Display directories where recent creates took place.
 */
public class RecentsCreateFragment extends Fragment {

    private View mEmptyView;
    private RecyclerView mRecView;
    private DocumentStackAdapter mAdapter;
    private LoaderCallbacks<List<DocumentStack>> mCallbacks;

    private static final int LOADER_RECENTS = 3;

    public static void show(FragmentManager fm) {
        final RecentsCreateFragment fragment = new RecentsCreateFragment();
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mRecView = (RecyclerView) view.findViewById(R.id.dir_list);
        mRecView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecView.addOnItemTouchListener(mItemListener);

        mEmptyView = view.findViewById(android.R.id.empty);

        mAdapter = new DocumentStackAdapter();
        mRecView.setAdapter(mAdapter);

        final RootsCache roots = DocumentsApplication.getRootsCache(context);
        final State state = ((BaseActivity) getActivity()).getDisplayState();

        mCallbacks = new LoaderCallbacks<List<DocumentStack>>() {
            @Override
            public Loader<List<DocumentStack>> onCreateLoader(int id, Bundle args) {
                return new RecentsCreateLoader(context, roots, state);
            }

            @Override
            public void onLoadFinished(
                    Loader<List<DocumentStack>> loader, List<DocumentStack> data) {
                mAdapter.update(data);

                // When launched into empty recents, show drawer
                if (mAdapter.isEmpty() && !state.hasLocationChanged()
                        && state.action != ACTION_CREATE
                        && context instanceof DocumentsActivity) {
                    ((DocumentsActivity) context).setRootsDrawerOpen(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<List<DocumentStack>> loader) {
                mAdapter.update(null);
            }
        };

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        getLoaderManager().restartLoader(LOADER_RECENTS, getArguments(), mCallbacks);
    }

    @Override
    public void onStop() {
        super.onStop();
        getLoaderManager().destroyLoader(LOADER_RECENTS);
    }

    private RecyclerView.OnItemTouchListener mItemListener =
            new RecyclerView.OnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                    try (MotionInputEvent event = MotionInputEvent.obtain(e, mRecView)) {
                        if (event.isOverItem() && event.isActionUp()) {
                            final DocumentStack stack = mAdapter.getItem(event.getItemPosition());
                            ((BaseActivity) getActivity()).onStackPicked(stack);
                            return true;
                        }
                        return false;
                    }
                }

                @Override
                public void onTouchEvent(RecyclerView rv, MotionEvent e) {}
                @Override
                public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            };

    public static class RecentsCreateLoader extends UriDerivativeLoader<Uri, List<DocumentStack>> {
        private final RootsCache mRoots;
        private final State mState;

        public RecentsCreateLoader(Context context, RootsCache roots, State state) {
            super(context, RecentsProvider.buildRecent());
            mRoots = roots;
            mState = state;
        }

        @Override
        public List<DocumentStack> loadInBackground(Uri uri, CancellationSignal signal) {
            final Collection<RootInfo> matchingRoots = mRoots.getMatchingRootsBlocking(mState);
            final ArrayList<DocumentStack> result = new ArrayList<>();

            final ContentResolver resolver = getContext().getContentResolver();
            final Cursor cursor = resolver.query(
                    uri, null, null, null, RecentColumns.TIMESTAMP + " DESC", signal);
            try {
                while (cursor != null && cursor.moveToNext()) {
                    final byte[] rawStack = cursor.getBlob(
                            cursor.getColumnIndex(RecentColumns.STACK));
                    try {
                        final DocumentStack stack = new DocumentStack();
                        DurableUtils.readFromArray(rawStack, stack);

                        // Only update root here to avoid spinning up all
                        // providers; we update the stack during the actual
                        // restore. This also filters away roots that don't
                        // match current filter.
                        stack.updateRoot(matchingRoots);
                        result.add(stack);
                    } catch (IOException e) {
                        Log.w(TAG, "Failed to resolve stack: " + e);
                    }
                }
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            return result;
        }
    }

    private static final class StackHolder extends RecyclerView.ViewHolder {
        public View view;
        public StackHolder(View view) {
            super(view);
            this.view = view;
        }
    }

    private class DocumentStackAdapter extends RecyclerView.Adapter<StackHolder> {
        @Nullable private List<DocumentStack> mItems;

        DocumentStack getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public int getItemCount() {
            return mItems == null ? 0 : mItems.size();
        }

        boolean isEmpty() {
            return mItems == null ? true : mItems.isEmpty();
        }

        void update(@Nullable List<DocumentStack> items) {
            mItems = items;

            if (isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
            }

            notifyDataSetChanged();
        }

        @Override
        public StackHolder onCreateViewHolder(ViewGroup parent, int viewType) {
          final Context context = parent.getContext();

          final LayoutInflater inflater = LayoutInflater.from(context);
          return new StackHolder(
                  inflater.inflate(R.layout.item_doc_list, parent, false));
        }

        @Override
        public void onBindViewHolder(StackHolder holder, int position) {
            Context context = getContext();
            View view = holder.view;

            final ImageView iconMime = (ImageView) view.findViewById(R.id.icon_mime);
            final TextView title = (TextView) view.findViewById(android.R.id.title);
            final View line2 = view.findViewById(R.id.line2);

            final DocumentStack stack = getItem(position);
            iconMime.setImageDrawable(stack.root.loadIcon(context));

            final Drawable crumb = context.getDrawable(R.drawable.ic_breadcrumb_arrow);
            crumb.setBounds(0, 0, crumb.getIntrinsicWidth(), crumb.getIntrinsicHeight());

            final SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(stack.root.title);
            for (int i = stack.size() - 2; i >= 0; i--) {
                appendDrawable(builder, crumb);
                builder.append(stack.get(i).displayName);
            }
            title.setText(builder);
            title.setEllipsize(TruncateAt.MIDDLE);

            if (line2 != null) line2.setVisibility(View.GONE);
        }
    }

    private static void appendDrawable(SpannableStringBuilder b, Drawable d) {
        final int length = b.length();
        b.append("\u232a");
        b.setSpan(new ImageSpan(d), length, b.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}