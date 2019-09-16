package jmeter.prometheus.exporter;

import io.prometheus.client.*;
import io.prometheus.client.exporter.MetricsServlet;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static jmeter.influx.writer.config.influxdb.RequestMeasurement.Tags.*;
import jmeter.influx.writer.config.influxdb.InfluxDBConfig;
import jmeter.influx.writer.config.influxdb.TestStartEndMeasurement;


public class PrometheusListener extends AbstractBackendListenerClient {

	private static final String KEY_TEST_NAME = "testName";
	private static final String KEY_RUN_ID = "runId";
	private static final String KEY_SAMPLERS_LIST = "samplersRegExp";
	private static final String EXPORTER_PORT = "exporterPort";

	private String testName;
	private String runId;
	private String nodeName;
	private String samplesRegEx = "";
	private String regexForSampleList = null;
	private boolean getEverySample = false;
	private boolean useAnnotations = true;

	private transient Server server;
	private transient Gauge threadCountCollector;
	private transient Summary responseTimeCollector;
	private transient Counter requestCollector;

	private String[] threadCountLabels;
	private String[] responseTimeLabels;
	private String[] requestLabels;

	private HashMap<String, Method> methodsMap = new HashMap<>();
	private String[] defaultLabels;
	private HashMap<String, String> defaultLabelsMap = new HashMap<>();

	private InfluxDB influxDB;
	InfluxDBConfig influxDBConfig;

	private List<SampleResult> gatherAllResults(List<SampleResult> sampleResults) {

		List<SampleResult> allSampleResults = new ArrayList<SampleResult>();

		for (SampleResult sampleResult : sampleResults) {
			allSampleResults.add(sampleResult);

			for (SampleResult subResult : sampleResult.getSubResults()) {
				allSampleResults.add(subResult);
			}
		}

		return allSampleResults;
	}

	public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
		List<SampleResult> allSampleResults = gatherAllResults(sampleResults);

