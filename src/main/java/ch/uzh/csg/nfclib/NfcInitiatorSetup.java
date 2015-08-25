package ch.uzh.csg.nfclib;

import android.app.Activity;
import android.content.Context;
import ch.uzh.csg.comm.NfcEvent;
import ch.uzh.csg.comm.NfcInitiator;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcResponder;

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
public class NfcInitiatorSetup {
	
	public static final String NULL_ARGUMENT = "The message is null";
	public static final String NFCTRANSCEIVER_NOT_CONNECTED = "Could not write message, NfcTransceiver is not connected.";
	public static final String UNEXPECTED_ERROR = "An error occured while transceiving the message.";
	public static final String INVALID_SEQUENCE = "Invalid sequence";
	public static final String TIMEOUT = "Timeout";

	private final NfcTrans transceiver;
	private final NfcInitiator initiator;
	
	
	

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
	public NfcInitiatorSetup(final NfcInitiatorHandler initiatorHandler, final Context context) throws NfcLibException {
		this.initiator = new NfcInitiator(initiatorHandler);
		if (hasClass("com.acs.smartcard.Reader") && ACSNfcTransceiver.isExternalReaderAttached(context)) {
			transceiver = new ACSNfcTransceiver(initiator.tagDiscoverHandler(), context);
		} else {
			transceiver = new AndroidNfcTransceiver(initiator.tagDiscoverHandler(), context);
		}
	}
	
	public NfcInitiator getNfcInitiator() {
		return initiator;
	}
	
	/**
	 * This class initializes the {@link NfcInitiatorSetup} as soon as a NFC tag has
	 * been discovered.
	 */
	

	

	/**
	 * Enables the NFC so that messages can be exchanged. Attention: the
	 * enable() method must be called first!
	 * @throws NfcLibException 
	 */
	public void startInitiating(Activity activity) {
		initiator.reset();
		transceiver.turnOn(activity);
		initiator.setInitiating(true);
		initiator.setFirst(true);
	}
	
	/**
	 * Soft disables the NFC to prevent devices such as the Samsung Galaxy Note
	 * 3 (other devices may show the same behavior!) to restart the protocol
	 * after having send the last message!
	 * 
	 * This should be called after a successful communication. Once you want to
	 * restart the NFC capability, call enableNFC.
	 */
	public void stopInitiating(Activity activity) {
		initiator.setInitiating(false);
		transceiver.turnOff(activity);
	}
	
	
	
	private static boolean hasClass(String className) {
	    try  {
	        Class.forName(className);
	        return true;
	    }  catch (final ClassNotFoundException e) {
	        return false;
	    }
	}
}
