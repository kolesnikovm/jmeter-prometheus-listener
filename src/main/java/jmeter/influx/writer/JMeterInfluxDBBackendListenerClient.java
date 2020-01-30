package jmeter.influx.writer;

import jmeter.influx.writer.config.influxdb.InfluxDBConfig;
import jmeter.influx.writer.config.influxdb.RequestMeasurement;
import jmeter.influx.writer.config.influxdb.TestStartEndMeasurement;
import jmeter.influx.writer.config.influxdb.VirtualUsersMeasurement;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Backend listener that writes JMeter metrics to influxDB directly.
 * 
 * @author Alexander Wert
 *
 */
public class JMeterInfluxDBBackendListenerClient extends AbstractBackendListenerClient implements Runnable {

	private static final Logger LOGGER = LoggingManager.getLoggerForClass();

	private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
	private static final String KEY_TEST_NAME = "testName";
	private static final String KEY_RUN_ID = "runId";
	private static final String KEY_SAMPLERS_LIST = "samplersList";
	private static final String KEY_RECORD_SUB_SAMPLES = "recordSubSamples";

	private static final String SEPARATOR = ";";
	private static final int ONE_MS_IN_NANOSECONDS = 1000000;
	private ScheduledExecutorService scheduler;
	private String testName;
	private String runId;
	private String nodeName;
	private String samplersList = "";
	private String regexForSamplerList;
	private Set<String> samplersToFilter;
	InfluxDBConfig influxDBConfig;
	private InfluxDB influxDB;
	private Random randomNumberGenerator;
	private boolean recordSubSamples;

	public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
		// Gather all the listeners
		List<SampleResult> allSampleResults = new ArrayList<SampleResult>();
		for (SampleResult sampleResult : sampleResults) {
            allSampleResults.add(sampleResult);

            if(recordSubSamples) {
				for (SampleResult subResult : sampleResult.getSubResults()) {
					allSampleResults.add(subResult);
				}
			}
        }

		for(SampleResult sampleResult: allSampleResults) {
            getUserMetrics().add(sampleResult);

			if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList)) || samplersToFilter.contains(sampleResult.getSampleLabel())) {
				Point point = Point.measurement(RequestMeasurement.MEASUREMENT_NAME)
						.time(System.currentTimeMillis() * ONE_MS_IN_NANOSECONDS + getUniqueNumberForTheSamplerThread(), TimeUnit.NANOSECONDS)
						.tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
//						.tag(RequestMeasurement.Tags.THREAD_NAME, sampleResult.getThreadName())
						.tag(RequestMeasurement.Tags.THREAD_GROUP, sampleResult.getThreadName().substring(0, sampleResult.getThreadName().lastIndexOf(32)))
						.tag(RequestMeasurement.Tags.TEST_NAME, testName)
						.tag(RequestMeasurement.Tags.NODE_NAME, nodeName)
						.tag(RequestMeasurement.Tags.RESPONSE_MESSAGE, sampleResult.getResponseMessage())
						.tag(RequestMeasurement.Tags.RESPONSE_CODE, sampleResult.getResponseCode())
						.tag(RequestMeasurement.Tags.RUN_ID, runId)
						.tag(RequestMeasurement.Tags.REQUEST_STATUS, sampleResult.getErrorCount() == 0 ? "PASS" : "FAIL")
                        .addField(RequestMeasurement.Fields.ERROR_COUNT, sampleResult.getErrorCount())
						.addField(RequestMeasurement.Fields.RESPONSE_TIME, sampleResult.getTime())
						.addField(RequestMeasurement.Fields.LATENCY_TIME, sampleResult.getLatency())
						.addField(RequestMeasurement.Fields.SENT_BYTES, sampleResult.getSentBytes())
						.addField(RequestMeasurement.Fields.RECEIVED_BYTES, sampleResult.getBytesAsLong())
						.addField(RequestMeasurement.Fields.GROUP_THREADS, sampleResult.getGroupThreads())
						.build();
				influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), point);
			}
		}
	}

	@Override
	public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
		arguments.addArgument(KEY_TEST_NAME, "Test");
