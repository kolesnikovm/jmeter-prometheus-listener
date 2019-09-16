package jmeter.influx.writer.config.influxdb;

/**
 * Constants (Tag, Field, Measurement) names for the requests measurement.
 * 
 * @author Alexander Wert
 *
 */
public interface RequestMeasurement {

	/**
	 * Measurement name.
	 */
	String MEASUREMENT_NAME = "requestsRaw";

	/**
	 * Tags.
	 * 
	 * @author Alexander Wert
	 *
	 */
	public interface Tags {
		/**
		 * Request name tag.
		 */
		String REQUEST_NAME = "requestName";

		String RESPONSE_CODE = "responseCode";

		String RESPONSE_MESSAGE = "responseMessage";

        /**
         * Test name field
         */
        String TEST_NAME = "testName";

		/**
		 * Thread name field
		 */
		String THREAD_NAME = "threadName";

		/**
		 * Node name field
		 */
		String NODE_NAME = "nodeName";

		/**
		 * Influx DB tag for a unique identifier for each execution(aka 'run') of a load test.
		 */
		String RUN_ID = "runId";

		String THREAD_GROUP = "threadGroup";

        String REQUEST_STATUS = "requestStatus";
	}

	/**
	 * Fields.
	 * 
	 * @author Alexander Wert
	 *
	 */
	public interface Fields {
		/**
		 * Response time field.
		 */
		String RESPONSE_TIME = "responseTime";

		String LATENCY_TIME = "latencyTime";

		String SENT_BYTES = "sentBytes";

		String RECEIVED_BYTES = "receivedBytes";

		String GROUP_THREADS = "groupThreads";

		String ERROR_COUNT = "errorCount";
	}
}
