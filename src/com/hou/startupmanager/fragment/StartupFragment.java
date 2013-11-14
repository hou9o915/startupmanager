package com.hou.startupmanager.fragment;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.hou.startupmanager.R;
import com.hou.startupmanager.fragment.StartupFragment.AppListLoader;
import com.hou.startupmanager.util.RootUtil;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class StartupFragment extends ListFragment implements
		LoaderCallbacks<List<AppEntry>> {

	public static final Comparator<AppEntry> ALPHA_COMPARATOR = new Comparator<AppEntry>() {
		private final Collator sCollator = Collator.getInstance();

		@Override
		public int compare(AppEntry object1, AppEntry object2) {
			return sCollator.compare(object1.getLabel(), object2.getLabel());
		}
	};

	/**
	 * Helper for determining if the configuration has changed in an interesting
	 * way so we need to rebuild the app list.
	 */
	public static class InterestingConfigChanges {
		final Configuration mLastConfiguration = new Configuration();
		int mLastDensity;

		boolean applyNewConfig(Resources res) {
			int configChanges = mLastConfiguration.updateFrom(res
					.getConfiguration());
			boolean densityChanged = mLastDensity != res.getDisplayMetrics().densityDpi;
			if (densityChanged
					|| (configChanges & (ActivityInfo.CONFIG_LOCALE
							| ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_SCREEN_LAYOUT)) != 0) {
				mLastDensity = res.getDisplayMetrics().densityDpi;
				return true;
			}
			return false;
		}
	}

	/**
	 * Helper class to look for interesting changes to the installed apps so
	 * that the loader can be updated.
	 */
	public static class PackageIntentReceiver extends BroadcastReceiver {
		final AppListLoader mLoader;

		public PackageIntentReceiver(AppListLoader loader) {
			mLoader = loader;
			IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
			filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
			filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
			filter.addDataScheme("package");
			mLoader.getContext().registerReceiver(this, filter);
			// Register for events related to sdcard installation.
			IntentFilter sdFilter = new IntentFilter();
			sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
			sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
			mLoader.getContext().registerReceiver(this, sdFilter);
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			// Tell the loader about the change.
			mLoader.onContentChanged();
		}
	}

	/**
	 * A custom Loader that loads all of the installed applications.
	 */
	public static class AppListLoader extends AsyncTaskLoader<List<AppEntry>> {
		final InterestingConfigChanges mLastConfig = new InterestingConfigChanges();
		final PackageManager mPm;

		List<AppEntry> mApps;
		PackageIntentReceiver mPackageObserver;

		public AppListLoader(Context context) {
			super(context);

			mPm = getContext().getPackageManager();
		}

		@Override
		public List<AppEntry> loadInBackground() {
			Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
			List<ResolveInfo> apps = mPm.queryBroadcastReceivers(intent,
					PackageManager.GET_UNINSTALLED_PACKAGES
							| PackageManager.GET_DISABLED_COMPONENTS
							| PackageManager.GET_RECEIVERS);

			// List<ApplicationInfo> apps = mPm
			// .getInstalledApplications(PackageManager.GET_UNINSTALLED_PACKAGES
			// | PackageManager.GET_DISABLED_COMPONENTS);
			if (apps == null) {
				apps = new ArrayList<ResolveInfo>();
			}

			final Context context = getContext();

			// Create corresponding array of entries and load their labels.
			List<AppEntry> entries = new ArrayList<AppEntry>(apps.size());
			for (int i = 0; i < apps.size(); i++) {
				AppEntry entry = new AppEntry(this, apps.get(i), mPm);
				entry.loadLabel(context);
				entries.add(entry);
			}

			// Sort the list.
			Collections.sort(entries, ALPHA_COMPARATOR);

			// Done!
			return entries;
		}

		/**
		 * Called when there is new data to deliver to the client. The super
		 * class will take care of delivering it; the implementation here just
		 * adds a little more logic.
		 */
		@Override
		public void deliverResult(List<AppEntry> apps) {
			if (isReset()) {
				// An async query came in while the loader is stopped. We
				// don't need the result.
				if (apps != null) {
					onReleaseResources(apps);
				}
			}
			List<AppEntry> oldApps = apps;
			mApps = apps;

			if (isStarted()) {
				// If the Loader is currently started, we can immediately
				// deliver its results.
				super.deliverResult(apps);
			}

			// At this point we can release the resources associated with
			// 'oldApps' if needed; now that the new result is delivered we
			// know that it is no longer in use.
			if (oldApps != null) {
				onReleaseResources(oldApps);
			}
		}

		/**
		 * Handles a request to start the Loader.
		 */
		@Override
		protected void onStartLoading() {
			if (mApps != null) {
				// If we currently have a result available, deliver it
				// immediately.
				deliverResult(mApps);
			}

			if (mPackageObserver == null) {
				mPackageObserver = new PackageIntentReceiver(this);
			}

			boolean configChange = mLastConfig.applyNewConfig(getContext()
					.getResources());

			if (takeContentChanged() || mApps == null || configChange) {
				forceLoad();
			}
		}

		/**
		 * Handles a request to stop the Loader.
		 */
		@Override
		protected void onStopLoading() {
			// Attempt to cancel the current load task if possible.
			cancelLoad();
		}

		/**
		 * Handles a request to cancel a load.
		 */
		@Override
		public void onCanceled(List<AppEntry> apps) {
			super.onCanceled(apps);

			// At this point we can release the resources associated with 'apps'
			// if needed.
			onReleaseResources(apps);
		}

		/**
		 * Handles a request to completely reset the Loader.
		 */
		@Override
		protected void onReset() {
			super.onReset();

			// Ensure the loader is stopped
			onStopLoading();

			// At this point we can release the resources associated with 'apps'
			// if needed.
			if (mApps != null) {
				onReleaseResources(mApps);
				mApps = null;
			}

			// Stop monitoring for changes.
			if (mPackageObserver != null) {
				getContext().unregisterReceiver(mPackageObserver);
				mPackageObserver = null;
			}
		}

		/**
		 * Helper function to take care of releasing resources associated with
		 * an actively loaded data set.
		 */
		protected void onReleaseResources(List<AppEntry> apps) {
			// For a simple List<> there is nothing to do. For something
			// like a Cursor, we would close it here.
		}
	}

	public static class AppListAdapter extends ArrayAdapter<AppEntry> {
		private final LayoutInflater mInflater;

		public AppListAdapter(Context context) {
			super(context, android.R.layout.simple_list_item_2);
			mInflater = (LayoutInflater) context
					.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		}

		public void setData(List<AppEntry> data) {
			clear();
			if (data != null) {
				for (AppEntry entry : data) {
					add(entry);
				}
			}
		}

		/**
		 * Populate new items in the list.
		 */
		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view;

			if (convertView == null) {
				view = mInflater.inflate(R.layout.view_item_app, parent, false);
				view.setOnClickListener(null);
			} else {
				view = convertView;
			}

			final AppEntry item = getItem(position);

			((ImageView) view.findViewById(R.id.view_item_app_icon))
					.setImageDrawable(item.getIcon());
			((TextView) view.findViewById(R.id.view_item_app_text))
					.setText(item.getLabel());

			TextView b = (TextView) view.findViewById(R.id.view_item_app_btn);
			final boolean enabled = item.isEnable();
			if (enabled) {
				b.setBackgroundResource(R.drawable.bg_clickable_red);
				b.setText(R.string.disable);
			} else {
				b.setBackgroundResource(R.drawable.bg_clickable_blue);
				b.setText(R.string.enable);
			}

			b.setOnClickListener(new OnClickListener() {

				@Override
				public void onClick(View v) {
					// TODO Auto-generated method stub
						boolean b = RootUtil.setStartupEnable(!enabled, item.getPackageName(), item.getClassName());
						if(b){
							Toast.makeText(getContext(), "aaaaa", Toast.LENGTH_LONG).show();
						}
				}
			});

			return view;
		}
	}

	private AppListAdapter mAdapter;

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onViewCreated(view, savedInstanceState);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onActivityCreated(savedInstanceState);
		setEmptyText("No applications");

		mAdapter = new AppListAdapter(getActivity());
		setListAdapter(mAdapter);
		setListShown(false);
		getListView().setSelector(new ColorDrawable(Color.TRANSPARENT));
		getLoaderManager().initLoader(0, null, this);
		
	}

	@Override
	public Loader<List<AppEntry>> onCreateLoader(int id, Bundle args) {
		return new AppListLoader(getActivity());
	}

	@Override
	public void onLoadFinished(Loader<List<AppEntry>> loader,
			List<AppEntry> data) {
		mAdapter.setData(data);

		if (isResumed()) {
			setListShown(true);
		} else {
			setListShownNoAnimation(true);
		}
	}

	@Override
	public void onLoaderReset(Loader<List<AppEntry>> loader) {
		mAdapter.setData(null);
	}

}

