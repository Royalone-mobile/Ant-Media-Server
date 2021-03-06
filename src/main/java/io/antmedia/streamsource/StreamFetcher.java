package io.antmedia.streamsource;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.javacpp.avcodec.av_packet_unref;
import static org.bytedeco.javacpp.avcodec.avcodec_parameters_copy;
import static org.bytedeco.javacpp.avformat.AVFMT_GLOBALHEADER;
import static org.bytedeco.javacpp.avformat.AVFMT_NOFILE;
import static org.bytedeco.javacpp.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.javacpp.avformat.av_interleaved_write_frame;
import static org.bytedeco.javacpp.avformat.av_read_frame;
import static org.bytedeco.javacpp.avformat.av_write_trailer;
import static org.bytedeco.javacpp.avformat.avformat_alloc_output_context2;
import static org.bytedeco.javacpp.avformat.avformat_close_input;
import static org.bytedeco.javacpp.avformat.avformat_find_stream_info;
import static org.bytedeco.javacpp.avformat.avformat_free_context;
import static org.bytedeco.javacpp.avformat.avformat_new_stream;
import static org.bytedeco.javacpp.avformat.avformat_open_input;
import static org.bytedeco.javacpp.avformat.avformat_write_header;
import static org.bytedeco.javacpp.avformat.avio_closep;
import static org.bytedeco.javacpp.avutil.AV_ROUND_NEAR_INF;
import static org.bytedeco.javacpp.avutil.AV_ROUND_PASS_MINMAX;
import static org.bytedeco.javacpp.avutil.av_dict_free;
import static org.bytedeco.javacpp.avutil.av_dict_set;
import static org.bytedeco.javacpp.avutil.av_rescale_q;
import static org.bytedeco.javacpp.avutil.av_rescale_q_rnd;

import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.rest.model.Result;

public class StreamFetcher {

	protected static Logger logger = LoggerFactory.getLogger(StreamFetcher.class);

	private Broadcast stream;

	private WorkerThread thread;

	private AVPacket pkt = new AVPacket();

	private long[] lastDTS;

	/**
	 * Connection setup timeout value
	 */
	private int timeout;

	public boolean exceptionInThread = false;

	/**
	 * Last packet received time
	 */
	private long lastPacketReceivedTime = 0;

	private boolean threadActive = false;
	
	private Result cameraError=new Result(false,"");



	private static final int PACKET_RECEIVED_INTERVAL_TIMEOUT = 3000;

	public StreamFetcher(Broadcast stream) {
		this.stream = stream;
	}


	/*
	 * This default constructor is needed for test cases
	 * 
	 */
	public StreamFetcher() {

	}

	public Result prepareInput(AVFormatContext inputFormatContext) {

		setConnectionTimeout(5000);

		Result result = new Result(false);
		if (inputFormatContext == null) {
			logger.info("cannot allocate input context");
			return result;
		}

		if (stream == null || stream.getStreamUrl() == null) {
			logger.info("stream is null");
			return result;
		}

		AVDictionary optionsDictionary = new AVDictionary();


		av_dict_set(optionsDictionary, "rtsp_transport", "tcp", 0);

		String timeout = String.valueOf(this.timeout);
		av_dict_set(optionsDictionary, "stimeout", timeout, 0);


		int ret;

		logger.info("stream url:  " + stream.getStreamUrl());

		if ((ret = avformat_open_input(inputFormatContext, stream.getStreamUrl(), null, optionsDictionary)) < 0) {

			byte[] data = new byte[1024];
			avutil.av_strerror(ret, data, data.length);

			String errorStr=new String(data, 0, data.length);
			
			result.setMessage(errorStr);		
			
			logger.info("cannot open input context with error::" +result.getMessage());
			return result;
		}
		
		
		
		av_dict_free(optionsDictionary);

		ret = avformat_find_stream_info(inputFormatContext, (AVDictionary) null);
		if (ret < 0) {
			
			result.setMessage("Could not find stream information\n");
			logger.info(result.getMessage());
			return result;
		}

		lastDTS = new long[inputFormatContext.nb_streams()];

		for (int i = 0; i < lastDTS.length; i++) {
			lastDTS[i] = -1;
		}
		result.setSuccess(true);
		return result;

	}

