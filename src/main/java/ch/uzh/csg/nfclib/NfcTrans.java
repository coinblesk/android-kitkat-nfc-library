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
	public boolean turnOn(Activity activity);

	/**
	 * Turns off the NFC controller, i.e., removes the binding between the NFC
	 * controller and the given activity.
	 * 
	 * @throws IOException
	 */
	public void turnOff(Activity activity);
}
