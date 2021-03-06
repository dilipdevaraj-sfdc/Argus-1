package com.salesforce.dva.argus.service.schema;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import com.salesforce.dva.argus.entity.KeywordQuery;
import com.salesforce.dva.argus.entity.Metric;
import com.salesforce.dva.argus.entity.MetricSchemaRecord;
import com.salesforce.dva.argus.entity.MetricSchemaRecordQuery;
import com.salesforce.dva.argus.service.DefaultService;
import com.salesforce.dva.argus.service.SchemaService;
import com.salesforce.dva.argus.system.SystemAssert;
import com.salesforce.dva.argus.system.SystemConfiguration;

/**
 * Implementation of the abstract schema service class
 *
 * @author  Dilip Devaraj (ddevaraj@salesforce.com)
 */
public abstract class AbstractSchemaService extends DefaultService implements SchemaService {
	private static final long POLL_INTERVAL_MS = 10 * 60 * 1000L;
	private static final int DAY_IN_SECONDS = 24 * 60 * 60;
	private static final int HOUR_IN_SECONDS = 60 * 60;

	/* Have three separate bloom filters one for metrics schema, one only for scope names schema and one only for scope name and metric name schema.
	 * Since scopes will continue to repeat more often on subsequent kafka batch reads, we can easily check this from the  bloom filter for scopes only.
	 * Hence we can avoid the extra call to populate scopenames index on ES in subsequent Kafka reads.
	 * The same logic applies to scope name and metric name schema.
	 */
	protected static BloomFilter<CharSequence> bloomFilter;
	protected static BloomFilter<CharSequence> bloomFilterScopeOnly;
	protected static BloomFilter<CharSequence> bloomFilterScopeAndMetricOnly;
	private Random rand = new Random();
	private int randomNumber = rand.nextInt();
	private int bloomFilterExpectedNumberInsertions;
	private double bloomFilterErrorRate;
	private int bloomFilterScopeOnlyExpectedNumberInsertions;
	private double bloomFilterScopeOnlyErrorRate;
	private int bloomFilterScopeAndMetricOnlyExpectedNumberInsertions;
	private double bloomFilterScopeAndMetricOnlyErrorRate;
	private final Logger _logger = LoggerFactory.getLogger(getClass());
	private final Thread _bloomFilterMonitorThread;
	protected final boolean _syncPut;
	private int bloomFilterFlushHourToStartAt;
	private ScheduledExecutorService scheduledExecutorService;

