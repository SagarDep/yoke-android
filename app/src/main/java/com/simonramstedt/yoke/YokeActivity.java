package com.simonramstedt.yoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.nsd.NsdManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.net.nsd.NsdServiceInfo;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.locks.ReentrantLock;


public class YokeActivity extends Activity implements SensorEventListener, NsdManager.DiscoveryListener {

    private static final String SERVICE_TYPE = "_yoke._udp.";
    private static final String NOTHING = "> nothing ";
    private static final String ENTER_IP = "> new manual connection";
    private SensorManager mSensorManager;
    private PowerManager mPowerManager;
    private WindowManager mWindowManager;
    private Display mDisplay;
    private WakeLock mWakeLock;
    private Sensor mAccelerometer;
    private ServerSocket mServerSocket;
    private NsdManager mNsdManager;
    private NsdServiceInfo mNsdServiceInfo;
    private NsdServiceInfo mService;
    private final ReentrantLock resolving = new ReentrantLock();
    private DatagramSocket mSocket;
    private float[] vals = {0, 0, 0, 0, 0, 0, 0};
    private Timer mTimer;
    private Map<String, NsdServiceInfo> mServiceMap = new HashMap<>();
    private List<String> mServiceNames = new ArrayList<>();
    private SharedPreferences sharedPref;
    private TextView mTextView;
    private Spinner mSpinner;
    private ArrayAdapter<String> mAdapter;
    private String mTarget = "";
    private JoystickView joystick1;
    private Handler handler;
    private JoystickView joystick2;

    private void log(String m) {
        if(BuildConfig.DEBUG)
            Log.d("Yoke", m);
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = getPreferences(Context.MODE_PRIVATE);
        setContentView(R.layout.main);

        // Get an instance of the SensorManager
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        // Get an instance of the PowerManager
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Get an instance of the WindowManager
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        joystick1 = (JoystickView) findViewById(R.id.joystickView1);
        joystick2 = (JoystickView) findViewById(R.id.joystickView2);

        mTextView = (TextView) findViewById(R.id.textView);

        mSpinner = (Spinner) findViewById(R.id.spinner);
        mAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_spinner_item);
        mAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapter.add(NOTHING);
        mAdapter.add(ENTER_IP);