	public Result prepare(AVFormatContext inputFormatContext, AVFormatContext outputRTMPFormatContext) {

		Result result=prepareInput(inputFormatContext);
		
		setCameraError(result);

		if (result.isSuccess()) {
			return prepareOutput(inputFormatContext, outputRTMPFormatContext);
		} 
		
		return result;
	}

	/*
	 * public AVFormatContext getInputContext() { return inputFormatContext; }
	 */

	private Result prepareOutput(AVFormatContext inputFormatContext, AVFormatContext outputRTMPFormatContext) {
		// outputRTMPFormatContext = new AVFormatContext(null);
		
		Result result=new Result(false);

		int ret = avformat_alloc_output_context2(outputRTMPFormatContext, null, "flv", null);
		for (int i = 0; i < inputFormatContext.nb_streams(); i++) {

			AVStream in_stream = inputFormatContext.streams(i);

			AVStream out_stream = avformat_new_stream(outputRTMPFormatContext, in_stream.codec().codec());

			ret = avcodec_parameters_copy(out_stream.codecpar(), in_stream.codecpar());
			if (ret < 0) {
				logger.warn("Cannot get codec parameters\n");
				return result;
			}

			out_stream.codec().codec_tag(0);
		}


		if ((outputRTMPFormatContext.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
			// out_stream->codec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
			outputRTMPFormatContext.oformat()
			.flags(outputRTMPFormatContext.oformat().flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
		}

		if ((outputRTMPFormatContext.flags() & AVFMT_NOFILE) == 0) {
			AVIOContext pb = new AVIOContext(null);

			// TODO: get application name from red5 context, do not use embedded
			// url

			String urlStr = "rtmp://localhost/LiveApp/" + stream.getStreamId();
			// logger.debug("rtmp url: " + urlStr);
			//
			ret = avformat.avio_open(pb, urlStr, AVIO_FLAG_WRITE);
			if (ret < 0) {
				byte[] data = new byte[1024];
				avutil.av_strerror(ret, data, data.length);
				logger.info("Cannot open url: " + urlStr + " error is " + new String(data, 0, data.length));
				return result;
			}
			outputRTMPFormatContext.pb(pb);

			ret = avformat_write_header(outputRTMPFormatContext, (AVDictionary) null);
			if (ret < 0) {
				logger.info("Cannot write header to rtmp\n");
				return result;
			}
		}

		result.setSuccess(true);
		return result;
	}

	public class WorkerThread extends Thread {

		private volatile boolean stopRequestReceived = false;
		
		


		@Override
		public void run() {
		
			setThreadActive(true);
			AVFormatContext inputFormatContext = new AVFormatContext(null); // avformat.avformat_alloc_context();
			AVFormatContext outputRTMPFormatContext = new AVFormatContext(null);

			logger.info("before prepare");
			
			Result result=prepare(inputFormatContext, outputRTMPFormatContext);

			try {
				if (result.isSuccess()) {

					while (true) {
						int ret = av_read_frame(inputFormatContext, pkt);
						if (ret < 0) {
							logger.info("cannot read frame from input context");

							break;
						}

						lastPacketReceivedTime = System.currentTimeMillis();

						int packetIndex = pkt.stream_index();
						AVStream in_stream = inputFormatContext.streams(packetIndex);
						AVStream out_stream = outputRTMPFormatContext.streams(packetIndex);

						if (pkt.dts() < 0) {
							av_packet_unref(pkt);
							continue;
						}

						if (lastDTS[packetIndex] >= pkt.dts()) {
							// logger.warn("dts timestamps are not in correct order
							// last dts:" + lastDTS[packetIndex]
							// + " current dts:" + pkt.dts() + " fixing problem by
							// adding offset");

							pkt.dts(lastDTS[packetIndex] + 1);
						}

						lastDTS[packetIndex] = pkt.dts();
						if (pkt.dts() > pkt.pts()) {
							pkt.pts(pkt.dts());
						}

						pkt.pts(av_rescale_q_rnd(pkt.pts(), in_stream.time_base(), out_stream.time_base(),
								AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
						pkt.dts(av_rescale_q_rnd(pkt.dts(), in_stream.time_base(), out_stream.time_base(),
								AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
						pkt.duration(av_rescale_q(pkt.duration(), in_stream.time_base(), out_stream.time_base()));
						pkt.pos(-1);

						/*
						 * Use Mux adaptor writePacket method
						 * 
						 */

						ret = av_interleaved_write_frame(outputRTMPFormatContext, pkt);

						if (ret < 0) {
							logger.info("cannot write frame to muxer");
							break;
						}
						av_packet_unref(pkt);

						if (stopRequestReceived) {
							logger.warn("breaking the loop");
							break;
						}

					}
					
					avformat_close_input(inputFormatContext);
					inputFormatContext = null;
					
					av_write_trailer(outputRTMPFormatContext);

					if ((outputRTMPFormatContext.flags() & AVFMT_NOFILE) == 0) {
						logger.warn("before avio_closep(outputRTMPFormatContext.pb());");
						avio_closep(outputRTMPFormatContext.pb());
						outputRTMPFormatContext.pb(null);
					}

					logger.warn("before avformat_free_context(outputRTMPFormatContext);");
					avformat_free_context(outputRTMPFormatContext);
					outputRTMPFormatContext = null;
					
					setCameraError(result);
				}else {

					if (inputFormatContext != null) {
						avformat_close_input(inputFormatContext);
					}
					if (outputRTMPFormatContext != null && !outputRTMPFormatContext.isNull()) {
						if (outputRTMPFormatContext.pb() != null) {
							avio_closep(outputRTMPFormatContext.pb());
						}

						avformat_free_context(outputRTMPFormatContext);
					}

					logger.warn("Prepare for " + stream.getName() + " returned false");
					
					
					setCameraError(result);
					
				}

				
				setThreadActive(false);
				
				logger.info("Leaving StreamFetcher Thread");

			} catch (Exception e) {
				logger.info("---Exception in thread---");
				e.printStackTrace();
				exceptionInThread  = true;
			}
		}

		public void setStopRequestReceived() {
			logger.warn("inside of setStopRequestReceived");
			stopRequestReceived = true;

		}

		public boolean isStopRequestReceived() {
			return stopRequestReceived;
		}
	}

	public void startStream() {
		new Thread() {
			public void run() {
				try {
					while (threadActive) {
						Thread.sleep(100);
					}
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}

				exceptionInThread = false;
				thread = new WorkerThread();
				thread.start();
				logger.info("StartStream called, new thread is started");
			};
		}.start();

	}

	/**
	 * If thread is alive and receiving packet with in the {@link PACKET_RECEIVED_INTERVAL_TIMEOUT} time
	 * mean it is running
	 * @return true if it is running and false it is not
	 */
	public boolean isStreamAlive() {

		return ((System.currentTimeMillis() - lastPacketReceivedTime) < PACKET_RECEIVED_INTERVAL_TIMEOUT);
	}

	public boolean isStopped() {
		return thread.isInterrupted();
	}

	public void stopStream() {

		if(getThread()!=null) {
			logger.warn("stop stream called");
			getThread().setStopRequestReceived();

		}else {

			logger.warn("thread is null");
		}
	}

	public boolean isStopRequestReceived() {
		return getThread().isStopRequestReceived();
	}

	public WorkerThread getThread() {
		return thread;
	}

	public void setThread(WorkerThread thread) {
		this.thread = thread;
	}

	public Broadcast getStream() {
		return stream;
	}

	public void restart() {
		stopStream();
		new Thread() {
			public void run() {
				try {
					while (isStreamAlive()) {
						Thread.sleep(100);

					}

					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
					Thread.currentThread().interrupt();
				}

				startStream();

			};
		}.start();

	}

	/**
	 * Set timeout when establishing connection
	 * @param timeout in ms
	 */
	public void setConnectionTimeout(int timeout) {
		this.timeout = timeout * 1000;
	}

	public boolean isExceptionInThread() {
		return exceptionInThread;
	}

	public void setThreadActive(boolean threadActive) {
		this.threadActive = threadActive;
	}

	public boolean isThreadActive() {
		return threadActive;
	}
	public Result getCameraError() {
		return cameraError;
	}


	public void setCameraError(Result cameraError) {
		this.cameraError = cameraError;
	}

}
