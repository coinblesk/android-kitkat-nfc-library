package ch.uzh.csg.nfclib;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.ReaderCallback;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;

/**
 * This class handles the initialization and the message exchange over NFC for
 * the internal or build-in NFC controller of the Android smartphone or tablet.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class AndroidNfcTransceiver implements ReaderCallback, NfcTrans {
	
	private static final String TAG = "ch.uzh.csg.nfclib.transceiver.AndroidNfcTransceiver";

	/*
	 * NXP chip supports max 255 bytes (problems might arise sometimes if
	 * sending exactly 255 bytes)
	 */
	private static final int MAX_WRITE_LENGTH = 245;
	private final TagDiscoverHandler nfcInit;
	private final NfcAdapter nfcAdapter;
	private final Activity activity;
	private final ExecutorService executorService;
	
	/*
	 * not sure if this is called from different threads. Make it volatile just
	 * in case.
	 */
	
	private volatile IsoDep isoDep;

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
	public AndroidNfcTransceiver(TagDiscoverHandler nfcInit, ExecutorService executorService, Activity activity) throws NfcLibException {
		this.nfcInit = nfcInit;
		this.executorService = executorService;
		this.activity = activity;
		this.nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(activity);
		if (nfcAdapter == null) {
			throw new NfcLibException("NFC Adapter is null");
		}

		if (!nfcAdapter.isEnabled()) {
			throw new NfcLibException("NFC is not enabled");
		}
	}

	@Override
	public void onTagDiscovered(Tag tag) {
		if (Config.DEBUG) {
			Log.d(TAG, "tag discovered: " + tag);
		}
		
		isoDep = IsoDep.get(tag);
		
		try {
			isoDep.connect();
			final NfcTransceiver transceiver = new AndroidTransceiver(isoDep, nfcAdapter);
			executorService.submit(new PollTagLost(nfcInit, isoDep, transceiver));
			nfcInit.tagDiscovered(transceiver);
		} catch (IOException e) {
			if (Config.DEBUG) {
				Log.e(TAG, "Could not connnect isodep: ", e);
			}
			nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
		}
	}
	
	public void shutdown() {
		nfcAdapter.disableReaderMode(activity);
	}
	
	private static class AndroidTransceiver implements NfcTransceiver {
		
		final private IsoDep isoDep;
		final private NfcAdapter nfcAdapter;
				
		private AndroidTransceiver(IsoDep isoDep, NfcAdapter nfcAdapter) {
			this.isoDep = isoDep;
			this.nfcAdapter = nfcAdapter;
		}

		

		@Override
		public byte[] write(byte[] input) throws IOException {
			
			if (!nfcAdapter.isEnabled()) {
				if (Config.DEBUG) {
					Log.d(TAG, "could not write message, nfcAdapter is not enabled");
				}
				throw new IOException(NFCTRANSCEIVER_NOT_ENABLED);
			}

			if (!isoDep.isConnected()) {
				if (Config.DEBUG) {
					Log.d(TAG, "could not write message, isodep is not or no longer connected");
				}
				throw new IOException(NFCTRANSCEIVER_NOT_CONNECTED);
			}

			if (input.length > isoDep.getMaxTransceiveLength()) {
				throw new IOException("This message length exceeds the maximum capacity of " + isoDep.getMaxTransceiveLength() + " bytes.");
			} else if (input.length > MAX_WRITE_LENGTH) {
				throw new IOException("The message length exceeds the maximum capacity of " + MAX_WRITE_LENGTH + " bytes.");
			}
			
			return isoDep.transceive(input);
		}

		@Override
		public int maxLen() {
			return MAX_WRITE_LENGTH;
		}
	}
	
	private static class PollTagLost implements Runnable {
		
		final private TagDiscoverHandler nfcInit;
		final private IsoDep isoDep;
		final private NfcTransceiver nfcTransceiver;
		
		private PollTagLost(TagDiscoverHandler nfcInit, IsoDep isoDep, NfcTransceiver nfcTransceiver) {
			this.nfcInit = nfcInit;
			this.isoDep = isoDep;
			this.nfcTransceiver = nfcTransceiver;
		}
		public void run() {
			try {
				while(isoDep.isConnected()) {
					Thread.sleep(50);
				}
			} catch (Throwable t) {
				if (Config.DEBUG) {
					Log.e(TAG, "Could not connnect isodep1: ", t);
				}
			}
			try {
				isoDep.close();
			} catch (Throwable t) {
				if (Config.DEBUG) {
					Log.e(TAG, "Could not connnect isodep2: ", t);
				}
			}
			nfcInit.tagLost(nfcTransceiver);
		}
	}

	@Override
	public int maxLen() {
		return MAX_WRITE_LENGTH;
	}

	@Override
	public void turnOn(Activity activity) throws NfcLibException {
		/*
		 * Based on the reported issue in
		 * https://code.google.com/p/android/issues/detail?id=58773, there is a
		 * failure in the Android NFC protocol. The IsoDep might transceive a
		 * READ BINARY, if the communication with the tag (or HCE) has been idle
		 * for a given time (125ms as mentioned on the issue report). This idle
		 * time can be changed with the EXTRA_READER_PRESENCE_CHECK_DELAY
		 * option.
		 */
		Bundle options = new Bundle();
		//this causes a huge delay for a second reconnect! don't use this!
		options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 0);

		nfcAdapter.enableReaderMode(activity, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, options);
	}

	@Override
	public void turnOff(Activity activity) {
		try {
			isoDep.close();
		} catch (IOException e) {
			if (Config.DEBUG) {
				Log.d(TAG, "could not close isodep", e);
			}
		}
		nfcAdapter.disableReaderMode(activity);
		
	}
}