		for(SampleResult sampleResult: allSampleResults) {
			getUserMetrics().add(sampleResult);

			if ((regexForSampleList != null && sampleResult.getSampleLabel().matches(regexForSampleList)) || getEverySample) {
				threadCountCollector.labels(getLabelValues(sampleResult, threadCountLabels)).set(sampleResult.getGroupThreads());
				responseTimeCollector.labels(getLabelValues(sampleResult, responseTimeLabels)).observe(sampleResult.getTime());
				requestCollector.labels(getLabelValues(sampleResult, requestLabels)).inc();
			}
		}
	}

	@Override
	public Arguments getDefaultParameters() {
		Arguments arguments = new Arguments();
		arguments.addArgument(TEST_NAME, "Test");
		arguments.addArgument(RUN_ID, "R001");
		arguments.addArgument(EXPORTER_PORT, "9270");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_HOST, "localhost");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PORT, Integer.toString(InfluxDBConfig.DEFAULT_PORT));
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_USER, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PASSWORD, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_DATABASE, InfluxDBConfig.DEFAULT_DATABASE);
		arguments.addArgument(InfluxDBConfig.KEY_RETENTION_POLICY, InfluxDBConfig.DEFAULT_RETENTION_POLICY);
		arguments.addArgument(KEY_SAMPLERS_LIST, ".*UC.*");
		return arguments;
	}

	@Override
	public void setupTest(BackendListenerContext context) {
		testName = context.getParameter(KEY_TEST_NAME, "Test");
		runId = context.getParameter(KEY_RUN_ID, "System.currentTimeMillis()");
		try {
			nodeName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			nodeName = "Test-Node";
		}
		
		defaultLabelsMap.put(TEST_NAME, testName);
		defaultLabelsMap.put(RUN_ID, runId);
		defaultLabelsMap.put(NODE_NAME, nodeName);

		defaultLabels = defaultLabelsMap.keySet().toArray(new String[defaultLabelsMap.size()]);

		try {
			methodsMap.put(REQUEST_NAME, PrometheusListener.class.getMethod("getRequestName", SampleResult.class));
			methodsMap.put(RESPONSE_CODE, PrometheusListener.class.getMethod("getResponseCode", SampleResult.class));
			methodsMap.put(RESPONSE_MESSAGE, PrometheusListener.class.getMethod("getResponseMessage", SampleResult.class));
			methodsMap.put(THREAD_GROUP, PrometheusListener.class.getMethod("getThreadGroup", SampleResult.class));
			methodsMap.put(REQUEST_STATUS,PrometheusListener.class.getMethod("getRequestStatus", SampleResult.class));
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		int exporterPort = Integer.parseInt(context.getParameter(EXPORTER_PORT, "9270"));
		startExportingServer(exporterPort);

		getSamplesFilter(context);

		try {
			setupInfluxClient(context);
		} catch(Exception e) {
			useAnnotations = false;

			System.out.printf("[WARN] Unable to connect to influx %s:%s"
				, context.getParameter(InfluxDBConfig.KEY_INFLUX_DB_HOST)
				, context.getParameter(InfluxDBConfig.KEY_INFLUX_DB_PORT));
		}

		if (useAnnotations) {
			writeInfluxAnnotation(TestStartEndMeasurement.Values.STARTED);
		}
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {

		if (useAnnotations) {
			writeInfluxAnnotation(TestStartEndMeasurement.Values.FINISHED);
		}

		stopExportingServer();

		super.teardownTest(context);
	}

	private void getSamplesFilter(BackendListenerContext context) {

		samplesRegEx = context.getParameter(KEY_SAMPLERS_LIST, "");

		if (samplesRegEx != "") {
			regexForSampleList = samplesRegEx;
		} else {
			getEverySample = true;
		}
	}

	protected void createSampleCollectors() {

		threadCountLabels = new String[]{ THREAD_GROUP };
		responseTimeLabels = new String[]{ REQUEST_NAME };
		requestLabels = new String[]{ REQUEST_NAME, RESPONSE_CODE, RESPONSE_MESSAGE, REQUEST_STATUS };

		threadCountCollector = Gauge.build()
				.name("jmeter_running_threads")
				.help("Counter for running threads")
				.labelNames((String[]) ArrayUtils.addAll(threadCountLabels, defaultLabels))
				.create()
				.register(CollectorRegistry.defaultRegistry);
		responseTimeCollector = Summary.build()
				.name("jmeter_response_time")
				.help("Summary for sample duration in ms")
				.labelNames((String[]) ArrayUtils.addAll(responseTimeLabels, defaultLabels))
				.quantile(0.9, 0.05)
				.maxAgeSeconds(5)
				.create()
				.register(CollectorRegistry.defaultRegistry);
		requestCollector = Counter.build()
				.name("jmeter_requests")
				.help("Counter for requests")
				.labelNames((String[]) ArrayUtils.addAll(requestLabels, defaultLabels))
				.create()
				.register(CollectorRegistry.defaultRegistry);
	}

	private void setupInfluxClient(BackendListenerContext context) {

		influxDBConfig = new InfluxDBConfig(context);
		influxDB = InfluxDBFactory.connect(influxDBConfig.getInfluxDBURL(), influxDBConfig.getInfluxUser(), influxDBConfig.getInfluxPassword());
		influxDB.enableBatch(100, 5, TimeUnit.SECONDS);
		createDatabaseIfNotExistent();
	}

	private void createDatabaseIfNotExistent() {

		List<String> dbNames = influxDB.describeDatabases();
		if (!dbNames.contains(influxDBConfig.getInfluxDatabase())) {
			influxDB.createDatabase(influxDBConfig.getInfluxDatabase());
		}
	}

	private void writeInfluxAnnotation(String type) {

		influxDB.write(
			influxDBConfig.getInfluxDatabase(),
			influxDBConfig.getInfluxRetentionPolicy(),
			Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME)
				.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
				.tag(TestStartEndMeasurement.Tags.TYPE, type)
				.tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
				.tag(TestStartEndMeasurement.Tags.RUN_ID, runId)
				.addField(TestStartEndMeasurement.Fields.VALUE, runId)
				.build()
		);
	}

	private void startExportingServer(int port) {

		CollectorRegistry.defaultRegistry.clear();
		createSampleCollectors();

		server = new Server(port);
		ServletContextHandler servletContextHandler = new ServletContextHandler();
		servletContextHandler.setContextPath("/");
		server.setHandler(servletContextHandler);
		servletContextHandler.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");

		try {
			server.start();
			System.out.println("[INFO] Exporting metrics at " + port);
		} catch (Exception e) {}
	}

	private void stopExportingServer() {
		try {
			this.server.stop();
		} catch (Exception e) {}
	}


	private String[] getLabelValues(SampleResult sampleResult, String[] labels) {

		String[] labelValues = new String[labels.length + defaultLabels.length];
		int valuesIndex = 0;

		for (String label: labels) {
			try {
				labelValues[valuesIndex++] = methodsMap.get(label).invoke(this, sampleResult).toString();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		for(Map.Entry<String, String> label : defaultLabelsMap.entrySet()) {
			labelValues[valuesIndex++] = label.getValue();
		}

		return labelValues;
	}

	public String getRequestStatus(SampleResult sampleResult)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

		return SampleResult.class.getMethod("getErrorCount").invoke(sampleResult).toString()
				.equals("0") ? "PASS" : "FAIL";
	}

	public String getRequestName(SampleResult sampleResult)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

		return SampleResult.class.getMethod("getSampleLabel").invoke(sampleResult).toString();
	}

	public String getResponseCode(SampleResult sampleResult)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

		return SampleResult.class.getMethod("getResponseCode").invoke(sampleResult).toString();
	}

	public String getResponseMessage(SampleResult sampleResult)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

		return SampleResult.class.getMethod("getResponseMessage").invoke(sampleResult).toString();
	}

	public String getThreadGroup(SampleResult sampleResult)
			throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

		return SampleResult.class.getMethod("getThreadName").invoke(sampleResult).toString()
				.substring(0, sampleResult.getThreadName().lastIndexOf(32));
	}

}