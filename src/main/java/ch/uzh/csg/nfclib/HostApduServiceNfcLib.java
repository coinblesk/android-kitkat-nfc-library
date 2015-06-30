package ch.uzh.csg.nfclib;

import java.util.Arrays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

/**
 * This class handles incoming messages over NFC, which are passed to this
 * directly by the Android OS. It is to be registered in the Manifest.xml of
 * your application.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public final class HostApduServiceNfcLib extends HostApduService {
	/*
	 * DO NOT RENAME THIS CLASS!! It is referenced in other projects in xml
	 * files and will crash other projects if you do so!
	 */

	private static final String TAG = "ch.uzh.csg.nfclib.HostApduServiceNfcLib";
	
	public static final String NFC_SERVICE_SEND_INTENT = "com.coinblesk.NFC_SERVICE_SEND_INTENT";
	public static final String NFC_SERVICE_RECEIVE_INTENT = "com.coinblesk.NFC_SERVICE_RECEIVE_INTENT";
	public static final String NFC_SERVICE_SEND_DATA = "com.coinblesk.NFC_SERVICE_SEND_DATA";
	public static final String NFC_SERVICE_SEND_DEACTIVATE = "com.coinblesk.NFC_SERVICE_SEND_DEACTIVATE";
	public static final String NFC_SERVICE_RECEIVE_DATA = "com.coinblesk.NFC_SERVICE_RECEIVE_DATA";
	
	final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final byte[] responseApdu = intent.getExtras().getByteArray(NFC_SERVICE_RECEIVE_DATA);
			if (Config.DEBUG) {
				Log.d(TAG, "about to return "+Arrays.toString(responseApdu));
			}
			sendResponseApdu(responseApdu);
		}
	};
	
	@Override
	public void onCreate() {
		if (Config.DEBUG) {
			Log.d(TAG, "created HostApduService service");
		}
		super.onCreate();
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(NFC_SERVICE_RECEIVE_INTENT);
		registerReceiver(broadcastReceiver, intentFilter);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterReceiver(broadcastReceiver);
	}

	@Override
	public byte[] processCommandApdu(final byte[] bytes, final Bundle extras) {
		sendBroadcast(bytes);
		return null;
	}
	
	@Override
	public void onDeactivated(final int reason) {
		if (Config.DEBUG) {
			Log.d(TAG, "deactivate: "+reason);
		}
		final Intent intent = new Intent(NFC_SERVICE_SEND_INTENT);
	    intent.putExtra(NFC_SERVICE_SEND_DEACTIVATE, reason);
	    sendBroadcast(intent);
	}
	
	private void sendBroadcast(final byte[] bytes) {
		final Intent intent = new Intent(NFC_SERVICE_SEND_INTENT);
	    intent.putExtra(NFC_SERVICE_SEND_DATA, bytes);
	    sendBroadcast(intent);
	}
}
