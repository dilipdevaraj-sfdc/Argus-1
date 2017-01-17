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

package com.salesforce.dva.argus.service.alert.notifier;

import static com.salesforce.dva.argus.system.SystemAssert.requireArgument;

import java.io.IOException;
import java.net.URLEncoder;
import java.sql.Date;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.persistence.EntityManager;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.salesforce.dva.argus.entity.Notification;
import com.salesforce.dva.argus.entity.Trigger;
import com.salesforce.dva.argus.inject.SLF4JTypeListener;
import com.salesforce.dva.argus.service.AnnotationService;
import com.salesforce.dva.argus.service.AuditService;
import com.salesforce.dva.argus.service.MailService;
import com.salesforce.dva.argus.service.MetricService;
import com.salesforce.dva.argus.service.alert.DefaultAlertService.NotificationContext;
import com.salesforce.dva.argus.system.SystemConfiguration;

import joptsimple.internal.Strings;

/**
 * Slack Notifier: api user can only post alert to slack
 *
 * @author  Dilip Devaraj (ddevaraj@salesforce.com)
 */
public class SlackNotifier extends AuditNotifier {

	//~ Static fields/initializers *******************************************************************************************************************
	private static final int CONNECTION_TIMEOUT_MILLIS = 10000;
	private static final int READ_TIMEOUT_MILLIS = 10000;
	private static final String UTF_8 = "UTF-8";

	//~ Instance fields ******************************************************************************************************************************
	@SLF4JTypeListener.InjectLogger
	private Logger _logger;
	private final MultiThreadedHttpConnectionManager theConnectionManager;
	{
		theConnectionManager = new MultiThreadedHttpConnectionManager();

		HttpConnectionManagerParams params = theConnectionManager.getParams();

		params.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
		params.setSoTimeout(READ_TIMEOUT_MILLIS);
	}    

	//~ Constructors *********************************************************************************************************************************

	/**
	 * Creates a new SlackNotifier object.
	 *
	 * @param  metricService      The metric service to use.  Cannot be null.
	 * @param  annotationService  The annotation service to use.  Cannot be null.
	 * @param  auditService       The audit service to use.  Cannot be null.
	 * @param  mailService        The mail service to use.  Cannot be null.
	 * @param  config             The system configuration.  Cannot be null.
	 * @param  emf                The entity manager factory to use.  Cannot be null.
	 */
	@Inject
	public SlackNotifier(MetricService metricService, AnnotationService annotationService, AuditService auditService, MailService mailService,
			SystemConfiguration config, Provider<EntityManager> emf) {
		super(metricService, annotationService, auditService, config, emf);
		requireArgument(mailService != null, "Mail service cannot be null.");
		requireArgument(config != null, "The configuration cannot be null.");
	}

	//~ Methods **************************************************************************************************************************************

	@Override
	public String getName() {
		return SlackNotifier.class.getName();
	}

	@Override
	protected void sendAdditionalNotification(NotificationContext context) {
		requireArgument(context != null, "Notification context cannot be null.");
		super.sendAdditionalNotification(context);

		Notification notification = null;
		Trigger trigger = null;

		for (Notification tempNotification : context.getAlert().getNotifications()) {
			if (tempNotification.getName().equalsIgnoreCase(context.getNotification().getName())) {
				notification = tempNotification;
				break;
			}
		}
		requireArgument(notification != null, "Notification in notification context cannot be null.");
		for (Trigger tempTrigger : context.getAlert().getTriggers()) {
			if (tempTrigger.getName().equalsIgnoreCase(context.getTrigger().getName())) {
				trigger = tempTrigger;
				break;
			}
		}
		requireArgument(trigger != null, "Trigger in notification context cannot be null.");

		String feed = generateSlackFeed(notification, trigger, context);

		postToSlack(feed);
	}

