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

package com.salesforce.dva.argus.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.salesforce.dva.argus.service.tsdb.MetricQuery;
import com.salesforce.dva.argus.system.SystemAssert;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import static com.salesforce.dva.argus.system.SystemAssert.requireArgument;

/**
 * Time series metric entity object. This entity encapsulates all the information needed to represent a time series for a metric within a single
 * scope. The following tag names are reserved. Any methods that set tags, which use these reserved tag names, will throw a runtime exception.
 *
 * <ul>
 *   <li>metric</li>
 *   <li>displayName</li>
 *   <li>units</li>
 * </ul>
 *
 * @author  Tom Valine (tvaline@salesforce.com), Bhinav Sura (bhinav.sura@salesforce.com)
 */
@SuppressWarnings("serial")
public class Histogram extends TSDBEntity implements Serializable {

	//~ Instance fields ******************************************************************************************************************************

	private String _namespace;
	private String _displayName;
	private String _units;
	private Map<HistogramBucket, Long> _buckets;

	//~ Constructors *********************************************************************************************************************************

	/**
	 * Creates a new Histogram object by performing a shallow copy of the given Histogram object.
	 *
	 * @param  histogram  The histogram object to clone. Cannot be null.
	 */
	public Histogram(Histogram histogram) {
		SystemAssert.requireArgument(histogram != null, "Metric to clone cannot be null.");
		setScope(histogram.getScope());
		setMetric(histogram.getMetric());
		setTags(histogram.getTags());
		_buckets = new TreeMap<>();
		setBuckets(histogram.getBuckets());
		setNamespace(histogram.getNamespace());
		setDisplayName(histogram.getDisplayName());
		setUnits(histogram.getUnits());
	}

	/**
	 * Creates a new Histogram object.
	 *
	 * @param  scope   The reverse dotted name of the collection scope. Cannot be null or empty.
	 * @param  metric  The name of the metric. Cannot be null or empty.
	 */
	public Histogram(String scope, String metric) {
		this();
		setScope(scope);
		setMetric(metric);
	}

	/** Creates a new Histogram object. */
	protected Histogram() {
		super(null, null);
		_buckets = new TreeMap<>();
	}

	//~ Methods **************************************************************************************************************************************

	@Override
	public void setScope(String scope) {
		requireArgument(scope != null && !scope.trim().isEmpty(), "Scope cannot be null or empty.");
		super.setScope(scope);
	}

	@Override
	public void setMetric(String metric) {
		requireArgument(metric != null && !metric.trim().isEmpty(), "Metric cannot be null or empty.");
		super.setMetric(metric);
	}

	/**
	 * Returns the namespace for the Histogram.
	 *
	 * @return  The namespace of the Histogram or null if the Histogram belongs to the global namespace.
	 */
	public String getNamespace() {
		return _namespace;
	}

	/**
	 * Sets the namespace for the Histogram.
	 *
	 * @param  namespace  The namespace for the Histogram.  If null, the Histogram will belong to the global namespace.
	 */
	public void setNamespace(String namespace) {
		_namespace = namespace;
	}

	/**
	 * Returns an unmodifiable map of time series data points which is backed by the entity objects internal data.
	 *
	 * @return  The map of time series data points. Will never be null, but may be empty.
	 */
	public Map<HistogramBucket, Long> getBuckets() {
		return Collections.unmodifiableMap(_buckets);
	}

	/**
	 * Deletes the current set of data points and replaces them with a new set.
	 *
	 * @param  datapoints  The new set of data points. If null or empty, only the deletion of the current set of data points is performed.
	 */
	public void setBuckets(Map<HistogramBucket, Long> buckets) {
		_buckets.clear();
		if (buckets != null) {
			_buckets.putAll(buckets);
		}
	}

	/**
	 * Sets the display name for the metric.
	 *
	 * @param  displayName  The display name for the metric. Can be null or empty.
	 */
	public void setDisplayName(String displayName) {
		_displayName = displayName;
	}

	/**
	 * Returns the display name for the metric.
	 *
	 * @return  The display name for the metric. Can be null or empty.
	 */
	public String getDisplayName() {
		return _displayName;
	}

	/**
	 * Sets the units of the time series data point values.
	 *
	 * @param  units  The units of the time series data point values. Can be null or empty.
	 */
	public void setUnits(String units) {
		_units = units;
	}

	/**
	 * Returns the units of the time series data point values.
	 *
	 * @return  The units of the time series data point values. Can be null or empty.
	 */
	public String getUnits() {
		return _units;
	}
	
	@Override
	public String toString() {
		Object[] params = {getNamespace(), getScope(), getMetric(), getTags(), getBuckets() };
		String format = "namespace=>{0}, scope=>{1}, metric=>{2}, tags=>{3}, buckets=>{4}";

		return MessageFormat.format(format, params);
	}

	/**
	 * To return an identifier string, the format is &lt;namespace&gt;:&lt;scope&gt;:&lt;name&gt;{&lt;tags&gt;}
	 *
	 * @return  Returns a metric identifier for the metric.  Will never return null.
	 */
	@JsonIgnore
	public String getIdentifier() {

		String tags = "";

		Map<String, String> sortedTags = new TreeMap<>();
		sortedTags.putAll(getTags());
		if(!sortedTags.isEmpty()) {
			StringBuilder tagListBuffer = new StringBuilder("{");
			for (String tagKey : sortedTags.keySet()) {
				tagListBuffer.append(tagKey).append('=').append(sortedTags.get(tagKey)).append(',');
			}

			tags = tagListBuffer.substring(0, tagListBuffer.length() - 1).concat("}");
		}

		String namespace = getNamespace();
		Object[] params = { namespace == null ? "" : namespace + ":", getScope(), getMetric(), tags };
		String format = "{0}{1}:{2}" + "{3}";

		return MessageFormat.format(format, params);
	}

}
/* Copyright (c) 2018, Salesforce.com, Inc.  All rights reserved. */
