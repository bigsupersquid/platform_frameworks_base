/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.util.ArrayList;

class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final String TAG = "BluetoothManagerService";
    private static final boolean DBG = true;

    private static final boolean ALWAYS_SYNC_NAME_ADDRESS=true; //If true, always load name and address
    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    private static final String ACTION_SERVICE_STATE_CHANGED="com.android.bluetooth.btservice.action.STATE_CHANGED";
    private static final String EXTRA_ACTION="action";
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDRESS="bluetooth_address";
    private static final String SECURE_SETTINGS_BLUETOOTH_NAME="bluetooth_name";
    private static final int TIMEOUT_BIND_MS = 3000; //Maximum msec to wait for a bind
    private static final int TIMEOUT_SAVE_MS = 500; //Maximum msec to wait for a save

    private static final int MESSAGE_ENABLE = 1;
    private static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_AIRPLANE_MODE_OFF=10;
    private static final int MESSAGE_AIRPLANE_MODE_ON=11;
    private static final int MESSAGE_REGISTER_ADAPTER = 20;
    private static final int MESSAGE_UNREGISTER_ADAPTER = 21;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    private static final int MESSAGE_BLUETOOTH_ON = 50;
    private static final int MESSAGE_BLUETOOTH_OFF = 51;
    private static final int MESSAGE_TIMEOUT_BIND =100;
    private static final int MESSAGE_TIMEOUT_UNBIND =101;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS=200;
    private static final int MESSAGE_SAVE_NAME_AND_ADDRESS=201;
    private static final int MAX_SAVE_RETRIES=3;

    private final Context mContext;
    private String mAddress;
    private String mName;
    private ContentResolver mContentResolver;
    private List<IBluetoothManagerCallback> mCallbacks;
    private List<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private IBluetooth mBluetooth;
    private boolean mBinding;
    private boolean mUnbinding;

    private void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);
        boolean mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.System.RADIO_BLUETOOTH);
        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_OFF) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_OFF);
                    mHandler.sendMessage(msg);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_ON);
                    mHandler.sendMessage(msg);
                }
            } else if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                Log.d(TAG, "Bluetooth Adapter name changed to " + newName);
                if (newName != null) {
                    storeNameAndAddress(newName, null);
                }
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                if (isAirplaneModeOn()) {
                    Message msg = mHandler.obtainMessage(MESSAGE_AIRPLANE_MODE_ON);
                    msg.arg1=0;
                    mHandler.sendMessage(msg);
                } else {
                    Message msg = mHandler.obtainMessage(MESSAGE_AIRPLANE_MODE_OFF);
                    msg.arg1=0;
                    mHandler.sendMessage(msg);
                }
            }
        }
    };

    BluetoothManagerService(Context context) {
        mContext = context;
        mBluetooth = null;
        mBinding = false;
        mUnbinding = false;
        mAddress = null;
        mName = null;
        mContentResolver = context.getContentResolver();
        mCallbacks = new ArrayList<IBluetoothManagerCallback>();
        mStateChangeCallbacks = new ArrayList<IBluetoothStateChangeCallback>();
        IntentFilter mFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerForAirplaneMode(mFilter);
        mContext.registerReceiver(mReceiver, mFilter);
        boolean airplaneModeOn = isAirplaneModeOn();
        boolean bluetoothOn = isBluetoothPersistedStateOn();
        loadStoredNameAndAddress();
        if (DBG) Log.d(TAG, "airplaneModeOn: " + airplaneModeOn + " bluetoothOn: " + bluetoothOn);
        if (!airplaneModeOn &&  bluetoothOn) {
            //Enable
            if (DBG) Log.d(TAG, "Auto-enabling Bluetooth.");
            enable();
        } else if (ALWAYS_SYNC_NAME_ADDRESS || !isNameAndAddressSet()) {
            //Sync the Bluetooth name and address from the Bluetooth Adapter
            if (DBG) Log.d(TAG,"Retrieving Bluetooth Adapter name and address...");
            getNameAndAddress();
        }
    }

    /**
     *  Returns true if airplane mode is currently on
     */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /**
     *  Returns true if the Bluetooth saved state is "on"
     */
    private final boolean isBluetoothPersistedStateOn() {
        return Settings.Secure.getInt(mContentResolver,
                Settings.Secure.BLUETOOTH_ON, 0) ==1;
    }

    /**
     *  Save the Bluetooth on/off state
     *
     */
    private void persistBluetoothSetting(boolean setOn) {
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.BLUETOOTH_ON,
                               setOn ? 1 : 0);
    }

    /**
     * Returns true if the Bluetooth Adapter's name and address is
     * locally cached
     * @return
     */
    private boolean isNameAndAddressSet() {
        return mName !=null && mAddress!= null && mName.length()>0 && mAddress.length()>0;
    }

    /**
     * Retrieve the Bluetooth Adapter's name and address and save it in
     * in the local cache
     */
    private void loadStoredNameAndAddress() {
        if (DBG) Log.d(TAG, "Loading stored name and address");
        mName = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME);
        mAddress = Settings.Secure.getString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS);
        if (mName == null || mAddress == null) {
            if (DBG) Log.d(TAG, "Name or address not cached...");
        }
    }

    /**
     * Save the Bluetooth name and address in the persistent store.
     * Only non-null values will be saved.
     * @param name
     * @param address
     */
    private void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME, name);
            mName = name;
            if (DBG) Log.d(TAG,"Stored Bluetooth name: " +
                Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_NAME));
        }

        if (address != null) {
            Settings.Secure.putString(mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS, address);
            mAddress=address;
            if (DBG)  Log.d(TAG,"Stored Bluetoothaddress: " +
                Settings.Secure.getString(mContentResolver,SECURE_SETTINGS_BLUETOOTH_ADDRESS));
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback){
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_ADAPTER);
        msg.obj = callback;
        mHandler.sendMessage(msg);
        synchronized(mConnection) {
            return mBluetooth;
        }
    }

    public void unregisterAdapter(IBluetoothManagerCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_ADAPTER);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                                "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK);
        msg.obj = callback;
        mHandler.sendMessage(msg);
    }

    public boolean isEnabled() {
        synchronized(mConnection) {
            try {
                return (mBluetooth != null && mBluetooth.isEnabled());
            } catch (RemoteException e) {
                Log.e(TAG, "isEnabled()", e);
            }
        }
        return false;
    }

    private boolean isConnected() {
        return mBluetooth != null;
    }

    public void getNameAndAddress() {
        if (DBG) {
            Log.d(TAG,"getNameAndAddress(): mBluetooth = " +
                (mBluetooth==null?"null":mBluetooth) +
                " mBinding = " + mBinding +
                " isConnected = " + isConnected());
        }
        synchronized(mConnection) {
            if (mBinding) return ;
            if (!isConnected()) mBinding = true;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
        mHandler.sendMessage(msg);
    }

    public boolean enable() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        if (DBG) {
            Log.d(TAG,"enable():  mBluetooth =" +
                    (mBluetooth==null?"null":mBluetooth) +
                    " mBinding = " + mBinding +
                    " isConnected = " + isConnected());
        }

        synchronized(mConnection) {
            //if (mBluetooth != null) return false;
            if (mBinding) {
                Log.w(TAG,"enable(): binding in progress. Returning..");
                return true;
            }
            if (!isConnected()) mBinding = true;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_ENABLE);
        msg.arg1=1; //persist
        mHandler.sendMessage(msg);
        return true;
    }

    public boolean disable(boolean persist) {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permissicacheNameAndAddresson");
        if (DBG) {
            Log.d(TAG,"disable(): mBluetooth = " +
                (mBluetooth==null?"null":mBluetooth) +
                " mBinding = " + mBinding +
                " isConnected = " + isConnected());}

        synchronized(mConnection) {
             if (mBluetooth == null) return false;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_DISABLE);
        msg.arg1=(persist?1:0);
        mHandler.sendMessage(msg);
        return true;
    }

    public void unbindAndFinish() {
        if (DBG) {
            Log.d(TAG,"unbindAndFinish(): " +
                (mBluetooth==null?"null":mBluetooth) +
                " mBinding = " + mBinding +
                " isConnected = " + isConnected());
        }

        synchronized (mConnection) {
            if (mUnbinding) return;
            mUnbinding = true;
            if (isConnected()) {
                if (DBG) Log.d(TAG, "Sending unbind request.");
                mContext.unbindService(mConnection);
                mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED));
            } else {
                mUnbinding=false;
            }
        }
    }

    public String getAddress() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        return mAddress;
    }

    public String getName() {
        mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                "Need BLUETOOTH ADMIN permission");
        return mName;
    }

    private class BluetoothServiceConnection implements ServiceConnection {

        private boolean mGetNameAddressOnly;

        public void setGetNameAddressOnly(boolean getOnly) {
            mGetNameAddressOnly = getOnly;
        }

        public boolean isGetNameAddressOnly() {
            return mGetNameAddressOnly;
        }

        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "BluetoothServiceConnection: connected to AdapterService");
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
            msg.obj = service;
            mHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName className) {
            // Called if we unexpected disconnected.
            if (DBG) Log.d(TAG, "BluetoothServiceConnection: disconnected from AdapterService");
            Message msg = mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            mHandler.sendMessage(msg);
        }
    }

    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (DBG) Log.d (TAG, "Message: " + msg.what);
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS: {
                    if (DBG) Log.d(TAG,"MESSAGE_GET_NAME_AND_ADDRESS");
                    if (mBluetooth == null) {
                        //Start bind request
                        if (!isConnected()) {
                            if (DBG) Log.d(TAG, "Binding to service to get name and address");
                            mConnection.setGetNameAddressOnly(true);
                            //Start bind timeout and bind
                            Message timeoutMsg = mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (!mContext.bindService(i, mConnection,
                                                  Context.BIND_AUTO_CREATE)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                                Log.e(TAG, "fail to bind to: " + IBluetooth.class.getName());
                            }
                        }
                    } else {
                        Message saveMsg= mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                        mHandler.sendMessage(saveMsg);
                    }
                }
                    break;
                case MESSAGE_SAVE_NAME_AND_ADDRESS: {
                    if (DBG) Log.d(TAG,"MESSAGE_SAVE_NAME_AND_ADDRESS");
                    if (mBluetooth != null) {
                        String name =  null;
                        String address = null;
                        try {
                            name =  mBluetooth.getName();
                            address = mBluetooth.getAddress();
                        } catch (RemoteException re) {
                            Log.e(TAG,"",re);
                        }

                        if (name != null && address != null) {
                            storeNameAndAddress(name,address);
                            Intent i = new Intent(IBluetooth.class.getName());
                            i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                            i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_OFF);
                            mContext.startService(i);
                            unbindAndFinish();
                        } else {
                            if (msg.arg1 < MAX_SAVE_RETRIES) {
                                Message retryMsg = mHandler.obtainMessage(MESSAGE_SAVE_NAME_AND_ADDRESS);
                                retryMsg.arg1= 1+msg.arg1;
                                if (DBG) Log.d(TAG,"Retrying name/address remote retrieval and save.....Retry count =" + retryMsg.arg1);
                                mHandler.sendMessageDelayed(retryMsg, TIMEOUT_SAVE_MS);
                            } else {
                                Log.w(TAG,"Maximum name/address remote retrieval retry exceeded");
                                unbindAndFinish();
                            }
                        }
                    }
                }
                    break;
                case MESSAGE_AIRPLANE_MODE_OFF: {
                    if (DBG) Log.d(TAG,"MESSAGE_AIRPLANE_MODE_OFF");
                    //Check if we should turn on bluetooth
                    if (!isBluetoothPersistedStateOn()) {
                        if (DBG)Log.d(TAG, "Bluetooth persisted state is off. Not turning on Bluetooth.");
                        return;
                    }
                    //Fall through to MESSAGE_ENABLE
                }
                case MESSAGE_ENABLE: {
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_ENABLE: mBluetooth = " + mBluetooth +
                            " isConnected = " + isConnected());
                    }
                    boolean persist =  (1==msg.arg1);
                    if (persist) {
                        persistBluetoothSetting(true);
                    }
                    if (mBluetooth == null) {
                        //Start bind request
                        if (!isConnected()) {
                            //Start bind timeout and bind
                            Message timeoutMsg=mHandler.obtainMessage(MESSAGE_TIMEOUT_BIND);
                            mHandler.sendMessageDelayed(timeoutMsg,TIMEOUT_BIND_MS);
                            Intent i = new Intent(IBluetooth.class.getName());
                            i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                            i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON);
                            mContext.startService(i);
                            mConnection.setGetNameAddressOnly(false);
                            if (!mContext.bindService(i, mConnection,Context.BIND_AUTO_CREATE)) {
                                mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
                                Log.e(TAG, "Fail to bind to: " + IBluetooth.class.getName());
                            }
                        }
                    } else {
                        //Check if name and address is loaded if not get it first.
                        if (ALWAYS_SYNC_NAME_ADDRESS || !isNameAndAddressSet()) {
                            try {
                                if (DBG) Log.d(TAG,"Getting and storing Bluetooth name and address prior to enable.");
                                storeNameAndAddress(mBluetooth.getName(),mBluetooth.getAddress());
                            } catch (RemoteException e) {Log.e(TAG, "", e);};
                        }
                        Intent i = new Intent(IBluetooth.class.getName());
                        i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                        i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_ON);
                        mContext.startService(i);
                    }
                    // TODO(BT) what if service failed to start:
                    // [fc] fixed: watch for bind timeout and handle accordingly
                    // TODO(BT) persist the setting depending on argument
                    // [fc]: let AdapterServiceHandle
                }
                    break;
                case MESSAGE_AIRPLANE_MODE_ON:;
                    if (DBG) {
                        Log.d(TAG, "MESSAGE_AIRPLANE_MODE_ON: mBluetooth = " + mBluetooth +
                                " isConnected = " + isConnected());
                      //Fall through to MESSAGE_DISABLE
                    }
                case MESSAGE_DISABLE:
                    if (mBluetooth != null ) {
                        boolean persist =  (1==msg.arg1);
                        if (persist) {
                            persistBluetoothSetting(false);
                        }
                        mConnection.setGetNameAddressOnly(false);
                        if (DBG) Log.d(TAG,"Sending off request.");
                        Intent i = new Intent(IBluetooth.class.getName());
                        i.putExtra(EXTRA_ACTION, ACTION_SERVICE_STATE_CHANGED);
                        i.putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_OFF);
                        mContext.startService(i);
                    }
                    // TODO(BT) what if service failed to stop:
                    // [fc] fixed: watch for disable event and unbind accordingly
                    // TODO(BT) persist the setting depending on argument
                    // [fc]: let AdapterServiceHandle

                    break;
                case MESSAGE_REGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    mCallbacks.add(callback);
                }
                    break;
                case MESSAGE_UNREGISTER_ADAPTER:
                {
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    mCallbacks.remove(callback);
                }
                    break;
                case MESSAGE_REGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.add(callback);
                }
                    break;
                case MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK:
                {
                    IBluetoothStateChangeCallback callback = (IBluetoothStateChangeCallback) msg.obj;
                    mStateChangeCallbacks.remove(callback);
                }
                    break;
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED:
                {
                    if (DBG) Log.d(TAG,"MESSAGE_BLUETOOTH_SERVICE_CONNECTED");

                    //Remove timeout
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                    IBinder service = (IBinder) msg.obj;
                    synchronized(mConnection) {
                        mBinding = false;
                        mBluetooth = IBluetooth.Stub.asInterface(service);
                    }

                    if (mConnection.isGetNameAddressOnly()) {
                        //Request GET NAME AND ADDRESS
                        Message getMsg = mHandler.obtainMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                        mHandler.sendMessage(getMsg);
                        return;
                    }

                    try {
                        for (IBluetoothManagerCallback callback : mCallbacks) {
                            callback.onBluetoothServiceUp(mBluetooth);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }

                }
                    break;
                case MESSAGE_TIMEOUT_BIND: {
                    Log.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    synchronized(mConnection) {
                        mBinding = false;
                    }
                }
                    break;
                case MESSAGE_BLUETOOTH_ON:
                {
                    if (DBG) Log.d(TAG, "MESSAGE_BLUETOOTH_ON");
                    try {
                        for (IBluetoothStateChangeCallback callback : mStateChangeCallbacks) {
                            callback.onBluetoothStateChange(true);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }
                }
                    break;
                case MESSAGE_BLUETOOTH_OFF:
                {
                    if (DBG) Log.d(TAG, "MESSAGE_BLUETOOTH_OFF");
                    try {
                        for (IBluetoothStateChangeCallback callback : mStateChangeCallbacks) {
                            callback.onBluetoothStateChange(false);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "", e);
                    }
                    unbindAndFinish();
                }
                    break;
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED:
                {
                    if (DBG) Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED");
                    boolean isUnexpectedDisconnect = false;
                    synchronized(mConnection) {
                        mBluetooth = null;
                        if (mUnbinding) {
                            mUnbinding = false;
                        } else {
                            isUnexpectedDisconnect = true;
                        }
                    }
                    if (!mConnection.isGetNameAddressOnly()) {
                            if (DBG) Log.d(TAG,"MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED: Calling onBluetoothSerivceDown callbacks");
                            try {
                                for (IBluetoothManagerCallback callback : mCallbacks) {
                                    callback.onBluetoothServiceDown();
                                }
                            }  catch (RemoteException e) {
                                Log.e(TAG, "", e);
                            }
                    }
                }
                    break;
                case MESSAGE_TIMEOUT_UNBIND:
                {
                    Log.e(TAG, "MESSAGE_TIMEOUT_UNBIND");
                    synchronized(mConnection) {
                        mUnbinding = false;
                    }
                }
                    break;
            }
        }
    };
}
