/*
 * qaul.net is free software
 * licensed under GPL (version 3)
 */

package net.qaul.qaul;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
//import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.Build;
import android.preference.PreferenceManager;
import android.tether.system.Configuration;
import android.tether.system.CoreTask;
import android.util.Log;
import android.widget.Toast;

public class QaulApplication extends Application {
	public static final String MSG_TAG = "QaulApplication";
	
	// Devices-Information
	//public String deviceType = Configuration.DEVICE_GENERIC; 
	//public String interfaceDriver = Configuration.DRIVER_WEXT; 
	
	// StartUp-Check performed
	public boolean startupCheckPerformed = false;

	// WifiManager
	private WifiManager mWifiManager;
    private WifiManagerNew mWifiManagerNew;
    private WifiConfigurationNew wifiConfig;
	
	// PowerManagement
	private PowerManager powerManager = null;
	private PowerManager.WakeLock wakeLock = null;

	// Preferences
	public SharedPreferences settings = null;
	public SharedPreferences.Editor preferenceEditor = null;
	
    // Notification
	public NotificationManager notificationManager;
	private Notification notification;
	
	// Intents
	private PendingIntent mainIntent;
	//private PendingIntent accessControlIntent;
    
	// Original States
	private static boolean origWifiState = false;
	// Supplicant
	public CoreTask.WpaSupplicant wpasupplicant = null;
	// TiWlan.conf
	public CoreTask.TiWlanConf tiwlan = null;
	// tether.conf
	public CoreTask.TetherConfig tethercfg = null;
	
	// CoreTask
	public CoreTask coretask = null;
	
	// QaulActivity
	private QaulActivity myMainActivity = null;

	// -------------------------------------------------------------------
	// qaul variables & functions
	// -------------------------------------------------------------------
    NativeQaul nativeQaul;
    static String dataPathString;
    int qaulStarted = -1;
    int qaulWifiMethod = 0;
    
    // these are crashing the app ... use a different setup
    // timers & events
    boolean qaulTimersStop = false;
    private Handler qaulStartHandler = new Handler();
    private Runnable qaulStartDelayed = new Runnable(){ 
    	public void run() {
    		qaulStarted++;
    		qaulConfigure();
    	}
    };
    
