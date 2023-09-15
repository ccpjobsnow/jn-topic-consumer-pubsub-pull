package com.ccp.topic.consumer.pubsub.pull;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

import com.ccp.decorators.CcpMapDecorator;
import com.ccp.decorators.CcpStringDecorator;
import com.ccp.dependency.injection.CcpDependencyInjection;
import com.ccp.implementations.db.bulk.elasticsearch.Bulk;
import com.ccp.implementations.db.dao.elasticsearch.Dao;
import com.ccp.implementations.db.utils.elasticsearch.DbUtils;
import com.ccp.implementations.db.utils.elasticsearch.Query;
import com.ccp.implementations.emails.sendgrid.Email;
import com.ccp.implementations.file.bucket.gcp.FileBucket;
import com.ccp.implementations.http.apache.mime.Http;
import com.ccp.implementations.instant.messenger.telegram.InstantMessenger;
import com.ccp.implementations.text.extractor.apache.tika.JsonHandler;
import com.ccp.implementations.text.extractor.apache.tika.TextExtractor;
import com.ccp.jn.async.AsyncServices;
import com.ccp.jn.async.business.NotifyError;
import com.google.api.gax.core.ExecutorProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.Subscriber.Builder;
import com.google.pubsub.v1.ProjectSubscriptionName;
public class PubSubStarter { 

	final CcpMapDecorator parameters;
	
	private final JnMessageReceiver queue;
	
	private NotifyError notifyError = new NotifyError();
	
	
	public PubSubStarter( CcpMapDecorator args, Function<CcpMapDecorator, CcpMapDecorator> function) {
		this.parameters = args;
		String topic = this.parameters.getAsString("topic");
		this.queue = new JnMessageReceiver(topic, function);

	}
		
	public void synchronizeMessages() {
		
		Subscriber subscriber = null;
		try {
			String topic = this.parameters.getAsString("topic");
			String projectName = this.parameters.getAsString("project");
			Integer threads = this.parameters.getAsIntegerNumber("threads");
			
			ProjectSubscriptionName subscription = ProjectSubscriptionName.of(projectName, topic);
			ExecutorProvider executorProvider = InstantiatingExecutorProvider.newBuilder().setExecutorThreadCount(threads).build();

			FixedCredentialsProvider credentials = this.getCredentials();
			
			Builder newBuilder = Subscriber.newBuilder(subscription, this.queue);
			Builder setCredentialsProvider = newBuilder.setCredentialsProvider(credentials);
			Builder setExecutorProvider = setCredentialsProvider.setExecutorProvider(executorProvider);
			subscriber = setExecutorProvider.build(); 
			subscriber.startAsync();
			subscriber.awaitTerminated();
		}catch (java.lang.IllegalStateException e) {
			if(e.getCause() instanceof com.google.api.gax.rpc.NotFoundException) {
				this.notifyError.apply(new RuntimeException("Topic still has not been created: " + this.parameters.getAsString("topic")));
			}
		} catch (Throwable e) {
			this.notifyError.apply(e);
		} finally {
			if (subscriber != null) {
				subscriber.stopAsync();
			}
		}
	}

	private FixedCredentialsProvider getCredentials(){
		
		String fileName = this.parameters.getAsString("credentials");

		InputStream is = new CcpStringDecorator(fileName).inputStreamFrom().file();

		FixedCredentialsProvider create;
		try {
			create = FixedCredentialsProvider.create(ServiceAccountCredentials.fromStream(is));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return create;

	
	}

	
	public static void main(String[] args) {
		
		CcpDependencyInjection.loadAllDependencies
		(
				new InstantMessenger(),
				new JsonHandler(),
				new TextExtractor(),
				new FileBucket(),
				new DbUtils(),
				new Email(),
				new Query(),
				new Http(),
				new Bulk(),
				new Dao()
		);
		String json = args[0];
		CcpMapDecorator md = new CcpMapDecorator(json);
		PubSubStarter pubSubStarter = new PubSubStarter(md, mdMessage -> AsyncServices.executeProcess(md.getAsString("topic"), mdMessage));
		pubSubStarter.synchronizeMessages();
		
	}
	
}
