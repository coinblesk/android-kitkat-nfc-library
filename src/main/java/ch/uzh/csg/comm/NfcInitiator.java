package ch.uzh.csg.comm;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.uzh.csg.comm.NfcMessage.Type;

public class NfcInitiator {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(NfcInitiator.class);

	private final NfcInitiatorHandler initiatorHandler;
	
	// state
	private final Deque<NfcMessage> messageQueue = new ConcurrentLinkedDeque<NfcMessage>();
	private final NfcMessageSplitter messageSplitter = new NfcMessageSplitter();
	private NfcMessage lastMessageSent;

	private volatile boolean initiating = true;
	private volatile boolean first = false;
	
	public NfcInitiator(NfcInitiatorHandler initiatorHandler) {
		this.initiatorHandler = initiatorHandler;
	}

	public TagDiscoverHandler tagDiscoverHandler() {

		return new TagDiscoverHandler() {
			@Override
			public void tagDiscovered(final NfcTransceiver nfcTransceiver, boolean handshake, boolean continueNFC) {
				if (Config.DEBUG) {
					LOGGER.debug( "Tag detected!");
				}
				initiatorHandler.nfcTagFound();
				try {			
					if (!initiating) {
						if (Config.DEBUG) {
							LOGGER.debug( "Nothing to do shutdown 1!");
						}
						nfcTransceiver.close();
						return;
					}
					if(handshake) {
						try {
							handshake(nfcTransceiver);
						} catch (IOException e) {
							initiatorHandler.handleFailed(e.toString());
							return;
						}
					}
					initiatorHandler.handleStatus("handshake complete");
					// check if we should resume
					if (!messageQueue.isEmpty()) {
						if (!processMessage(nfcTransceiver)) {
							if (Config.DEBUG) {
								LOGGER.debug( "Nothing to do shutdown 2!");
							}
							return;
						}
					}
					// get the complete message
					while (initiatorHandler.hasMoreMessages()) {
						byte[] message = initiatorHandler.nextMessage();
						if (message == null) {
							// start polling
							if (Config.DEBUG) {
								LOGGER.debug( "Start polling");
							}
							messageQueue.offer(new NfcMessage(Type.POLLING_REQUEST));
						} else {

							// split it
							for (NfcMessage msg : messageSplitter.getFragments(message)) {
								messageQueue.offer(msg);
							}
						}

						if (!processMessage(nfcTransceiver)) {
							return;
						}

					}
					
					if(Config.DEBUG) {
						LOGGER.debug( "loop done");
					}
					
					if(!continueNFC) {
						return;
					}

					// we are complete

					// hack for PN547 devices
					// Oneplus One with PN547: nfaDeviceManagementCallback:
					// crash NFC service: happens often in initiator mode:

					// F/libc (31968): Fatal signal 6 (SIGABRT), code -6 in tid
					// 31992 (Thread-2661)
					// I/DEBUG ( 270): *** *** *** *** *** *** *** *** *** ***
					// *** *** *** *** *** ***
					// I/DEBUG ( 270): Build fingerprint:
					// 'oneplus/bacon/A0001:5.0.2/LRX22G/YNG1TAS2I3:user/release-keys'
					// I/DEBUG ( 270): Revision: '0'
					// I/DEBUG ( 270): ABI: 'arm'
					// I/DEBUG ( 270): pid: 31968, tid: 31992, name: Thread-2661
					// >>> com.android.nfc <<<
					// E/DEBUG ( 270): AM write failure (32 / Broken pipe)
					// I/DEBUG ( 270): signal 6 (SIGABRT), code -6 (SI_TKILL),
					// fault addr --------
					// I/DEBUG ( 270): r0 00000000 r1 00007cf8 r2 00000006 r3
					// 00000000
					// I/DEBUG ( 270): r4 a2e01db8 r5 00000006 r6 00000000 r7
					// 0000010c
					// I/DEBUG ( 270): r8 a2e01ca0 r9 ffffff34 sl b6f2a9fd fp
					// a2e01db0
					// I/DEBUG ( 270): ip 00007cf8 sp a2e01b58 lr b6f2b235 pc
					// b6f4de44 cpsr 600f0010
					// I/DEBUG ( 270):
					// I/DEBUG ( 270): backtrace:
					// I/DEBUG ( 270): #00 pc 00036e44 /system/lib/libc.so
					// (tgkill+12)
					// I/DEBUG ( 270): #01 pc 00014231 /system/lib/libc.so
					// (pthread_kill+52)
					// I/DEBUG ( 270): #02 pc 00014f93 /system/lib/libc.so
					// (raise+10)
					// I/DEBUG ( 270): #03 pc 000116a5 /system/lib/libc.so
					// (__libc_android_abort+36)
					// I/DEBUG ( 270): #04 pc 0000fd3c /system/lib/libc.so
					// (abort+4)
					// I/DEBUG ( 270): #05 pc 0000d761
					// /system/lib/libnfc_nci_jni.so
					// I/DEBUG ( 270): #06 pc 0001aed0 /system/lib/libnfc-nci.so
					// I/DEBUG ( 270): #07 pc 0003d940 /system/lib/libnfc-nci.so
					// (nfc_ncif_event_status+64)
					// I/DEBUG ( 270): #08 pc 0003d9a0 /system/lib/libnfc-nci.so
					// (nfc_ncif_cmd_timeout+52)
					// I/DEBUG ( 270): #09 pc 0003caf8 /system/lib/libnfc-nci.so
					// (nfc_process_timer_evt+120)
					// I/DEBUG ( 270): #10 pc 0003d000 /system/lib/libnfc-nci.so
					// (nfc_task+412)
					// I/DEBUG ( 270): #11 pc 00058630 /system/lib/libnfc-nci.so
					// (gki_task_entry+48)
					// I/DEBUG ( 270): #12 pc 00013a1b /system/lib/libc.so
					// (__pthread_start(void*)+30)
					// I/DEBUG ( 270): #13 pc 00011a0f /system/lib/libc.so
					// (__start_thread+6)
					// I/DEBUG ( 270):
					// I/DEBUG ( 270): Tombstone written to:
					// /data/tombstones/tombstone_01
					try {
						while (true) {
							NfcMessage request = new NfcMessage(Type.POLLING_REQUEST);
							request.sequenceNumber(lastMessageSent);
							nfcTransceiver.write(request.bytes());
							lastMessageSent = request;
						}
					} catch (NfcLibException e) {
						initiating = false;
						if (Config.DEBUG) {
							LOGGER.debug( "loop done");
						}
						reset();
					}

				} catch (Throwable t) {
					t.printStackTrace();
					initiatorHandler.handleFailed(t.toString());
					return;
				}

			}

			@Override
			public void tagFailed(String message) {
				initiatorHandler.handleFailed(message);
			}

			@Override
			public void tagLost(NfcTransceiver nfcTransceiver) {
				LOGGER.debug("Tag lost");
				initiatorHandler.nfcTagLost();
			}
		};
	}

