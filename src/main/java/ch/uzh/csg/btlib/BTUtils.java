package ch.uzh.csg.btlib;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Pair;

public class BTUtils {
	public static Pair<BluetoothManager,BluetoothAdapter> checkBT(final Activity activity) {
		// Use this check to determine whether BLE is supported on the device. Then
		// you can selectively disable BLE-related features.
    	if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
			return null;
		}
		final BluetoothManager bluetoothManager =
		        (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
		// Ensures Bluetooth is available on the device and it is enabled. If not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if(bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		    activity.startActivityForResult(enableBtIntent, 1);
		    bluetoothAdapter = bluetoothManager.getAdapter();
		}
		return new Pair<BluetoothManager, BluetoothAdapter>(bluetoothManager, bluetoothAdapter);
    }
}