    private Runnable qaulCheckEvents = new Runnable(){ 
    	public void run() {
    		int event = nativeQaul.timedCheckAppEvent();
    		if(event == 0)
    		{
    			// nothing happened, no action to take
    		}
    		else if(event == 99)
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: quit app");
    			qaulQuit();
    		}
    		else if(event == 100)
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: open file chooser");
    			qaulSelectFile();
    		}
    		else if(event == 101)
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: open file");
    			qaulOpenFile(nativeQaul.getAppEventOpenPath());
    		}
    		else if(event == 102)
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: open URL");
    			qaulOpenURL(nativeQaul.getAppEventOpenURL());
    		}
    		else if(event == 103 || event == 104)
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: notify");
    			
    			// Get instance of Vibrator from current Context
    			Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    			// Vibrate for 300 milliseconds
    			v.vibrate(300);

    			// play ring tone
    			// fixme: seems not to work
    			Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    			Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
    			r.play();
    		}
    		else
    		{
    			Log.d(MSG_TAG, "qaulCheckEvents: unknown event "+event);
    		}
    		
    		if(!qaulTimersStop) qaulStartHandler.postDelayed(qaulCheckEvents, 300);
    		else Log.d(MSG_TAG, "qaulCheckEvents: stopped");
    	}
    };

	public void qaulInitLib()
    {
		// common paths
		File dataPath = getFilesDir();
        dataPathString = dataPath.toString();
        
        // check if this is the first time this app is started
        // copy all files
        Log.i(MSG_TAG, "check if data exists");
        File filesPath = new File(dataPathString +"/files");
        if(!filesPath.exists())
        {
        	Log.i(MSG_TAG, "copy all files to data directory");
        	qaulCopyFileOrDir("files");
        }
        
        // initialize the library
        nativeQaul = new NativeQaul();
        Log.i(MSG_TAG, String.format("Files directory is: %s", dataPathString));
        nativeQaul.libInit(dataPath.toString(), dataPath.toString());
        // start web server
        nativeQaul.webserverStart(this.getAssets());

        Log.i(MSG_TAG, "qaulInitLib() web server started");
    }
	
	public void qaulConfigure()
	{
		// initialize
		if(qaulStarted == 0)
		{
	        Log.i(MSG_TAG, "qaulConfigure 0");
			// already initialized
			qaulStarted = 10;
		}
		
		// check authorization rights
        // code from android.tether Main.Activity.java
        // Startup-Check
		if(qaulStarted == 10)
		{
	        Log.i(MSG_TAG, "qaulConfigure 10");
        	// Check root-permission, files
	    	if (!this.coretask.hasRootPermission())
	    	{
	    		Log.e(MSG_TAG, "has no root permission");
	    		// TODO: show error dialog
	    	}
	    	// FIXME: wait until root permissions are granted...
	    	
	        qaulStarted = 11;
		}

		// install files
		if(qaulStarted == 11)
		{
	        Log.i(MSG_TAG, "qaulConfigure 11");
	    	// Check if binaries need to be updated
	        if (this.binariesExists() == false || this.coretask.filesetOutdated()) {
	    		Log.i(MSG_TAG, "install / update binaries");
	        	this.installFiles();
	        	// wait until installed
	        	qaulStartHandler.postDelayed(qaulStartDelayed, 5000);
	        }
	    	else
	    	{
	    		Log.i(MSG_TAG, "binaries already installed");
	    		qaulStarted = 13;
	    	}
		}		

		// wait until files are installed
		if(qaulStarted == 12)
		{
			Log.i(MSG_TAG, "qaulConfigure 12");
			// TODO: do this differently: check if thread has finished...
			if (this.binariesExists() == false || this.coretask.filesetOutdated()) {
				Log.i(MSG_TAG, "not finished installing yet");
				// wait until all files are installed / updated
				qaulStarted--;
				qaulStartHandler.postDelayed(qaulStartDelayed, 1000);
			}
			else 
				qaulStarted = 13;
		}

		// start UI configuration
		if(qaulStarted == 13)
		{
			nativeQaul.configStart();
			qaulStarted = 20;
		}		
		
		// configure wifi
		if(qaulStarted == 20)
		{
	        Log.i(MSG_TAG, "qaulConfigure 20");
	        
	        // try if the java API to configure IBSS is present
	        // (this method currently onyl works on some phones with cyanogen mod)
	        if(startWifiConfigCyanogen())
	        {
	        	Log.i(MSG_TAG, "Wifi configured via cyanogen: startWifiConfigCyanogen()");
	        	qaulWifiMethod = 1;
	        	// create tether configuration file
	        	updateConfiguration();
	        	
	        	qaulStarted = 30;
	        }
	        else
	        {
	        	Log.i(MSG_TAG, "Wifi cyanogen method not present: startWifiConfigCyanogen()");
	        	
	        	if(startTether()) 
				{
	        		Log.d(MSG_TAG, "wifi successfully configured");
					this.showStartNotification();
				}
				else 
				{
					// TODO: show error screen
					Log.e(MSG_TAG, "wifi configuration error");
				}
        		qaulWifiMethod = 2;
				
				qaulStarted = 29;	        	
	        }
		}

		// wait for wifi to start
		// TODO: wait for wifi
		if(qaulStarted == 29)
		{
			Log.i(MSG_TAG, "qaulConfigure 29");
			qaulStartHandler.postDelayed(qaulStartDelayed, 2000);
		}
		
		// check if user name has been set
		if(qaulStarted == 30)
		{
	        Log.i(MSG_TAG, "qaulConfigure 30");
			if(nativeQaul.existsUsername() == 1) 
				qaulStarted = 40;
			else
			{
				Log.d(MSG_TAG, "wait for username ...");
				qaulStarted--;
				qaulStartHandler.postDelayed(qaulStartDelayed, 500);
			}
		}
		
		// start routing
		if(qaulStarted == 40)
		{
	        Log.i(MSG_TAG, "qaulConfigure 40");
			// start olsr
	        Log.d(MSG_TAG, "start olsrd on interface " +this.tethercfg.get("wifi.interface"));
	        if (this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/olsrd_start.sh "+this.tethercfg.get("wifi.interface")))
	        {
	        	Log.i(MSG_TAG, "qaulConfigure 40 done");
	        	qaulStarted = 49;
	        }
	        else
	        {
	        	Log.i(MSG_TAG, "qaulConfigure 40 error!");
	        	qaulStarted = 49;
	        }
		}
		
		// wait for olsrd to start
		if(qaulStarted == 49)
		{
			Log.i(MSG_TAG, "qaulConfigure 49");
			qaulStartHandler.postDelayed(qaulStartDelayed, 500);
		}
		
		// start services
		// start captive portal
		if(qaulStarted == 50)
		{
	        Log.i(MSG_TAG, "qaulConfigure 50");
	        
	    	// start captive portal
			nativeQaul.captiveStart();
	          
	        // Check if required kernel-features are enabled
	    	if (this.coretask.isNetfilterSupported()) 
	    	{
	    		Log.i(MSG_TAG, "net filter is supported");
	    		
	    		// TODO: check if /system/bin/iptables executable exists
	    		//       how is tether app doing that?
	    		//File f = new File("/system/bin/iptables");
	    		//if(f.exists()) 
	    		//File f = getContext().getFileStreamPath("/system/bin/iptables");
	    		File f = this.getFileStreamPath("");
	    		if(f.exists()) 
	    			Log.i(MSG_TAG, "/system/bin/iptables exists");
	    		else 
	    			Log.i(MSG_TAG, "/system/bin/iptables does not exist");

	    		// start iptables
	    		this.coretask.runRootCommand("/data/data/net.qaul.qaul/bin/iptables_start.sh " +this.tethercfg.get("wifi.interface") +" " +nativeQaul.getIP());	    		
	    	}
	    	else 
	    	{
	    		Log.i(MSG_TAG, "net filter is not supported");
	    		// start socket based port forwarding
	    		this.coretask.runRootCommand("/data/data/net.qaul.qaul/bin/socat_start.sh");	    		
	    	}

	    	qaulStarted = 51;
		}

		// start ipc
		if(qaulStarted == 51)
		{
	        Log.i(MSG_TAG, "qaulConfigure 51");
			// start ipc
	        nativeQaul.ipcConnect();
	        
			qaulStarted = 52;
		}

		// start timers
		if(qaulStarted == 52)
		{
	        Log.i(MSG_TAG, "qaulConfigure 52");
			qaulTimersStart();
			qaulStarted = 53;
		}

		// finished
		if(qaulStarted == 53)
		{
	        Log.i(MSG_TAG, "qaulConfigure 53");
	        nativeQaul.configurationFinished();
			qaulStarted = 60;
		}
	}
    
    private void qaulCopyFileOrDir(String path) 
    {
        AssetManager assetManager = this.getAssets();
        String assets[] = null;
        try {
            Log.i(MSG_TAG, "copyFileOrDir() "+path);
            assets = assetManager.list(path);
            if (assets.length == 0) {
            	qaulCopyFile(path);
            } else {
                String fullPath =  dataPathString +"/" + path;
                Log.i(MSG_TAG, "path="+fullPath);
                File dir = new File(fullPath);
                if (!dir.exists() && !path.startsWith("images") && !path.startsWith("sounds") && !path.startsWith("webkit"))
                    if (!dir.mkdirs());
                        Log.i(MSG_TAG, "could not create dir "+fullPath);
                for (int i = 0; i < assets.length; ++i) {
                    String p;
                    if (path.equals(""))
                        p = "";
                    else 
                        p = path + "/";

                    if (!path.startsWith("images") && !path.startsWith("sounds") && !path.startsWith("webkit"))
                    	qaulCopyFileOrDir( p + assets[i]);
                }
            }
        } catch (IOException ex) {
            Log.e(MSG_TAG, "I/O Exception", ex);
        }
    }

    private void qaulCopyFile(String filename) 
    {
        AssetManager assetManager = this.getAssets();

        InputStream in = null;
        OutputStream out = null;
        String newFileName = null;
        try {
            Log.i(MSG_TAG, "copyFile() "+filename);
            in = assetManager.open(filename);
            if (filename.endsWith(".jpg")) // extension was added to avoid compression on APK file
                newFileName = dataPathString +"/" + filename.substring(0, filename.length()-4);
            else
                newFileName = dataPathString +"/" + filename;
            out = new FileOutputStream(newFileName);

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            in = null;
            out.flush();
            out.close();
            out = null;
        } catch (Exception e) {
            Log.e(MSG_TAG, "Exception in copyFile() of "+newFileName);
            Log.e(MSG_TAG, "Exception in copyFile() "+e.toString());
        }
    }

    private void qaulCopyFile(File src, File dst) 
    {
        try {
	    	InputStream in = new FileInputStream(src);
	        OutputStream out = new FileOutputStream(dst);
	
	        // Transfer bytes from in to out
	        byte[] buf = new byte[1024];
	        int len;
	        while ((len = in.read(buf)) > 0) {
	            out.write(buf, 0, len);
	        }
	        in.close();
	        out.close();
        }  catch (Exception e) {
            Log.e(MSG_TAG, "Exception in copyFile(src, dst)");
            Log.e(MSG_TAG, "Exception in copyFile(src, dst) "+e.toString());
        }
    }
    
    private void qaulTimersStart()
    {
    	this.qaulTimersStop = false;
    	// FIXME: this functions crashes the app
    	// -> make a callback function
    	qaulStartHandler.postDelayed(qaulCheckEvents, 0);
    	//qaulStartHandler.postDelayed(qaulCheckSockets, 0);
    	//qaulStartHandler.postDelayed(qaulCheckTopology, 0);
    }
    
    private void qaulTimersStop()
    {
    	this.qaulTimersStop = true;
    }

    private void qaulQuit()
    {
    	Log.d(MSG_TAG, "Calling qaulQuit()");
    	
    	// qaul: stop timers
		qaulTimersStop();
		// qaul: stop ipc
		nativeQaul.ipcClose();
		// qaul: stopping library
		nativeQaul.libExit();
    	// qaul: stopping olsr
		this.coretask.runRootCommand("killall olsrd");
		// qaul: stop portforwarding
    	if (!this.coretask.isNetfilterSupported()) {
    		this.coretask.runRootCommand("/data/data/net.qaul.qaul/bin/iptables_stop.sh");
    	}
    	else {
    		this.coretask.runRootCommand("killall socat");
    	}
		// Stopping Tether
    	if(qaulWifiMethod == 2)
    	{
    		this.stopTether();
    		
    		// switch wifi on and off to disable wifi
    		mWifiManager.setWifiEnabled(true);
    		try {
    			Thread.sleep(500);
    		} catch (InterruptedException e) {
    			// nothing
    		}
    		mWifiManager.setWifiEnabled(false);
    		try {
    			Thread.sleep(500);
    		} catch (InterruptedException e) {
    			// nothing
    		}
    	}
    	
		// Remove all notifications
		this.notificationManager.cancelAll();
		
		// kill this process
		System.exit(0);
    }
    
    private void qaulSelectFile()
    {
    	Log.d(MSG_TAG, "Calling qaulSelectFile()");
    	if(myMainActivity != null)
    	{
    		Log.d(MSG_TAG, "QaulActivity reference set");
    		myMainActivity.openFilePicker();
    	}
    }
    
    private void qaulOpenFile(String myPath)
    {
		// check if sdcard is mounted
		if(android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED))
		{
			// check if location on sdcard exists
			String myDirPath = "/sdcard/qaul";
			File myDir = new File(myDirPath);
	    	if (myDir.exists() == false) 
	    	{
	    		if (!myDir.mkdir()) 
	    		{
	    			Log.d(MSG_TAG, "ERROR: unable to create " +myDirPath);
	    		}
	    	}
	    	if (myDir.exists()) 
	    	{
				// check if file exists on sdcard
	    		String myNewPath = myDirPath +"/" +myPath.substring(myPath.lastIndexOf("/")+1);
	    		File myNewFile = new File(myNewPath);
				if(myNewFile.exists() == false)
				{
					// copy file to sdcard
					File mySrcFile = new File(myPath);
					this.qaulCopyFile(mySrcFile, myNewFile);
				}
				if(myNewFile.exists())
				{
			    	// open file
			    	Log.d(MSG_TAG, "Calling qaulOpenFile() "+myPath);
			    	if(myMainActivity != null)
			    	{
			    		Log.d(MSG_TAG, "QaulActivity reference set");
			    		myMainActivity.openFile(myNewFile);
			    	}
				}
	    	}
		}
    }
    
    private void qaulOpenURL(String myURL)
    {
    	Uri uriUrl = Uri.parse(myURL);
    	Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
    	
    	if(myMainActivity != null)
    	{
    		Log.d(MSG_TAG, "QaulActivity reference set");
    		myMainActivity.startActivity(launchBrowser);
    	}
    	else
    		Log.d(MSG_TAG, "QaulActivity no Activity set");
    	
    }
    
    public void qaulSetActivity(QaulActivity myActivity)
    {
    	myMainActivity = myActivity;
    }
    
    public void qaulRemoveActivity()
    {
    	myMainActivity = null;
    }
	// -------------------------------------------------------------------
	// end qaul functions
	// -------------------------------------------------------------------
	
	@Override
	public void onCreate() {
		Log.d(MSG_TAG, "Calling onCreate()");
		
		//create CoreTask
		this.coretask = new CoreTask();
		this.coretask.setPath(this.getApplicationContext().getFilesDir().getParent());
		Log.d(MSG_TAG, "Current directory is "+this.getApplicationContext().getFilesDir().getParent());

        // Check Homedir, or create it
        this.checkDirs(); 
        
        // qaul: initialize library
        this.qaulInitLib();
        
        // Set device-information
        //this.deviceType = Configuration.getDeviceType();
        //this.interfaceDriver = Configuration.getWifiInterfaceDriver(this.deviceType);
        
        // Preferences
		this.settings = PreferenceManager.getDefaultSharedPreferences(this);
		
        // preferenceEditor
        this.preferenceEditor = settings.edit();
		
        // init wifiManager
        mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE); 
        mWifiManagerNew = new WifiManagerNew(mWifiManager);
        
        // Supplicant config
        this.wpasupplicant = this.coretask.new WpaSupplicant();
        
        // tiwlan.conf
        this.tiwlan = this.coretask.new TiWlanConf();
        
        // tether.cfg
        this.tethercfg = this.coretask.new TetherConfig();
        this.tethercfg.read();

        // Powermanagement
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TETHER_WAKE_LOCK");

        // init notificationManager
        this.notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
    	this.notification = new Notification(R.drawable.start_notification, "qaul.net running", System.currentTimeMillis());
    	this.mainIntent = PendingIntent.getActivity(this, 0, new Intent(this, QaulActivity.class), 0);

    	// qaul: start configuration
        Log.i(MSG_TAG, "before qaulStartHandler");
    	qaulStartHandler.postDelayed(qaulStartDelayed, 3000);
        Log.i(MSG_TAG, "after qaulStartHandler");
	}

	@Override
	public void onTerminate() {
		Log.d(MSG_TAG, "Calling onTerminate()");
		
		// this will never be called in a production environment (according to documentation)
	}
		
	public void updateConfiguration() {
		// TODO: get configuration from library
		
		long startStamp = System.currentTimeMillis();
		
		boolean encEnabled = this.settings.getBoolean("encpref", false);
        String txpower = this.settings.getString("txpowerpref", "disabled");
        
        this.tethercfg.read();
        
		// write system config
		//this.tethercfg.put("device.type", deviceType);
		this.tethercfg.put("device.model", android.os.Build.MODEL);
		this.tethercfg.put("device.product", android.os.Build.PRODUCT);
		this.tethercfg.put("device.manufacturer", android.os.Build.MANUFACTURER);
		this.tethercfg.put("device.device", android.os.Build.DEVICE);
		this.tethercfg.put("device.brand", android.os.Build.BRAND);
		this.tethercfg.put("device.fingerprint", android.os.Build.FINGERPRINT);
		this.tethercfg.put("device.hardware", android.os.Build.HARDWARE);
		this.tethercfg.put("device.id", android.os.Build.ID);
		this.tethercfg.put("device.board", android.os.Build.BOARD);
		
		this.tethercfg.put("os.name", System.getProperty("os.name"));
		this.tethercfg.put("os.version", System.getProperty("os.version"));
		this.tethercfg.put("os.version2",  android.os.Build.VERSION.INCREMENTAL);
		this.tethercfg.put("os.version3",  android.os.Build.VERSION.RELEASE);
		this.tethercfg.put("os.API", android.os.Build.VERSION.SDK);
		this.tethercfg.put("os.API_INT", Integer.toString(android.os.Build.VERSION.SDK_INT));
		this.tethercfg.put("os.arch", System.getProperty("os.arch"));
		
		this.tethercfg.put("os.wifi.driver", Configuration.getWifiModule());
		this.tethercfg.put("os.wifi.firmware_path", Configuration.getFirmwarePath());
		this.tethercfg.put("os.wifi.nvram_path", Configuration.getNvramPath());
		 
        // write wifi config
		this.tethercfg.put("wifi.essid", nativeQaul.getWifiIbss());
        this.tethercfg.put("wifi.channel", Integer.toString(nativeQaul.getWifiChannel()));
		//this.tethercfg.put("ip.network", "10.0.0.0");
		int netmask = nativeQaul.getNetMask();
		if(netmask == 8)
			this.tethercfg.put("ip.netmask", "255.0.0.0");
		else if(netmask == 16)
			this.tethercfg.put("ip.netmask", "255.255.0.0");
		else if(netmask == 32)
			this.tethercfg.put("ip.netmask", "255.255.255.0");
		else
			this.tethercfg.put("ip.netmask", "255.0.0.0");
		this.tethercfg.put("ip.address", nativeQaul.getIP());
		this.tethercfg.put("ip.gateway", nativeQaul.getNetGateway());
		if (Configuration.enableFixPersist()) {
			this.tethercfg.put("tether.fix.persist", "true");
		}
		else {
			this.tethercfg.put("tether.fix.persist", "false");
		}
		if (Configuration.enableFixRoute()) {
			this.tethercfg.put("tether.fix.route", "true");
		}
		else {
			this.tethercfg.put("tether.fix.route", "false");
		}
		
		this.tethercfg.put("wifi.interface", this.coretask.getProp("wifi.interface"));

		this.tethercfg.put("wifi.txpower", txpower);
		
		// configure wifi as open
		this.tethercfg.put("wifi.encryption", "open");
		this.tethercfg.put("wifi.encryption.key", "none");
		
		// Make sure to remove wpa_supplicant.conf
		if (this.wpasupplicant.exists()) {
			this.wpasupplicant.remove();
		}			

		// determine driver wpa_supplicant
		this.tethercfg.put("wifi.driver", Configuration.getWifiInterfaceDriver());
		
		// determine which setup method to use
		//this.tethercfg.put("wifi.setup", "test");
		this.tethercfg.put("wifi.setup", "iwconfig");
		
		// -------------------------------------------------------------------------
		// writing config-file
		if (this.tethercfg.write() == false) {
			Log.e(MSG_TAG, "Unable to update tether.conf!");
		}
		
//		/*
//		 * TODO
//		 * Need to find a better method to identify if the used device is a
//		 * HTC Dream aka T-Mobile G1
//		 */
//		if (deviceType.equals(Configuration.DEVICE_DREAM)) {
//			Hashtable<String,String> values = new Hashtable<String,String>();
//			values.put("dot11DesiredSSID", this.settings.getString("ssidpref", "qaul.net"));
//			values.put("dot11DesiredChannel", this.settings.getString("channelpref", "11"));
//			this.tiwlan.write(values);
//		}
		
		Log.d(MSG_TAG, "Creation of configuration-files took ==> "+(System.currentTimeMillis()-startStamp)+" milliseconds.");
	}
	
	// configure Wifi accoring to the new 
	// java API in cyanogen mod
	public boolean startWifiConfigCyanogen()
	{
		try{
			boolean status = false;
			
			Log.i(MSG_TAG, "startWifiConfigCyanogen create new configuration");
			
			/* We use WifiConfigurationNew which provides a way to access
			 * the Ad-hoc mode and static IP configuration options which are
			 * not part of the standard API yet */
			WifiConfigurationNew wifiConfig = new WifiConfigurationNew();
	
			Log.i(MSG_TAG, "startWifiConfigCyanogen set SSID");
			
			/* Set the SSID and security as normal */
			wifiConfig.SSID = "\"" +nativeQaul.getWifiIbss() +"\"";
			wifiConfig.allowedKeyManagement.set(KeyMgmt.NONE);
			
			Log.i(MSG_TAG, "startWifiConfigCyanogen set IBSS");
			
			/* Use reflection until API is official */
			wifiConfig.setIsIBSS(true);
			wifiConfig.setFrequency(this.wifiChannel2Frequency(nativeQaul.getWifiChannel()));
	
			Log.i(MSG_TAG, "startWifiConfigCyanogen set address");
			
			/* Use reflection to configure static IP addresses */
	    	if (Build.VERSION.SDK_INT >= 21)
	        {
	    		Log.i(MSG_TAG, "startWifiConfigCyanogen ipconfig >= Lollipop");
	    		
	    		try
				{                
					wifiConfig.setStaticIpLollipop(
							InetAddress.getByName(nativeQaul.getIP()), nativeQaul.getNetMask(),	// IP & Netmask
							InetAddress.getByName(nativeQaul.getNetGateway()),					// Gateway
			                new InetAddress[] { InetAddress.getByName("5.45.96.220"), InetAddress.getByName("185.82.22.133") } // DNS IP's
							);
				}
				catch(Exception e)
				{
					e.printStackTrace();
				}
	        }
	    	else
	    	{
	    		Log.i(MSG_TAG, "startWifiConfigCyanogen ipconfig < Lollipop");
	    		
	    		wifiConfig.setIpAssignment("STATIC");
				wifiConfig.setIpAddress(InetAddress.getByName(nativeQaul.getIP()), nativeQaul.getNetMask());
				wifiConfig.setGateway(InetAddress.getByName(nativeQaul.getNetGateway()));
				wifiConfig.setDNS(InetAddress.getByName("5.45.96.220"));
	    	}
	    	
			Log.i(MSG_TAG, "startWifiConfigCyanogen save network");
			
			/* Add, enable and save network as normal */
			int id = mWifiManager.addNetwork(wifiConfig);
			if (id < 0)
			{
				Log.i(MSG_TAG, "Failed to add Ad-hoc network");
				status = false;
			}
			else
			{
				Log.i(MSG_TAG, "Ad-hoc network added");
				
				mWifiManager.enableNetwork(id, true);
				mWifiManager.saveConfiguration();
				status = true;
			}
			
			return status;
		} catch (Exception e) {
            Log.i(MSG_TAG, "Wifi configuration failed!");
            e.printStackTrace();
            return false;
        }
	}
	
	// Start/Stop Tethering
    public boolean startTether() {

        // Updating all configs
        this.updateConfiguration();

        this.disableWifi();

    	// Starting service
    	if (this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether start 1")) {
        	
			// Acquire Wakelock
			this.acquireWakeLock();
			
    		return true;
    	}
    	return false;
    }
    
    public boolean stopTether() {
    	this.releaseWakeLock();

    	boolean stopped = this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether stop 1");
		this.notificationManager.cancelAll();
		
		// Put WiFi back, if applicable.
		this.enableWifi();

		return stopped;
    }
	
    public boolean restartTether() {
    	boolean status = this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether stop 1");
		this.notificationManager.cancelAll();
    	
        // Updating all configs
        this.updateConfiguration();       
        
        this.disableWifi();
        
    	// Starting service
        if (status == true)
        	status = this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether start 1");
        
        this.showStartNotification();
        
    	return status;
    }
    
    public String getTetherNetworkDevice() {
		return this.coretask.getProp("wifi.interface");
    }
    
    // gets user preference on whether wakelock should be disabled during tethering
    public boolean isWakeLockDisabled(){
		return this.settings.getBoolean("wakelockpref", true);
	} 
	
    // gets user preference on whether sync should be disabled during tethering
    public boolean isSyncDisabled(){
		return this.settings.getBoolean("syncpref", false);
	}
    
    // gets user preference on whether sync should be disabled during tethering
    public boolean isUpdatecDisabled(){
		return this.settings.getBoolean("updatepref", false);
	}
    
    // Wifi
    public void disableWifi() {
    	if (this.mWifiManager.isWifiEnabled()) {
    		origWifiState = true;
    		this.mWifiManager.setWifiEnabled(false);
    		Log.d(MSG_TAG, "Wifi disabled!");
        	// Waiting for interface-shutdown
    		try {
    			Thread.sleep(5000);
    		} catch (InterruptedException e) {
    			// nothing
    		}
    	}
    }
    
    public void enableWifi() {
    	if (origWifiState) {
        	// Waiting for interface-restart
    		this.mWifiManager.setWifiEnabled(true);
    		try {
    			Thread.sleep(5000);
    		} catch (InterruptedException e) {
    			// nothing
    		}
    		Log.d(MSG_TAG, "Wifi started!");
    	}
    }
    
    // WakeLock
	public void releaseWakeLock() {
		try {
			if(this.wakeLock != null && this.wakeLock.isHeld()) {
				Log.d(MSG_TAG, "Trying to release WakeLock NOW!");
				this.wakeLock.release();
			}
		} catch (Exception ex) {
			Log.d(MSG_TAG, "Ups ... an exception happend while trying to release WakeLock - Here is what I know: "+ex.getMessage());
		}
	}
    
	public void acquireWakeLock() {
		try {
			if (this.isWakeLockDisabled() == false) {
				Log.d(MSG_TAG, "Trying to acquire WakeLock NOW!");
				this.wakeLock.acquire();
			}
		} catch (Exception ex) {
			Log.d(MSG_TAG, "Ups ... an exception happend while trying to acquire WakeLock - Here is what I know: "+ex.getMessage());
		}
	}
    
    public int getNotificationType() {
		return Integer.parseInt(this.settings.getString("notificationpref", "2"));
    }
    
    // Notification
    public void showStartNotification() {
		notification.flags = Notification.FLAG_ONGOING_EVENT;
		
    	notification.setLatestEventInfo(this, getString(R.string.app_name), getString(R.string.qaul_running), this.mainIntent);
    	this.notificationManager.notify(-1, this.notification);
    }
    
    public boolean binariesExists() {
    	Log.d(MSG_TAG, String.format("binariesExists() %s", this.coretask.DATA_FILE_PATH+"/bin/olsrd_start.sh"));
    	File file = new File(this.coretask.DATA_FILE_PATH+"/bin/olsrd_start.sh");
    	return file.exists();
    }
    
    public void installWpaSupplicantConfig() {
    	Log.d(MSG_TAG, String.format("installWpaSupplicantConfig() %s", this.coretask.DATA_FILE_PATH+"/conf/wpa_supplicant.conf"));
    	this.copyFile(this.coretask.DATA_FILE_PATH+"/conf/wpa_supplicant.conf", "0644", R.raw.wpa_supplicant_conf);
    }
    
    Handler displayMessageHandler = new Handler(){
        public void handleMessage(Message msg) {
       		if (msg.obj != null) {
       			QaulApplication.this.displayToastMessage((String)msg.obj);
       		}
        	super.handleMessage(msg);
        }
    };

    public void installFiles() {
    	Log.d(MSG_TAG, "installFiles()");
    	
    	new Thread(new Runnable(){
			public void run(){
		    	Log.d(MSG_TAG, "installFiles.Runnable()");
				String message = null;
				// tether & edify
		    	if (message == null) {
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/tether"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/tether", "0755", R.raw.tether);
					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/tether.conf", "0644", R.raw.tether_conf);
					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/tether.edify", "0644", R.raw.tether_edify);
		    	}
		    	// iptables & portforwarding
		    	if (message == null) {
//		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables"));
//			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables", "0755", R.raw.iptables);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables_start.sh"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables_start.sh", "0755", R.raw.iptables_start);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables_stop.sh"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables_stop.sh", "0755", R.raw.iptables_stop);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/socat"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/socat", "0755", R.raw.socat);
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/socat_start.sh"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/socat_start.sh", "0755", R.raw.socat_start);
		    	}
//		    	// ifconfig
//		    	if (message == null) {
//		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/ifconfig"));
//			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/ifconfig", "0755", R.raw.ifconfig);
//		    	}	
		    	// iwconfig
		    	if (message == null) {
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iwconfig"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/iwconfig", "0755", R.raw.iwconfig);
		    	}
		    	// FIXME: is this really needed? What for?
		    	// ultra_bcm_config
		    	if (message == null) {
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/ultra_bcm_config"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/ultra_bcm_config", "0755", R.raw.ultra_bcm_config);
		    	}
		    	// wificonfig
		    	if (message == null) {
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/wificonfig"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/wificonfig", "0755", R.raw.wificonfig);
		    	}
		    	// olsr
		    	if (message == null) {
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd.conf"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd.conf", "0755", R.raw.olsrd_conf);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_qaul.so.0.1"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_qaul.so.0.1", "0755", R.raw.olsrd_qaul_so_0_1);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_dyn_gw.so.0.5"));
		        	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_dyn_gw.so.0.5", "0755", R.raw.olsrd_dyn_gw_so_0_5);
		        	Log.d(MSG_TAG, String.format("installFiles.Runnable() %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd", "0755", R.raw.olsrd);
		        	Log.d(MSG_TAG, String.format("copy configuration %s", QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_start.sh"));
			    	message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/olsrd_start.sh", "0755", R.raw.olsrd_start);
		    	}

//				// install device specific configuration files
//				// bcm4330
//				if (message == null) {
//					Log.d(MSG_TAG, "device specific configuration files");
//					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/dhd.ko", "0644", R.raw.custom_bcm4330_dhd_ko);
//					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/custom_bcm4330_sta.bin", "0644", R.raw.custom_bcm4330_sta_bin);
//					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/custom_bcm4330_nvram_net.txt", "0644", R.raw.custom_bcm4330_nvram_net_txt);
//				}
		    	// tiwlan.ini
				if (message == null) {
					Log.d(MSG_TAG, "tiwlan.ini");
					QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/tiwlan.ini", "0644", R.raw.tiwlan_ini);
				}
				
		    	// Install fix-scripts if needed
				if (Configuration.enableFixPersist()) {	
					Log.d(MSG_TAG, "Configuration.enableFixPersist()");
					// fixpersist.sh
					if (message == null) {
						message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/fixpersist.sh", "0755", R.raw.fixpersist_sh);
					}				
				}
				if (Configuration.enableFixRoute()) {
					Log.d(MSG_TAG, "Configuration.enableFixRoute()");
					// fixroute.sh
					if (message == null) {
						message = QaulApplication.this.copyFile(QaulApplication.this.coretask.DATA_FILE_PATH+"/bin/fixroute.sh", "0755", R.raw.fixroute_sh);
					}
				}
				
				// wpa_supplicant drops privileges, we need to make files readable.
				QaulApplication.this.coretask.chmod(QaulApplication.this.coretask.DATA_FILE_PATH+"/conf/", "0755");

				if (message == null) {
			    	message = getString(R.string.qaul_installed);
				}
				
				// Sending message
				Message msg = new Message();
				msg.obj = message;
				QaulApplication.this.displayMessageHandler.sendMessage(msg);
			}
		}).start();
    }
    
    private String copyFile(String filename, String permission, int ressource) {
    	String result = this.copyFile(filename, ressource);
    	if (result != null) {
    		return result;
    	}
    	if (this.coretask.chmod(filename, permission) != true) {
    		result = "Can't change file-permission for '"+filename+"'!";
    	}
    	return result;
    }
    
    private String copyFile(String filename, int ressource) {
    	File outFile = new File(filename);
    	Log.d(MSG_TAG, "Copying file '"+filename+"' ...");
    	InputStream is = this.getResources().openRawResource(ressource);
    	byte buf[] = new byte[1024];
        int len;
        try {
        	OutputStream out = new FileOutputStream(outFile);
        	while((len = is.read(buf))>0) {
				out.write(buf,0,len);
			}
        	out.close();
        	is.close();
		} catch (IOException e) {
			return "Couldn't install file - "+filename+"!";
		}
		return null;
    }
    
    private void checkDirs() {
    	File dir = new File(this.coretask.DATA_FILE_PATH);
    	if (dir.exists() == false) {
				Log.e(MSG_TAG, "Application data-dir does not exist!");
    			this.displayToastMessage("Application data-dir does not exist!");
    	}
    	else {
    		//String[] dirs = { "/bin", "/var", "/conf", "/library" };
    		String[] dirs = { "/bin", "/var", "/conf" };
    		for (String dirname : dirs) {
    			dir = new File(this.coretask.DATA_FILE_PATH + dirname);
    	    	if (dir.exists() == false) {
    	    		if (!dir.mkdir()) {
    	    			this.displayToastMessage("Couldn't create " + dirname + " directory!");
    	    		}
    	    	}
    	    	else {
    	    		Log.d(MSG_TAG, "Directory '"+dir.getAbsolutePath()+"' already exists!");
    	    	}
    		}
    	}
    }
    
    public void restartSecuredWifi() {
    	try {
			if (!this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether restartsecwifi 1")) {
				this.displayToastMessage(getString(R.string.qaul_error_restartsecwifi));
				return;
			}
		} catch (Exception e) {
			// nothing
		}
    }
    
    // Display Toast-Message
	public void displayToastMessage(String message) {
		Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}
    
    public int getVersionNumber() {
    	int version = -1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionCode;
        } catch (Exception e) {
            Log.e(MSG_TAG, "Package name not found", e);
        }
        return version;
    }
    
    public String getVersionName() {
    	String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception e) {
            Log.e(MSG_TAG, "Package name not found", e);
        }
        return version;
    }

    /**
     * Check if changing the transmit-power is supported
     */
    public boolean isTransmitPowerSupported() {
    	// Only supported for the nexusone 
    	if (Configuration.getWifiInterfaceDriver().equals(Configuration.DRIVER_WEXT)) {
    		return true;
    	}
    	return false;
    }
    
    /**
     * Convert Wifi channel number to frequency
     */
    public int wifiChannel2Frequency(int channel)
    {
    	int freq;
    	
    	switch (channel)
    	{
    		case 1: 
    			freq = 2412;
    			break;
    		case 2: 
    			freq = 2417;
    			break;
    		case 3: 
    			freq = 2422;
    			break;
    		case 4: 
    			freq = 2427;
    			break;
    		case 5: 
    			freq = 2432;
    			break;
    		case 6: 
    			freq = 2437;
    			break;
    		case 7: 
    			freq = 2442;
    			break;
    		case 8: 
    			freq = 2447;
    			break;
    		case 9: 
    			freq = 2452;
    			break;
    		case 10: 
    			freq = 2457;
    			break;
    		case 11: 
    			freq = 2462;
    			break;
    		case 12: 
    			freq = 2467;
    			break;
    		case 13: 
    			freq = 2472;
    			break;
    		case 14: 
    			freq = 2484;
    			break;
    		case 34:
    			freq = 5170;
    			break;
    		case 36: 
    			freq = 5180;
    			break;
    		case 38: 
    			freq = 5190;
    			break;
    		case 40: 
    			freq = 5200;
    			break;
    		case 42: 
    			freq = 5210;
    			break;
    		case 44: 
    			freq = 5220;
    			break;
    		case 46: 
    			freq = 5230;
    			break;
    		case 48: 
    			freq = 5240;
    			break;
    		case 50: 
    			freq = 5250;
    			break;
    		case 52: 
    			freq = 5260;
    			break;
    		case 54: 
    			freq = 5270;
    			break;
    		case 56: 
    			freq = 5280;
    			break;
    		case 68: 
    			freq = 5290;
    			break;
    		case 60: 
    			freq = 5300;
    			break;
    		case 62: 
    			freq = 5310;
    			break;
    		case 64: 
    			freq = 5320;
    			break;
    		case 100: 
    			freq = 5500;
    			break;
    		case 102: 
    			freq = 5510;
    			break;
    		case 104: 
    			freq = 5520;
    			break;
    		case 106: 
    			freq = 5530;
    			break;
    		case 108: 
    			freq = 5540;
    			break;
    		case 110: 
    			freq = 5550;
    			break;
    		case 112: 
    			freq = 5560;
    			break;
    		case 114: 
    			freq = 5570;
    			break;
    		case 116: 
    			freq = 5580;
    			break;
    		case 118: 
    			freq = 5590;
    			break;
    		case 120: 
    			freq = 5600;
    			break;
    		case 122: 
    			freq = 5610;
    			break;
    		case 124: 
    			freq = 5620;
    			break;
    		case 126: 
    			freq = 5630;
    			break;
    		case 128: 
    			freq = 5640;
    			break;
    		case 132: 
    			freq = 5660;
    			break;
    		case 134: 
    			freq = 5670;
    			break;
    		case 136: 
    			freq = 5680;
    			break;
    		case 138: 
    			freq = 5690;
    			break;
    		case 140: 
    			freq = 5700;
    			break;
    		case 142: 
    			freq = 5710;
    			break;
    		case 144: 
    			freq = 5720;
    			break;
    		case 149: 
    			freq = 5745;
    			break;
    		case 151: 
    			freq = 5755;
    			break;
    		case 153: 
    			freq = 5765;
    			break;
    		case 155: 
    			freq = 5775;
    			break;
    		case 157: 
    			freq = 5785;
    			break;
    		case 159: 
    			freq = 5795;
    			break;
    		case 161: 
    			freq = 5805;
    			break;
    		case 165: 
    			freq = 5825;
    			break;
    		case 183: 
    			freq = 4915;
    			break;
    		case 184: 
    			freq = 4920;
    			break;
    		case 185: 
    			freq = 4925;
    			break;
    		case 187: 
    			freq = 4935;
    			break;
    		case 188: 
    			freq = 4940;
    			break;
    		case 189: 
    			freq = 4945;
    			break;
    		case 192: 
    			freq = 4960;
    			break;
    		case 196: 
    			freq = 4980;
    			break;
    		default:
    			freq = 2462;
    			break;
    	}
    	
    	return freq;
    }
}
