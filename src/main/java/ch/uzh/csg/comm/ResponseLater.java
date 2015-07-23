package ch.uzh.csg.comm;

/**
 * Send response at a later stage, in the meantime, start polling
 * 
 * @author Thomas Bocek
 *
 */
public interface ResponseLater {
	public void response(byte[] data);
}
