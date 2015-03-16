package ch.uzh.csg.nfclib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import android.app.Activity;
import android.util.Log;
import ch.uzh.csg.nfclib.NfcInitiator.TagDiscoveredHandler;
import ch.uzh.csg.nfclib.events.INfcEventHandler;
import ch.uzh.csg.nfclib.events.NfcEvent;
import ch.uzh.csg.nfclib.messages.NfcMessage;
import ch.uzh.csg.nfclib.messages.NfcMessage.Type;
import ch.uzh.csg.nfclib.transceiver.INfcTransceiver;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Log.class)
public class TransceiverTest {

	public static long userId = 1234567891011121314L;

	private Activity activity = mock(Activity.class);

	private static class State {
		NfcEvent event;
		private byte[] response = null;
	}

	private List<State> states = new ArrayList<State>();

	private INfcEventHandler eventHandler1 = new INfcEventHandler() {
		@Override
		public synchronized void handleMessage(NfcEvent event, Object object) {
			State state = new State();
			state.event = event;
			switch (event) {
			case FATAL_ERROR:
				if (object != null) {
					if (object instanceof Throwable) {
						((Throwable) object).printStackTrace();
					} else if (object instanceof String) {
						state.response = ((String) object).getBytes();
					}
				}
				break;
			case MESSAGE_RECEIVED:
				if (object != null && object instanceof byte[]) {
					state.response = (byte[]) object;
				}
				break;
			default:
				break;
			}
			states.add(state);
		}
	};
	
	private INfcEventHandler eventHandler2 = new INfcEventHandler() {
		@Override
		public synchronized void handleMessage(NfcEvent event, Object object) {
			State state = new State();
			state.event = event;
			switch (event) {
			case FATAL_ERROR:
				if (object != null) {
					if (object instanceof Throwable) {
						((Throwable) object).printStackTrace();
					} else if (object instanceof String) {
						state.response = ((String) object).getBytes();
					}
				}
				break;
			case MESSAGE_RECEIVED:
				if (object != null && object instanceof byte[]) {
					state.response = (byte[]) object;
				}
				break;
			default:
				break;
			}
			states.add(state);
			if(event != NfcEvent.INITIALIZED) {
				if (object != null && object instanceof byte[]) {
					byteCallable.set((byte[]) object);
				} else {
					byteCallable.set(null);
				}
			}
		}
	};
	
	private ByteCallable byteCallable = null;
	private FutureTask<byte[]> futureTask = null;
	
	private static class ByteCallable implements Callable<byte[]> {
		private byte[] value;
		private FutureTask<byte[]> futureTask;

		public void set(byte[] value) {
			synchronized (this) {
				this.value = value;
			}
			futureTask.run();
		}

		public void future(FutureTask<byte[]> futureTask) {
			synchronized (this) {
				this.futureTask = futureTask;
			}
		}

		@Override
		public byte[] call() throws Exception {
			synchronized (this) {
				return value;
			}
		}
	};

	private class MyNfcTransceiverImpl implements INfcTransceiver {

		private boolean enabled = false;
		private final NfcResponder customHostApduService;
		private int counterRequest = 0;
		private int counterResponse = 0;
		private final int limitRequest;
		private final int limitResponse;
		private final boolean process;
		private final int timeout;

		private TagDiscoveredHandler handler;

		public MyNfcTransceiverImpl(NfcResponder customHostApduService, int limitRequest, int limitResponse, boolean process, int timeout) {
			this.customHostApduService = customHostApduService;
			this.limitRequest = limitRequest;
			this.limitResponse = limitResponse;
			this.process = process;
			this.timeout = timeout;
		}

		public void handler(TagDiscoveredHandler handler) {
			this.handler = handler;
		}
		
		@Override
		public NfcMessage write(NfcMessage input) throws IOException {
			if (limitRequest > 0) {
				counterRequest++;
				if (counterRequest > limitRequest) {
					counterRequest = 0;
					startThread();
					throw new IOException("fake exception");
				}
			}
			if (limitResponse > 0) {
				counterResponse++;
				if (counterResponse > limitResponse) {
					counterResponse = 0;
					if (process) {
						customHostApduService.processIncomingData(input.bytes());
					}
					startThread();
					return null;
				} else {
					byte[] repsonse = customHostApduService.processIncomingData(input.bytes());
					return new NfcMessage(repsonse);
				}
			} else {
				byte[] repsonse = customHostApduService.processIncomingData(input.bytes());
				if(repsonse == null) {
					repsonse = customHostApduService.processIncomingData(input.bytes());
					return null;
				}
				return new NfcMessage(repsonse);
			}
		}

