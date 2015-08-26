package org.openott.example.firstexample;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import com.futarque.mediarite.IMediaRite;
import com.futarque.mediarite.io.IGpioPin;
import com.futarque.mediarite.io.IGpioProvider;
import com.futarque.mediarite.io.IIOController;
import com.futarque.mediarite.io.PinState;
import com.futarque.mediarite.io.PioConfig;

import java.util.List;

public class HwExampleActivity extends AppCompatActivity {
    private IMediaRite mIMediarite = null;
    private IIOController mIOController = null;
    private TextView mStatusText = null;
    private IGpioPin mPin=null;
    private Switch mSwitch = null;

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mIMediarite = IMediaRite.Stub.asInterface(service);
            try {
                mStatusText.setText("Status: Attempting access IOService");
                mIOController = mIMediarite.getIOController();
                mIOController.getGpioProvider();
                IGpioProvider provider = mIOController.getGpioProvider();
                mPin = provider.openPinNumber(3);
                PioConfig cfg = new PioConfig();
                cfg.mIsOutput=true;
                mPin.setPioConfig(cfg);
                mPin.setState(PinState.LOW);
                mStatusText.setText("Status: GPIO 3 initialized as output");
                mSwitch.setEnabled(true);
            } catch(RemoteException e) {
                mStatusText.setText("Status: Error creating IOController");
            }

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mStatusText.setText("Status: Could not connect to IOService");
            mIMediarite = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hw_example);
        mStatusText = (TextView) findViewById(R.id.statusText);
        mSwitch = (Switch) findViewById(R.id.mySwitch);

        //attach a listener to check for changes in state
        mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView,
                                         boolean isChecked) {
                try {
                    if (isChecked) {
                        mPin.setState(PinState.HIGH);
                    } else {
                        mPin.setState(PinState.LOW);
                    }
                } catch (RemoteException e) {
                    mStatusText.setText("Status: Error setting GPIO pin 3");
                }
            }
        });


        // Now connect to Mediarite service, which is Futarque's Android system service
        Intent implicitIntent = new Intent("com.futarque.mediarite.IOService");
        List<ResolveInfo> resolveInfo = getPackageManager().queryIntentServices(implicitIntent, 0);

        if (resolveInfo == null || resolveInfo.size() != 1) {
            mStatusText.setText("Status: No unique com.futarque.mediarite.IOService service found");
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
        mStatusText.setText("Status: Attempting to connect to Mediarite");
        bindService(explicitIntent, mServiceConnection, 0);

    }
};
