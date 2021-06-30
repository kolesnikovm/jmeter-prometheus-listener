# Prometheus Listener for JMeter

Apache JMeter Backend Listener implementation for Prometheus metrics exporting.

## Installation

Download the [latest release](https://github.com/kolesnikovm/jmeter-prometheus-listener/releases/latest) or build your own from the source code.
Then put `jmeter-prometheus-listener-x.x.x.jar` to `JMETER_HOME/lib/ext` directory.

## Usage

1. Add `Listener->Backend Listener` to your Test Plan.
2. Select `com.github.kolesnikovm.PrometheusListener` in Backend Listener implementation combobox.
3. Edit parameters to your taste.

## Parameters

All parameters are required.

| Param | Type | Description |
|:---:|:---:|:---:|
| testName | String | Common label for all metrics in your test |
| runId | String | Common label for all metrics in your test |
| exporterPort | int | Port for exposing metrics, path `/metrics` |
| samplersRegExp | String | Regular expression for filtering sample results |

## Metrics

Every metric has default label set `testName, runId, nodeName` and also may have some additional labels. Check the table below for details on metrics and their specific labels.

| Metric | Type | Labels | Comment |
| :---: | :---: | :---: | :---: |
| jmeter_active_threads | Gauge | | |
| jmeter_running_threads | Gauge | threadGroup | |
| jmeter_requests | Counter | requestName, requestStatus, responseCode, responseMessage | |
| jmeter_response_time | Summary | requestName, requestStatus, responseCode, responseMessage | Unit: milliseconds<br/> Quantiles: 0.9, 0.95, 0.99 with max age 10 seconds |
| jmeter_latency | Summary | requestName, requestStatus, responseCode, responseMessage | Unit: milliseconds<br/> Quantiles: 0.9, 0.95, 0.99 with max age 10 seconds |
| jmeter_request_size | Summary | requestName, requestDirection | Unit: bytes |
