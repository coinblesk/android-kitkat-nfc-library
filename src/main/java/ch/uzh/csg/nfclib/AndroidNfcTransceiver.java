package ch.uzh.csg.nfclib;

import java.io.IOException;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.NfcAdapter.ReaderCallback;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcEvent;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcTransceiver;
import ch.uzh.csg.comm.ReplyCallback;
import ch.uzh.csg.comm.TagDiscoverHandler;

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
	 * @param executorService 
	 * @throws NfcLibException 
	 */
	public AndroidNfcTransceiver(TagDiscoverHandler nfcInit, Activity activity) throws NfcLibException {
		this.nfcInit = nfcInit;
		this.activity = activity;
		this.nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(activity);
		//this.executorService = executorService;
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
			Log.d(TAG, "tag discovered:ExecutorService executorService; " + tag);
		}
		
		isoDep = IsoDep.get(tag);
		 
		try {
			isoDep.connect();
			final NfcTransceiver transceiver = new AndroidTransceiver(isoDep, nfcAdapter, nfcInit);
			nfcInit.tagDiscovered(transceiver);
		} catch (IOException e) {
			if (Config.DEBUG) {
				Log.e(TAG, "Could not connnect isodep: ", e);
			}
			nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
		}
	}
	
	public void shutdown() {
		turnOff(activity);
	}
	
	private static class AndroidTransceiver implements NfcTransceiver {
		
		final private IsoDep isoDep;
		final private NfcAdapter nfcAdapter;
		final private TagDiscoverHandler nfcInit;
				
		private AndroidTransceiver(IsoDep isoDep, NfcAdapter nfcAdapter, TagDiscoverHandler nfcInit) {
			this.isoDep = isoDep;
			this.nfcAdapter = nfcAdapter;
			this.nfcInit = nfcInit;
		}

		@Override
		public byte[] write(byte[] input) throws Exception {
			
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
			try {
				byte[] retVal = isoDep.transceive(input);
				return retVal;
			} catch (IOException e) {
				e.printStackTrace();
				nfcInit.tagLost(this);
				isoDep.close();
				throw new NfcLibException("connection seems to be lost: ", e);
			}
			
		}

		@Override
		public int maxLen() {
			return MAX_WRITE_LENGTH;
		}

		@Override
		public void close() {
			try {
				isoDep.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
	}

	@Override
	public int maxLen() {
		return MAX_WRITE_LENGTH;
	}

	@Override
	public void turnOn(Activity activity) throws NfcLibException {
		
		if (Config.DEBUG) {
			Log.d(TAG, "turn on device");
		}
		
		/*
		 * Based on the reported issue in
		 * https://code.google.com/p/android/issues/detail?id=58773, there is a
		 * failure in the Android NFC protocol. The IsoDep might transceive a
		 * READ BINARY, if the communication with the tag (or HCE) has been idle
		 * for a given time (125ms as mentioned on the issue report). This idle
		 * time can be changed with the EXTRA_READER_PRESENCE_CHECK_DELAY
		 * option.
		 */
		//Bundle options = new Bundle();
		//this causes a huge delay for a second reconnect! don't use this! -> setting this to 0 crashes the Oneplus One
		//options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000);

		nfcAdapter.enableReaderMode(activity, 
				this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, Bundle.EMPTY);
		
		if (!nfcAdapter.isEnabled()) {
			if (Config.DEBUG) {
				Log.d(TAG, "could not turn on NFC, nfcAdapter is not enabled");
			}
			throw new NfcLibException("could not turn on NFC, nfcAdapter is not enabled");
		}
	}

	@Override
	public void turnOff(Activity activity) {
		if (Config.DEBUG) {
			Log.d(TAG, "turn off device");
		}
		if(isoDep!=null) {
			try {
				isoDep.close();
			} catch (Throwable e) {
				Log.e(TAG, "could not close isodep", e);
			}
		}
		if (nfcAdapter.isEnabled()) {
			nfcAdapter.disableReaderMode(activity);
		}
	}
	
}