	private String generateSlackFeed(Notification notification, Trigger trigger, NotificationContext context) {
		StringBuilder sb = new StringBuilder();
		String notificationName = context.getNotification().getName();
		String alertName = context.getAlert().getName();
		String triggerFiredTime = DATE_FORMATTER.get().format(new Date(context.getTriggerFiredTime()));
		String triggerName = trigger.getName();
		String notificationCooldownExpiraton = DATE_FORMATTER.get().format(new Date(context.getCoolDownExpiration()));
		String metricExpression = context.getAlert().getExpression();
		String triggerDetails = getTriggerDetails(trigger);
		String triggerEventValue = context.getTriggerEventValue();
		Object[] arguments = new Object[] {
				notificationName, alertName, triggerFiredTime, triggerName, notificationCooldownExpiraton, metricExpression, triggerDetails,
				triggerEventValue, String.valueOf(context.getTriggerFiredTime()), context.getTriggeredMetric()
		};

		/** gus feed template for notification information. */
		String gusFeedNotificationTemplate = "Alert Notification {0} is triggered, more info as following:\n" + "Alert {1}  was triggered at {2}\n" +
				"Notification:   {0}\n" +
				"Triggered by:   {3}\n" + "Notification is on cooldown until:   {4}\n" +
				"Evaluated metric expression:   {5}\n" + "Triggered on Metric:   {9}\n" + "Trigger details:  {6}\n" +
				"Triggering event value:   {7}\n" + "Triggering event timestamp:   {8}\n\n";

		sb.append(MessageFormat.format(gusFeedNotificationTemplate, arguments));

		/** gus feed template for links. */
		String gusFeedLinkTemplate = "Click here to view {0}\n{1}\n";

		for (String metricToAnnotate : notification.getMetricsToAnnotate()) {
			sb.append(MessageFormat.format(gusFeedLinkTemplate, "the annotated series for",
					super.getMetricUrl(metricToAnnotate, context.getTriggerFiredTime())));
		}
		if(context.getNotification().getCustomText() != null && context.getNotification().getCustomText().length()>0){
			sb.append(context.getNotification().getCustomText()).append("\n>"); 
		}
		sb.append(MessageFormat.format(gusFeedLinkTemplate, "alert definition.", super.getAlertUrl(notification.getAlert().getId())));
		return sb.toString();
	}

	private void postToSlack(String feed) {
		PostMethod slackPost = new PostMethod(_config.getValue(Property.POST_ENDPOINT.getName(), Property.POST_ENDPOINT.getDefaultValue()));

		try {
			
			slackPost.setRequestHeader("Content-type", "application/json");
// 			String slackMessage = "{\"channel\": \"#general\", \"username\": \"webhookbot\", \"text\" : \"hello\"}";
			String slackMessage = "{\"channel\": \"#general\", \"username\": \"webhookbot\", \"text\" : \""+feed+"\"}";
			slackPost.setRequestEntity(new StringRequestEntity(slackMessage, "application/json", null));
			HttpClient httpclient = getHttpClient(_config);
			int respCode = httpclient.executeMethod(slackPost);
			_logger.info("Slack message response code '{}'", respCode);
			if (respCode == 201 || respCode == 204) {
				_logger.info("Success - send to Slack group");
			} else {
				_logger.error("Failure - send to Slack group. Cause {}", slackPost.getResponseBodyAsString());
			}
		} catch (Exception e) {
			_logger.error("Throws Exception {} when posting to Slack group", e);
		} finally {
			slackPost.releaseConnection();
		}
	}

	/**
	 * Get HttpClient with proper proxy and timeout settings.
	 *
	 * @param   config  The system configuration.  Cannot be null.
	 *
	 * @return  HttpClient
	 */
	public  HttpClient getHttpClient(SystemConfiguration config) {
		HttpClient httpclient = new HttpClient(theConnectionManager);

		// Wait for 2 seconds to get a connection from pool
		httpclient.getParams().setParameter("http.connection-manager.timeout", 2000L); 
		return httpclient;
	}    

	@Override
	public Properties getNotifierProperties() {
		Properties result = super.getNotifierProperties();

		for( Property property : Property.values()) {
			result.put(property.getName(), property.getDefaultValue());
		}
		return result;
	}

	public enum Property {
		/** The Slack post endpoint. */
		POST_ENDPOINT("notifier.property.alert.slack_post_endpoint", "https://hooks.slack.com/services/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");

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
		 * Returns the default value.
		 *
		 * @return  The default value.
		 */
		public String getDefaultValue() {
			return _defaultValue;
		}
	}
}
/* Copyright (c) 2016, Salesforce.com, Inc.  All rights reserved. */