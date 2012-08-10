package com.newsblur.service;

import java.util.ArrayList;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

import com.newsblur.database.DatabaseConstants;
import com.newsblur.database.FeedProvider;
import com.newsblur.domain.OfflineUpdate;
import com.newsblur.network.APIClient;
import com.newsblur.network.APIManager;

/**
 * The SyncService is based on an app architecture that tries to place network calls
 * (especially larger calls or those called regularly) on independent services, making the 
 * activity / fragment a passive receiver of its updates. This, along with data fragments for  
 * handling UI updates, throttles network access and ensures the UI is passively updated
 * and decoupled from network calls. Examples of other apps using this architecture include 
 * the NBCSportsTalk and Google I/O 2011 apps.
 */
public class SyncService extends IntentService {

	private static final String TAG = "SyncService";
	public static final String EXTRA_STATUS_RECEIVER = "resultReceiverExtra";
	public static final String EXTRA_TASK_FEED_ID = "taskFeedId";
	public static final String EXTRA_TASK_STORY_ID = "taskStoryId";
	public static final String EXTRA_TASK_SOCIALFEED_ID = "userId";
	public static final String EXTRA_TASK_SOCIALFEED_USERNAME = "username";
	public static final String EXTRA_TASK_MARK_SOCIAL_JSON = "socialJson";
	public static final String EXTRA_TASK_PAGE_NUMBER = "page";
	
	public final static int STATUS_RUNNING = 0x02;
	public final static int STATUS_FINISHED = 0x03;
	public final static int STATUS_ERROR = 0x04;
	public static final int NOT_RUNNING = 0x01;
	
	public static final int EXTRA_TASK_FOLDER_UPDATE = 30;
	public static final int EXTRA_TASK_FEED_UPDATE = 31;
	public static final int EXTRA_TASK_REFRESH_COUNTS = 32;
	public static final int EXTRA_TASK_MARK_STORY_READ = 33;
	public static final int EXTRA_TASK_SOCIALFEED_UPDATE = 34;
	public static final int EXTRA_TASK_MARK_SOCIALSTORY_READ = 35;
	
	public APIClient apiClient;
	private APIManager apiManager;
	public static final String SYNCSERVICE_TASK = "syncservice_task";
	
	public SyncService() {
		super(TAG);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		apiManager = new APIManager(this);
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		Log.d(TAG, "Received SyncService handleIntent call.");
		final ResultReceiver receiver = intent.getParcelableExtra(EXTRA_STATUS_RECEIVER);
		try {
			if (receiver != null) {
				receiver.send(STATUS_RUNNING, Bundle.EMPTY);
			}
			
			switch (intent.getIntExtra(SYNCSERVICE_TASK , -1)) {
			case EXTRA_TASK_FOLDER_UPDATE:
				apiManager.getFolderFeedMapping();
				break;
				
				// For the moment, we only retry offline updates when we refresh counts. We also assume here that every update is to mark a story as read.
			case EXTRA_TASK_REFRESH_COUNTS:
				Cursor cursor = getContentResolver().query(FeedProvider.OFFLINE_URI, null, null, null, null);
				while (cursor.moveToNext()) {
					OfflineUpdate update = OfflineUpdate.fromCursor(cursor);
					ArrayList<String> storyId = new ArrayList<String>();
					storyId.add(update.arguments[1]);
					if (apiManager.markStoryAsRead(update.arguments[0], storyId)) {
						getContentResolver().delete(FeedProvider.OFFLINE_URI, DatabaseConstants.UPDATE_ID + " = ?", new String[] { Integer.toString(update.id) });
					}
				}
				apiManager.refreshFeedCounts();
				break;	
				
			case EXTRA_TASK_MARK_STORY_READ:
				final String feedId = intent.getStringExtra(EXTRA_TASK_FEED_ID);
				final ArrayList<String> storyIds = intent.getStringArrayListExtra(EXTRA_TASK_STORY_ID);
				if (!TextUtils.isEmpty(feedId) && storyIds.size() > 0) {
					if (!apiManager.markStoryAsRead(feedId, storyIds)) {
						for (String storyId : storyIds) {
							OfflineUpdate update = new OfflineUpdate();
							update.arguments = new String[] { feedId, storyId };
							update.type = OfflineUpdate.UpdateType.MARK_FEED_AS_READ;
							getContentResolver().insert(FeedProvider.OFFLINE_URI, update.getContentValues());
						}
					}
				} else {
					Log.e(TAG, "No feed/stories to mark as read included in SyncRequest");
					receiver.send(STATUS_ERROR, Bundle.EMPTY);
				}
				break;
			case EXTRA_TASK_MARK_SOCIALSTORY_READ:
				final String markSocialJson = intent.getStringExtra(EXTRA_TASK_MARK_SOCIAL_JSON);
				if (!TextUtils.isEmpty(markSocialJson)) {
					apiManager.markSocialStoryAsRead(markSocialJson);
				} else {
					Log.e(TAG, "No feed/story to mark as read included in SyncRequest");
					receiver.send(STATUS_ERROR, Bundle.EMPTY);
				}
				break;
			case EXTRA_TASK_FEED_UPDATE:
				if (!TextUtils.isEmpty(intent.getStringExtra(EXTRA_TASK_FEED_ID))) {
					apiManager.getStoriesForFeed(intent.getStringExtra(EXTRA_TASK_FEED_ID), intent.getStringExtra(EXTRA_TASK_PAGE_NUMBER));
				} else {
					Log.e(TAG, "No feed to refresh included in SyncRequest");
					receiver.send(STATUS_ERROR, Bundle.EMPTY);
				}
				break;
			case EXTRA_TASK_SOCIALFEED_UPDATE:
				if (!TextUtils.isEmpty(intent.getStringExtra(EXTRA_TASK_SOCIALFEED_ID)) && !TextUtils.isEmpty(intent.getStringExtra(EXTRA_TASK_SOCIALFEED_USERNAME))) {
					apiManager.getStoriesForSocialFeed(intent.getStringExtra(EXTRA_TASK_SOCIALFEED_ID), intent.getStringExtra(EXTRA_TASK_SOCIALFEED_USERNAME), intent.getStringExtra(EXTRA_TASK_PAGE_NUMBER));
				} else {
					Log.e(TAG, "Missing parameters forsocialfeed SyncRequest");
					receiver.send(STATUS_ERROR, Bundle.EMPTY);
				}
				break;		
			default:
				Log.e(TAG, "SyncService called without relevant task assignment");
				break;
			}
		} catch (Exception e) {
			e.printStackTrace();
			Log.e(TAG, "Couldn't synchronise with Newsblur servers: " + e.getMessage(), e.getCause());
			if (receiver != null) {
				final Bundle bundle = new Bundle();
				bundle.putString(Intent.EXTRA_TEXT, e.toString());
				receiver.send(STATUS_ERROR, bundle);
			}
		}
	
		if (receiver != null) {
			receiver.send(STATUS_FINISHED, Bundle.EMPTY);
		} else {
			Log.e(TAG, "No receiver attached to Sync?");
		}
	}

}