		private void startThread() {
			new Thread(new Runnable() {
				@Override
				public void run() {
					if (handler != null) {
						try {
							if (timeout > 0) {
								Thread.sleep(timeout);
							}
							handler.tagDiscovered();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}).start();
		}

		@Override
		public int maxLen() {
			return 30;
		}

		@Override
		public boolean isEnabled() {
			return enabled;
		}

		@Override
		public void turnOn(Activity activity) throws NfcLibException {
		}

		@Override
		public void turnOff(Activity activity) {
			disable();
		}

		@Override
		public void enable() {
			enabled = true;
		}

		@Override
		public void disable() {
			enabled = false;
		}
	};

	public NfcInitiator createTransceiver() {
		return createTransceiver((byte[]) null, -1, -1, false, -1, false, -1);
	}

	public NfcInitiator createTransceiver(final byte[] payload) {
		return createTransceiver(payload, -1, -1, false, -1, false, -1);
	}

	public NfcInitiator createTransceiver(final byte[] payload, int limitRequest, int limitResponse, boolean process, int timeout, final boolean sendLaterr, final int sendLaterTimeout) {
		final NfcResponder customHostApduService = new NfcResponder(eventHandler1, new ITransceiveHandler() {

	        @Override
	        public byte[] handleMessage(byte[] message, final ISendLater sendLater) {
	        	if(sendLaterr) {
	        		new Thread(new Runnable() {
						@Override
						public void run() {
							try {
                                Thread.sleep(sendLaterTimeout);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
							sendLater.sendLater(payload);
						}
					}).start();
	        		return null;
	        	}
		        if (payload != null) {
			        return payload;
		        } else {
			        return new NfcMessage(Type.DEFAULT).bytes();
		        }

	        }
        });
		return createTransceiver(customHostApduService, limitRequest, limitResponse, process, timeout);
	}

	public NfcInitiator createTransceiver(NfcResponder customHostApduService) {
		return createTransceiver(customHostApduService, -1, -1, false, -1);
	}

	public NfcInitiator createTransceiver(NfcResponder customHostApduService, int limitRequest, int limitResponse, boolean process, int timeout) {
		MyNfcTransceiverImpl myNfcTransceiverImpl = new MyNfcTransceiverImpl(customHostApduService, limitRequest, limitResponse, process, timeout);
		NfcInitiator nfc = new NfcInitiator(eventHandler2, activity, userId, myNfcTransceiverImpl);
		myNfcTransceiverImpl.handler(nfc.tagDiscoveredHandler());
		nfc.enable(null);
		return nfc;
	}
	
	final static Answer<Integer> answerd = new Answer<Integer>() {
		@Override
		public Integer answer(InvocationOnMock invocation) throws Throwable {
			System.err.println("DBG:{"+Thread.currentThread().getName()+"}" + Arrays.toString(invocation.getArguments()));
			return 0;
		}
	};
	final static Answer<Integer> answere = new Answer<Integer>() {
		@Override
		public Integer answer(InvocationOnMock invocation) throws Throwable {
			System.err.println("ERR:{"+Thread.currentThread().getName()+"}" + Arrays.toString(invocation.getArguments()));
			return 0;
		}
	};

	@Before
	public void before() {
		PowerMockito.mockStatic(Log.class);
		PowerMockito.when(Log.d(Mockito.anyString(), Mockito.anyString())).then(answerd);
		PowerMockito.when(Log.w(Mockito.anyString(), Mockito.anyString())).then(answerd);
		PowerMockito.when(Log.e(Mockito.anyString(), Mockito.anyString())).then(answere);
		PowerMockito.when(Log.e(Mockito.anyString(), Mockito.anyString(), Mockito.any(Throwable.class))).then(answere);
	}
	
	@After
	public void after() {
		/*
		 * powermock keeps everthing in memory, with time testcase get super slow
		 */
		Mockito.reset(activity);
	}

	private void reset() {
		states.clear();
		
		if(byteCallable != null) {
			byteCallable.set(null);
		}
		byteCallable = new ByteCallable();
		futureTask = new FutureTask<byte[]>(byteCallable);
		byteCallable.future(futureTask);
	}

	@Test
	public void testInitNfc() throws IOException, InterruptedException {
		reset();

		NfcInitiator transceiver = createTransceiver();
		transceiver.initNfc();

		assertEquals(2, states.size());

		assertEquals(NfcEvent.INITIALIZED, states.get(0).event);
		assertEquals(NfcEvent.INITIALIZED, states.get(1).event);
	}

	@Test
	public void testInitNfc_Fail() throws IOException, InterruptedException, NfcLibException {
		reset();

		NfcResponder c = mock(NfcResponder.class);
		// return error after first message
		when(c.processIncomingData(any(byte[].class))).thenReturn(new NfcMessage(Type.ERROR).bytes());
		NfcInitiator transceiver = createTransceiver(c);
		transceiver.initNfc();

		assertEquals(1, states.size());
		assertEquals(NfcEvent.INIT_FAILED, states.get(0).event);
	}

	@Test
	public void testTransceive_Reassembly() throws IOException, IllegalArgumentException, InterruptedException {
		reset();

		NfcInitiator transceiver = createTransceiver();
		transceiver.initNfc();

		byte[] me = TestUtils.getRandomBytes(200);
		transceiver.transceive(me);
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me, states.get(2).response));
	}
	
	@Test
	public void testTransceiveConsecutiveLoop() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		for(int i=0;i<20;i++) {
			testTransceiveConsecutive();
		}
	}

