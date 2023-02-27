package org.joinmastodon.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.notifications.GetNotificationByID;
import org.joinmastodon.android.api.requests.statuses.SetStatusBookmarked;
import org.joinmastodon.android.api.requests.statuses.SetStatusFavorited;
import org.joinmastodon.android.api.requests.statuses.SetStatusReblogged;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.NotificationAction;
import org.joinmastodon.android.model.Preferences;
import org.joinmastodon.android.model.PushNotification;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.parceler.Parcels;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class PushNotificationReceiver extends BroadcastReceiver{
	private static final String TAG="PushNotificationReceive";

	public static final int NOTIFICATION_ID=178;
	private static int notificationId = 0;

	@Override
	public void onReceive(Context context, Intent intent){
		if(BuildConfig.DEBUG){
			Log.e(TAG, "received: "+intent);
			Bundle extras=intent.getExtras();
			for(String key : extras.keySet()){
				Log.i(TAG, key+" -> "+extras.get(key));
			}
		}
		if("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
			String k=intent.getStringExtra("k");
			String p=intent.getStringExtra("p");
			String s=intent.getStringExtra("s");
			String pushAccountID=intent.getStringExtra("x");
			if(!TextUtils.isEmpty(pushAccountID) && !TextUtils.isEmpty(k) && !TextUtils.isEmpty(p) && !TextUtils.isEmpty(s)){
				MastodonAPIController.runInBackground(()->{
					try{
						List<AccountSession> accounts=AccountSessionManager.getInstance().getLoggedInAccounts();
						AccountSession account=null;
						for(AccountSession acc:accounts){
							if(pushAccountID.equals(acc.pushAccountID)){
								account=acc;
								break;
							}
						}
						if(account==null){
							Log.w(TAG, "onReceive: account for id '"+pushAccountID+"' not found");
							return;
						}
						String accountID=account.getID();
						PushNotification pn=AccountSessionManager.getInstance().getAccount(accountID).getPushSubscriptionManager().decryptNotification(k, p, s);
						new GetNotificationByID(pn.notificationId+"")
								.setCallback(new Callback<>(){
									@Override
									public void onSuccess(org.joinmastodon.android.model.Notification result){
										MastodonAPIController.runInBackground(()->PushNotificationReceiver.this.notify(context, pn, accountID, result));
									}

									@Override
									public void onError(ErrorResponse error){
										MastodonAPIController.runInBackground(()->PushNotificationReceiver.this.notify(context, pn, accountID, null));
									}
								})
								.exec(accountID);
					}catch(Exception x){
						Log.w(TAG, x);
					}
				});
			}else{
				Log.w(TAG, "onReceive: invalid push notification format");
			}
		}
		if(intent.getBooleanExtra("fromNotificationAction", false)) {
			String accountID=intent.getStringExtra("accountID");
			int notificationId=intent.getIntExtra("notificationId", -1);

			if ( notificationId >= 0) {
				NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
				notificationManager.cancel(notificationId);
			}

			if(intent.hasExtra("notification")){
				org.joinmastodon.android.model.Notification notification=Parcels.unwrap(intent.getParcelableExtra("notification"));
				String statusID=notification.status.id;
				if (statusID != null) {
					AccountSessionManager accountSessionManager = AccountSessionManager.getInstance();
					Preferences preferences = accountSessionManager.getAccount(accountID).preferences;

					switch (NotificationAction.values()[intent.getIntExtra("notificationAction", 0)]) {
						case FAVORITE -> new SetStatusFavorited(statusID, true).exec(accountID);
						case BOOKMARK -> new SetStatusBookmarked(statusID, true).exec(accountID);
						case BOOST -> new SetStatusReblogged(notification.status.id, true, preferences.postingDefaultVisibility).exec(accountID);
						case UNBOOST -> new SetStatusReblogged(notification.status.id, false, preferences.postingDefaultVisibility).exec(accountID);
						default -> Log.w(TAG, "onReceive: Failed to get NotificationAction");
					}
				}
			} else {
				Log.e(TAG, "onReceive: Failed to load notification");
			}
		}
	}

	private void notify(Context context, PushNotification pn, String accountID, org.joinmastodon.android.model.Notification notification){
		NotificationManager nm=context.getSystemService(NotificationManager.class);
		Account self=AccountSessionManager.getInstance().getAccount(accountID).self;
		String accountName="@"+self.username+"@"+AccountSessionManager.getInstance().getAccount(accountID).domain;
		Notification.Builder builder;
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
			boolean hasGroup=false;
			List<NotificationChannelGroup> channelGroups=nm.getNotificationChannelGroups();
			for(NotificationChannelGroup group:channelGroups){
				if(group.getId().equals(accountID)){
					hasGroup=true;
					break;
				}
			}
			if(!hasGroup){
				NotificationChannelGroup group=new NotificationChannelGroup(accountID, accountName);
				nm.createNotificationChannelGroup(group);
				List<NotificationChannel> channels=Arrays.stream(PushNotification.Type.values())
						.map(type->{
							NotificationChannel channel=new NotificationChannel(accountID+"_"+type, context.getString(type.localizedName), NotificationManager.IMPORTANCE_DEFAULT);
							channel.setGroup(accountID);
							return channel;
						})
						.collect(Collectors.toList());
				nm.createNotificationChannels(channels);
			}
			builder=new Notification.Builder(context, accountID+"_"+pn.notificationType);
		}else{
			builder=new Notification.Builder(context)
					.setPriority(Notification.PRIORITY_DEFAULT)
					.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
		}
		Drawable avatar=ImageCache.getInstance(context).get(new UrlImageLoaderRequest(pn.icon, V.dp(50), V.dp(50)));
		Intent contentIntent=new Intent(context, MainActivity.class);
		contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		contentIntent.putExtra("fromNotification", true);
		contentIntent.putExtra("accountID", accountID);
		if(notification!=null){
			contentIntent.putExtra("notification", Parcels.wrap(notification));
		}
		builder.setContentTitle(pn.title)
				.setContentText(pn.body)
				.setStyle(new Notification.BigTextStyle().bigText(pn.body))
				.setSmallIcon(R.drawable.ic_ntf_logo)
				.setContentIntent(PendingIntent.getActivity(context, notificationId, contentIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
				.setWhen(notification==null ? System.currentTimeMillis() : notification.createdAt.toEpochMilli())
				.setShowWhen(true)
				.setCategory(Notification.CATEGORY_SOCIAL)
				.setAutoCancel(true)
				.setColor(context.getColor(R.color.shortcut_icon_background));

		if (!GlobalUserPreferences.uniformNotificationIcon) {
			builder.setSmallIcon(switch (pn.notificationType) {
				case FAVORITE -> R.drawable.ic_fluent_star_24_filled;
				case REBLOG -> R.drawable.ic_fluent_arrow_repeat_all_24_filled;
				case FOLLOW -> R.drawable.ic_fluent_person_add_24_filled;
				case MENTION -> R.drawable.ic_fluent_mention_24_filled;
				case POLL -> R.drawable.ic_fluent_poll_24_filled;
				case STATUS -> R.drawable.ic_fluent_chat_24_filled;
				case UPDATE -> R.drawable.ic_fluent_history_24_filled;
				case REPORT -> R.drawable.ic_fluent_warning_24_filled;
				case SIGN_UP -> R.drawable.ic_fluent_person_available_24_filled;
			});
		}

		if(avatar!=null){
			builder.setLargeIcon(UiUtils.getBitmapFromDrawable(avatar));
		}
		if(AccountSessionManager.getInstance().getLoggedInAccounts().size()>1){
			builder.setSubText(accountName);
		}

		int id = GlobalUserPreferences.keepOnlyLatestNotification ? NOTIFICATION_ID : notificationId++;

		if (notification != null) {
			switch (pn.notificationType) {
				case MENTION, STATUS -> {
					builder.addAction(buildNotificationAction(context, id, accountID, notification,  context.getString(R.string.sk_notification_action_favorite), NotificationAction.FAVORITE));
					builder.addAction(buildNotificationAction(context, id, accountID, notification, context.getString(R.string.sk_notification_action_bookmark), NotificationAction.BOOKMARK));
					if(notification.status.visibility != StatusPrivacy.DIRECT)
						builder.addAction(buildNotificationAction(context, id, accountID, notification,  context.getString(R.string.sk_notification_action_boost), NotificationAction.BOOST));
				}
				case UPDATE -> {
					if(notification.status.reblogged)
						builder.addAction(buildNotificationAction(context, id, accountID, notification,  context.getString(R.string.sk_notification_action_unboost), NotificationAction.UNBOOST));
				}
			}
		}

		nm.notify(accountID, id, builder.build());
	}

	private Notification.Action buildNotificationAction(Context context, int notificationId, String accountID, org.joinmastodon.android.model.Notification notification, String title, NotificationAction action){
		Intent notificationIntent=new Intent(context, PushNotificationReceiver.class);
		notificationIntent.putExtra("notificationId", notificationId);
		notificationIntent.putExtra("fromNotificationAction", true);
		notificationIntent.putExtra("accountID", accountID);
		notificationIntent.putExtra("notificationAction", action.ordinal());
		notificationIntent.putExtra("notification", Parcels.wrap(notification));
		PendingIntent actionPendingIntent =
				PendingIntent.getBroadcast(context, new Random().nextInt(), notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

		return new Notification.Action.Builder(null, title, actionPendingIntent).build();
	}
}
