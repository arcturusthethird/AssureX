package com.example.assurex;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.control.PendingTroubleCodesCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.commands.fuel.FuelLevelCommand;
import com.github.pires.obd.commands.protocol.AvailablePidsCommand_01_20;
import com.github.pires.obd.commands.protocol.DescribeProtocolCommand;
import com.github.pires.obd.commands.protocol.DescribeProtocolNumberCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.HeadersOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.ObdRawCommand;
import com.github.pires.obd.commands.protocol.ObdResetCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.commands.temperature.AmbientAirTemperatureCommand;
import com.github.pires.obd.enums.ObdProtocols;
import com.github.pires.obd.exceptions.NoDataException;
import com.github.pires.obd.exceptions.UnableToConnectException;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import static com.example.assurex.App.BT_CHANNEL_ID;

public class BluetoothService extends Service {

    private final static String TAG = "BluetoothService";
    BluetoothAdapter myBtAdapter;
    BluetoothDevice myDevice = null;
    Set<BluetoothDevice> pairedDevices;
    UUID myUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    BluetoothSocket mySocket;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        Intent notificationIntent = new Intent(this, Speed.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, BT_CHANNEL_ID)
                .setContentTitle("OBDII Bluetooth Service")
                .setContentText("running...")
                .setSmallIcon(R.drawable.ic_android)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
        Log.d(TAG, "Started Foreground notification");

        // get phone's bluetooth adapter
        myBtAdapter = BluetoothAdapter.getDefaultAdapter();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: ");

        BluetoothThread mBtThread = new BluetoothThread();
        mBtThread.start();

        Log.d(TAG, "onStartCommand: thread started");


        return START_STICKY;
    }

    class BluetoothThread extends Thread {

        @Override
        public void run() {

            connectionSetup();

            if (myDevice != null)
            {
                try {
                    // try to connect and do desired work
                    mySocket.connect();
                    showToast("Connection successful");

                    connectBtnState(true);


                    // initialize car with obd initialization commands
                    try {
                        // resets obd
                        new ObdResetCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(500);

                        // duplicate commands in case one is ignored
                        new EchoOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        new EchoOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(250);

                        // duplicate commands in case one is ignored
                        new HeadersOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        new HeadersOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(250);
                        new ObdRawCommand("ATD").run(mySocket.getInputStream(), mySocket.getOutputStream());

                        new ObdRawCommand("ATZ").run(mySocket.getInputStream(), mySocket.getOutputStream());

                        new EchoOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());

                        // not duplicated since it appears to be received by obd device correctly
                        new LineFeedOffCommand().run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(100);

                        // not duplicated since it appears to be received by obd device correctly
                        new TimeoutCommand(100).run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(100);

                        new ObdRawCommand("AT S0").run(mySocket.getInputStream(), mySocket.getOutputStream());

                        new ObdRawCommand("AT H0").run(mySocket.getInputStream(), mySocket.getOutputStream());

                        new TimeoutCommand(255).run(mySocket.getInputStream(), mySocket.getOutputStream());

                        // not duplicated since it appears to be received by obd device correctly
                        new SelectProtocolCommand(ObdProtocols.AUTO).run(mySocket.getInputStream(), mySocket.getOutputStream());
                        Thread.sleep(100);


                        SpeedCommand speedCommand = new SpeedCommand();
                        RPMCommand rpmCommand = new RPMCommand();
                        //PendingTroubleCodesCommand tcCommand = new PendingTroubleCodesCommand();


                        float prevSpd = 0, currentSpd;
                        boolean isEngineOn = false, isBeingTimed = false;
                        long duration = 0;



                        // loop thread for a constant stream of refreshed data, 1 sec interval
                        while (!Thread.currentThread().isInterrupted() && mySocket.isConnected())
                        {

                            if (isEngineOn && !isBeingTimed)
                            {
                                duration = 0;
                                isBeingTimed = true;
                            }

                            try {
                                rpmCommand.run(mySocket.getInputStream(), mySocket.getOutputStream());
                                speedCommand.run(mySocket.getInputStream(), mySocket.getOutputStream());
                                //tcCommand.run(mySocket.getInputStream(), mySocket.getOutputStream());

                                if (rpmCommand.getRPM()  > 0)
                                {
                                    isEngineOn = true;

                                    Log.d(TAG, "run: data acquired");



                                    currentSpd = speedCommand.getImperialUnit();
                                    Bundle b = new Bundle();
                                    //b.putString("troubleCodes", tcCommand.getFormattedResult());
                                    b.putBoolean("isEngineOn", isEngineOn);
                                    b.putDouble("tripTime", duration);
                                    b.putInt("speed", (int) currentSpd);
                                    b.putFloat("acceleration", (currentSpd - prevSpd)); // multiply by 0.0455853936 to get g force
                                    sendMessageToActivity(b);

                                    prevSpd = currentSpd;
                                }
                                else
                                {
                                    isEngineOn = false;
                                    isBeingTimed = false;

                                    Bundle b = new Bundle();
                                    //b.putString("troubleCodes", "Pending Search");
                                    b.putBoolean("isEngineOn", isEngineOn);
                                    b.putDouble("tripTime", 0);
                                    b.putInt("speed", 0);
                                    b.putFloat("acceleration", 0); // multiply by 0.0455853936 to get g force
                                    sendMessageToActivity(b);
                                }




                            } catch (NoDataException | UnableToConnectException e){
                                e.printStackTrace();
                                isEngineOn = false;
                                isBeingTimed = false;

                                Bundle b = new Bundle();
                                //b.putString("troubleCodes", "Pending Search");
                                b.putBoolean("isEngineOn", isEngineOn);
                                b.putDouble("tripTime", 0);
                                b.putInt("speed", 0);
                                b.putFloat("acceleration", 0); // multiply by 0.0455853936 to get g force
                                sendMessageToActivity(b);
                            }


                            Thread.sleep(1000);
                            duration++;
                        }


                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }



                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            showToast("Connection Failure");
            connectBtnState(false);
            stopSelf();
        }




    }




    private void connectionSetup() {

        // look in phone's bonded devices
        pairedDevices = myBtAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("OBDII")) {
                    myDevice = device;
                    showToast("Target device found");

                    // set the key to obd device to allow connection
                    try {
                        mySocket = myDevice.createRfcommSocketToServiceRecord(myUUID);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
            if (myDevice == null) {
                showToast("Target device not found");
                Intent openBtSettings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                startActivity(openBtSettings);
            }
        } else {
            showToast("No devices paired");
            Intent openBtSettings = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(openBtSettings);
        }

    }

    private void sendMessageToActivity(Bundle b) {
        Intent sendCarData = new Intent("CarDataUpdates");

        sendCarData.putExtra("CarData", b);

        sendBroadcast(sendCarData);
    }

    private void connectBtnState(boolean msg)
    {
        Intent sendStateData = new Intent("BtnStateUpdate");

        sendStateData.putExtra("value", msg);

        sendBroadcast(sendStateData);
    }

    public void showToast(String message) {
        final String msg = message;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");


        if (mySocket != null)
        {
            try {
                mySocket.getInputStream().close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                mySocket.getOutputStream().close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                mySocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
