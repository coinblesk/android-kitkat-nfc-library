package ch.uzh.csg.btlib;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Pair;

public class BTUtils {
	
	private BTUtils(){}
	
	public static boolean btleSupport(final Activity activity) {
		return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
	}
	
	public static boolean btlePeripheralSupport(BluetoothAdapter bluetoothAdapter) {
		if(bluetoothAdapter == null) {
			return false;
		}
		try {
			return bluetoothAdapter.isMultipleAdvertisementSupported();
		} catch (NoSuchMethodError e) {
			return false;
		}
	}
	
	public static Pair<BluetoothManager,BluetoothAdapter> checkBT(final Activity activity) {
		// Use this check to determine whether BLE is supported on the device. Then
		// you can selectively disable BLE-related features.
    	if (!btleSupport(activity)) {
			return null;
		}
		final BluetoothManager bluetoothManager =
		        (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
		
		//On some devices this does not work
		try {
			bluetoothAdapter.getBluetoothLeScanner();
		} catch (NoSuchMethodError e) {
			return null;
		} 
		
		// Ensures Bluetooth is available on the device and it is enabled. If not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if(bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		    activity.startActivityForResult(enableBtIntent, 1);
		    bluetoothAdapter = bluetoothManager.getAdapter();
		}
		return new Pair<BluetoothManager, BluetoothAdapter>(bluetoothManager, bluetoothAdapter);
    }
	
	public static byte[] btAddress(BluetoothAdapter adapter) {
		String macAddress = adapter.getAddress();
		String[] macAddressParts = macAddress.split(":");

		// convert hex string to byte values
		byte[] macAddressBytes = new byte[6];
		for(int i=0; i<6; i++){
		    int hex = Integer.parseInt(macAddressParts[i], 16);
		    macAddressBytes[i] = (byte) hex;
		}
		return macAddressBytes;
	}
}
