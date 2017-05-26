/*
 * Copyright (c) 2016, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.dva.argus.service.tsdb;

import static com.salesforce.dva.argus.system.SystemAssert.requireArgument;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.salesforce.dva.argus.entity.Annotation;
import com.salesforce.dva.argus.entity.Metric;
import com.salesforce.dva.argus.service.DefaultService;
import com.salesforce.dva.argus.service.MonitorService;
import com.salesforce.dva.argus.service.SecondNamedBinding;
import com.salesforce.dva.argus.service.TSDBService;
import com.salesforce.dva.argus.service.tsdb.DefaultTSDBService.AnnotationWrapper;
import com.salesforce.dva.argus.service.tsdb.DefaultTSDBService.AnnotationWrappers;
import com.salesforce.dva.argus.service.tsdb.DefaultTSDBService.HttpMethod;
import com.salesforce.dva.argus.system.SystemConfiguration;
import com.salesforce.dva.argus.system.SystemException;

/**
 * The federated implementation of the TSDBService.
 * - Federation occurs for multiple endpoints (and backup if primary endpoint is down)
 * - Federation occurs for large range queries into smaller range sub queries
 *
 * @author Dilip Devaraj (ddevaraj@salesforce.com)
 */
@Singleton
public class FederatedTSDBService extends DefaultService implements TSDBService {

	// ~ Static fields/initializers
	// *******************************************************************************************************************
	private static final long TIME_FEDERATE_LIMIT_MILLIS = 86400000L;

	// ~ Instance fields
	// ******************************************************************************************************************************

	private final ObjectMapper _mapper;
	protected Logger _logger = LoggerFactory.getLogger(getClass());
	private Map<String, CloseableHttpClient> _readPortMap = new HashMap<>();
	private CloseableHttpClient _writePort;
	private final String _writeEndpoint;
	private final List<String> _readEndPoints = new ArrayList<>();
	private final List<String> _readBackupEndPoints = new ArrayList<>();
	private final SystemConfiguration _configuration;
	private final ExecutorService _executorService;
	private final MonitorService _monitorService;
	private final TSDBService _defaultTSDBService;

	// ~ Constructors
	// *********************************************************************************************************************************

