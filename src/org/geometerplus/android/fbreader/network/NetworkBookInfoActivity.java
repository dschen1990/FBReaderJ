/*
 * Copyright (C) 2010-2011 Geometer Plus <contact@geometerplus.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.network;

import java.util.*;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;

import org.geometerplus.zlibrary.ui.android.R;

import org.geometerplus.zlibrary.core.image.ZLImage;
import org.geometerplus.zlibrary.core.image.ZLLoadableImage;
import org.geometerplus.zlibrary.core.network.ZLNetworkException;
import org.geometerplus.zlibrary.core.resources.ZLResource;
import org.geometerplus.zlibrary.core.util.MimeType;

import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageManager;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageData;
import org.geometerplus.zlibrary.ui.android.network.SQLiteCookieDatabase;

import org.geometerplus.fbreader.network.*;
import org.geometerplus.fbreader.network.tree.NetworkBookTree;
import org.geometerplus.fbreader.network.urlInfo.*;
import org.geometerplus.fbreader.network.opds.OPDSBookItem;

import org.geometerplus.android.fbreader.network.action.OpenCatalogAction;
import org.geometerplus.android.fbreader.network.action.NetworkBookActions;

import org.geometerplus.android.util.UIUtil;

public class NetworkBookInfoActivity extends Activity implements NetworkLibrary.ChangeListener {
	private NetworkBookTree myTree;
	private NetworkBookItem myBook;
	private View myMainView;

	private final ZLResource myResource = ZLResource.resource("networkBookView");
	private BookDownloaderServiceConnection myConnection;

	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		Thread.setDefaultUncaughtExceptionHandler(new org.geometerplus.zlibrary.ui.android.library.UncaughtExceptionHandler(this));

		SQLiteCookieDatabase.init(this);

		myMainView = getLayoutInflater().inflate(R.layout.network_book, null, false);
		setContentView(myMainView);
	}

	@Override
	protected void onResume() {
		super.onResume();

		NetworkLibrary.Instance().fireModelChangedEvent(NetworkLibrary.ChangeListener.Code.SomeCode);

		if (!myInitializerStarted) {
			UIUtil.wait("loadingNetworkBookInfo", myInitializer, this);
		}
	}

	private volatile boolean myInitializerStarted;

	private final Runnable myInitializer = new Runnable() {
		public void run() {
			synchronized (this) {
				if (myInitializerStarted) {
					return;
				}
				myInitializerStarted = true;
			}
			final NetworkLibrary library = NetworkLibrary.Instance();
			if (!library.isInitialized()) {
				if (SQLiteNetworkDatabase.Instance() == null) {
					new SQLiteNetworkDatabase();
				}
				library.initialize();
			}
        
			if (myBook == null) {
				final Uri url = getIntent().getData();
				if (url != null && "litres-book".equals(url.getScheme())) {
					myBook = OPDSBookItem.create(
						library.getLinkBySiteName("litres.ru"),
						url.toString().replace("litres-book://", "http://")
					);
					if (myBook != null) {
						myTree = library.getFakeBookTree(myBook);
					}
				} else {
					final NetworkTree tree = library.getTreeByKey(
						(NetworkTree.Key)getIntent().getSerializableExtra(
							NetworkLibraryActivity.TREE_KEY_KEY
						)
					);
					if (tree instanceof NetworkBookTree) {
						myTree = (NetworkBookTree)tree;
						myBook = myTree.Book;
					}
				}

				runOnUiThread(myViewInitializer);
			}
		}
	};

	private final Runnable myViewInitializer = new Runnable() {
		public void run() {
			if (myBook == null) {
				finish();
			} else {
				myConnection = new BookDownloaderServiceConnection();
				bindService(
					new Intent(getApplicationContext(), BookDownloaderService.class),
					myConnection,
					BIND_AUTO_CREATE
				);
            
				setTitle(myBook.Title);
            
				setupDescription();
				setupExtraLinks();
				setupInfo();
				setupCover();
			}
		}
	};

	View getMainView() {
		return myMainView;
	}

	private void setTextById(int id, CharSequence text) {
		((TextView)findViewById(id)).setText(text);
	}

	private void setTextFromResource(int id, String resourceKey) {
		setTextById(id, myResource.getResource(resourceKey).getValue());
	}

	@Override
	public void onDestroy() {
		if (myConnection != null) {
			unbindService(myConnection);
			myConnection = null;
		}
		super.onDestroy();
	}

	private final void setupDescription() {
		setTextFromResource(R.id.network_book_description_title, "description");

		CharSequence description = myBook.getSummary();
		if (description == null) {
			description = myResource.getResource("noDescription").getValue();
		}
		final TextView descriptionView = (TextView)findViewById(R.id.network_book_description);
		descriptionView.setText(description);
		descriptionView.setMovementMethod(new LinkMovementMethod());
		descriptionView.setTextColor(
			ColorStateList.valueOf(descriptionView.getTextColors().getDefaultColor())
		);
	}

	private final void setupExtraLinks() {
		final List<UrlInfo> extraLinks = myBook.getAllInfos(UrlInfo.Type.Related);
		if (extraLinks.isEmpty()) {
			findViewById(R.id.network_book_extra_links_title).setVisibility(View.GONE);
			findViewById(R.id.network_book_extra_links).setVisibility(View.GONE);
		} else {
			setTextFromResource(R.id.network_book_extra_links_title, "extraLinks");
			final LinearLayout extraLinkSection =
				(LinearLayout)findViewById(R.id.network_book_extra_links);
			final LayoutInflater inflater = getLayoutInflater();
			View linkView = null;
			for (UrlInfo info : extraLinks) {
				if (!(info instanceof RelatedUrlInfo)) {
					continue;
				}
				final RelatedUrlInfo relatedInfo = (RelatedUrlInfo)info;
				linkView = inflater.inflate(R.layout.extra_link_item, extraLinkSection, false);
				linkView.setOnClickListener(new View.OnClickListener() {
					public void onClick(View view) {
						final NetworkCatalogItem catalogItem =
							myBook.createRelatedCatalogItem(relatedInfo);
						if (catalogItem != null) {
							new OpenCatalogAction(NetworkBookInfoActivity.this)
								.run(NetworkLibrary.Instance().getFakeCatalogTree(catalogItem));
						} else if (MimeType.TEXT_HTML.equals(relatedInfo.Mime)) {
							Util.openInBrowser(NetworkBookInfoActivity.this, relatedInfo.Url);
						}
					}
				});
				((TextView)linkView.findViewById(R.id.extra_link_title)).setText(relatedInfo.Title);
				extraLinkSection.addView(linkView);
			}
			linkView.findViewById(R.id.extra_link_divider).setVisibility(View.GONE);
		}
	}

	private void setPairLabelTextFromResource(int id, String resourceKey) {
		final LinearLayout layout = (LinearLayout)findViewById(id);
		((TextView)layout.findViewById(R.id.book_info_key))
			.setText(myResource.getResource(resourceKey).getValue());
	}

	private void setPairValueText(int id, CharSequence text) {
		final LinearLayout layout = (LinearLayout)findViewById(id);
		((TextView)layout.findViewById(R.id.book_info_value)).setText(text);
	}

	private void setupInfo() {
		setTextFromResource(R.id.network_book_info_title, "bookInfo");

		setPairLabelTextFromResource(R.id.network_book_title, "title");
		setPairLabelTextFromResource(R.id.network_book_authors, "authors");
		setPairLabelTextFromResource(R.id.network_book_series_title, "series");
		setPairLabelTextFromResource(R.id.network_book_series_index, "indexInSeries");
		setPairLabelTextFromResource(R.id.network_book_tags, "tags");
		setPairLabelTextFromResource(R.id.network_book_catalog, "catalog");

		setPairValueText(R.id.network_book_title, myBook.Title);

		if (myBook.Authors.size() > 0) {
			findViewById(R.id.network_book_authors).setVisibility(View.VISIBLE);
			final StringBuilder authorsText = new StringBuilder();
			for (NetworkBookItem.AuthorData author : myBook.Authors) {
				if (authorsText.length() > 0) {
					authorsText.append(", ");
				}
				authorsText.append(author.DisplayName);
			}
			setPairValueText(R.id.network_book_authors, authorsText);
		} else {
			findViewById(R.id.network_book_authors).setVisibility(View.GONE);
		}

		if (myBook.SeriesTitle != null) {
			findViewById(R.id.network_book_series_title).setVisibility(View.VISIBLE);
			setPairValueText(R.id.network_book_series_title, myBook.SeriesTitle);
			final float indexInSeries = myBook.IndexInSeries;
			if (indexInSeries > 0) {
				final String seriesIndexString;
				if (Math.abs(indexInSeries - Math.round(indexInSeries)) < 0.01) {
					seriesIndexString = String.valueOf(Math.round(indexInSeries));
				} else {
					seriesIndexString = String.format("%.1f", indexInSeries);
				}
				setPairValueText(R.id.network_book_series_index, seriesIndexString);
				findViewById(R.id.network_book_series_index).setVisibility(View.VISIBLE);
			} else {
				findViewById(R.id.network_book_series_index).setVisibility(View.GONE);
			}
		} else {
			findViewById(R.id.network_book_series_title).setVisibility(View.GONE);
			findViewById(R.id.network_book_series_index).setVisibility(View.GONE);
		}

		if (myBook.Tags.size() > 0) {
			findViewById(R.id.network_book_tags).setVisibility(View.VISIBLE);
			final StringBuilder tagsText = new StringBuilder();
			for (String tag : myBook.Tags) {
				if (tagsText.length() > 0) {
					tagsText.append(", ");
				}
				tagsText.append(tag);
			}
			setPairValueText(R.id.network_book_tags, tagsText);
		} else {
			findViewById(R.id.network_book_tags).setVisibility(View.GONE);
		}

		setPairValueText(R.id.network_book_catalog, myBook.Link.getTitle());
	}

	private final void setupCover() {
		final View rootView = findViewById(R.id.network_book_root);
		final ImageView coverView = (ImageView)findViewById(R.id.network_book_cover);

		final DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);

		final int maxHeight = metrics.heightPixels * 2 / 3;
		final int maxWidth = maxHeight * 2 / 3;
		Bitmap coverBitmap = null;
		final ZLImage cover = NetworkTree.createCover(myBook);
		if (cover != null) {
			ZLAndroidImageData data = null;
			final ZLAndroidImageManager mgr = (ZLAndroidImageManager)ZLAndroidImageManager.Instance();
			if (cover instanceof ZLLoadableImage) {
				final ZLLoadableImage img = (ZLLoadableImage)cover;
				img.startSynchronization(new Runnable() {
					public void run() {
						img.synchronizeFast();
						final ZLAndroidImageData data = mgr.getImageData(img);
						if (data != null) {
							final Bitmap coverBitmap = data.getBitmap(maxWidth, maxHeight);
							if (coverBitmap != null) {
								coverView.setImageBitmap(coverBitmap);
								coverView.setVisibility(View.VISIBLE);
								rootView.invalidate();
								rootView.requestLayout();
							}
						}
					}
				});
			} else {
				data = mgr.getImageData(cover);
			}
			if (data != null) {
				coverBitmap = data.getBitmap(maxWidth, maxHeight);
			}
		}
		if (coverBitmap != null) {
			coverView.setImageBitmap(coverBitmap);
			coverView.setVisibility(View.VISIBLE);
		} else {
			coverView.setVisibility(View.GONE);
		}
	}

	private void addMenuItem(Menu menu, int index, String label, int iconId) {
		final MenuItem item = menu.add(0, index, Menu.NONE, label);
		if (iconId != -1) {
			item.setIcon(iconId);
			item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		}
	}

	private void updateView() {
		final View rootView = findViewById(R.id.network_book_root);
		rootView.invalidate();
		rootView.requestLayout();
	}

	@Override
	protected void onStart() {
		super.onStart();
		NetworkLibrary.Instance().addChangeListener(this);
	}

	@Override
	protected void onStop() {
		NetworkLibrary.Instance().removeChangeListener(this);
		super.onStop();
	}

	public void onLibraryChanged(NetworkLibrary.ChangeListener.Code code, Object[] params) {
		if (code == NetworkLibrary.ChangeListener.Code.InitializationFailed) {
			// TODO: implement
			return;
		}

		if (myBook == null || myTree == null) {
			return;
		}

		runOnUiThread(new Runnable() {
			public void run() {
				updateView();
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		for (final NetworkBookActions.NBAction a : NetworkBookActions.getContextMenuActions(this, myTree, myConnection)) {
			addMenuItem(menu, a.Code, a.getContextLabel(null), a.IconId);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		for (final NetworkBookActions.NBAction a : NetworkBookActions.getContextMenuActions(this, myTree, myConnection)) {
			if (a.Code == item.getItemId()) {
				a.run(myTree);
				NetworkBookInfoActivity.this.updateView();
				return true;
			}
		}
		return false;
	}
}