        for(String adr : sharedPref.getString("addresses", "").split(System.lineSeparator())){
            adr = adr.trim(); // workaround for android bug where random whitespace is added to Strings in shared preferences
            if(!adr.isEmpty()) {
                mAdapter.add(adr);
                mServiceNames.add(adr);
                log("adding " + adr);
            }
        }
        mSpinner.setAdapter(mAdapter);
        mSpinner.setPrompt("Connect to ...");
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long l) {

                String tgt = parent.getItemAtPosition(pos).toString();

                // clean up old target if no longer available
                String oldtgt = mSpinner.getSelectedItem().toString();
                if (!mServiceNames.contains(oldtgt) && !oldtgt.equals(NOTHING) && !oldtgt.equals(ENTER_IP)) {
                    mAdapter.remove(oldtgt);
                    if(oldtgt.equals(tgt)){
                        tgt = NOTHING;
                    }
                }

                closeConnection();

                if(tgt.equals(NOTHING)){

                } else if(tgt.equals(ENTER_IP)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this);
                    builder.setTitle("Enter ip address and port");

                    final EditText input = new EditText(YokeActivity.this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint("e.g. 192.168.1.123:11111");

                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
                        String name = input.getText().toString();

                        boolean invalid = name.split(":").length != 2;

                        if(!invalid){
                            try {
                                Integer.parseInt(name.split(":")[1]);
                            } catch (NumberFormatException e) {
                                invalid = true;
                            }
                        }

                        if(invalid){
                            mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                            Toast.makeText(YokeActivity.this, "Invalid address", Toast.LENGTH_SHORT).show();

                        } else {
                            mServiceNames.add(name);
                            mAdapter.add(name);
                            mSpinner.setSelection(mAdapter.getPosition(name));

                            SharedPreferences.Editor editor = sharedPref.edit();
                            String addresses = sharedPref.getString("addresses", "");
                            addresses = addresses + name + System.lineSeparator();
                            editor.putString("addresses", addresses);
                            editor.apply();
                        }
                    });
                    builder.setNegativeButton("Cancel", (dialog, which) -> {
                        mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                        dialog.cancel();
                    });

                    builder.show();
                } else {
                    log("new target " + tgt);

                    if(mService != null)  // remove
                        log("SERVICE NOT NULL!!!");

                    if (mServiceMap.containsKey(tgt)) {
                        connectToService(tgt);
                    } else {
                        connectToAddress(tgt);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                log("nothing selected");
            }
        });

        ((Switch) findViewById(R.id.switch1)).setOnCheckedChangeListener((compoundButton, b) -> joystick1.setFixed(b));

        ((Switch) findViewById(R.id.switch2)).setOnCheckedChangeListener((compoundButton, b) -> joystick2.setFixed(b));
    }

    @Override
    protected void onResume() {
        super.onResume();

        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_UI);

        mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);

        handler = new Handler();
        handler.post(new Runnable() {

            @Override
            public void run() {
                float[] p1 = joystick1.getRelPos();
                float[] p2 = joystick2.getRelPos();
                vals[3] = p1[0];
                vals[4] = p1[1];
                vals[5] = p2[0];
                vals[6] = p2[1];
                update();

                if(handler != null)
                    handler.postDelayed(this, 20);

            }
        });

    }

    @Override
    protected void onPause() {
        super.onPause();

        mSensorManager.unregisterListener(this);

        mNsdManager.stopServiceDiscovery(this);

        closeConnection();

        handler = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GRAVITY)
            return;

        vals[0] = event.values[0];
        vals[1] = event.values[1];
        vals[2] = event.values[2];

        update();

    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void update(){
        if(mSocket != null){
            StringBuilder s = new StringBuilder();
            for(float v : vals){
                s.append(" ");
                s.append(String.valueOf(v));
            }
            send(s.toString().getBytes());
        }
    }

    public void send(byte[] msg) {
        try {
            mSocket.send(new DatagramPacket(msg, msg.length));
        } catch (IOException e) {
            log("Send error: " + e.getMessage());
        } catch (NullPointerException e) {
            log("Send error " + e.getMessage());
        }

    }

    public void connectToService(String tgt){
        NsdServiceInfo service = mServiceMap.get(tgt);
        log("Resolving Service: " + service.getServiceType());
        mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                log("Resolve failed: " + errorCode);
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                // check name again (could have changed in the mean time)
                if (tgt.equals(serviceInfo.getServiceName())) {
                    log("Resolve Succeeded. " + serviceInfo);

                    mService = serviceInfo;
                    openSocket(mService.getHost().getHostName(), mService.getPort());

                }
            }
        });
    }

    public void connectToAddress(String tgt){
        log("Connecting directly ip address " + tgt);
        String[] addr = tgt.split(":");
        (new Thread(()-> openSocket(addr[0], Integer.parseInt(addr[1])))).start();
    }

    public void openSocket(String host, int port){
        log("Trying to open UDP socket to " + host + " on port " + port);

        try {
            mSocket = new DatagramSocket(0);
            mSocket.connect(InetAddress.getByName(host), port);

            log("Connected");
            YokeActivity.this.runOnUiThread(() -> mTextView.setText("Connected to"));

        } catch (SocketException | UnknownHostException e) {
            mSocket = null;
            YokeActivity.this.runOnUiThread(() -> {
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                Toast.makeText(YokeActivity.this, "Failed to connect to " + host + ':' + port, Toast.LENGTH_SHORT).show();
            });
            log("Failed to open UDP socket (error message following)");
            e.printStackTrace();
        }
    }

    public void onDiscoveryStarted(String regType) {
        log("Service discovery started");
    }

    @Override
    public void onServiceFound(NsdServiceInfo service) {
        log("Service found " + service);
        mServiceMap.put(service.getServiceName(), service);
        mServiceNames.add(service.getServiceName());
        this.runOnUiThread(() -> {
            if(mSpinner.getSelectedItem().toString().equals(service.getServiceName()))
                return;
            mAdapter.add(service.getServiceName());
        });

    }



    @Override
    public void onServiceLost(NsdServiceInfo service) {
        log("Service lost " + service);
        mServiceMap.remove(service.getServiceName());
        mServiceNames.remove(service.getServiceName());
        this.runOnUiThread(() -> {
            if(mSpinner.getSelectedItem().toString().equals(service.getServiceName()))
                return;

            mAdapter.remove(service.getServiceName());
        });

    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
        log("Discovery stopped: " + serviceType);
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        log("Discovery failed: Error code:" + errorCode);
        mNsdManager.stopServiceDiscovery(this);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log("Discovery failed: Error code:" + errorCode);
        mNsdManager.stopServiceDiscovery(this);
    }

    private void closeConnection() {
        mService = null;
        if(mSocket != null){
            log("Connection closed");
            mTextView.setText(" Connect to ");
            mSocket.close();
            mSocket = null;
        }
    }
}