	/**
	 * Creates a new Federated TSDB Service
	 *
	 * @param config
	 *            The system _configuration used to configure the service.
	 * @param monitorService
	 *            The monitor service used to collect query time window
	 *            counters. Cannot be null.
	 *
	 * @throws SystemException
	 *             If an error occurs configuring the service.
	 */
	@Inject
	public FederatedTSDBService(SystemConfiguration config, MonitorService monitorService, @SecondNamedBinding TSDBService tsdbService) {
		super(config);
		requireArgument(config != null, "System configuration cannot be null.");
		requireArgument(monitorService != null, "Monitor service cannot be null.");
		requireArgument(tsdbService != null, "TSDBService cannot be null.");
		_configuration = config;
		_monitorService = monitorService;
		_defaultTSDBService = tsdbService;

		_mapper = getMapper();
		int connCount = Integer.parseInt(_configuration.getValue(Property.TSD_CONNECTION_COUNT.getName(),
				Property.TSD_CONNECTION_COUNT.getDefaultValue()));
		int connTimeout = Integer.parseInt(_configuration.getValue(Property.TSD_ENDPOINT_CONNECTION_TIMEOUT.getName(),
				Property.TSD_ENDPOINT_CONNECTION_TIMEOUT.getDefaultValue()));
		int socketTimeout = Integer.parseInt(_configuration.getValue(Property.TSD_ENDPOINT_SOCKET_TIMEOUT.getName(),
				Property.TSD_ENDPOINT_SOCKET_TIMEOUT.getDefaultValue()));

		String readMultiEndPoint = _configuration.getValue(Property.TSD_MULTI_ENDPOINT_READ.getName(),
				Property.TSD_MULTI_ENDPOINT_READ.getDefaultValue());
		Collections.addAll(_readEndPoints, readMultiEndPoint.split(","));
		requireArgument((_readEndPoints != null) && (!_readEndPoints.isEmpty()), "Illegal read endpoint URL.");

		String readBackupMultiEndPoint = _configuration.getValue(Property.TSD_MULTI_ENDPOINT_BACKUP_READ.getName(),
				Property.TSD_MULTI_ENDPOINT_BACKUP_READ.getDefaultValue());
		Collections.addAll(_readBackupEndPoints, readBackupMultiEndPoint.split(","));

		_writeEndpoint = _configuration.getValue(Property.TSD_ENDPOINT_WRITE.getName(),
				Property.TSD_ENDPOINT_WRITE.getDefaultValue());
		requireArgument((_writeEndpoint != null) && (!_writeEndpoint.isEmpty()), "Illegal write endpoint URL.");
		requireArgument(connCount >= 2, "At least two connections are required.");
		requireArgument(connTimeout >= 1, "Timeout must be greater than 0.");
		try {
			for (String readEndpoint : _readEndPoints) {
				_readPortMap.put(readEndpoint, getClient(readEndpoint, connCount / 2, connTimeout, socketTimeout));
			}
			for (String readBackupEndpoint : _readBackupEndPoints) {
				if (!readBackupEndpoint.isEmpty())
					_readPortMap.put(readBackupEndpoint,
							getClient(readBackupEndpoint, connCount / 2, connTimeout, socketTimeout));
			}
			_writePort = getClient(_writeEndpoint, connCount / 2, connTimeout, socketTimeout);
			_executorService = Executors.newFixedThreadPool(connCount);
		} catch (MalformedURLException ex) {
			throw new SystemException("Error initializing the TSDB HTTP Client.", ex);
		}
	}

	// ~ Methods
	// **************************************************************************************************************************************

	/** @see TSDBService#dispose() */
	@Override
	public void dispose() {
		super.dispose();
		_defaultTSDBService.dispose();		
		for (CloseableHttpClient client : _readPortMap.values()) {
			try {
				client.close();
			} catch (Exception ex) {
				_logger.warn("A TSDB HTTP client failed to shutdown properly.", ex);
			}
		}

		for (CloseableHttpClient client : new CloseableHttpClient[] { _writePort }) {
			try {
				client.close();
			} catch (Exception ex) {
				_logger.warn("A TSDB HTTP client failed to shutdown properly.", ex);
			}
		}
		_executorService.shutdownNow();
		try {
			_executorService.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			_logger.debug("Await Termination Interrupted", e);
		}
	}

	@Override
	public void putMetrics(List<Metric> metrics) {
		_defaultTSDBService.putMetrics(metrics);
	}

	/** @see TSDBService#getMetrics(java.util.List) */
	@Override
	public Map<MetricQuery, List<Metric>> getMetrics(List<MetricQuery> queries) {
		requireNotDisposed();
		requireArgument(queries != null, "Metric Queries cannot be null.");
		_logger.trace("Active Threads in the pool = " + ((ThreadPoolExecutor) _executorService).getActiveCount());

		Map<MetricQuery, Long> queryStartExecutionTime = new HashMap<>();
		Map<MetricQuery, List<MetricQuery>> mapQuerySubQueries = new HashMap<>();

		List<MetricQuery> queriesSplit = timeFederateQueries(queries, queryStartExecutionTime, mapQuerySubQueries);
		Map<MetricQuery, List<Future<List<Metric>>>> queryFuturesMap = endPointFederateQueries(queriesSplit);
		Map<MetricQuery, List<Metric>> subQueryMetricsMap = endPointMergeMetrics(queryFuturesMap);
		return timeMergeMetrics(queries, mapQuerySubQueries, subQueryMetricsMap, queryStartExecutionTime);
	}

	@Override
	public void putAnnotations(List<Annotation> annotations) {
		_defaultTSDBService.putAnnotations(annotations);
	}

