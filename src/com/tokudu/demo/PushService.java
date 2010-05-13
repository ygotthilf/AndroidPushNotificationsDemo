package com.tokudu.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Date;

import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttNotConnectedException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttPersistenceException;
import com.ibm.mqtt.MqttSimpleCallback;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings.Secure;
import android.util.Log;

/* 
 * PushService that does all of the work.
 * Most of the logic is borrowed from KeepAliveService.
 * http://code.google.com/p/android-random/source/browse/trunk/TestKeepAlive/src/org/devtcg/demo/keepalive/KeepAliveService.java?r=219
 */
public class PushService extends Service
{
	// this is the log tag
	public static final String 	   TAG = "DemoPushService";

	// the IP address, where your MQTT broker is running.
	private static final String    MQTT_HOST = "209.124.50.185";
	// the port at which the broker is running. 
	private static int             MQTT_BROKER_PORT_NUM      = 1883;
	// Let's not use the MQTT persistence.
	private static MqttPersistence MQTT_PERSISTENCE          = null;
	// We don't need to remember any state between the connections, so we use a clean start. 
	private static boolean         MQTT_CLEAN_START          = true;
	// Let's set the internal keep alive for MQTT to 15 mins. I haven't tested this value much. It could probably be inreased.
	private static short           MQTT_KEEP_ALIVE           = 60 * 15;
	// Set quality of services to 0 (at most once delivery), since we don't want push notifications 
	// arrive more than once. However, this means that some messages might get lost (delivery is not guaranteed)
	private static int[]           MQTT_QUALITIES_OF_SERVICE = { 0 } ;
	private static int             MQTT_QUALITY_OF_SERVICE   = 0;
	// The broker should not retain any messages.
	private static boolean         MQTT_RETAINED_PUBLISH     = false;
		
	// MQTT client ID, which is given the broker. In this example, I also use this for the topic header. 
	// You can use this to run push notifications for multiple apps with one MQTT broker. 
	public static String 		   MQTT_CLIENT_ID = "com.tokudu.demo";

	// These are the actions for the service (name are descriptive enough)
	private static final String    ACTION_START = MQTT_CLIENT_ID + ".START";
	private static final String    ACTION_STOP = MQTT_CLIENT_ID + ".STOP";
	private static final String    ACTION_KEEPALIVE = MQTT_CLIENT_ID + ".KEEP_ALIVE";
	private static final String    ACTION_RECONNECT = MQTT_CLIENT_ID + ".RECONNECT";
	
	// Connectivity manager to determining, when the phone loses connection
	private ConnectivityManager mConnMan;
	// Notification manager to displaying arrived push notifications 
	private NotificationManager mNotifMan;

	// Whether or not the service has been started.	
	private boolean mStarted;

	// This the application level keep-alive interval, that is used by the AlarmManager
	// to keep the connection active, even when the device goes to sleep.
	private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

	// Retry intervals, when the connection is lost.
	private static final long INITIAL_RETRY_INTERVAL = 1000 * 10;
	private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

	// Preferences instance 
	private SharedPreferences mPrefs;
	// We store in the preferences, whether or not the service has been started
	public static final String PREF_STARTED = "isStarted";
	// We also store the deviceID (target)
	public static final String PREF_DEVICE_ID = "deviceID";
	// We store the last retry interval
	public static final String PREF_RETRY = "retryInterval";

	// Notification id
	private static final int NOTIF_CONNECTED = 0;	
		
	// This is the instance of an MQTT connection.
	private MQTTConnection mConnection;

