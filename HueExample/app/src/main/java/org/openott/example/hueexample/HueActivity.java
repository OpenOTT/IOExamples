package org.openott.example.hueexample;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import com.futarque.mediarite.IMediaRite;
import com.futarque.mediarite.common.FutarqueBoolean;
import com.futarque.mediarite.common.FutarqueDouble;
import com.futarque.mediarite.io.AdcConfig;
import com.futarque.mediarite.io.IGpioPin;
import com.futarque.mediarite.io.IGpioProvider;
import com.futarque.mediarite.io.IIOController;
import com.futarque.mediarite.io.IOError;
import com.philips.lighting.hue.sdk.PHAccessPoint;
import com.philips.lighting.hue.sdk.PHBridgeSearchManager;
import com.philips.lighting.hue.sdk.PHHueSDK;
import com.philips.lighting.hue.sdk.PHMessageType;
import com.philips.lighting.hue.sdk.PHSDKListener;
import com.philips.lighting.model.PHBridge;
import com.philips.lighting.model.PHHueError;
import com.philips.lighting.model.PHHueParsingError;
import com.philips.lighting.model.PHLight;
import com.philips.lighting.model.PHLightState;

import org.florescu.android.rangeseekbar.RangeSeekBar;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The project demonstrates OpenOTT I/O controlling Philips Hue Zigbee LightLink lamps via a Philips Hue IP bridge.
 * It assumes that the Hue bridge has at least two lamps connected and that there is one (and only one)
 * Hue IP bridge on the same network as the OpenOTT board.
 *
 * You have the 3.3v voltage over a photoresistor connected to the ADC on pin 40.
 * For safety, the photoresistor should be in serial with a resistor, e.g. 10k ohm, to ensure that even if the
 * photoresistor shorts, you do not short 3.3v to ground. The ADC input controls the intensity of the first Hue lamp.
 * You can adjust the sensitivity of the ADC input via a UI slider, to compensate for the serial resistors
 * value and max/min lighting conditions.
 *
 * Additionally you can connect a pushbutton to the GPIO on pin 5 to control the second lamp. This is
 * not stricly necessary as there is also a UI on/off switch for that
 *
 * */