	/** @see TSDBService#getAnnotations(java.util.List) */
	@Override
	public List<Annotation> getAnnotations(List<AnnotationQuery> queries) {
		requireNotDisposed();
		requireArgument(queries != null, "Annotation queries cannot be null.");

		List<Annotation> annotations = new ArrayList<>();

		for (AnnotationQuery query : queries) {
			long start = System.currentTimeMillis();
			int index = 0;
			for (String readEndPoint : _readEndPoints) {
				String pattern = readEndPoint + "/api/query?{0}";
				String requestUrl = MessageFormat.format(pattern, query.toString());
				List<AnnotationWrapper> wrappers = null;
				try {
					HttpResponse response = executeHttpRequest(HttpMethod.GET, requestUrl,  _readPortMap.get(readEndPoint), null);
					wrappers = toEntity(((DefaultTSDBService) _defaultTSDBService).extractResponse(response), new TypeReference<AnnotationWrappers>() {
					});
				} catch (Exception ex) {
					_logger.warn("Failed to get annotations from TSDB. Reason: " + ex.getMessage());
					try {
						if (!_readBackupEndPoints.get(index).isEmpty()) {
							_logger.warn("Trying to read from Backup endpoint");
							pattern = _readBackupEndPoints.get(index) + "/api/query?{0}";
							requestUrl = MessageFormat.format(pattern, query.toString());							
							HttpResponse response = executeHttpRequest(HttpMethod.GET, requestUrl, _readPortMap.get( _readBackupEndPoints.get(index)), null);
							wrappers = toEntity(((DefaultTSDBService) _defaultTSDBService).extractResponse(response), new TypeReference<AnnotationWrappers>() {
							});
						}
					} catch (Exception e) {
						_logger.warn("Failed to get annotations from Backup TSDB. Reason: " + e.getMessage());
						index++;
						continue;
					}					
				} 

				index++;

				if (wrappers != null) {
					for (AnnotationWrapper wrapper : wrappers) {
						for (Annotation existing : wrapper.getAnnotations()) {
							String source = existing.getSource();
							String id = existing.getId();
							String type = query.getType();
							String scope = query.getScope();
							String metric = query.getMetric();
							Long timestamp = existing.getTimestamp();
							Annotation updated = new Annotation(source, id, type, scope, metric, timestamp);

							updated.setFields(existing.getFields());
							updated.setTags(query.getTags());
							annotations.add(updated);
						}
					}
				}
			}
			((DefaultTSDBService) _defaultTSDBService).instrumentQueryLatency(_monitorService, query, start, "annotations");
		}
		return annotations;
	}

	private ObjectMapper getMapper() {
		ObjectMapper mapper = new ObjectMapper();
		SimpleModule module = new SimpleModule();

		module.addSerializer(Metric.class, new MetricTransform.Serializer());
		module.addDeserializer(ResultSet.class, new MetricTransform.MetricListDeserializer());
		module.addSerializer(AnnotationWrapper.class, new AnnotationTransform.Serializer());
		module.addSerializer(AnnotationWrapper.class, new AnnotationTransform.Serializer());
		module.addDeserializer(AnnotationWrappers.class, new AnnotationTransform.Deserializer());
		module.addSerializer(MetricQuery.class, new MetricQueryTransform.Serializer());
		mapper.registerModule(module);
		return mapper;
	}

	/* Helper to create the read and write clients. */
	private CloseableHttpClient getClient(String endpoint, int connCount, int connTimeout, int socketTimeout)
			throws MalformedURLException {
		URL url = new URL(endpoint);
		int port = url.getPort();

		requireArgument(port != -1, "Read endpoint must include explicit port.");

		PoolingHttpClientConnectionManager connMgr = new PoolingHttpClientConnectionManager();

		connMgr.setMaxTotal(connCount);
		connMgr.setDefaultMaxPerRoute(connCount);

		String route = endpoint.substring(0, endpoint.lastIndexOf(":"));
		HttpHost host = new HttpHost(route, port);
		RequestConfig reqConfig = RequestConfig.custom().setConnectionRequestTimeout(connTimeout)
				.setConnectTimeout(connTimeout).setSocketTimeout(socketTimeout).build();

		connMgr.setMaxPerRoute(new HttpRoute(host), connCount / 2);
		return HttpClients.custom().setConnectionManager(connMgr).setDefaultRequestConfig(reqConfig).build();
	}

