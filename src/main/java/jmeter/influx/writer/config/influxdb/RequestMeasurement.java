package jmeter.influx.writer.config.influxdb;

public interface RequestMeasurement {

	String MEASUREMENT_NAME = "requestsRaw";

	public interface Tags {

		String REQUEST_NAME = "requestName";

		String RESPONSE_CODE = "responseCode";

		String RESPONSE_MESSAGE = "responseMessage";

        String TEST_NAME = "testName";

		String THREAD_NAME = "threadName";

		String NODE_NAME = "nodeName";

		String RUN_ID = "runId";

		String THREAD_GROUP = "threadGroup";

		String REQUEST_STATUS = "requestStatus";
		
		String REQUEST_DIRECTION = "requestDirection";
	}

	public interface Fields {

		String RESPONSE_TIME = "responseTime";

		String LATENCY_TIME = "latencyTime";

		String SENT_BYTES = "sentBytes";

		String RECEIVED_BYTES = "receivedBytes";

		String GROUP_THREADS = "groupThreads";

		String ERROR_COUNT = "errorCount";
	}
}
