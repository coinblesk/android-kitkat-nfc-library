package ch.uzh.csg.nfclib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcEvent;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcResponder;
import ch.uzh.csg.comm.NfcResponseHandler;

/**
 * This class represents the NFC party which initiates a NFC connection. It
 * sends a request and receives a response from the {@link NfcResponder}. This
 * can be repeated as often as required.
 * 
 * To be able to send and receive messages, enable() has to be called first.
 * Afterwards, transceive(byte[]) can be called. Once all messages are
 * exchanged, disable() has to be called in order to stop the services
 * appropriately.
 * 
 * Packet flow (handshake):
 * sender -> recipient
 * AID ->
 * <- AID_SELECTED
 * -> USER_ID
 * <- USER_ID
 * = handshake complete
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcResponderSetup  {
	private static final Logger LOGGER = LoggerFactory.getLogger(HostApduServiceNfcLib.class);
	
	public static final String NULL_ARGUMENT = "The message is null";
	public static final String NFCTRANSCEIVER_NOT_CONNECTED = "Could not write message, NfcTransceiver is not connected.";
	public static final String UNEXPECTED_ERROR = "An error occured while transceiving the message.";
	public static final String INVALID_SEQUENCE = "Invalid sequence";
	public static final String TIMEOUT = "Timeout";

	private final AppBroadcastReceiver broadcastReceiver;

	final public class AppBroadcastReceiver extends BroadcastReceiver {
		
		final private NfcResponder responder;
		public AppBroadcastReceiver(final NfcResponder responder) {
			this.responder = responder;
		}
		
        @Override
        public void onReceive(final Context context, final Intent intent) {
        	if(Config.DEBUG) {
        		LOGGER.debug( "received broadcast message ", intent);
        	}
        	final byte[] responseApdu = intent.getExtras().getByteArray(HostApduServiceNfcLib.NFC_SERVICE_SEND_DATA);
        	if(responseApdu != null) {
        		byte[] processed = responder.processIncomingData(responseApdu);
        		sendBroadcast(context, processed);
        	} else {
        		final int reason = intent.getExtras().getInt(HostApduServiceNfcLib.NFC_SERVICE_SEND_DEACTIVATE);
        		responder.onDeactivated(reason);
        	}
        	
        }
        
        private void sendBroadcast(Context context, final byte[] bytes) {
    		final Intent intent = new Intent(HostApduServiceNfcLib.NFC_SERVICE_RECEIVE_INTENT);
    	    intent.putExtra(HostApduServiceNfcLib.NFC_SERVICE_RECEIVE_DATA, bytes);
    	    context.sendBroadcast(intent);
    	}
    };

	/**
	 * Instantiates a new object. If the ACR122u USB NFC reader is attached, it
	 * will be used for the NFC. Otherwise, the build-in NFC controller will be
	 * used.
	 * 
	 * @param eventHandler
	 *            the {@link NfcInitiatorHandler} to listen for {@link NfcEvent}s
	 * @param activity
	 *            the application's current activity to bind the NFC service to
	 *            it
	 * @param userId
	 *            the identifier of this user (or this mobile device)
	 * @throws NfcLibException 
	 */
	public NfcResponderSetup(final NfcResponseHandler responseHandler) {
		final NfcResponder responder = new NfcResponder(responseHandler, AndroidNfcTransceiver.MAX_WRITE_LENGTH);
		broadcastReceiver = new AppBroadcastReceiver(responder);
	}
	
	public void enable(final Activity activity) {
		IntentFilter filter = new IntentFilter();
		filter.addAction(HostApduServiceNfcLib.NFC_SERVICE_SEND_INTENT);
		activity.registerReceiver(broadcastReceiver, filter);
	}
	
	public void disable(final Activity activity) {
		activity.unregisterReceiver(broadcastReceiver);
	}
	
}
