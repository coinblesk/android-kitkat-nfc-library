package ch.uzh.csg.nfclib;

import java.io.IOException;

import android.app.Activity;
import ch.uzh.csg.comm.NfcLibException;

public interface NfcTrans {
	
	
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