	/*
	 * Helper method to convert JSON String representation to the corresponding
	 * Java entity.
	 */
	private <T> T toEntity(String content, TypeReference<T> type) {
		try {
			return _mapper.readValue(content, type);
		} catch (IOException ex) {
			throw new SystemException(ex);
		}
	}

	/* Helper method to convert a Java entity to a JSON string. */
	private <T> String fromEntity(T type) {
		try {
			return _mapper.writeValueAsString(type);
		} catch (JsonProcessingException ex) {
			throw new SystemException(ex);
		}
	}

	/* Execute a request given by type requestType. */
	private HttpResponse executeHttpRequest(HttpMethod requestType, String url, CloseableHttpClient port, StringEntity entity) {
		HttpResponse httpResponse = null;

		if (entity != null) {
			entity.setContentType("application/json");
		}
		try {
			switch (requestType) {
			case POST:

				HttpPost post = new HttpPost(url);

				post.setEntity(entity);
				httpResponse = port.execute(post);
				break;
			case GET:

				HttpGet httpGet = new HttpGet(url);

				httpResponse = port.execute(httpGet);
				break;
			case DELETE:

				HttpDelete httpDelete = new HttpDelete(url);

				httpResponse = port.execute(httpDelete);
				break;
			case PUT:

				HttpPut httpput = new HttpPut(url);

				httpput.setEntity(entity);
				httpResponse = port.execute(httpput);
				break;
			default:
				throw new MethodNotSupportedException(requestType.toString());
			}
		} catch (IOException | MethodNotSupportedException ex) {
			throw new SystemException(ex);
		}
		return httpResponse;
	}

	// Federate query to list of Read TSDB endpoints
	private Map<MetricQuery, List<Future<List<Metric>>>> endPointFederateQueries(List<MetricQuery> queries) {
		Map<MetricQuery, List<Future<List<Metric>>>> queryFuturesMap = new HashMap<>();
		for (MetricQuery query : queries) {
			String requestBody = fromEntity(query);
			List<Future<List<Metric>>> futures = new ArrayList<>();
			// Look at each read endpoint
			for (String readEndpoint : _readEndPoints) {
				String requestUrl = readEndpoint + "/api/query";
				futures.add(_executorService.submit(new QueryWorker(requestUrl, readEndpoint, requestBody)));
			}
			queryFuturesMap.put(query, futures);
		}
		return queryFuturesMap;
	}