public class HueActivity extends AppCompatActivity {
    private static final String TAG = HueActivity.class.getSimpleName();
    private IMediaRite mIMediaRite = null;
    private IIOController mIOController = null;
    private ProgressBar mProgressBar=null;
    private Timer timer=null;
    private MyTimerTask myTimerTask=null;
    private IGpioPin mPin40 =null;
    private IGpioPin mPin5 =null;
    private PHHueSDK phHueSDK;
    private PHBridge bridge;
    private TextView hueTextView;
    private SharedPreferences prefs = null;
    private SharedPreferences.Editor prefsEditor = null;
    private String hueIP;
    private int mMin=0;
    private int mMax=3300;
    private Switch mSwitch = null;
    private boolean mPrevPin5State=true;
    private int taskCounter=0;
    private void initialize() {
        Log.i(TAG, "Connected to Mediarite");
        final TextView helloTextView = (TextView) findViewById(R.id.hellotext);
        helloTextView.setText("Now connected to Mediarite");
        try {
            mIOController = mIMediaRite.getIOController();
            mIOController.getGpioProvider();
            IGpioProvider provider = mIOController.getGpioProvider();
            mPin5 = provider.openPinNumber(5);
            mPin40 = provider.openPinNumber(40);
            // Here we make assumptions about the layout of the connector.
            // The proper way is to check e.g.IGpioProvider.hasPinByNumber()
            // and check pin capabilities using IGpioPin.getPinCapabilities()
            AdcConfig config = new AdcConfig();
            config.mAdcRefLevelmV = 3300;
            mPin40.setAdcConfig(config);
            timer = new Timer();
            myTimerTask = new MyTimerTask();
            timer.schedule(myTimerTask, 10, 100);
        } catch(RemoteException e) {
            Log.e(TAG,"Error creating IOController",e);
        }
    };


    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "Service Connection Established");
            mIMediaRite = IMediaRite.Stub.asInterface(service);
            ConnectToHue();
            initialize();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Service Disconnected");
            mIMediaRite = null;
        }
    };

    private void doHueStuff(int val) {
        if(phHueSDK==null) {
            return;
        }
        bridge = phHueSDK.getSelectedBridge();

        if(bridge != null) {
            List<PHLight> allLights = bridge.getResourceCache().getAllLights();

            PHLightState lightState = new PHLightState();
            //Log.i(TAG, "val:" + val);
            double d = val;
            // 255 is the max brightness of the Hue API
            double brightness = 255 - (((double) (val)) - mMin) / (mMax - mMin) * 255;
            //Log.i(TAG, "brightness:" + brightness);
            if (brightness < 0) {
                brightness = 0;
            }
            if (brightness > 255) {
                brightness = 254;
            }
            //Log.i(TAG, "capped brightness:" + brightness);
            lightState.setTransitionTime(1); // Transition time in multiples of 100mS - default is 400mS
            if (brightness > 0) {
                lightState.setOn(true);
            } else {
                lightState.setOn(false);
            }
            lightState.setBrightness((int) brightness);
            //lightState.setHue(val*15);
            bridge.updateLightState(allLights.get(0), lightState);   // If no bridge response is required then use this simpler form.
        } else {
            Log.i(TAG, "Unable to get Philips Hue Bridge\n");
            //hueTextView.setText("Unable to connect to Philips Hue Bridge");
        }
    }

    private void ConnectToHue() {
        hueTextView = (TextView) findViewById(R.id.hueTextView);

        phHueSDK = PHHueSDK.create();

        // Set the Device Name (name of your app). This will be stored in your bridge whitelist entry.
        phHueSDK.setAppName("FutDemo");
        phHueSDK.setDeviceName(android.os.Build.MODEL);

        // Register the PHSDKListener to receive callbacks from the bridge.
        phHueSDK.getNotificationManager().registerSDKListener(listener);

        // Try to automatically connect to the last known bridge.  For first time use this will be empty so a bridge search is automatically started.
        hueIP   = prefs.getString("lastHueIP", "");
        String lastUsername    = prefs.getString("lastHueUser", "");

        // Automatically try to connect to the last connected IP Address.  For multiple bridge support a different implementation is required.
        if (hueIP !=null && !hueIP.equals("")) {
            PHAccessPoint lastAccessPoint = new PHAccessPoint();
            lastAccessPoint.setIpAddress(hueIP);
            lastAccessPoint.setUsername(lastUsername);

            if (!phHueSDK.isAccessPointConnected(lastAccessPoint)) {
                hueTextView.setText("Hue connecting to " + hueIP);
                phHueSDK.connect(lastAccessPoint);
            }
        }
        else {  // First time use, so perform a bridge search.
            PHBridgeSearchManager sm = (PHBridgeSearchManager) phHueSDK.getSDKService(PHHueSDK.SEARCH_BRIDGE);
            hueTextView.setText("Searching for Philips Hue Bridges");
            sm.search(true, true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hue);
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);
        prefs = getApplicationContext().getSharedPreferences("huePrefs", 0); // 0 - for private mode
        prefsEditor = prefs.edit();
        mProgressBar.setScaleY(3f);
        final Button button = (Button) findViewById(R.id.hueButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                phHueSDK.destroySDK();
                phHueSDK=null;
                prefsEditor.putString("lastHueUser", "");
                prefsEditor.putString("lastHueIP", "");
                prefsEditor.commit();
                ConnectToHue();
            }
        });

        // install listener for rangebar
        RangeSeekBar<Integer> rangeSeekBar = (RangeSeekBar) findViewById(R.id.rangeSeekBar);
        rangeSeekBar.setOnRangeSeekBarChangeListener(new RangeSeekBar.OnRangeSeekBarChangeListener<Integer>() {
            @Override
            public void onRangeSeekBarValuesChanged(RangeSeekBar<?> bar, Integer minValue, Integer maxValue) {
                mMin=minValue;
                mMax=maxValue;
                Log.i(TAG, "\nClicked:" + minValue + " " + maxValue+"\n");
            }
        });

        mSwitch = (Switch) findViewById(R.id.lampSwitch);

        //attach a listener to check for changes in state
        mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                                         boolean isChecked) {
                if(phHueSDK==null) {
                    return;
                }
                bridge = phHueSDK.getSelectedBridge();

                if(bridge != null) {
                    List<PHLight> allLights = bridge.getResourceCache().getAllLights();

                    PHLightState lightState = new PHLightState();
                    lightState.setBrightness((int) 254);
                    lightState.setOn(isChecked);
                    lightState.setTransitionTime(2); // Transition time in multiples of 100mS - default is 400mS
                    bridge.updateLightState(allLights.get(1), lightState);   // If no bridge response is required then use this simpler form.
                } else {
                    Log.i(TAG, "Unable to get Philips Hue Bridge\n");
                }
            }
        });


        // Connect to Mediarite to get IOController
        Intent implicitIntent = new Intent("com.futarque.mediarite.IOService");
        List<ResolveInfo> resolveInfo = getPackageManager().queryIntentServices(implicitIntent, 0);

        if (resolveInfo == null) {
            Log.e(TAG, "No Mediarite service found");
            return;
        }

        if (resolveInfo.size() != 1) {
            Log.e(TAG, "More than one Mediarite service("+resolveInfo.size()+")!?");
            for(ResolveInfo info : resolveInfo) {
                Log.e(TAG, "resname: " + info.resolvePackageName);
                Log.e(TAG, "pkgname: " + info.serviceInfo.packageName);
                Log.e(TAG, "process: " + info.serviceInfo.processName);
                Log.e(TAG, "clsname: " + info.serviceInfo.applicationInfo.className);
            }
            return;
        }
        // Get component info and create ComponentName
        ResolveInfo serviceInfo = resolveInfo.get(0);
        String packageName = serviceInfo.serviceInfo.packageName;
        String className = serviceInfo.serviceInfo.name;
        ComponentName component = new ComponentName(packageName, className);

        // Create a new intent. Use the old one for extras and such reuse
        Intent explicitIntent = new Intent(implicitIntent);

        // Set the component to be explicit
        explicitIntent.setComponent(component);

        startService(explicitIntent);
        bindService(explicitIntent, mServiceConnection, 0);
    }

    class MyTimerTask extends TimerTask {

        @Override
        public void run() {

            runOnUiThread(new Runnable(){

                @Override
                public void run() {
                    double mV=0;
                    int adcRefmV=3300;
                    try {
                        //Log.i(TAG, "Plop!\n");
                        FutarqueDouble value = new FutarqueDouble();
                        if (mPin40.getAdcValue(value) == IOError.IOE_NOERROR) {
                            AdcConfig config = new AdcConfig();
                            mPin40.getAdcConfig(config);
                            adcRefmV=config.mAdcRefLevelmV;
                            mV = value.mValue *  adcRefmV / 1000;
                            //Log.i(TAG, "adcValue: "+value.mValue);
                            //Log.i(TAG, "adcV: " + voltage);
                        }
                        FutarqueBoolean isPinSet=new FutarqueBoolean();
                        isPinSet.mValue=false;
                        if (mPin5.getState(isPinSet) == IOError.IOE_NOERROR) {
                            //Log.i(TAG,"prev= "+mPrevPin5State+" now= "+isPinSet.mValue);
                            if(mPrevPin5State == true && isPinSet.mValue==false) {
                                mSwitch.toggle();
                            }
                            mPrevPin5State=isPinSet.mValue;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error getting ADC value", e);
                    }

                    mProgressBar.setMax(adcRefmV);
                    mProgressBar.setProgress((int) (mV * 1000));
                    if((taskCounter % 5) ==0) { // Sending too many Hue requests tend to make things less responsive - see Hue documentation
                        doHueStuff((int) (mV * 1000));
                    }
                    taskCounter++;
                }});
        }

    }

    // Local SDK Listener
    private PHSDKListener listener = new PHSDKListener() {

        @Override
        public void onAccessPointsFound(List<PHAccessPoint> accessPoint) {
            Log.w(TAG, "Access Points Found. " + accessPoint.size());
            hueIP = accessPoint.get(0).getIpAddress();
            for (PHAccessPoint ap : accessPoint) {
                System.out.println(ap.getIpAddress());
            }

            if (accessPoint != null && accessPoint.size() > 0) {
                phHueSDK.getAccessPointsFound().clear();
                phHueSDK.getAccessPointsFound().addAll(accessPoint);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hueTextView.setText("Hue attempting to connect to bridge: "+hueIP);
                    }
                });
                phHueSDK.connect(accessPoint.get(0));
            }

        }

        @Override
        public void onCacheUpdated(List<Integer> arg0, PHBridge bridge) {
            //Log.w(TAG, "On CacheUpdated");
        }

        @Override
        public void onBridgeConnected(PHBridge b, String username) {
            phHueSDK.setSelectedBridge(b);
            phHueSDK.enableHeartbeat(b, PHHueSDK.HB_INTERVAL);
            phHueSDK.getLastHeartbeat().put(b.getResourceCache().getBridgeConfiguration().getIpAddress(), System.currentTimeMillis());

            List<PHLight> allLights = b.getResourceCache().getAllLights();
            PHLightState lightState=new PHLightState();
            lightState.setOn(false);
            lightState.setTransitionTime(0); // Transition time in multiples of 100mS - default is 400mS
            bridge.updateLightState(allLights.get(1), lightState);   // Turn off lamp initially

            Log.i(TAG, "Hue IP: " + b.getResourceCache().getBridgeConfiguration().getIpAddress()+", user: "+username);
            prefsEditor.putString("lastHueUser", username);
            prefsEditor.putString("lastHueIP", b.getResourceCache().getBridgeConfiguration().getIpAddress());
            prefsEditor.commit();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hueTextView.setText("Hue connected to bridge: " + hueIP);
                }
            });
        }

        @Override
        public void onAuthenticationRequired(PHAccessPoint accessPoint) {
            Log.w(TAG, "Authentication Required.");
            phHueSDK.startPushlinkAuthentication(accessPoint);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hueTextView.setText("Hue authentication required: Push Hue bridge authentication button within 30 secs!");
                    //adapter.updateData(phHueSDK.getAccessPointsFound());
                }
            });
            //startActivity(new Intent(PHHomeActivity.this, PHPushlinkActivity.class));
        }

        @Override
        public void onConnectionResumed(PHBridge bridge) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hueTextView.setText("Hue connected to bridge: " + hueIP);
                }
            });
            Log.v(TAG, "onConnectionResumed" + bridge.getResourceCache().getBridgeConfiguration().getIpAddress());
            phHueSDK.getLastHeartbeat().put(bridge.getResourceCache().getBridgeConfiguration().getIpAddress(), System.currentTimeMillis());
            for (int i = 0; i < phHueSDK.getDisconnectedAccessPoint().size(); i++) {

                if (phHueSDK.getDisconnectedAccessPoint().get(i).getIpAddress().equals(bridge.getResourceCache().getBridgeConfiguration().getIpAddress())) {
                    phHueSDK.getDisconnectedAccessPoint().remove(i);
                }
            }
        }

        @Override
        public void onConnectionLost(PHAccessPoint accessPoint) {
            Log.v(TAG, "onConnectionLost : " + accessPoint.getIpAddress());
            if (!phHueSDK.getDisconnectedAccessPoint().contains(accessPoint)) {
                phHueSDK.getDisconnectedAccessPoint().add(accessPoint);
            }
        }

        @Override
        public void onError(int code, final String message) {
            Log.e(TAG, "on Error Called : " + code + ":" + message);

            if (code == PHHueError.NO_CONNECTION) {
                Log.w(TAG, "On No Connection");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hueTextView.setText("Hue bridge - no connection");
                    }
                });
            }

            else if(code==PHHueError.AUTHENTICATION_FAILED||code==1158)

            {
                Log.w(TAG, "On No Connection");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hueTextView.setText("Hue authentication failed!");
                    }
                });
            }

            else if(code==PHHueError.BRIDGE_NOT_RESPONDING)

            {
                Log.w(TAG, "Bridge Not Responding . . . ");
//                PHWizardAlertDialog.getInstance().closeProgressDialog();
//                PHHomeActivity.this.runOnUiThread(new Runnable() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hueTextView.setText("Hue bridge not responding - searching again");
                        PHBridgeSearchManager sm = (PHBridgeSearchManager) phHueSDK.getSDKService(PHHueSDK.SEARCH_BRIDGE);
                        sm.search(true, true);
                    }
                });


            }

            else if(code==PHMessageType.BRIDGE_NOT_FOUND)

            {

                //PHWizardAlertDialog.getInstance().closeProgressDialog();
//                PHHomeActivity.this.runOnUiThread(new Runnable() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //PHWizardAlertDialog.showErrorDialog(PHHomeActivity.this, message, R.string.btn_ok);
                        hueTextView.setText("Hue bridge not found");
                    }
                });
            }
        }

        @Override
        public void onParsingErrors(List<PHHueParsingError> parsingErrorsList) {
            for (PHHueParsingError parsingError: parsingErrorsList) {
                Log.e(TAG, "ParsingError : " + parsingError.getMessage());
            }
        }
    };

}