	@Test
	public void testTransceiveConsecutive() throws IOException, IllegalArgumentException, InterruptedException {
		reset();

		NfcInitiator transceiver = createTransceiver();
		transceiver.initNfc();

		byte[] me = TestUtils.getRandomBytes(200);
		transceiver.transceive(me);
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me, states.get(2).response));

		reset();

		me = TestUtils.getRandomBytes(300);
		transceiver.transceive(me);
		
		assertEquals(2, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(1).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(0).event);
		assertTrue(Arrays.equals(me, states.get(0).response));
	}
	
	@Test
	public void testTransceiveBigMessagesLoop() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		for(int i=0;i<20;i++) {
			testTransceiveBigMessages();
		}
	}

	@Test
	public void testTransceiveBigMessages() throws IOException, IllegalArgumentException, InterruptedException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(4000);
		transceiver.transceive(me2);
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));

		assertTrue(Arrays.equals(me1, states.get(3).response));

		reset();

		me2 = TestUtils.getRandomBytes(300);
		transceiver.transceive(me2);
		
		assertEquals(2, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(1).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(0).event);
		assertTrue(Arrays.equals(me2, states.get(0).response));
		assertTrue(Arrays.equals(me1, states.get(1).response));
	}

	@Test
	public void testTransceiveResume_SenderException() throws IOException, IllegalArgumentException, InterruptedException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(4000);
		transceiver.transceive(me2);

		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));

		reset();
		transceiver.initNfc();

		me2 = TestUtils.getRandomBytes(300);
		transceiver.transceive(me2);

		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}

	@Test
	public void testTransceiveResume2_SenderException() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, 19, -1, false, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(2);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);

		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}

	@Test
	public void testTransceiveResume3_SenderException() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, 19, -1, false, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();

		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);

		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}

	@Test
	public void testTransceiveResume1_ReceiverException() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, -1, 19, false, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}

	@Test
	public void testTransceiveResume2_ReceiverException() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, -1, 19, true, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();

		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}

	@Test
	public void testTransceiveResume3_ReceiverExceptionLoop() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		for(int i=0;i<20;i++) {
			testTransceiveResume3_ReceiverException();
		}
	}
	
	@Test
	public void testTransceiveResume3_ReceiverException() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, -1, 40, true, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));

		reset();
		me2 = TestUtils.getRandomBytes(3001);
		transceiver.transceive(me2);
		futureTask.get();

		assertEquals(2, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(1).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(0).event);
		assertTrue(Arrays.equals(me2, states.get(0).response));
		assertTrue(Arrays.equals(me1, states.get(1).response));
	}

	@Test
	public void testUltimate() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, 42, 45, true, -1, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));

		reset();
		me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();

		assertEquals(2, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(1).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(0).event);
		assertTrue(Arrays.equals(me2, states.get(0).response));
		assertTrue(Arrays.equals(me1, states.get(1).response));

		reset();
	}
	
	@Test
	public void testTransceiveNoTimeout() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, 27, 34, true, 300, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
	}
	
	@Test
	public void testTransceiveTimeout2() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, 24, 18, true, 600, false, -1);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		// if we sleep here, we will see two more events: INITIALIZED. This is
		// due to onTagDiscovered, that this test will throw. So we'll
		// initialize the library again.
		Thread.sleep(1500);
		
		assertEquals(5, states.size());
		assertEquals(NfcEvent.CONNECTION_LOST, states.get(2).event);
	}
	
	@Test
	public void testPolling() throws IOException, IllegalArgumentException, InterruptedException, ExecutionException {
		reset();
		
		byte[] me1 = TestUtils.getRandomBytes(2000);
		NfcInitiator transceiver = createTransceiver(me1, -1, 50, false, -1, true, 10);
		transceiver.initNfc();

		byte[] me2 = TestUtils.getRandomBytes(3000);
		transceiver.transceive(me2);
		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));
	}
	
	@Test
	public void testTransceive_IllegalVersion_Responder() throws IOException, InterruptedException, NfcLibException, ExecutionException {
		/*
		 * Test when the NfcResponder returns a NfcMessage with an illegal
		 * version
		 */
		reset();

		// the NfcMessage with the invalid sequence number
		byte header = (byte) 0x1C; // 00011100 (Type == USER_ID)
		byte sqNr = 0x01;
		NfcMessage m2 = new NfcMessage(new byte[] { header, sqNr });
		
		NfcResponder c = mock(NfcResponder.class);
		when(c.processIncomingData(any(byte[].class)))
			.thenReturn(new NfcMessage(Type.AID).response().bytes())
			.thenReturn(m2.bytes());
		
		NfcInitiator transceiver = createTransceiver(c);
		transceiver.initNfc();
		
		assertEquals(1, states.size());
		assertEquals(NfcEvent.FATAL_ERROR, states.get(0).event);
		assertNotNull(states.get(0).response);
		assertEquals(NfcInitiator.INCOMPATIBLE_VERSIONS, new String(states.get(0).response));
	}
	
	@Test
	public void testTransceive_IllegalVersion_Initiator() throws IOException, InterruptedException, NfcLibException, ExecutionException {
		/*
		 * Test when the NfcInitiator sends a NfcMessage with an illegal version
		 */
		reset();

		// the NfcMessage with the invalid sequence number
		byte header = (byte) 0x1C; // 00011100 (Type == USER_ID)
		byte sqNr = 0x01;
		NfcMessage m2 = new NfcMessage(new byte[] { header, sqNr });
		
		NfcResponder responder = new NfcResponder(eventHandler1, null);
		byte[] response = responder.processIncomingData(m2.bytes());
		
		assertEquals(1, states.size());
		assertEquals(NfcEvent.FATAL_ERROR, states.get(0).event);
		assertNotNull(states.get(0).response);
		assertEquals(NfcInitiator.INCOMPATIBLE_VERSIONS, new String(states.get(0).response));
		
		assertNotNull(response);
		NfcMessage msg = new NfcMessage(response);
		assertTrue(msg.isError());
		assertNotNull(msg.payload());
		assertEquals(NfcInitiator.INCOMPATIBLE_VERSIONS, new String((byte[]) msg.payload()));
	}
	
	@Test
	public void testPolling_NfcInitiator() throws Exception {
		reset();

		byte[] me1 = TestUtils.getRandomBytes(20);
		final byte[] me2 = TestUtils.getRandomBytes(20);
		
		final NfcInitiator transceiver = createTransceiver(me1, 1000, 1000, true, -1, false, -1);
		transceiver.initNfc();
		
		// Start the thread before, since startPolling blocks! calling sendLater
		// from another thread solves that problem.
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(50);
					transceiver.sendLater(me2);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}).start();
		transceiver.startPolling();

		futureTask.get();
		
		assertEquals(4, states.size());
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(3).event);
		assertEquals(NfcEvent.MESSAGE_RECEIVED, states.get(2).event);
		assertTrue(Arrays.equals(me2, states.get(2).response));
		assertTrue(Arrays.equals(me1, states.get(3).response));

		reset();
	}
	
}