	private Map<MetricQuery, List<Metric>> endPointMergeMetrics(Map<MetricQuery, List<Future<List<Metric>>>> queryFuturesMap) {
		Map<MetricQuery, List<Metric>> subQueryMetricsMap = new HashMap<>();
		for (Entry<MetricQuery, List<Future<List<Metric>>>> entry : queryFuturesMap.entrySet()) {
			Map<String, Metric> metricMergeMap = new HashMap<>();
			List<Future<List<Metric>>> futures = entry.getValue();
			List<Metric> metrics = new ArrayList<>();
			String metricIdentifier;
			int index = 0;

			for (Future<List<Metric>> future : futures) {
				List<Metric> m = null;
				try {
					m = future.get();
				} catch (InterruptedException | ExecutionException e) {
					_logger.warn("Failed to get metrics from TSDB. Reason: " + e.getMessage());
					try {
						if (!_readBackupEndPoints.get(index).isEmpty()) {
							_logger.warn("Trying to read from Backup endpoint");
							m = new QueryWorker(_readBackupEndPoints.get(index) + "/api/query",
									_readBackupEndPoints.get(index), fromEntity(entry.getKey())).call();
						}
					} catch (Exception ex) {
						_logger.warn("Failed to get metrics from Backup TSDB. Reason: " + ex.getMessage());
						index++;
						continue;
					}
				}

				index++;

				// Merge metrics from different endpoints
				if (m != null) {
					for (Metric metric : m) {
						if (metric != null) {
							metricIdentifier = metric.getIdentifier();
							Metric finalMetric = metricMergeMap.get(metricIdentifier);
							if (finalMetric == null) {
								metric.setQuery(entry.getKey());
								metricMergeMap.put(metricIdentifier, metric);
							} else {
								finalMetric.addDatapoints(metric.getDatapoints());
							}
						}
					}
				}
			}

			for (Metric finalMetric : metricMergeMap.values()) {
				metrics.add(finalMetric);
			}

			subQueryMetricsMap.put(entry.getKey(), metrics);
		}
		return subQueryMetricsMap;
	}

	// Split large range queries into smaller queries
	private List<MetricQuery> timeFederateQueries(List<MetricQuery> queries,
			Map<MetricQuery, Long> queryStartExecutionTime, Map<MetricQuery, List<MetricQuery>> mapQuerySubQuery) {
		List<MetricQuery> queriesSplit = new ArrayList<>();
		for (MetricQuery query : queries) {
			queryStartExecutionTime.put(query, System.currentTimeMillis());
			List<MetricQuery> metricSubQueries = new ArrayList<>();
			if (query.getEndTimestamp() - query.getStartTimestamp() > TIME_FEDERATE_LIMIT_MILLIS) {
				for (long time = query.getStartTimestamp(); time <= query.getEndTimestamp(); time = time + TIME_FEDERATE_LIMIT_MILLIS) {
					MetricQuery mq = new MetricQuery(query);
					mq.setStartTimestamp(time);
					if (time + TIME_FEDERATE_LIMIT_MILLIS > query.getEndTimestamp()) {
						mq.setEndTimestamp(query.getEndTimestamp());
					} else {
						mq.setEndTimestamp(time + TIME_FEDERATE_LIMIT_MILLIS);
					}
					queriesSplit.add(mq);
					metricSubQueries.add(mq);
				}
				mapQuerySubQuery.put(query, metricSubQueries);
			} else {
				metricSubQueries.add(query);
				mapQuerySubQuery.put(query, metricSubQueries);
				queriesSplit.add(query);
			}
		}

		return queriesSplit;
	}

	// Merge metrics from split queries
	private Map<MetricQuery, List<Metric>> timeMergeMetrics(List<MetricQuery> queries,
			Map<MetricQuery, List<MetricQuery>> mapQuerySubQueries, Map<MetricQuery, List<Metric>> subQueryMetricsMap,
			Map<MetricQuery, Long> queryStartExecutionTime) {
		Map<MetricQuery, List<Metric>> queryMetricsMap = new HashMap<>();
		Map<String, Metric> metricMergeMap = new HashMap<>();
		String metricIdentifier = null;
		for (MetricQuery query : queries) {
			List<Metric> metrics = new ArrayList<>();
			List<MetricQuery> subQueries = mapQuerySubQueries.get(query);
			for (MetricQuery subQuery : subQueries) {
				List<Metric> metricsFromSubQuery = subQueryMetricsMap.get(subQuery);
				if (metricsFromSubQuery != null) {
					for (Metric metric : metricsFromSubQuery) {
						if (metric != null) {
							metricIdentifier = metric.getIdentifier();
							Metric finalMetric = metricMergeMap.get(metricIdentifier);
							if (finalMetric == null) {
								metric.setQuery(query);
								metricMergeMap.put(metricIdentifier, metric);
							} else {
								finalMetric.addDatapoints(metric.getDatapoints());
							}
						}
					}
				}
			}
			for (Metric finalMetric : metricMergeMap.values()) {
				metrics.add(finalMetric);
			}
			((DefaultTSDBService) _defaultTSDBService).instrumentQueryLatency(_monitorService, query, queryStartExecutionTime.get(query), "metrics");
			queryMetricsMap.put(query, metrics);
		}
		return queryMetricsMap;
	}