	protected AbstractSchemaService(SystemConfiguration config) {
		super(config);

		bloomFilterExpectedNumberInsertions = Integer.parseInt(config.getValue(Property.BLOOMFILTER_EXPECTED_NUMBER_INSERTIONS.getName(),
				Property.BLOOMFILTER_EXPECTED_NUMBER_INSERTIONS.getDefaultValue()));
		bloomFilterErrorRate = Double.parseDouble(config.getValue(Property.BLOOMFILTER_ERROR_RATE.getName(),
				Property.BLOOMFILTER_ERROR_RATE.getDefaultValue()));

		bloomFilterScopeOnlyExpectedNumberInsertions = Integer.parseInt(config.getValue(Property.BLOOMFILTER_SCOPE_ONLY_EXPECTED_NUMBER_INSERTIONS.getName(),
				Property.BLOOMFILTER_SCOPE_ONLY_EXPECTED_NUMBER_INSERTIONS.getDefaultValue()));
		bloomFilterScopeOnlyErrorRate = Double.parseDouble(config.getValue(Property.BLOOMFILTER_SCOPE_ONLY_ERROR_RATE.getName(),
				Property.BLOOMFILTER_SCOPE_ONLY_ERROR_RATE.getDefaultValue()));

		bloomFilterScopeAndMetricOnlyExpectedNumberInsertions = Integer.parseInt(config.getValue(Property.BLOOMFILTER_SCOPE_AND_METRIC_ONLY_EXPECTED_NUMBER_INSERTIONS.getName(),
				Property.BLOOMFILTER_SCOPE_AND_METRIC_ONLY_EXPECTED_NUMBER_INSERTIONS.getDefaultValue()));
		bloomFilterScopeAndMetricOnlyErrorRate = Double.parseDouble(config.getValue(Property.BLOOMFILTER_SCOPE_AND_METRIC_ONLY_ERROR_RATE.getName(),
				Property.BLOOMFILTER_SCOPE_AND_METRIC_ONLY_ERROR_RATE.getDefaultValue()));

		bloomFilter = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), bloomFilterExpectedNumberInsertions , bloomFilterErrorRate);

		bloomFilterScopeOnly = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), bloomFilterScopeOnlyExpectedNumberInsertions , bloomFilterScopeOnlyErrorRate);
		bloomFilterScopeAndMetricOnly = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()),
				bloomFilterScopeAndMetricOnlyExpectedNumberInsertions , bloomFilterScopeAndMetricOnlyErrorRate);

		_syncPut = Boolean.parseBoolean(
				config.getValue(Property.SYNC_PUT.getName(), Property.SYNC_PUT.getDefaultValue()));

		_bloomFilterMonitorThread = new Thread(new BloomFilterMonitorThread(), "bloom-filter-monitor");
		_bloomFilterMonitorThread.start();

		bloomFilterFlushHourToStartAt = Integer.parseInt(config.getValue(Property.BLOOM_FILTER_FLUSH_HOUR_TO_START_AT.getName(),
				Property.BLOOM_FILTER_FLUSH_HOUR_TO_START_AT.getDefaultValue()));
		createScheduledExecutorService(bloomFilterFlushHourToStartAt);
	}

	@Override
	public void put(Metric metric) {
		requireNotDisposed();
		SystemAssert.requireArgument(metric != null, "Metric cannot be null.");
		put(Arrays.asList(metric));
	}

	@Override
	public void put(List<Metric> metrics) {
		requireNotDisposed();
		SystemAssert.requireArgument(metrics != null, "Metric list cannot be null.");

		// Create a list of metricsToPut that do not exist on the BLOOMFILTER and then call implementation
		// specific put with only those subset of metricsToPut.
		List<Metric> metricsToPut = new ArrayList<>(metrics.size());
		Set<String> scopesToPut = new HashSet<>(metrics.size());

		Set<Pair<String, String>> scopesAndMetricsNamesToPut = new HashSet<>(metrics.size());

		for(Metric metric : metrics) {
			// check metric schema bloom filter
			if(metric.getTags().isEmpty()) {
				// if metric does not have tags
				String key = constructKey(metric, null);
				boolean found = bloomFilter.mightContain(key);
				if(!found) {
					metricsToPut.add(metric);
				}
			} else {
				// if metric has tags
				boolean newTags = false;
				for(Entry<String, String> tagEntry : metric.getTags().entrySet()) {
					String key = constructKey(metric, tagEntry);
					boolean found = bloomFilter.mightContain(key);
					if(!found) {
						newTags = true;
					}
				}

				if(newTags) {
					metricsToPut.add(metric);
				}
			}

			String scopeName = metric.getScope();
			String metricName = metric.getMetric();

			// Check scope only bloom filter
			String key = constructScopeOnlyKey(scopeName);
			boolean found = bloomFilterScopeOnly.mightContain(key);
			if(!found) {
				scopesToPut.add(scopeName);
			}

			// Check scope and metric only bloom filter
			key = constructScopeAndMetricOnlyKey(scopeName, metricName);
			found = bloomFilterScopeAndMetricOnly.mightContain(key);
			if(!found) {
				scopesAndMetricsNamesToPut.add(Pair.of(scopeName, metricName));
			}
		}

		implementationSpecificPut(metricsToPut, scopesToPut, scopesAndMetricsNamesToPut);
	}

	/*
	 * Calls the implementation specific write for indexing the records
	 *
	 * @param  metrics    The metrics metadata that will be written to a separate index.
	 * @param  scopeNames The scope names that will be written to a separate index.
	 * @param  scopesAndMetricNames The scope and metric names that will be written to a separate index.
	 */
	protected abstract void implementationSpecificPut(List<Metric> metrics, Set<String> scopeNames,
													  Set<Pair<String, String>> scopesAndMetricNames);

	@Override
	public void dispose() {
		requireNotDisposed();
		if (_bloomFilterMonitorThread != null && _bloomFilterMonitorThread.isAlive()) {
			_logger.info("Stopping bloom filter monitor thread.");
			_bloomFilterMonitorThread.interrupt();
			_logger.info("Bloom filter monitor thread interrupted.");
			try {
				_logger.info("Waiting for bloom filter monitor thread to terminate.");
				_bloomFilterMonitorThread.join();
			} catch (InterruptedException ex) {
				_logger.warn("Bloom filter monitor thread was interrupted while shutting down.");
			}
			_logger.info("System monitoring stopped.");
		} else {
			_logger.info("Requested shutdown of bloom filter monitor thread aborted, as it is not yet running.");
		}
		shutdownScheduledExecutorService();
	}

	@Override
	public abstract Properties getServiceProperties();

	@Override
	public abstract List<MetricSchemaRecord> get(MetricSchemaRecordQuery query);

	@Override
	public abstract List<MetricSchemaRecord> getUnique(MetricSchemaRecordQuery query, RecordType type);

	@Override
	public abstract List<MetricSchemaRecord> keywordSearch(KeywordQuery query);

	protected String constructKey(Metric metric, Entry<String, String> tagEntry) {
		StringBuilder sb = new StringBuilder(metric.getScope());
		sb.append('\0').append(metric.getMetric());

		if(metric.getNamespace() != null) {
			sb.append('\0').append(metric.getNamespace());
		}

		if(tagEntry != null) {
			sb.append('\0').append(tagEntry.getKey()).append('\0').append(tagEntry.getValue());
		}

		// Add randomness for each instance of bloom filter running on different
		// schema clients to reduce probability of false positives that metric schemas are not written to ES
		sb.append('\0').append(randomNumber);

		return sb.toString();
	}

	protected String constructKey(String scope, String metric, String tagk, String tagv, String namespace) {

		StringBuilder sb = new StringBuilder(scope);

		if(!StringUtils.isEmpty(metric)) {
			sb.append('\0').append(metric);
		}

		if(!StringUtils.isEmpty(namespace)) {
			sb.append('\0').append(namespace);
		}

		if(!StringUtils.isEmpty(tagk)) {
			sb.append('\0').append(tagk);
		}

		if(!StringUtils.isEmpty(tagv)) {
			sb.append('\0').append(tagv);
		}

		// Add randomness for each instance of bloom filter running on different
		// schema clients to reduce probability of false positives that metric schemas are not written to ES
		sb.append('\0').append(randomNumber);

		return sb.toString();
	}

	protected String constructScopeOnlyKey(String scope) {

		return constructKey(scope, null, null, null, null);
	}

	protected String constructScopeAndMetricOnlyKey(String scope, String metric) {

		return constructKey(scope, metric, null, null, null);
	}

	private void createScheduledExecutorService(int targetHourToStartAt){
		scheduledExecutorService = Executors.newScheduledThreadPool(1);
		int initialDelayInSeconds = getNumHoursUntilTargetHour(targetHourToStartAt) * HOUR_IN_SECONDS;
		BloomFilterFlushThread bloomFilterFlushThread = new BloomFilterFlushThread();
		scheduledExecutorService.scheduleAtFixedRate(bloomFilterFlushThread, initialDelayInSeconds, DAY_IN_SECONDS, TimeUnit.SECONDS);
	}

	private void shutdownScheduledExecutorService(){
		_logger.info("Shutting down scheduled bloom filter flush executor service");
		scheduledExecutorService.shutdown();
		try {
			scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			_logger.warn("Shutdown of executor service was interrupted.");
			Thread.currentThread().interrupt();
		}
	}

	protected int getNumHoursUntilTargetHour(int targetHour){
		_logger.info("Initialized bloom filter flushing out, at {} hour of day", targetHour);
		Calendar calendar = Calendar.getInstance();
		int hour = calendar.get(Calendar.HOUR_OF_DAY);
		return hour < targetHour ? (targetHour - hour) : (targetHour + 24 - hour);
	}

	/**
	 * The set of implementation specific configuration properties.
	 *
	 * @author  Bhinav Sura (bhinav.sura@salesforce.com)
	 */
	public enum Property {
		SYNC_PUT("service.property.schema.sync.put", "false"),
		BLOOMFILTER_EXPECTED_NUMBER_INSERTIONS("service.property.schema.bloomfilter.expected.number.insertions", "40"),
		BLOOMFILTER_ERROR_RATE("service.property.schema.bloomfilter.error.rate", "0.00001"),

		/*
		* Estimated Filter Size using bloomFilter 1 million entries
		* https://hur.st/bloomfilter/?n=1000000&p=1.0E-5&m=&k= 2.86MiB
		* Storing in a Set 100K entries with avg length of 15 chars would be 100K * 15 * 2 B = 30B * 100K = 3 MB
		* If # of entries is 1 million, then it would be 30 MB resulting in savings in space.
		*/

		BLOOMFILTER_SCOPE_ONLY_EXPECTED_NUMBER_INSERTIONS("service.property.schema.bloomfilter.scope.only.expected.number.insertions", "40"),
		BLOOMFILTER_SCOPE_ONLY_ERROR_RATE("service.property.schema.bloomfilter.scope.only.error.rate", "0.00001"),

		/*
		 * Estimated Filter Size using bloomFilter 500 million entries
		 * https://hur.st/bloomfilter/?n=10000000&p=1.0E-5&m=&k= 1.39GiB
		 * Storing in a Set 100M entries with avg length of 30 chars would be 100M * 30 * 2 B = 60B * 100M = 6 GB
		 * If # of entries is 500 million, then it would be 30 GB resulting in savings in space.
		*/

		BLOOMFILTER_SCOPE_AND_METRIC_ONLY_EXPECTED_NUMBER_INSERTIONS("service.property.schema.bloomfilter.scope.and.metric.only.expected.number.insertions", "40"),
		BLOOMFILTER_SCOPE_AND_METRIC_ONLY_ERROR_RATE("service.property.schema.bloomfilter.scope.and.metric.only.error.rate", "0.00001"),

		/*
		 *  Have a different configured flush start hour for different machines to prevent thundering herd problem.
		*/
		BLOOM_FILTER_FLUSH_HOUR_TO_START_AT("service.property.schema.bloomfilter.flush.hour.to.start.at","2");

		private final String _name;
		private final String _defaultValue;

		private Property(String name, String defaultValue) {
			_name = name;
			_defaultValue = defaultValue;
		}

		/**
		 * Returns the property name.
		 *
		 * @return  The property name.
		 */
		public String getName() {
			return _name;
		}

		/**
		 * Returns the default value for the property.
		 *
		 * @return  The default value.
		 */
		public String getDefaultValue() {
			return _defaultValue;
		}
	}


	//~ Inner Classes ********************************************************************************************************************************

	/**
	 * Bloom Filter monitoring thread.
	 *
	 * @author  Dilip Devaraj (ddevaraj@salesforce.com)
	 */
	private class BloomFilterMonitorThread implements Runnable {
		@Override
		public void run() {
			_logger.info("Initialized random number for bloom filter key = {}", randomNumber);
			while (!Thread.currentThread().isInterrupted()) {
				_sleepForPollPeriod();
				if (!Thread.currentThread().isInterrupted()) {
					try {
						_checkBloomFilterUsage();
					} catch (Exception ex) {
						_logger.warn("Exception occurred while checking bloom filter usage.", ex);
					}
				}
			}
		}

		private void _checkBloomFilterUsage() {
			_logger.info("Metrics Bloom approx no. elements = {}", bloomFilter.approximateElementCount());
			_logger.info("Metrics Bloom expected error rate = {}", bloomFilter.expectedFpp());
			_logger.info("Scope only Bloom approx no. elements = {}", bloomFilterScopeOnly.approximateElementCount());
			_logger.info("Scope only Bloom expected error rate = {}", bloomFilterScopeOnly.expectedFpp());
			_logger.info("Scope and metric only Bloom approx no. elements = {}", bloomFilterScopeAndMetricOnly.approximateElementCount());
			_logger.info("Scope and metric only Bloom expected error rate = {}", bloomFilterScopeAndMetricOnly.expectedFpp());
		}

		private void _sleepForPollPeriod() {
			try {
				_logger.info("Sleeping for {}s before checking bloom filter statistics.", POLL_INTERVAL_MS / 1000);
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException ex) {
				_logger.warn("AbstractSchemaService memory monitor thread was interrupted.");
				Thread.currentThread().interrupt();
			}
		}
	}

	private class BloomFilterFlushThread implements Runnable {
		@Override
		public void run() {
			try{
				_flushBloomFilter();
			} catch (Exception ex) {
				_logger.warn("Exception occurred while flushing bloom filter.", ex);
			}
		}

		private void _flushBloomFilter() {
			_logger.info("Flushing out bloom filter entries");
			bloomFilter = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), bloomFilterExpectedNumberInsertions , bloomFilterErrorRate);
			bloomFilterScopeOnly = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()), bloomFilterScopeOnlyExpectedNumberInsertions , bloomFilterScopeOnlyErrorRate);
			bloomFilterScopeAndMetricOnly = BloomFilter.create(Funnels.stringFunnel(Charset.defaultCharset()),
					bloomFilterScopeAndMetricOnlyExpectedNumberInsertions , bloomFilterScopeAndMetricOnlyErrorRate);
			/* Don't need explicit synchronization to prevent slowness majority of the time*/
			randomNumber = rand.nextInt();
		}
	}
}
