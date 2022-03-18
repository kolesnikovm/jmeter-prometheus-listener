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

These parameters are set in Backend Listener element. All parameters are required.

| Param | Type | Description |
|:---:|:---:|:---:|
| testName | String | Common label for all metrics in your test |
| runId | String | Common label for all metrics in your test |
| exporterPort | int | Port for exposing metrics, path `/metrics` |
| samplersRegExp | String | Regular expression for filtering sample results |

## Properties

| Property | Default | Comment |
| :---: | :---: | :---: |
| prometheus.collect_jvm | false | Boolean parameter for enabling JVM metrics collection |
| prometheus.quantiles_age | 10 | Max age in seconds for Summary collectors' quantiles |
| prometheus.log_errors | false | Boolean parameter for enabling extended error logging |

## Metrics

Every metric has default label set `testName, runId, nodeName` and also may have some additional labels. Check the table below for details on metrics and their specific labels.

| Metric | Type | Labels | Comment |
| :---: | :---: | :---: | :---: |
| jmeter_active_threads | Gauge | | |
| jmeter_running_threads | Gauge | threadGroup | |
| jmeter_requests | Counter | requestName, requestStatus, responseCode, responseMessage, isTransaction | |
| jmeter_response_time | Summary | requestName, requestStatus, responseCode, responseMessage, isTransaction | Unit: milliseconds<br/> Quantiles: 0.9, 0.95, 0.99 |
| jmeter_latency | Summary | requestName, requestStatus, responseCode, responseMessage, isTransaction | Unit: milliseconds<br/> Quantiles: 0.9, 0.95, 0.99 |
| jmeter_request_size | Summary | requestName, requestDirection, isTransaction | Unit: bytes |

## Custom metrics

Also, you can create your own custom collectors. See the documentation on [Prometheus JVM Client](https://github.com/prometheus/client_java).

### Creation

![Custom collector creation](/docs/images/collector_creation.jpeg?raw=true)

### Usage

![Custom collector usage](/docs/images/collector_usage.jpeg?raw=true)

## Dependency

Plugin is hosted on Maven Central. You can find dependency [here](https://search.maven.org/artifact/io.github.kolesnikovm/jmeter-prometheus-listener). Example use with [jmeter-maven-plugin](https://github.com/jmeter-maven-plugin/jmeter-maven-plugin):

```xml
<jmeterExtensions>
    <artifact>io.github.kolesnikovm:jmeter-prometheus-listener:x.x.x</artifact>
</jmeterExtensions>
```

## Building

To build, simply run:

```bash
gradle clean fatJar
```