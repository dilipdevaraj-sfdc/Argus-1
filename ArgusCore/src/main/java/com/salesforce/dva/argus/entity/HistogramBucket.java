package com.salesforce.dva.argus.entity;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

public class HistogramBucket implements Serializable, Comparable<HistogramBucket> {
	private float lowerBound;
	private float upperBound;

	public HistogramBucket() {
	}

	public HistogramBucket(int lowerBound, int upperBound) {
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
	}

	public HistogramBucket(float lowerBound, float upperBound) {
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
	}

	public HistogramBucket(String value) {
		String[] bounds = value.split(",");
		this.lowerBound = Float.parseFloat(bounds[0].trim());
		this.upperBound = Float.parseFloat(bounds[1].trim());
	}

	/**
	 * Returns the name of the layout type.
	 *
	 * @return  The name of the layout type.
	 */
	@JsonValue
	public String value() {
		return this.toString();
	}

	public static class KeySerializer extends JsonSerializer<HistogramBucket> {

		@Override
		public void serialize(HistogramBucket histogramBucket, JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonProcessingException {

			jgen.writeStartObject();
			jgen.writeNumberField("lowerBound", histogramBucket.lowerBound);
			jgen.writeNumberField("upperBound", histogramBucket.upperBound);
			jgen.writeEndObject();
		}
	}

	@Override
	public int compareTo(HistogramBucket that) {
		if(this.equals(that)){
			return 0;
		} else {
			int lowerBoundCompare = Float.compare(this.lowerBound, that.lowerBound); 
			if(lowerBoundCompare !=0) return lowerBoundCompare;
			else return Float.compare(this.upperBound, that.upperBound);
		}
	}
}
