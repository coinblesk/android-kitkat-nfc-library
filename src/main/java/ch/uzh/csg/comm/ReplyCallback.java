package ch.uzh.csg.comm;

public interface ReplyCallback {

	void response(byte[] value) throws Exception;

}