//		arguments.addArgument(KEY_NODE_NAME, "Test-Node");
		arguments.addArgument(KEY_RUN_ID, "R001");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_HOST, "localhost");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PORT, Integer.toString(InfluxDBConfig.DEFAULT_PORT));
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_USER, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PASSWORD, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_DATABASE, InfluxDBConfig.DEFAULT_DATABASE);
		arguments.addArgument(InfluxDBConfig.KEY_RETENTION_POLICY, InfluxDBConfig.DEFAULT_RETENTION_POLICY);
		arguments.addArgument(KEY_SAMPLERS_LIST, ".*UC.*");
		arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
		arguments.addArgument(KEY_RECORD_SUB_SAMPLES, "true");
		return arguments;
	}

	@Override
	public void setupTest(BackendListenerContext context) {
		testName = context.getParameter(KEY_TEST_NAME, "Test");
        runId = context.getParameter(KEY_RUN_ID,"R001");
		randomNumberGenerator = new Random();
		try {
			nodeName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			nodeName = "Test-Node";
		}

		setupInfluxClient(context);
		influxDB.write(
				influxDBConfig.getInfluxDatabase(),
				influxDBConfig.getInfluxRetentionPolicy(),
				Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME)
						.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
						.tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.STARTED)
						.tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
						.tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
						.addField(TestStartEndMeasurement.Fields.VALUE, "1")
						.build());

		parseSamplers(context);
		scheduler = Executors.newScheduledThreadPool(1);

		scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);

		// Indicates whether to write sub sample records to the database
		recordSubSamples = Boolean.parseBoolean(context.getParameter(KEY_RECORD_SUB_SAMPLES, "false"));
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {
		LOGGER.info("Shutting down influxDB scheduler...");
		scheduler.shutdown();

//		addVirtualUsersMetrics(0,0,0,0,JMeterContextService.getThreadCounts().finishedThreads);
		influxDB.write(
				influxDBConfig.getInfluxDatabase(),
				influxDBConfig.getInfluxRetentionPolicy(),
				Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME)
						.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
						.tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.FINISHED)
						.tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
						.tag(TestStartEndMeasurement.Tags.RUN_ID, runId)
						.tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
						.addField(TestStartEndMeasurement.Fields.VALUE,"1")
						.build());

		influxDB.disableBatch();
		try {
			scheduler.awaitTermination(30, TimeUnit.SECONDS);
			LOGGER.info("influxDB scheduler terminated!");
		} catch (InterruptedException e) {
			LOGGER.error("Error waiting for end of scheduler");
		}

		samplersToFilter.clear();
		super.teardownTest(context);
	}

	public void run() {
//		try {
//			ThreadCounts tc = JMeterContextService.getThreadCounts();
//			addVirtualUsersMetrics(getUserMetrics().getMinActiveThreads(), getUserMetrics().getMeanActiveThreads(), getUserMetrics().getMaxActiveThreads(), tc.startedThreads, tc.finishedThreads);
//		} catch (Exception e) {
//			LOGGER.error("Failed writing to influx", e);
//		}
	}

	private void setupInfluxClient(BackendListenerContext context) {
		influxDBConfig = new InfluxDBConfig(context);
		influxDB = InfluxDBFactory.connect(influxDBConfig.getInfluxDBURL(), influxDBConfig.getInfluxUser(), influxDBConfig.getInfluxPassword());
		influxDB.enableBatch(100, 5, TimeUnit.SECONDS);
		createDatabaseIfNotExistent();
	}

	private void parseSamplers(BackendListenerContext context) {
		samplersList = context.getParameter(KEY_SAMPLERS_LIST, "");
		samplersToFilter = new HashSet<String>();
		if (context.getBooleanParameter(KEY_USE_REGEX_FOR_SAMPLER_LIST, false)) {
			regexForSamplerList = samplersList;
		} else {
			regexForSamplerList = null;
			String[] samplers = samplersList.split(SEPARATOR);
			samplersToFilter = new HashSet<String>();
			for (String samplerName : samplers) {
				samplersToFilter.add(samplerName);
			}
		}
	}

	private void addVirtualUsersMetrics(int minActiveThreads, int meanActiveThreads, int maxActiveThreads, int startedThreads, int finishedThreads) {
		Builder builder = Point.measurement(VirtualUsersMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		builder.addField(VirtualUsersMeasurement.Fields.MIN_ACTIVE_THREADS, minActiveThreads);
		builder.addField(VirtualUsersMeasurement.Fields.MAX_ACTIVE_THREADS, maxActiveThreads);
		builder.addField(VirtualUsersMeasurement.Fields.MEAN_ACTIVE_THREADS, meanActiveThreads);
		builder.addField(VirtualUsersMeasurement.Fields.STARTED_THREADS, startedThreads);
		builder.addField(VirtualUsersMeasurement.Fields.FINISHED_THREADS, finishedThreads);
		builder.tag(VirtualUsersMeasurement.Tags.NODE_NAME, nodeName);
		builder.tag(VirtualUsersMeasurement.Tags.TEST_NAME, testName);
		builder.tag(VirtualUsersMeasurement.Tags.RUN_ID, runId);
		influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), builder.build());
	}

	private void createDatabaseIfNotExistent() {
		List<String> dbNames = influxDB.describeDatabases();
		if (!dbNames.contains(influxDBConfig.getInfluxDatabase())) {
			influxDB.createDatabase(influxDBConfig.getInfluxDatabase());
		}
	}

	private int getUniqueNumberForTheSamplerThread() {
		return randomNumberGenerator.nextInt(ONE_MS_IN_NANOSECONDS);
	}
}
