// Copyright 2020 Maxim Kolesnikov
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.github.kolesnikovm;

import io.prometheus.client.*;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.hotspot.DefaultExports;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.visualizers.backend.UserMetric;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PrometheusListener extends AbstractBackendListenerClient implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(PrometheusListener.class);

	// Labels used for user input
	private static final String TEST_NAME_KEY = "testName";
	private static final String RUN_ID_KEY = "runId";
	private static final String EXPORTER_PORT_KEY = "exporterPort";
	private static final String SAMPLERS_LIST_KEY = "samplersRegExp";

	// Property for enabling JVM metrics collection
	public static final String PROMETHEUS_COLLECT_JVM = "prometheus.collect_jvm";
	public static final boolean PROMETHEUS_COLLECT_JVM_DEFAULT = false;

	private boolean collectJVM = JMeterUtils.getPropDefault(PROMETHEUS_COLLECT_JVM, PROMETHEUS_COLLECT_JVM_DEFAULT);

	// Property for defining quantiles max age
	public static final String PROMETHEUS_QUANTILES_AGE= "prometheus.quantiles_age";
	public static final int PROMETHEUS_QUANTILES_AGE_DEFAULT = 10;

	private int quantilesAge = JMeterUtils.getPropDefault(PROMETHEUS_QUANTILES_AGE, PROMETHEUS_QUANTILES_AGE_DEFAULT);

	// Property for extended error logging
	public static final String PROMETHEUS_LOG_ERRORS = "prometheus.log_errors";
	public static final boolean PROMETHEUS_LOG_ERRORS_DEFAULT = false;

	private boolean logErrors = JMeterUtils.getPropDefault(PROMETHEUS_LOG_ERRORS, PROMETHEUS_LOG_ERRORS_DEFAULT);

	// General values with defaults
	private static String testName = "project";
	private static String runId = "1";
	private static int exporterPort = 9001;
	private static String samplesRegEx = "UC.+";
	private static String nodeName = "Test-Node";

	// Fields in metrics
	private static String REQUEST_NAME = "requestName";
	private static String RESPONSE_CODE = "responseCode";
	private static String RESPONSE_MESSAGE = "responseMessage";
	private static String TEST_NAME = "testName";
	private static String NODE_NAME = "nodeName";
	private static String RUN_ID = "runId";
	private static String THREAD_GROUP = "threadGroup";
	private static String REQUEST_STATUS = "requestStatus";
	private static String REQUEST_DIRECTION = "requestDirection";
	private static String IS_TRANSACTION = "isTransaction";

	private String[] defaultLabels;
	private String[] defaultLabelValues;
	private String[] threadLabels = new String[]{ THREAD_GROUP };
	private String[] requestLabels = new String[]{ REQUEST_NAME, RESPONSE_CODE, RESPONSE_MESSAGE, REQUEST_STATUS, IS_TRANSACTION };
	private String[] requestSizeLabels = new String[]{ REQUEST_DIRECTION, REQUEST_NAME, IS_TRANSACTION };

	private transient Server server;
	// Prometheus collectors
	private transient Gauge runningThreadsCollector;
	private transient Gauge activeThreadsCollector;
	private transient Summary responseTimeCollector;
	private transient Summary latencyCollector;
	private transient Counter requestCollector;
	private transient Summary requestSizeCollector;

	private String[] requestSent = new String[]{"sent"};
	private String[] requestReceived = new String[]{"received"};

	private HashMap<String, Method> methodsMap = new HashMap<>();

	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> timerHandle;


	private List<SampleResult> gatherAllResults(List<SampleResult> sampleResults) {

		List<SampleResult> allSampleResults = new ArrayList<SampleResult>();

		for (SampleResult sampleResult : sampleResults) {
			allSampleResults.add(sampleResult);

			List<SampleResult> subResults = Arrays.asList(sampleResult.getSubResults());
			if (subResults.size() != 0) {
				allSampleResults.addAll(gatherAllResults(subResults));
			}
		}

		return allSampleResults;
	}

	@Override
	public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
		List<SampleResult> allSampleResults = gatherAllResults(sampleResults);

		for(SampleResult sampleResult: allSampleResults) {
			if (logErrors && !sampleResult.isSuccessful() && sampleResult.getSubResults().length == 0) {
				log.error("===== ERROR in {} =====\n" +
						  "===== Request =====\n{}\n" +
						  "===== Response =====\n{}",
						sampleResult.getSampleLabel(),
						sampleResult.getSamplerData(),
						sampleResult.getResponseDataAsString());
			}

			if (samplesRegEx.equals("") || sampleResult.getSampleLabel().matches(samplesRegEx)) {
				runningThreadsCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, getLabelValues(sampleResult, threadLabels)))
						.set(sampleResult.getGroupThreads());
				responseTimeCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, getLabelValues(sampleResult, requestLabels)))
						.observe(sampleResult.getTime());
				latencyCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, getLabelValues(sampleResult, requestLabels)))
						.observe(sampleResult.getLatency());
				requestCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, getLabelValues(sampleResult, requestLabels)))
						.inc();
				requestSizeCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, ArrayUtils.addAll(requestSent, sampleResult.getSampleLabel(), isTransaction(sampleResult))))
						.observe(sampleResult.getSentBytes());
				requestSizeCollector
						.labels(ArrayUtils.addAll(defaultLabelValues, ArrayUtils.addAll(requestReceived, sampleResult.getSampleLabel(), isTransaction(sampleResult))))
						.observe(sampleResult.getBytesAsLong());
			}
		}
	}

	@Override
	public Arguments getDefaultParameters() {
		Arguments arguments = new Arguments();
		arguments.addArgument(TEST_NAME_KEY, testName);
		arguments.addArgument(RUN_ID_KEY, runId);
		arguments.addArgument(EXPORTER_PORT_KEY, String.valueOf(exporterPort));
		arguments.addArgument(SAMPLERS_LIST_KEY, samplesRegEx);
		return arguments;
	}

	@Override
	public void run() {
		UserMetric userMetrics = getUserMetrics();

		activeThreadsCollector.labels(defaultLabelValues).set(userMetrics.getStartedThreads() - userMetrics.getFinishedThreads());
	}

	@Override
	public void setupTest(BackendListenerContext context) {
		testName = context.getParameter(TEST_NAME_KEY);
		runId = context.getParameter(RUN_ID_KEY);
		try {
			nodeName = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			log.warn("Failed to get host name");
		}


		HashMap<String, String> defaultLabelsMap = new HashMap<>();
		defaultLabelsMap.put(TEST_NAME, testName);
		defaultLabelsMap.put(RUN_ID, runId);
		defaultLabelsMap.put(NODE_NAME, nodeName);

		defaultLabels = defaultLabelsMap.keySet().toArray(new String[defaultLabelsMap.size()]);
		defaultLabelValues = defaultLabelsMap.values().toArray(new String[defaultLabelsMap.size()]);

		try {
			methodsMap.put(REQUEST_NAME, PrometheusListener.class.getMethod("getRequestName", SampleResult.class));
			methodsMap.put(RESPONSE_CODE, PrometheusListener.class.getMethod("getResponseCode", SampleResult.class));
			methodsMap.put(RESPONSE_MESSAGE, PrometheusListener.class.getMethod("getResponseMessage", SampleResult.class));
			methodsMap.put(THREAD_GROUP, PrometheusListener.class.getMethod("getThreadGroup", SampleResult.class));
			methodsMap.put(REQUEST_STATUS, PrometheusListener.class.getMethod("getRequestStatus", SampleResult.class));
			methodsMap.put(IS_TRANSACTION, PrometheusListener.class.getMethod("isTransaction", SampleResult.class));
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}

		exporterPort = context.getIntParameter(EXPORTER_PORT_KEY);
		startExportingServer(exporterPort);

		samplesRegEx = context.getParameter(SAMPLERS_LIST_KEY);

		scheduler = Executors.newScheduledThreadPool(1);
		timerHandle = scheduler.scheduleAtFixedRate(this, 0, 5, TimeUnit.SECONDS);
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {
		boolean cancelState = timerHandle.cancel(false);
		log.debug("Canceled state: {}", cancelState);

		scheduler.shutdown();
		try {
			scheduler.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			log.error("Error waiting for end of scheduler");
			Thread.currentThread().interrupt();
		}

		stopExportingServer();

		super.teardownTest(context);
	}

	protected void createSampleCollectors() {

		runningThreadsCollector = Gauge.build()
				.name("jmeter_running_threads")
				.help("Counter for running threads")
				.labelNames(ArrayUtils.addAll(defaultLabels, threadLabels))
				.register();
		activeThreadsCollector = Gauge.build()
				.name("jmeter_active_threads")
				.help("Counter for active threads")
				.labelNames(defaultLabels)
				.register();
		responseTimeCollector = Summary.build()
				.name("jmeter_response_time")
				.help("Summary for sample duration in ms")
				.labelNames(ArrayUtils.addAll(defaultLabels, requestLabels))
				.quantile(0.9, 0.01)
				.quantile(0.95, 0.01)
				.quantile(0.99, 0.01)
				.maxAgeSeconds(quantilesAge)
				.register();
		latencyCollector = Summary.build()
				.name("jmeter_latency")
				.help("Summary for sample ttfb in ms")
				.labelNames(ArrayUtils.addAll(defaultLabels, requestLabels))
				.quantile(0.9, 0.01)
				.quantile(0.95, 0.01)
				.quantile(0.99, 0.01)
				.maxAgeSeconds(quantilesAge)
				.register();
		requestCollector = Counter.build()
				.name("jmeter_requests")
				.help("Counter for requests")
				.labelNames(ArrayUtils.addAll(defaultLabels, requestLabels))
				.register();
		requestSizeCollector = Summary.build()
				.name("jmeter_request_size")
				.help("Summary for jmeter request size in bytes")
				.labelNames(ArrayUtils.addAll(defaultLabels, requestSizeLabels))
				.register();
	}

	private void startExportingServer(int port) {

		CollectorRegistry.defaultRegistry.clear();
		createSampleCollectors();

		if (collectJVM) {
			DefaultExports.register(CollectorRegistry.defaultRegistry);
		}

		server = new Server(port);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		server.setHandler(context);
		context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");

		try {
			server.start();
			System.out.println("[INFO] Exporting metrics at " + port);
		} catch (Exception e) {
			log.error("Failed to start metrics server: {}", e);
			System.out.println("[ERROR] Failed to start metrics server: " + e);
		}
	}

	private void stopExportingServer() {
		try {
			server.stop();
		} catch (Exception e) {
			log.warn("Failed to stop metrics server: {}", e);
		}
	}

	private String[] getLabelValues(SampleResult sampleResult, String[] labels) {

		String[] labelValues = new String[labels.length];
		int valuesIndex = 0;

		for (String label: labels) {
			try {
				labelValues[valuesIndex++] = methodsMap.get(label).invoke(this, sampleResult).toString();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return labelValues;
	}

	public String getRequestStatus(SampleResult sampleResult) {
		return sampleResult.isSuccessful() ? "PASS" : "FAIL";
	}

	public String getRequestName(SampleResult sampleResult) {
		return sampleResult.getSampleLabel();
	}

	public String getResponseCode(SampleResult sampleResult) {
		return sampleResult.getResponseCode();
	}

	public String getResponseMessage(SampleResult sampleResult) {
		return sampleResult.getResponseMessage();
	}

	public String getThreadGroup(SampleResult sampleResult) {
		return sampleResult.getThreadName()
				.substring(0, sampleResult.getThreadName().lastIndexOf(32))
				.replace("-ThreadStarter", "");
	}

	public String isTransaction(SampleResult sampleResult) {
		return TransactionController.isFromTransactionController(sampleResult) ? "true" : "false";
	}

}