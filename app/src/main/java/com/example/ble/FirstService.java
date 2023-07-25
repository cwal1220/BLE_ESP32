package com.example.ble;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.UUID;

public class FirstService extends Service {

    private static final String TAG = "BLEService";
    private static final UUID SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b"); // ESP32의 GATT 서비스 UUID로 대체해야 합니다.
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8"); // ESP32의 특성 UUID로 대체해야 합니다.

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic characteristic;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        FirstService getService() {
            return FirstService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 서비스가 시작될 때 실행되는 로직을 처리합니다.
        createNotificationChannel();

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            // 블루투스 매니저를 가져올 수 없는 경우 처리
            Toast.makeText(this, "BluetoothManager를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show();
            stopSelf(); // 서비스를 중지합니다.
            return START_NOT_STICKY;
        }

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            // 블루투스를 지원하지 않는 경우 처리
            Toast.makeText(this, "블루투스를 지원하지 않는 기기입니다.", Toast.LENGTH_SHORT).show();
            stopSelf(); // 서비스를 중지합니다.
            return START_NOT_STICKY;
        }

        // 블루투스 기기 검색 결과를 받기 위해 BroadcastReceiver 등록
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);
        // 블루투스 기기 검색 시작
        bluetoothAdapter.startDiscovery();

        // 포그라운드 서비스로 실행합니다.
        startForeground(1, createNotification());

        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onDestroy() {
        super.onDestroy();
        // 서비스가 종료될 때 호출됩니다. 블루투스 기기 검색 중지 및 BroadcastReceiver 등록 해제
        bluetoothAdapter.cancelDiscovery();
        unregisterReceiver(receiver);
        // 블루투스 연결 종료
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getName() != null && device.getName().equals("ESP32")) {
                    // ESP32 기기를 찾았을 때 자동으로 연결합니다.
                    connectToDevice(device);
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(this, true, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                // GATT 서비스 발견 시작
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server. Trying to reconnect...");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
                    // Notification을 활성화하여 데이터 수신 준비
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // 데이터를 수신한 경우 호출됩니다.
            byte[] data = characteristic.getValue();
            String receivedMessage = new String(data);
            Log.i(TAG, "Received message: " + receivedMessage);
            // 여기서 receivedMessage를 활용하여 필요한 처리를 수행합니다.
        }
    };

    @SuppressLint("MissingPermission")
    public void sendMessage(String message) {
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.setValue(message);
            bluetoothGatt.writeCharacteristic(characteristic);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                NotificationChannel channel = new NotificationChannel("1", "Foreground Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder builder = new Notification.Builder(this, "1")
                    .setContentTitle("Foreground Service")
                    .setContentText("서비스가 실행 중입니다.")
                    .setSmallIcon(R.drawable.ic_launcher_background); // 알림 아이콘을 설정합니다.
            return builder.build();
        }
        return null;
    }
}
