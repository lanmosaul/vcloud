package com.jbrisbin.vcloud.logging;

import com.rabbitmq.client.*;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.spi.LoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Calendar;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Jon Brisbin <jon.brisbin@npcinternational.com>
 */
public class RabbitMQAppender extends AppenderSkeleton {

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final boolean DEBUG = log.isDebugEnabled();
  private ConnectionParameters connParams = new ConnectionParameters();
  private Connection connection = null;
  private Channel channel = null;
  private String host = "localhost";
  private int port = 5672;
  private String user = "guest";
  private String password = "guest";
  private String virtualHost = "/";
  private int publishPoolMaxSize = 10;
  private String exchange = "vcloud.logging.events";
  private String appenderId = System.getProperty("HOSTNAME");
  private ExecutorService workerPool = Executors.newCachedThreadPool();


  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
    connParams.setUsername(user);
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
    connParams.setPassword(password);
  }

  public String getVirtualHost() {
    return virtualHost;
  }

  public void setVirtualHost(String virtualHost) {
    this.virtualHost = virtualHost;
    connParams.setVirtualHost(virtualHost);
  }

  public int getPublishPoolMaxSize() {
    return publishPoolMaxSize;
  }

  public void setPublishPoolMaxSize(int publishPoolMaxSize) {
    this.publishPoolMaxSize = publishPoolMaxSize;
    this.workerPool = Executors.newFixedThreadPool(publishPoolMaxSize);
  }

  public String getExchange() {
    return exchange;
  }

  public void setExchange(String exchange) {
    this.exchange = exchange;
    Channel mq = null;
    try {
      mq = getChannel();
      synchronized (mq) {
        mq.exchangeDeclare(exchange, "topic");
      }
    } catch (IOException e) {
      log.error(e.getMessage(), e);
    }
  }

  public String getAppenderId() {
    return appenderId;
  }

  public void setAppenderId(String appenderId) {
    this.appenderId = appenderId;
  }

  @Override
  protected void append(final LoggingEvent event) {
    try {
      workerPool.submit(new AppenderPublisher(event));
    } catch (IOException e) {
      log.error(e.getMessage(), e);
    }
  }

  public void close() {
    if (null != channel && channel.isOpen()) {
      try {
        channel.close();
      } catch (IOException e) {
        log.error(e.getMessage(), e);
      }
    }
    if (null != connection && connection.isOpen()) {
      try {
        connection.close();
      } catch (IOException e) {
        log.error(e.getMessage(), e);
      }
    }
  }

  public boolean requiresLayout() {
    return true;
  }

  protected Channel getChannel() throws IOException {
    if (null == channel || !channel.isOpen()) {
      if (DEBUG) {
        log.debug("Opening a new channel...");
      }
      channel = getConnection().createChannel();
    }
    return channel;
  }

  protected Connection getConnection() throws IOException {
    if (null == connection || !connection.isOpen()) {
      if (DEBUG) {
        log.debug("Opening a new connection...");
      }
      connection = new ConnectionFactory(connParams).newConnection(host, port);
    }
    return connection;
  }

  class AppenderPublisher implements Callable<LoggingEvent> {

    LoggingEvent event;
    StringBuffer message;

    AppenderPublisher(LoggingEvent event) throws IOException {
      this.event = event;
      this.message = new StringBuffer(layout.format(event));
      // Capture stack trace if layout ignores it
      if (layout.ignoresThrowable()) {
        String[] stackTrace = event.getThrowableStrRep();
        if (null != stackTrace) {
          for (String s : stackTrace) {
            message.append(s).append(Layout.LINE_SEP);
          }
        }
      }
    }

    public LoggingEvent call() throws Exception {
      String id = String.format("%s:%s", appenderId, System.currentTimeMillis());
      String routingKey = String.format("%s.%s", event.getLevel().toString(), event.getLoggerName());

      AMQP.BasicProperties props = new AMQP.BasicProperties();
      props.setCorrelationId(id);
      props.setType(event.getLevel().toString());
      props.setTimestamp(Calendar.getInstance().getTime());

      getChannel().basicPublish(exchange, routingKey, props, message.toString().getBytes());

      return event;
    }
  }
}
