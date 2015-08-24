package ch.uzh.csg.comm;

/**
 * Handle if Tag is discovered
 * 
 * @author Thomas Bocek
 *
 */
public interface TagDiscoverHandler {
	
	public void tagDiscovered(NfcTransceiver nfcTransceiver, boolean handshake, boolean polling, boolean continueNFC);

	public void tagFailed(String message);

	public void tagLost(NfcTransceiver nfcTransceiver);
}