	// ~ Enums
	// ****************************************************************************************************************************************

	/**
	 * Enumerates the implementation specific configuration properties.
	 *
	 * @author Dilip Devaraj (ddevaraj@salesforce.com)
	 */
	public enum Property {
		/** The TSDB multi read endpoint. */
		TSD_MULTI_ENDPOINT_READ("service.property.tsdb.multi.endpoint.read", "http://localhost:4466"),
		/** The TSDB backup multi read endpoint. */
		TSD_MULTI_ENDPOINT_BACKUP_READ("service.property.tsdb.multi.endpoint.backup.read", "http://localhost:4466"),
		/** The TSDB write endpoint. */
		TSD_ENDPOINT_WRITE("service.property.tsdb.endpoint.write", "http://localhost:4477"),
		/** The TSDB connection timeout. */
		TSD_ENDPOINT_CONNECTION_TIMEOUT("service.property.tsdb.endpoint.connection.timeout", "10000"),
		/** The TSDB socket connection timeout. */
		TSD_ENDPOINT_SOCKET_TIMEOUT("service.property.tsdb.endpoint.socket.timeout", "10000"),
		/** The TSDB connection count. */
		TSD_CONNECTION_COUNT("service.property.tsdb.connection.count", "2"), TSD_ENDPOINT_COUNT("service.property.tsdb.connection.count", "2");

		private final String _name;
		private final String _defaultValue;

		private Property(String name, String defaultValue) {
			_name = name;
			_defaultValue = defaultValue;
		}

		/**
		 * Returns the property name.
		 *
		 * @return The property name.
		 */
		public String getName() {
			return _name;
		}

		/**
		 * Returns the default value for the property.
		 *
		 * @return The default value.
		 */
		public String getDefaultValue() {
			return _defaultValue;
		}
	}

	/**
	 * Helper class used to parallelize query execution.
	 *
	 * @author Dilip Devaraj (ddevaraj@salesforce.com)
	 */
	private class QueryWorker implements Callable<List<Metric>> {

		private final String _requestUrl;
		private final String _requestEndPoint;
		private final String _requestBody;

		/**
		 * Creates a new QueryWorker object.
		 *
		 * @param requestUrl
		 *            The URL to which the request will be issued.
		 * @param requestEndPoint
		 *            The endpoint to which the request will be issued.
		 * @param requestBody
		 *            The request body.
		 */
		public QueryWorker(String requestUrl, String requestEndPoint, String requestBody) {
			this._requestUrl = requestUrl;
			this._requestEndPoint = requestEndPoint;
			this._requestBody = requestBody;
		}

		@Override
		public List<Metric> call() {
			_logger.debug("TSDB Query = " + _requestBody);

			try {
				HttpResponse response = executeHttpRequest(HttpMethod.POST, _requestUrl,  _readPortMap.get(_requestEndPoint), new StringEntity(_requestBody));
				List<Metric> metrics = toEntity(((DefaultTSDBService) _defaultTSDBService).extractResponse(response), new TypeReference<ResultSet>() {
				}).getMetrics();
				return metrics;
			} catch (UnsupportedEncodingException e) {
				throw new SystemException("Failed to retrieve metrics.", e);
			}
		}
	}

	@Override
	public Properties getServiceProperties() {
		Properties serviceProps = new Properties();

		for (Property property : Property.values()) {
			serviceProps.put(property.getName(), property.getDefaultValue());
		}
		return serviceProps;
	}
}
/* Copyright (c) 2016, Salesforce.com, Inc. All rights reserved. */
