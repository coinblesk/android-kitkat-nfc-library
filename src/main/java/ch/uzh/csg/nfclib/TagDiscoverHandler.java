package ch.uzh.csg.nfclib;

/**
 * Handle if Tag is discovered
 * 
 * @author Thomas Bocek
 *
 */
public interface TagDiscoverHandler {
	
	public void tagDiscovered(NfcTransceiver nfcTransceiver);

	public void tagFailed(String message);

	public void tagLost(NfcTransceiver nfcTransceiver);
}
