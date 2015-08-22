package ch.uzh.csg.nfclib;

import java.io.IOException;

import android.app.Activity;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcTransceiver;

public interface NfcTrans {
	/**
	 * Enables the NFC message exchange (as soon as a NFC device is in range,
	 * the init handshake is done). The {@link NfcTransceiver} has to be turned
	 * on before enabling it.
	 */

	public int maxLen();
	
	/**
	 * Turns on the NFC controller, i.e., binds the NFC controller to the given
	 * activity.
	 * 
	 * @throws NfcLibException
	 */
	public void turnOn(Activity activity) throws NfcLibException;

	/**
	 * Turns off the NFC controller, i.e., removes the binding between the NFC
	 * controller and the given activity.
	 * 
	 * @throws IOException
	 */
	public void turnOff(Activity activity);
	
	/**
	 * Disables the NFC transceiver in order to avoid restarting the protocol.
	 * (The Samsung Galaxy Note 3 for example restarts the protocol by calling
	 * an init internally. This results in sequential payments if the two NFC
	 * devices stay tapped together).
	 * 
	 * This affects only the next init. The current message exchange is not
	 * affected and will be executed according the the upper layer protocol.
	 */
	//public void stopInitiating();
}
