package jmeter.influx.writer.config.influxdb;


public interface TestStartEndMeasurement {

	String MEASUREMENT_NAME = "testStartEnd";

	public interface Tags {
		String TYPE = "type";
		String NODE_NAME = "nodeName";
        String RUN_ID = "runId";
        String TEST_NAME = "testName";
	}
	
	public interface Fields {	
		String VALUE = "value";
	}
	
	public interface Values {
		String FINISHED = "finished";
		String STARTED = "started";
	}
}