	public boolean processMessage(NfcTransceiver transceiver) {
		try {
			messageLoop(transceiver);
		} catch (NfcLibException e) {
			if (Config.DEBUG) {
				LOGGER.debug( "Tag lost");
			}
			return false;
		} catch (Throwable t) {
			t.printStackTrace();
			reset();
			initiatorHandler.handleFailed(t.toString());
			return false;
		}
		messageSplitter.clear();
		return true;
	}

	public void reset() {
		lastMessageSent = null;
		messageQueue.clear();
		messageSplitter.clear();
	}
	
	public void setFirst(boolean first) {
		this.first = first;
	}
	
	public void setInitiating(boolean initiating) {
		this.initiating = initiating;
	}

	private void handshake(NfcTransceiver transceiver) throws Exception {
		if (Config.DEBUG) {
			LOGGER.debug( "init NFC");
		}
		final NfcMessage initMessage;
		final int maxLenThis = transceiver.maxLen();
		switch (maxLenThis) {
		case 53:
			initMessage = new NfcMessage(Type.AID_2);
			break;
		case 245:
			initMessage = new NfcMessage(Type.AID_3);
			break;
		case Integer.MAX_VALUE:
			initMessage = new NfcMessage(Type.AID_1);
			break;
		default:
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		if (first) {
			initMessage.first();
			first = false;
		}

		// no sequence number here,initiating.set( as this is a special message
		if (Config.DEBUG) {
			LOGGER.debug( "handshake write: {}", Arrays.toString(initMessage.bytes()));
		}
		final byte[] response = transceiver.write(initMessage.bytes());
		final NfcMessage responseMessage = new NfcMessage(response);
		if (Config.DEBUG) {
			LOGGER.debug( "handshake response: {}", Arrays.toString(response));
		}
		// --> here we can get an exception. We should get back this array:
		// {2,0,0,0,x}
		if (responseMessage.sequenceNumber() != 0) {
			LOGGER.error( "handshake header unexpected: {}", responseMessage);
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		if (responseMessage.payload().length != 2 + 16) {
			LOGGER.error( "handshake payload unexpected: {}", responseMessage);
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		final int maxLenOther = Utils.byteArrayToShort(responseMessage.payload(), 0);
		byte[] uuid = new byte[16];
		System.arraycopy(responseMessage.payload(), 2, uuid, 0, 16);
		initiatorHandler.setUUID(uuid);
		messageSplitter.maxTransceiveLength(Math.min(maxLenOther, maxLenThis));
	}
	
	public void setmaxTransceiveLength(int len) {
		messageSplitter.maxTransceiveLength(len);
	}

	private void messageLoop(NfcTransceiver transceiver) throws Exception {
		if (Config.DEBUG) {
			LOGGER.debug( "start message loop");
		}
		while (!messageQueue.isEmpty()) {
			final NfcMessage request = messageQueue.peek();

			request.sequenceNumber(lastMessageSent);

			if (Config.DEBUG) {
				LOGGER.debug( "loop write: {} / {}", request, Arrays.toString(request.bytes()));
			}
			byte[] response = transceiver.write(request.bytes());

			if (response == null) {
				throw new IOException("respones is null, unexpected");
			}
			NfcMessage responseMessage = new NfcMessage(response);
			if (Config.DEBUG) {
				LOGGER.debug( "loop response: {}", responseMessage);
			}

			// message successfully sent, remove from queue
			messageQueue.poll();
			initiatorHandler.handleStatus("message fragment sent, queue: " + messageQueue.size());

			if (!validateSequence(request, responseMessage)) {
				if (Config.DEBUG) {
					LOGGER.debug( "sequence error {} / {}", request, response);
				}
				throw new IOException("invalid sequence");
			}

			lastMessageSent = request;

			switch (responseMessage.type()) {
			case SINGLE:
			case FRAGMENT:
			case FRAGMENT_LAST:
				if (responseMessage.payload().length > 0) {
					// we receive fragments
					switch (responseMessage.type()) {
					case SINGLE:
						initiatorHandler.handleMessageReceived(responseMessage.payload());
						break;
					case FRAGMENT:
						messageSplitter.reassemble(responseMessage);
						messageQueue.offer(new NfcMessage(Type.FRAGMENT));
						break;
					case FRAGMENT_LAST:
						messageSplitter.reassemble(responseMessage);
						initiatorHandler.handleMessageReceived(messageSplitter.data());
						break;
					default:
						throw new RuntimeException("This can never happen");
					}
				} else {
					// we send fragments
					if (messageQueue.isEmpty()) {
						throw new IOException("message queue empty, cannot send fragments");
					}
				}
				break;
			case POLLING_REQUEST:
				messageQueue.offer(new NfcMessage(Type.POLLING_RESPONSE));
				break;
			case POLLING_RESPONSE:
				break;
			case ERROR:
				throw new IOException("the message " + request + " caused an exception on the other side");
			default:
				throw new IOException("did not expect the type " + responseMessage.type() + " as reply");
			}
		}
	}
	
	public static boolean validateSequence(final NfcMessage request, final NfcMessage response) {
		boolean check = request.sequenceNumber() == response.sequenceNumber();
		if (!check) {
			LOGGER.error( "sequence number mismatch, expected {}, but was: {}", ((request.sequenceNumber() + 1) % 16), response.sequenceNumber());
			return false;
		}
		return true;
	}
}
