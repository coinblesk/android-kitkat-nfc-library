package ch.uzh.csg.comm;

import android.app.Activity;

public interface CommSetup {

	void stopInitiating(Activity test);

	void shutdown(Activity test);

	void startInitiating(Activity test) throws NfcLibException;

}
