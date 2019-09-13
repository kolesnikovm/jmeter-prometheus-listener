package jmeter.prometheus.exporter;

import io.prometheus.client.*;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.exporter.PushGateway;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import static jmeter.influx.writer.config.influxdb.RequestMeasurement.Tags.*;


public class PrometheusListener extends AbstractBackendListenerClient implements Runnable {

	private static final Logger LOGGER = LoggingManager.getLoggerForClass();

	private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
	private static final String KEY_TEST_NAME = "testName";
	private static final String KEY_RUN_ID = "runId";
	private static final String KEY_NODE_NAME = "nodeName";
	private static final String KEY_SAMPLERS_LIST = "samplersList";
	private static final String KEY_RECORD_SUB_SAMPLES = "recordSubSamples";
	private static final String EXPORTER_PORT = "exporterPort";
	private static final String TEST_STATE = "testState";
	private static final String PUSHGATEWAY_ADDRESS = "pushgatewayAddress";

	private static final String SEPARATOR = ";";
	private String testName;
	private String runId;
	private String nodeName;
	private String samplersList = "";
	private String regexForSamplerList;
	private Set<String> samplersToFilter;
	private boolean recordSubSamples;

	private transient Server server;
	private transient Gauge threadCountCollector;
	private transient Summary responseTimeCollector;
	private transient Counter requestCollector;
	private transient Gauge testStartCollector;
	private transient Gauge testStopCollector;

	private String[] threadCountLabels;
	private String[] responseTimeLabels;
	private String[] requestLabels;

	private HashMap<String, Method> methodsMap = new HashMap<>();
	private String[] defaultLabels;
	private HashMap<String, String> defaultLabelsMap = new HashMap<>();

	CollectorRegistry startRegistry = new CollectorRegistry();
	CollectorRegistry stopRegistry = new CollectorRegistry();

	private PushGateway pg;

	public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
		// Gather all the listeners
		List<SampleResult> allSampleResults = new ArrayList<SampleResult>();
		for (SampleResult sampleResult : sampleResults) {
			allSampleResults.add(sampleResult);

			// if(recordSubSamples) {
				for (SampleResult subResult : sampleResult.getSubResults()) {
					allSampleResults.add(subResult);
				}
			// }
		}

		for(SampleResult sampleResult: allSampleResults) {
			getUserMetrics().add(sampleResult);

			if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList)) || samplersToFilter.contains(sampleResult.getSampleLabel())) {
				threadCountCollector.labels(labelValues(sampleResult, threadCountLabels)).set(sampleResult.getGroupThreads());
				responseTimeCollector.labels(labelValues(sampleResult, responseTimeLabels)).observe(sampleResult.getTime());
				requestCollector.labels(labelValues(sampleResult, requestLabels)).inc();
			}
		}
	}

	@Override
	public Arguments getDefaultParameters() {
		Arguments arguments = new Arguments();
		arguments.addArgument(TEST_NAME, "Test");
		arguments.addArgument(RUN_ID, "R001");
		arguments.addArgument(EXPORTER_PORT, "9270");
		arguments.addArgument(PUSHGATEWAY_ADDRESS, "127.0.0.1:9091");
		arguments.addArgument(KEY_SAMPLERS_LIST, ".*UC.*");
		arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
		// arguments.addArgument(KEY_RECORD_SUB_SAMPLES, "true");
		return arguments;
	}

	@Override
	public void setupTest(BackendListenerContext context) {
		defaultLabelsMap.put(TEST_NAME, context.getParameter(TEST_NAME));
		defaultLabelsMap.put(RUN_ID, context.getParameter(RUN_ID));
		try {
			defaultLabelsMap.put(NODE_NAME, InetAddress.getLocalHost().getHostName());
		} catch (UnknownHostException e) {
			nodeName = "Test-Node";
		}

		defaultLabels = new String[defaultLabelsMap.size()];

		int i = 0;
		for(Map.Entry<String, String> label: defaultLabelsMap.entrySet()) {
			String labelName = label.getKey();
			defaultLabels[i++] = labelName;
		}

		try {
			methodsMap.put(REQUEST_NAME, PrometheusListener.class.getMethod("getRequestName", SampleResult.class));
			methodsMap.put(RESPONSE_CODE, PrometheusListener.class.getMethod("getResponseCode", SampleResult.class));
			methodsMap.put(RESPONSE_MESSAGE, PrometheusListener.class.getMethod("getResponseMessage", SampleResult.class));
			methodsMap.put(THREAD_GROUP, PrometheusListener.class.getMethod("getThreadGroup", SampleResult.class));
			methodsMap.put(REQUEST_STATUS,PrometheusListener.class.getMethod("getRequestStatus", SampleResult.class));
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		CollectorRegistry.defaultRegistry.clear();
		createSamplerCollectors();

		server = new Server(Integer.parseInt(context.getParameter(EXPORTER_PORT, "9270")));
		ServletContextHandler servletContextHandler = new ServletContextHandler();
		servletContextHandler.setContextPath("/");
		server.setHandler(servletContextHandler);
		servletContextHandler.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");

		try {
			server.start();
			System.out.println("[INFO] Exporting metrics at " + context.getParameter(EXPORTER_PORT));
		} catch (Exception e) {}

		parseSamplers(context);
		// recordSubSamples = Boolean.parseBoolean(context.getParameter(KEY_RECORD_SUB_SAMPLES, "false"));

		pg = new PushGateway(context.getParameter(PUSHGATEWAY_ADDRESS));
		System.out.println("[INFO] Using pushgateway at " + context.getParameter(PUSHGATEWAY_ADDRESS));

		testStartCollector.labels(defaultLabelValues()).setToCurrentTime();
		try {
			pg.pushAdd(startRegistry, "testStart_job");
		} catch (IOException e) {
			System.out.println("[WARN] setupTest - Unable to push registry to pushgateway: " + context.getParameter(PUSHGATEWAY_ADDRESS));
		}
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {

		testStopCollector.labels(defaultLabelValues()).setToCurrentTime();
		try {
			pg.pushAdd(stopRegistry, "testStop_job");
		} catch (IOException e) {
			System.out.println("[WARN] teardownTest - Unable to push registry to pushgateway: " + context.getParameter(PUSHGATEWAY_ADDRESS));
		}

		try {
			this.server.stop();
		} catch (Exception e) {}

		samplersToFilter.clear();
		super.teardownTest(context);
	}

	public void run() {}

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

	protected void createSamplerCollectors(){

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
		testStartCollector = Gauge.build()
				.name("jmeter_test_start")
				.help("Marker for test start")
				.labelNames(defaultLabels)
				.create()
				.register(startRegistry);
		testStopCollector = Gauge.build()
				.name("jmeter_test_stop")
				.help("Marker for test stop")
				.labelNames(defaultLabels)
				.create()
				.register(stopRegistry);
	}

	private String[] labelValues(SampleResult sampleResult, String[] labels) {

		String[] labelValues = new String[labels.length + defaultLabels.length];
		int valuesIndex = 0;

		for (String label: labels) {
			try {
				labelValues[valuesIndex++] = methodsMap.get(label).invoke(this, sampleResult).toString();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
		for(Map.Entry<String, String> label : defaultLabelsMap.entrySet()) {
			labelValues[valuesIndex++] = label.getValue();
		}

		return labelValues;
	}

	private String[] defaultLabelValues() {

		String[] labelValues = new String[defaultLabels.length];
		int valuesIndex = 0;

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