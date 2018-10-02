package com.salesforce.dva.argus.entity;

public class HistogramBucket {
	private float lowerBound;
	private float upperBound;
	
	public HistogramBucket(int lowerBound, int upperBound) {
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
	}
	
	public HistogramBucket(float lowerBound, float upperBound) {
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
	}
}