class AppEntry {
	private ResolveInfo mResoveInfo;
	private PackageManager mPm;

	public AppEntry(AppListLoader loader, ResolveInfo info, PackageManager mPm) {
		this.mPm = mPm;
		mLoader = loader;
		mResoveInfo = info;
		mInfo = info.activityInfo.applicationInfo;
		mApkFile = new File(mInfo.sourceDir);
	}

	public ApplicationInfo getApplicationInfo() {
		return mInfo;
	}

	public String getLabel() {
		return mLabel;
	}
	
	public String getPackageName(){
		return mInfo.packageName;
	}
	
	public String getClassName(){
		return mResoveInfo.activityInfo.name;
	}

	public Drawable getIcon() {
		if (mIcon == null) {
			if (mApkFile.exists()) {
				mIcon = mInfo.loadIcon(mLoader.mPm);
				return mIcon;
			} else {
				mMounted = false;
			}
		} else if (!mMounted) {
			if (mApkFile.exists()) {
				mMounted = true;
				mIcon = mInfo.loadIcon(mLoader.mPm);
				return mIcon;
			}
		} else {
			return mIcon;
		}

		return mLoader.getContext().getResources()
				.getDrawable(android.R.drawable.sym_def_app_icon);
	}

	@Override
	public String toString() {
		return mLabel;
	}

	public boolean isEnable() {
		ComponentName mComponentName = new ComponentName(
				mResoveInfo.activityInfo.packageName,
				mResoveInfo.activityInfo.name);
		int state = mPm.getComponentEnabledSetting(mComponentName);
		if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
			return true;
		}
		if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
			return false;
		}
		if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
			return mResoveInfo.activityInfo.enabled;
		}
		return true;
	}

	void loadLabel(Context context) {
		if (mLabel == null || !mMounted) {
			if (!mApkFile.exists()) {
				mMounted = false;
				mLabel = mInfo.packageName;
			} else {
				mMounted = true;
				CharSequence label = mInfo.loadLabel(context
						.getPackageManager());
				mLabel = label != null ? label.toString() : mInfo.packageName;
				mLabel = mLabel + "\n" + mInfo.packageName + "\n"
						+ mResoveInfo.activityInfo.name;
			}
		}
	}

	private final AppListLoader mLoader;
	private final ApplicationInfo mInfo;
	private final File mApkFile;
	private String mLabel;
	private Drawable mIcon;
	private boolean mMounted;
}