	// Static method to start the service
	public static void actionStart(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_START);
		ctx.startService(i);
	}

	// Static method to stop the service
	public static void actionStop(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_STOP);
		ctx.startService(i);
	}
	
	// Static method to send a keep alive message
	public static void actionPing(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		ctx.startService(i);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		log("Creating service");

		// Get instances of preferences, connectivity manager and notification manager
		mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
		mConnMan = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
		mNotifMan = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
	
		/* If our process was reaped by the system for any reason we need
		 * to restore our state with merely a call to onCreate.  We record
		 * the last "started" value and restore it here if necessary. */
		handleCrashedService();
	}
	
	// This method does any necessary clean-up need in case the server has been destroyed by the system
	// and then restarted
	private void handleCrashedService() {
		if (wasStarted() == true) {
			log("Handling crashed service...");
			 // stop the keep alives
			stopKeepAlives(); 
				
			// Do a clean start
			start();
		}
	}
	
	@Override
	public void onDestroy() {
		log("Service destroyed (started=" + mStarted + ")");

		// Stop the services, if it has been started
		if (mStarted == true) {
			stop();
		}
	}
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		log("Service started with intent=" + intent);

		// Do an appropriate action based on the intent.
		if (intent.getAction().equals(ACTION_STOP) == true) {
			stop();
			stopSelf();
		} else if (intent.getAction().equals(ACTION_START) == true) {
			start();
		} else if (intent.getAction().equals(ACTION_KEEPALIVE) == true) {
			keepAlive();
		} else if (intent.getAction().equals(ACTION_RECONNECT) == true) {
			reconnectIfNecessary();
		}
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	// log helper function
	private void log(String message) {
		Log.i(TAG, message);
	}
	
	// Reads whether or not the service has been started from the preferences
	private boolean wasStarted() {
		return mPrefs.getBoolean(PREF_STARTED, false);
	}

	// Sets whether or not the services has been started in the preferences.
	private void setStarted(boolean started) {
		mPrefs.edit().putBoolean(PREF_STARTED, started).commit();		
		mStarted = started;
	}

	private synchronized void start() {
		log("Starting service...");
		
		// Do nothing, if the service is already running.
		if (mStarted == true) {
			Log.w(TAG, "Attempt to start connection that is already active");
			return;
		}
		
		// Establish an MQTT connection
		connect();
		
		// Register a connectivity listener
		registerReceiver(mConnectivityChanged, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));		
	}

	private synchronized void stop() {
		// Do nothing, if the service is not running.
		if (mStarted == false) {
			Log.w(TAG, "Attempt to stop connection not active.");
			return;
		}

		// Save stopped state in the preferences
		setStarted(false);

		// Remove the connectivity receiver
		unregisterReceiver(mConnectivityChanged);
		// Any existing reconnect timers should be removed, since we explicitly stopping the service.
		cancelReconnect();

		// Destroy the MQTT connection if there is one
		if (mConnection != null) {
			mConnection.disconnect();
			mConnection = null;
		}
	}
	
	// 
	private synchronized void connect() {		
		log("Connecting...");
		// fetch the device ID from the preferences.
		String deviceID = mPrefs.getString(PREF_DEVICE_ID, null);
		// Create a new connection only if the device id is not NULL
		if (deviceID == null) {
			log("Device ID not found.");
		} else {
			mConnection = new MQTTConnection(MQTT_HOST, deviceID);
			setStarted(true);				
		}
	}

	private synchronized void keepAlive() {
		try {
			// Send a keep alive, if there is a connection.
			if (mStarted == true && mConnection != null) {
				mConnection.sendKeepAlive();
			}
		} catch (IOException e) {
			Log.e(TAG, "Failed to send a keepalive", e);
		}
	}

	// Schedule application level keep-alives using the AlarmManager
	private void startKeepAlives() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP,
		  System.currentTimeMillis() + KEEP_ALIVE_INTERVAL,
		  KEEP_ALIVE_INTERVAL, pi);
	}

	// Remove all scheduled keep alives
	private void stopKeepAlives() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}

	// We schedule a reconnect based on the starttime of the service
	public void scheduleReconnect(long startTime) {
		// the last keep-alive interval
		long interval = mPrefs.getLong(PREF_RETRY, INITIAL_RETRY_INTERVAL);

		// Calculate the elapsed time since the start
		long now = System.currentTimeMillis();
		long elapsed = now - startTime;


		// Set an appropriate interval based on the elapsed time since start 
		if (elapsed < interval) {
			interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
		} else {
			interval = INITIAL_RETRY_INTERVAL;
		}
		
		log("Rescheduling connection in " + interval + "ms.");

		// Save the new internval
		mPrefs.edit().putLong(PREF_RETRY, interval).commit();

		// Schedule a reconnect using the alarm manager.
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
	}
	
	// Remove the scheduled reconnect
	public void cancelReconnect() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager)getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}
	
	private synchronized void reconnectIfNecessary() {		
		if (mStarted == true && mConnection == null) {
			log("Reconnecting...");
			connect();
		}
	}

	// This receiver listeners for network changes and updates the MQTT connection
	// accordingly
	private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			// Get network info
			NetworkInfo info = (NetworkInfo)intent.getParcelableExtra (ConnectivityManager.EXTRA_NETWORK_INFO);
			
			// Is there connectivity?
			boolean hasConnectivity = (info != null && info.isConnected()) ? true : false;

			log("Connectivity changed: connected=" + hasConnectivity);

			if (hasConnectivity) {
				// If there connectivity, we reconnect if necessary
				reconnectIfNecessary();
			} else if (mConnection != null) {
				// if there no connectivity, make sure MQTT connection is destroyed
				mConnection.disconnect();
				mConnection = null;
			}
		}
	};
	
	// Display the topbar notification
	private void showNotification(String text) {
		Notification n = new Notification();
				
		n.flags |= Notification.FLAG_SHOW_LIGHTS;
      	n.flags |= Notification.FLAG_AUTO_CANCEL;

        n.defaults = Notification.DEFAULT_ALL;
      	
		n.icon = com.tokudu.demo.R.drawable.icon;
		n.when = System.currentTimeMillis();

		// Simply open the parent activity
		PendingIntent pi = PendingIntent.getActivity(this, 0,
		  new Intent(this, PushActivity.class), 0);

		// Change the name of the notification here
		n.setLatestEventInfo(this, "Tokudu", text, pi);

		mNotifMan.notify(NOTIF_CONNECTED, n);
	}
	
	// Check if we are online
	private boolean isNetworkAvailable() {
		NetworkInfo info = mConnMan.getActiveNetworkInfo();
		if (info == null) {
			return false;
		}
		return info.isConnected();
	}
	
	// This inner class is a wrapper on top of MQTT client.
	private class MQTTConnection implements MqttSimpleCallback {
		IMqttClient mqttClient = null;
		private long startTime;
		
		// Creates a new connection given the broker address and initial topic
		public MQTTConnection(String brokerHostName, String initTopic) {
			// Create connection spec
	    	String mqttConnSpec = "tcp://" + brokerHostName + "@" + MQTT_BROKER_PORT_NUM;
	        try {
	        	// Create the client and connect
	        	mqttClient = MqttClient.createMqttClient(mqttConnSpec, MQTT_PERSISTENCE);
	        	mqttClient.connect(MQTT_CLIENT_ID, MQTT_CLEAN_START, MQTT_KEEP_ALIVE);

		        // register this client app has being able to receive messages
				mqttClient.registerSimpleHandler(this);
				
				// Subscribe to an initial topic, which is combination of client ID and device ID.
				initTopic = MQTT_CLIENT_ID + "/" + initTopic;
				subscribeToTopic(initTopic);
		
				log("Connection established to " + brokerHostName + " on topic " + initTopic);
		
				// Save start time
				startTime = System.currentTimeMillis();
				// Star the keep-alives
				startKeepAlives();				        
	        } catch (MqttException e) {
	        	Log.e(TAG, "MQQTEXCEPTION" + (e.getMessage() == null? e.getMessage():" NULL"), e);
	        }        
		}
		
		// Disconnect
		public void disconnect() {
			try {			
				stopKeepAlives();
				mqttClient.disconnect();
			} catch (MqttPersistenceException e) {
	        	Log.e(TAG, "MQQTEXCEPTION" + (e.getMessage() == null? e.getMessage():" NULL"), e);
			}
		}
		/*
		 * Send a request to the message broker to be sent messages published with 
		 *  the specified topic name. Wildcards are allowed.	
		 */
		private void subscribeToTopic(String topicName) {
			
			if ((mqttClient == null) || (mqttClient.isConnected() == false)) {
				// quick sanity check - don't try and subscribe if we don't have
				//  a connection
				log("Connection error" + "No connection");	
			} else {									
				try{
					String[] topics = { topicName };
					mqttClient.subscribe(topics, MQTT_QUALITIES_OF_SERVICE);
				} catch (MqttNotConnectedException e) {
					Log.e(TAG, "Connection error" + e.getMessage(), e);			
				} catch (IllegalArgumentException e) {
					Log.e(TAG, "Connection error" + e.getMessage(), e);			
				} catch (MqttException e) {
		        	Log.e(TAG, "MQQTEXCEPTION" + (e.getMessage() == null? e.getMessage():" NULL"), e);
				}
			}
		}	
		/*
		 * Sends a message to the message broker, requesting that it be published
		 *  to the specified topic.
		 */
		private void publishToTopic(String topicName, String message) {		
			if ((mqttClient == null) || (mqttClient.isConnected() == false)) {
				// quick sanity check - don't try and publish if we don't have
				//  a connection
					Log.e(TAG, "no connection publish");		
			} else {
				try {
					mqttClient.publish(topicName, 
									   message.getBytes(),
									   MQTT_QUALITY_OF_SERVICE, 
									   MQTT_RETAINED_PUBLISH);
				} catch (MqttPersistenceException e) {
					Log.e(TAG, "Connection error" + e.getMessage(), e);			
				} catch (MqttNotConnectedException e) {
					Log.e(TAG, "Connection error" + e.getMessage(), e);			
				} catch (IllegalArgumentException e){
					Log.e(TAG, "Connection error" + e.getMessage(), e);			
				} catch (MqttException e) {
					log("Connection error" + e.getMessage());			
				}
			}
		}		
		
		/*
		 * Called if the application loses it's connection to the message broker.
		 */
		public void connectionLost() throws Exception {
			log("Loss of connection" + "connection downed");
			stopKeepAlives();
			// null itself
			mConnection = null;
			if (isNetworkAvailable() == true) {
				scheduleReconnect(startTime);	
			}
		}		
		
		/*
		 * Called when we receive a message from the message broker. 
		 */
		public void publishArrived(String topicName, byte[] payload, int qos, boolean retained) {
			try {
				// Show a notification
				String s = new String(payload);
				showNotification(s);	
				log("Got message: " + s);
			} catch (Exception exc) {
				Log.e("recieving error:",exc.getMessage());
			}
		}   
		
		public void sendKeepAlive() throws IOException{
			// publish to a keep-alive topic
			publishToTopic(MQTT_CLIENT_ID + "/keepalive", mPrefs.getString(PREF_DEVICE_ID, ""));
		}		
	}
}