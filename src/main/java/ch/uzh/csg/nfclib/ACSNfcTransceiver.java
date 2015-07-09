package ch.uzh.csg.nfclib;

import java.io.IOException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.acs.smartcard.Reader;
import com.acs.smartcard.Reader.OnStateChangeListener;
import com.acs.smartcard.ReaderException;

/**
 * This class handles the ACR122u USB NFC reader initialization and the message
 * exchange over NFC.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class ACSNfcTransceiver implements NfcTrans {

	private static final String TAG = "ch.uzh.csg.nfclib.transceiver.ExternalNfcTransceiver";

	/*
	 * 64 is the maximum due to a sequence bug in the ACR122u
	 * http://musclecard.996296
	 * .n3.nabble.com/ACR122U-response-frames-contain-wrong
	 * -sequence-numbers-td5002.html If larger than 64, then I get a
	 * com.acs.smartcard.CommunicationErrorException: The sequence number (4) is
	 * invalid.
	 * 
	 * The same problem arises sometimes even with the length of 54.
	 */
	protected static final int MAX_WRITE_LENGTH = 53;

	private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
	
	private final Reader reader;
	
	private final IntentFilter filter;
	
	private final BroadcastReceiver broadcastReceiver;

	/**
	 * Creates a new instance.
	 * 
	 * @param eventHandler
	 *            the {@link NfcInitiatorHandler} (may not be null)
	 * @param nfcInit
	 *            the {@link TagDiscoveredHandler} which is notified as soon as
	 *            a NFC connection is established (may not be null)
	 * @throws NfcLibException 
	 */
	public ACSNfcTransceiver(final TagDiscoverHandler nfcInit, final Activity activity) throws NfcLibException {
		this.reader = createReader(activity);
		this.filter = new IntentFilter();
		filter.addAction(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		this.broadcastReceiver = createBroadcastReceiver(reader, nfcInit);
		final ACSTransceiver transceiver = new ACSTransceiver(reader, nfcInit);
		setOnStateChangedListener(reader, nfcInit, transceiver);
		
	}
	
	private static void setOnStateChangedListener(final Reader reader, 
			final TagDiscoverHandler nfcInit, final ACSTransceiver transceiver) {
		
		reader.setOnStateChangeListener(new OnStateChangeListener() {
			public void onStateChange(int slotNum, int prevState, int currState) {
				if (Config.DEBUG) {
					Log.d(TAG, "statechange from: " + prevState + " to: " + currState);
				}			
				if (currState == Reader.CARD_PRESENT) {				
					try {					
						transceiver.initCard();
						nfcInit.tagDiscovered(transceiver);
					} catch (ReaderException e) {
						if (Config.DEBUG) {
							Log.e(TAG, "Could not connnect reader (ReaderException): ", e);
						}
						nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
					}
				} else if(currState == Reader.CARD_ABSENT) {
					nfcInit.tagLost(transceiver);
				}
			}
		});
	}
	
	public void shutdown() {
		if (reader.isOpened()) {
			reader.close();
		}
	}
	

	/**
	 * Checks if the ACR122u USB NFC reader is attached via USB.
	 * 
	 * @param activity
	 *            the current activity, needed to retrieve all attached USB
	 *            devices
	 * @return true if the ACR122u USB NFC reader is attached, false otherwise
	 */
	public static boolean isExternalReaderAttached(Activity activity) {
		return externalReaderAttached(activity) != null;
	}
	
	private static UsbDevice externalReaderAttached(Activity activity) {
		UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
		Reader reader = new Reader(manager);

		for (UsbDevice device : manager.getDeviceList().values()) {
			if (reader.isSupported(device)) {
				return device;
			}
		}
		return null;
	}
	
	public static Reader createReader(Activity activity) throws NfcLibException {
		UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
		Reader reader = new Reader(manager);

		UsbDevice externalDevice = externalReaderAttached(activity);
		if (externalDevice == null) {
			throw new NfcLibException("External device is not set");
		}

		PendingIntent permissionIntent = PendingIntent.getBroadcast(activity, 0, new Intent(ACTION_USB_PERMISSION), 0);
		manager.requestPermission(externalDevice, permissionIntent);
		return reader;
	}

	
	
	private static class ACSTransceiver implements NfcTransceiver {
			
		final private Reader reader;
		final private TagDiscoverHandler nfcInit;
		
		private ACSTransceiver(Reader reader, TagDiscoverHandler nfcInit) {
			this.reader = reader;
			this.nfcInit = nfcInit;
		}
	
		private void initCard() throws ReaderException {
			reader.power(0, Reader.CARD_WARM_RESET);
			reader.setProtocol(0, Reader.PROTOCOL_T0 | Reader.PROTOCOL_T1);
			// Disable the standard buzzer when a tag is detected (Section 6.7). It sounds
			// immediately after placing a tag resulting in people lifting the tag off before
			// we've had a chance to read the ID.
			byte[] sendBuffer={(byte)0xFF, (byte)0x00, (byte)0x52, (byte)0x00, (byte)0x00};
			byte[] recvBuffer=new byte[8];
			int length = reader.transmit(0, sendBuffer, sendBuffer.length, recvBuffer, recvBuffer.length);
			if(length != 8) {
				nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
			}
		}
		
		

		@Override
		public byte[] write(byte[] input) throws IOException {
			if (!reader.isOpened()) {
				if (Config.DEBUG) {
					Log.d(TAG, "could not write message, reader is not or no longe open");
				}
				throw new IOException(NFCTRANSCEIVER_NOT_CONNECTED);
			}

			if (input.length > MAX_WRITE_LENGTH) {
				throw new IOException("The message length exceeds the maximum capacity of " + MAX_WRITE_LENGTH + " bytes.");
			}

			final byte[] recvBuffer = new byte[MAX_WRITE_LENGTH];
			final int length;
			try {
				length = reader.transmit(0, input, input.length, recvBuffer, recvBuffer.length);
			} catch (ReaderException e) {
				if (Config.DEBUG) {
					Log.e(TAG, "could not write message - ReaderException", e);
				}
				throw new IOException(UNEXPECTED_ERROR);
			}

			if (length <= 0) {
				if (Config.DEBUG) {
					Log.d(TAG, "could not write message - return value is 0");
				}
				throw new IOException(UNEXPECTED_ERROR);
			}

			byte[] received = new byte[length];
			System.arraycopy(recvBuffer, 0, received, 0, length);
			return received;
		}

		@Override
		public int maxLen() {
			return MAX_WRITE_LENGTH;
		}

		@Override
		public void close() {
			System.err.println("do nothing");
			
		}
	}
	
	private static BroadcastReceiver createBroadcastReceiver(final Reader reader, final TagDiscoverHandler nfcInit) {
		return new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();

				if (ACTION_USB_PERMISSION.equals(action)) {
					UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						if (device != null) {
							try {
								reader.open(device);
							} catch (Exception e) {
								nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
							}
						}
					}
				} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
					UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

					if (device != null && device.equals(reader.getDevice())) {
						reader.close();
					}
				}
			}

		};
	}

	@Override
	public int maxLen() {
		return MAX_WRITE_LENGTH;
	}
	
	@Override
	public void turnOn(Activity activity) throws NfcLibException {	
		activity.registerReceiver(broadcastReceiver, filter);
	}

	@Override
	public void turnOff(Activity activity) {
		activity.unregisterReceiver(broadcastReceiver);
	}

}
