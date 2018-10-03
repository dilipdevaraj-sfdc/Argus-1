package com.salesforce.dva.argus.entity;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;

public class HistogramBucketDeserializer extends KeyDeserializer{

	@Override
	public HistogramBucket deserializeKey(String key, DeserializationContext ctxt) throws IOException {
		return new HistogramBucket(key);
	}

}
