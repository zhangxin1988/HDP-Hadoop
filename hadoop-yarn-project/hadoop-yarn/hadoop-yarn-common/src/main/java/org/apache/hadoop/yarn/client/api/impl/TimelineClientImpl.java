/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.client.api.impl;

import java.io.Closeable;
import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.ws.rs.core.MediaType;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.client.ConnectionConfigurator;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticatedURL;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.KerberosDelegationTokenAuthenticator;
import org.apache.hadoop.security.token.delegation.web.PseudoDelegationTokenAuthenticator;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomain;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomains;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntityGroupId;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.yarn.client.api.TimelineClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.security.client.TimelineDelegationTokenIdentifier;
import org.apache.hadoop.yarn.webapp.YarnJacksonJaxbJsonProvider;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig.Feature;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.codehaus.jackson.util.MinimalPrettyPrinter;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;

@Private
@Evolving
public class TimelineClientImpl extends TimelineClient {

  private static final Log LOG = LogFactory.getLog(TimelineClientImpl.class);
  private static final String RESOURCE_URI_STR = "/ws/v1/timeline/";
  private static final Joiner JOINER = Joiner.on("");
  public final static int DEFAULT_SOCKET_TIMEOUT = 1 * 60 * 1000; // 1 minute

  private static Options opts;
  private static final String ENTITY_DATA_TYPE = "entity";
  private static final String DOMAIN_DATA_TYPE = "domain";

  static {
    opts = new Options();
    opts.addOption("put", true, "Put the timeline entities/domain in a JSON file");
    opts.getOption("put").setArgName("Path to the JSON file");
    opts.addOption(ENTITY_DATA_TYPE, false, "Specify the JSON file contains the entities");
    opts.addOption(DOMAIN_DATA_TYPE, false, "Specify the JSON file contains the domain");
    opts.addOption("help", false, "Print usage");
  }

  private Client client;
  private ConnectionConfigurator connConfigurator;
  private DelegationTokenAuthenticator authenticator;
  private DelegationTokenAuthenticatedURL.Token token;
  private URI resURI;
  private UserGroupInformation authUgi;
  private String doAsUser;
  private Path activePath = null;
  private FileSystem fs = null;
  private Set<String> summaryEntityTypes;
  private ObjectMapper objMapper = null;
  private long flushIntervalSecs;
  private long cleanIntervalSecs;
  private long ttl;
  private LogFDsCache logFDsCache = null;
  private boolean isAppendSupported;
  private Configuration conf;
  private float timeLineServiceVersion;

  // This is temporary solution. The configuration will be deleted once we have
  // the FileSystem API to check whether append operation is supported or not.
  private static final String TIMELINE_SERVICE_ENTITYFILE_FS_SUPPORT_APPEND
      = YarnConfiguration.TIMELINE_SERVICE_PREFIX
          + "entity-file.fs-support-append";

  // App log directory must be readable by group so server can access logs
  // and writable by group so it can be deleted by server
  private static final short APP_LOG_DIR_PERMISSIONS = 0770;
  // Logs must be readable by group so server can access them
  private static final short FILE_LOG_PERMISSIONS = 0640;
  private static final String DOMAIN_LOG_PREFIX = "domainlog-";
  private static final String SUMMARY_LOG_PREFIX = "summarylog-";
  private static final String ENTITY_LOG_PREFIX = "entitylog-";

  @Private
  @VisibleForTesting
  TimelineClientConnectionRetry connectionRetry;

  // Abstract class for an operation that should be retried by timeline client
  private static abstract class TimelineClientRetryOp {
    // The operation that should be retried
    public abstract Object run() throws IOException;
    // The method to indicate if we should retry given the incoming exception
    public abstract boolean shouldRetryOn(Exception e);
  }

  // Class to handle retry
  // Outside this class, only visible to tests
  @Private
  @VisibleForTesting
  static class TimelineClientConnectionRetry {

    // maxRetries < 0 means keep trying
    @Private
    @VisibleForTesting
    public int maxRetries;

    @Private
    @VisibleForTesting
    public long retryInterval;

    // Indicates if retries happened last time. Only tests should read it.
    // In unit tests, retryOn() calls should _not_ be concurrent.
    private boolean retried = false;

    @Private
    @VisibleForTesting
    boolean getRetired() {
      return retried;
    }

    // Constructor with default retry settings
    public TimelineClientConnectionRetry(Configuration conf) {
      Preconditions.checkArgument(conf.getInt(
          YarnConfiguration.TIMELINE_SERVICE_CLIENT_MAX_RETRIES,
          YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_MAX_RETRIES) >= -1,
          "%s property value should be greater than or equal to -1",
          YarnConfiguration.TIMELINE_SERVICE_CLIENT_MAX_RETRIES);
      Preconditions
          .checkArgument(
              conf.getLong(
                  YarnConfiguration.TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS,
                  YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS) > 0,
              "%s property value should be greater than zero",
              YarnConfiguration.TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS);
      maxRetries = conf.getInt(
        YarnConfiguration.TIMELINE_SERVICE_CLIENT_MAX_RETRIES,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_MAX_RETRIES);
      retryInterval = conf.getLong(
        YarnConfiguration.TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_CLIENT_RETRY_INTERVAL_MS);
    }

    public Object retryOn(TimelineClientRetryOp op)
        throws RuntimeException, IOException {
      int leftRetries = maxRetries;
      retried = false;

      // keep trying
      while (true) {
        try {
          // try perform the op, if fail, keep retrying
          return op.run();
        } catch (IOException | RuntimeException e) {
          // break if there's no retries left
          if (leftRetries == 0) {
            break;
          }
          if (op.shouldRetryOn(e)) {
            logException(e, leftRetries);
          } else {
            throw e;
          }
        }
        if (leftRetries > 0) {
          leftRetries--;
        }
        retried = true;
        try {
          // sleep for the given time interval
          Thread.sleep(retryInterval);
        } catch (InterruptedException ie) {
          LOG.warn("Client retry sleep interrupted! ");
        }
      }
      throw new RuntimeException("Failed to connect to timeline server. "
          + "Connection retries limit exceeded. "
          + "The posted timeline event may be missing");
    };

    private void logException(Exception e, int leftRetries) {
      if (leftRetries > 0) {
        LOG.info("Exception caught by TimelineClientConnectionRetry,"
              + " will try " + leftRetries + " more time(s).\nMessage: "
              + e.getMessage());
      } else {
        // note that maxRetries may be -1 at the very beginning
        LOG.info("ConnectionException caught by TimelineClientConnectionRetry,"
            + " will keep retrying.\nMessage: "
            + e.getMessage());
      }
    }
  }

  private class TimelineJerseyRetryFilter extends ClientFilter {
    @Override
    public ClientResponse handle(final ClientRequest cr)
        throws ClientHandlerException {
      // Set up the retry operation
      TimelineClientRetryOp jerseyRetryOp = new TimelineClientRetryOp() {
        @Override
        public Object run() {
          // Try pass the request, if fail, keep retrying
          return getNext().handle(cr);
        }

        @Override
        public boolean shouldRetryOn(Exception e) {
          // Only retry on connection exceptions
          return (e instanceof ClientHandlerException)
              && (e.getCause() instanceof ConnectException);
        }
      };
      try {
        return (ClientResponse) connectionRetry.retryOn(jerseyRetryOp);
      } catch (IOException e) {
        throw new ClientHandlerException("Jersey retry failed!\nMessage: "
              + e.getMessage());
      }
    }
  }

  public TimelineClientImpl() {
    super(TimelineClientImpl.class.getName());
  }

  protected void serviceInit(Configuration conf) throws Exception {
    this.conf = conf;
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    UserGroupInformation realUgi = ugi.getRealUser();
    if (realUgi != null) {
      authUgi = realUgi;
      doAsUser = ugi.getShortUserName();
    } else {
      authUgi = ugi;
      doAsUser = null;
    }
    ClientConfig cc = new DefaultClientConfig();
    cc.getClasses().add(YarnJacksonJaxbJsonProvider.class);
    connConfigurator = newConnConfigurator(conf);
    if (UserGroupInformation.isSecurityEnabled()) {
      authenticator = new KerberosDelegationTokenAuthenticator();
    } else {
      authenticator = new PseudoDelegationTokenAuthenticator();
    }
    authenticator.setConnectionConfigurator(connConfigurator);
    token = new DelegationTokenAuthenticatedURL.Token();

    connectionRetry = new TimelineClientConnectionRetry(conf);
    client = new Client(new URLConnectionClientHandler(
        new TimelineURLConnectionFactory()), cc);
    TimelineJerseyRetryFilter retryFilter = new TimelineJerseyRetryFilter();
    client.addFilter(retryFilter);

    if (YarnConfiguration.useHttps(conf)) {
      resURI = URI
          .create(JOINER.join("https://", conf.get(
              YarnConfiguration.TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS,
              YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_HTTPS_ADDRESS),
              RESOURCE_URI_STR));
    } else {
      resURI = URI.create(JOINER.join("http://", conf.get(
          YarnConfiguration.TIMELINE_SERVICE_WEBAPP_ADDRESS,
          YarnConfiguration.DEFAULT_TIMELINE_SERVICE_WEBAPP_ADDRESS),
          RESOURCE_URI_STR));
    }
    LOG.info("Timeline service address: " + resURI);

    timeLineServiceVersion =
        conf.getFloat(YarnConfiguration.TIMELINE_SERVICE_VERSION,
          YarnConfiguration.DEFAULT_TIMELINE_SERVICE_VERSION);
    super.serviceInit(conf);
  }
  @Override
  protected void serviceStart() throws Exception {
    if (timeLineServiceVersion == 1.5) {
      Configuration conf = new Configuration(getConfig());
      conf.setBoolean("dfs.client.retry.policy.enabled", true);
      String retryPolicy =
        conf.get(YarnConfiguration.FS_RM_STATE_STORE_RETRY_POLICY_SPEC,
          YarnConfiguration.DEFAULT_FS_RM_STATE_STORE_RETRY_POLICY_SPEC);
      conf.set("dfs.client.retry.policy.spec", retryPolicy);
      activePath =
          new Path(conf.get(
            YarnConfiguration.TIMELINE_SERVICE_ENTITYGROUP_FS_STORE_ACTIVE_DIR,
            YarnConfiguration
                .TIMELINE_SERVICE_ENTITYGROUP_FS_STORE_ACTIVE_DIR_DEFAULT));
      fs = activePath.getFileSystem(conf);
      if (!fs.exists(activePath)) {
        throw new IOException(activePath + " does not exist");
      }
      summaryEntityTypes = new HashSet<String>(conf.getStringCollection(
          YarnConfiguration
              .TIMELINE_SERVICE_ENTITYGROUP_FS_STORE_SUMMARY_ENTITY_TYPES));
      objMapper = createObjectMapper();
      flushIntervalSecs = conf.getLong(
          YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_FD_FLUSH_INTERVAL_SECS,
          YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_FD_FLUSH_INTERVAL_SECS_DEFAULT);
      cleanIntervalSecs = conf.getLong(
          YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_FD_CLEAN_INTERVAL_SECS,
          YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_FD_CLEAN_INTERVAL_SECS_DEFAULT);
      ttl = conf.getLong(
          YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_RETAIN_SECS,
          YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_RETAIN_SECS_DEFAULT);
      long timerTaskTTL = conf.getLong(
          YarnConfiguration.TIMELINE_SERVICE_CLIENT_INTERNAL_TIMERS_TTL_SECS,
          YarnConfiguration
              .TIMELINE_SERVICE_CLIENT_INTERNAL_TIMERS_TTL_SECS_DEFAULT);
      logFDsCache =
          new LogFDsCache(flushIntervalSecs, cleanIntervalSecs, ttl, timerTaskTTL);
      this.isAppendSupported =
          conf.getBoolean(TIMELINE_SERVICE_ENTITYFILE_FS_SUPPORT_APPEND, true);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_FLUSH_INTERVAL_SECS
                + ":" + flushIntervalSecs + ", " +
            YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_CLEAN_INTERVAL_SECS
                + ":" + cleanIntervalSecs + ", " +
            YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_RETAIN_SECS
                + ":" + ttl + ", " +
            TIMELINE_SERVICE_ENTITYFILE_FS_SUPPORT_APPEND
                + ":" + isAppendSupported);
      }
    }
  }

  @Override
  protected void serviceStop() throws Exception {
    if (this.logFDsCache != null) {
      this.logFDsCache.close();
    }
    super.serviceStop();
  }

  @Override
  public TimelinePutResponse putEntities(
      TimelineEntity... entities) throws IOException, YarnException {
    TimelineEntities entitiesContainer = new TimelineEntities();
    entitiesContainer.addEntities(Arrays.asList(entities));
    ClientResponse resp = doPosting(entitiesContainer, null);
    return resp.getEntity(TimelinePutResponse.class);
  }


  @Override
  public void putDomain(TimelineDomain domain) throws IOException,
      YarnException {
    doPosting(domain, "domain");
  }

  private ClientResponse doPosting(final Object obj, final String path)
      throws IOException, YarnException {
    ClientResponse resp;
    try {
      resp = authUgi.doAs(new PrivilegedExceptionAction<ClientResponse>() {
        @Override
        public ClientResponse run() throws Exception {
          return doPostingObject(obj, path);
        }
      });
    } catch (UndeclaredThrowableException e) {
        throw new IOException(e.getCause());
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    }
    if (resp == null ||
        resp.getClientResponseStatus() != ClientResponse.Status.OK) {
      String msg =
          "Failed to get the response from the timeline server.";
      LOG.error(msg);
      if (LOG.isDebugEnabled() && resp != null) {
        String output = resp.getEntity(String.class);
        LOG.debug("HTTP error code: " + resp.getStatus()
            + " Server response : \n" + output);
      }
      throw new YarnException(msg);
    }
    return resp;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Token<TimelineDelegationTokenIdentifier> getDelegationToken(
      final String renewer) throws IOException, YarnException {
    PrivilegedExceptionAction<Token<TimelineDelegationTokenIdentifier>> getDTAction =
        new PrivilegedExceptionAction<Token<TimelineDelegationTokenIdentifier>>() {

          @Override
          public Token<TimelineDelegationTokenIdentifier> run()
              throws Exception {
            DelegationTokenAuthenticatedURL authUrl =
                new DelegationTokenAuthenticatedURL(authenticator,
                    connConfigurator);
            return (Token) authUrl.getDelegationToken(
                resURI.toURL(), token, renewer, doAsUser);
          }
        };
    return (Token<TimelineDelegationTokenIdentifier>) operateDelegationToken(getDTAction);
  }

  @SuppressWarnings("unchecked")
  @Override
  public long renewDelegationToken(
      final Token<TimelineDelegationTokenIdentifier> timelineDT)
          throws IOException, YarnException {
    final boolean isTokenServiceAddrEmpty =
        timelineDT.getService().toString().isEmpty();
    final String scheme = isTokenServiceAddrEmpty ? null
        : (YarnConfiguration.useHttps(this.getConfig()) ? "https" : "http");
    final InetSocketAddress address = isTokenServiceAddrEmpty ? null
        : SecurityUtil.getTokenServiceAddr(timelineDT);
    PrivilegedExceptionAction<Long> renewDTAction =
        new PrivilegedExceptionAction<Long>() {

          @Override
          public Long run() throws Exception {
            // If the timeline DT to renew is different than cached, replace it.
            // Token to set every time for retry, because when exception happens,
            // DelegationTokenAuthenticatedURL will reset it to null;
            if (!timelineDT.equals(token.getDelegationToken())) {
              token.setDelegationToken((Token) timelineDT);
            }
            DelegationTokenAuthenticatedURL authUrl =
                new DelegationTokenAuthenticatedURL(authenticator,
                    connConfigurator);
            // If the token service address is not available, fall back to use
            // the configured service address.
            final URI serviceURI = isTokenServiceAddrEmpty ? resURI
                : new URI(scheme, null, address.getHostName(),
                address.getPort(), RESOURCE_URI_STR, null, null);
            return authUrl
                .renewDelegationToken(serviceURI.toURL(), token, doAsUser);
          }
        };
    return (Long) operateDelegationToken(renewDTAction);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void cancelDelegationToken(
      final Token<TimelineDelegationTokenIdentifier> timelineDT)
          throws IOException, YarnException {
    final boolean isTokenServiceAddrEmpty =
        timelineDT.getService().toString().isEmpty();
    final String scheme = isTokenServiceAddrEmpty ? null
        : (YarnConfiguration.useHttps(this.getConfig()) ? "https" : "http");
    final InetSocketAddress address = isTokenServiceAddrEmpty ? null
        : SecurityUtil.getTokenServiceAddr(timelineDT);
    PrivilegedExceptionAction<Void> cancelDTAction =
        new PrivilegedExceptionAction<Void>() {

          @Override
          public Void run() throws Exception {
            // If the timeline DT to cancel is different than cached, replace it.
            // Token to set every time for retry, because when exception happens,
            // DelegationTokenAuthenticatedURL will reset it to null;
            if (!timelineDT.equals(token.getDelegationToken())) {
              token.setDelegationToken((Token) timelineDT);
            }
            DelegationTokenAuthenticatedURL authUrl =
                new DelegationTokenAuthenticatedURL(authenticator,
                    connConfigurator);
            // If the token service address is not available, fall back to use
            // the configured service address.
            final URI serviceURI = isTokenServiceAddrEmpty ? resURI
                : new URI(scheme, null, address.getHostName(),
                address.getPort(), RESOURCE_URI_STR, null, null);
            authUrl.cancelDelegationToken(serviceURI.toURL(), token, doAsUser);
            return null;
          }
        };
    operateDelegationToken(cancelDTAction);
  }

  private Object operateDelegationToken(
      final PrivilegedExceptionAction<?> action)
      throws IOException, YarnException {
    // Set up the retry operation
    TimelineClientRetryOp tokenRetryOp = new TimelineClientRetryOp() {

      @Override
      public Object run() throws IOException {
        // Try pass the request, if fail, keep retrying
        authUgi.checkTGTAndReloginFromKeytab();
        try {
          return authUgi.doAs(action);
        } catch (UndeclaredThrowableException e) {
          throw new IOException(e.getCause());
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }

      @Override
      public boolean shouldRetryOn(Exception e) {
        // Only retry on connection exceptions
        return (e instanceof ConnectException);
      }
    };

    return connectionRetry.retryOn(tokenRetryOp);
  }

  @Private
  @VisibleForTesting
  public ClientResponse doPostingObject(Object object, String path) {
    WebResource webResource = client.resource(resURI);
    if (path == null) {
      return webResource.accept(MediaType.APPLICATION_JSON)
          .type(MediaType.APPLICATION_JSON)
          .post(ClientResponse.class, object);
    } else if (path.equals("domain")) {
      return webResource.path(path).accept(MediaType.APPLICATION_JSON)
          .type(MediaType.APPLICATION_JSON)
          .put(ClientResponse.class, object);
    } else {
      throw new YarnRuntimeException("Unknown resource type");
    }
  }

  private class TimelineURLConnectionFactory
      implements HttpURLConnectionFactory {

    @Override
    public HttpURLConnection getHttpURLConnection(final URL url) throws IOException {
      authUgi.checkTGTAndReloginFromKeytab();
      try {
        return new DelegationTokenAuthenticatedURL(
            authenticator, connConfigurator).openConnection(url, token,
              doAsUser);
      } catch (UndeclaredThrowableException e) {
        throw new IOException(e.getCause());
      } catch (AuthenticationException ae) {
        throw new IOException(ae);
      }
    }

  }

  private static ConnectionConfigurator newConnConfigurator(Configuration conf) {
    try {
      return newSslConnConfigurator(DEFAULT_SOCKET_TIMEOUT, conf);
    } catch (Exception e) {
      LOG.debug("Cannot load customized ssl related configuration. " +
          "Fallback to system-generic settings.", e);
      return DEFAULT_TIMEOUT_CONN_CONFIGURATOR;
    }
  }

  private static final ConnectionConfigurator DEFAULT_TIMEOUT_CONN_CONFIGURATOR =
      new ConnectionConfigurator() {
    @Override
    public HttpURLConnection configure(HttpURLConnection conn)
        throws IOException {
      setTimeouts(conn, DEFAULT_SOCKET_TIMEOUT);
      return conn;
    }
  };

  private static ConnectionConfigurator newSslConnConfigurator(final int timeout,
      Configuration conf) throws IOException, GeneralSecurityException {
    final SSLFactory factory;
    final SSLSocketFactory sf;
    final HostnameVerifier hv;

    factory = new SSLFactory(SSLFactory.Mode.CLIENT, conf);
    factory.init();
    sf = factory.createSSLSocketFactory();
    hv = factory.getHostnameVerifier();

    return new ConnectionConfigurator() {
      @Override
      public HttpURLConnection configure(HttpURLConnection conn)
          throws IOException {
        if (conn instanceof HttpsURLConnection) {
          HttpsURLConnection c = (HttpsURLConnection) conn;
          c.setSSLSocketFactory(sf);
          c.setHostnameVerifier(hv);
        }
        setTimeouts(conn, timeout);
        return conn;
      }
    };
  }

  private static void setTimeouts(URLConnection connection, int socketTimeout) {
    connection.setConnectTimeout(socketTimeout);
    connection.setReadTimeout(socketTimeout);
  }

  public static void main(String[] argv) throws Exception {
    CommandLine cliParser = new GnuParser().parse(opts, argv);
    if (cliParser.hasOption("put")) {
      String path = cliParser.getOptionValue("put");
      if (path != null && path.length() > 0) {
        if (cliParser.hasOption(ENTITY_DATA_TYPE)) {
          putTimelineDataInJSONFile(path, ENTITY_DATA_TYPE);
          return;
        } else if (cliParser.hasOption(DOMAIN_DATA_TYPE)) {
          putTimelineDataInJSONFile(path, DOMAIN_DATA_TYPE);
          return;
        }
      }
    }
    printUsage();
  }

  /**
   * Put timeline data in a JSON file via command line.
   * 
   * @param path
   *          path to the timeline data JSON file
   * @param type
   *          the type of the timeline data in the JSON file
   */
  private static void putTimelineDataInJSONFile(String path, String type) {
    File jsonFile = new File(path);
    if (!jsonFile.exists()) {
      LOG.error("File [" + jsonFile.getAbsolutePath() + "] doesn't exist");
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    YarnJacksonJaxbJsonProvider.configObjectMapper(mapper);
    TimelineEntities entities = null;
    TimelineDomains domains = null;
    try {
      if (type.equals(ENTITY_DATA_TYPE)) {
        entities = mapper.readValue(jsonFile, TimelineEntities.class);
      } else if (type.equals(DOMAIN_DATA_TYPE)){
        domains = mapper.readValue(jsonFile, TimelineDomains.class);
      }
    } catch (Exception e) {
      LOG.error("Error when reading  " + e.getMessage());
      e.printStackTrace(System.err);
      return;
    }
    Configuration conf = new YarnConfiguration();
    TimelineClient client = TimelineClient.createTimelineClient();
    client.init(conf);
    client.start();
    try {
      if (UserGroupInformation.isSecurityEnabled()
          && conf.getBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED, false)) {
        Token<TimelineDelegationTokenIdentifier> token =
            client.getDelegationToken(
                UserGroupInformation.getCurrentUser().getUserName());
        UserGroupInformation.getCurrentUser().addToken(token);
      }
      if (type.equals(ENTITY_DATA_TYPE)) {
        TimelinePutResponse response = client.putEntities(
            entities.getEntities().toArray(
                new TimelineEntity[entities.getEntities().size()]));
        if (response.getErrors().size() == 0) {
          LOG.info("Timeline entities are successfully put");
        } else {
          for (TimelinePutResponse.TimelinePutError error : response.getErrors()) {
            LOG.error("TimelineEntity [" + error.getEntityType() + ":" +
                error.getEntityId() + "] is not successfully put. Error code: " +
                error.getErrorCode());
          }
        }
      } else if (type.equals(DOMAIN_DATA_TYPE)) {
        boolean hasError = false;
        for (TimelineDomain domain : domains.getDomains()) {
          try {
            client.putDomain(domain);
          } catch (Exception e) {
            LOG.error("Error when putting domain " + domain.getId(), e);
            hasError = true;
          }
        }
        if (!hasError) {
          LOG.info("Timeline domains are successfully put");
        }
      }
    } catch(RuntimeException e) {
      LOG.error("Error when putting the timeline data", e);
    } catch (Exception e) {
      LOG.error("Error when putting the timeline data", e);
    } finally {
      client.stop();
    }
  }

  /**
   * Helper function to print out usage
   */
  private static void printUsage() {
    new HelpFormatter().printHelp("TimelineClient", opts);
  }

  @VisibleForTesting
  @Private
  public UserGroupInformation getUgi() {
    return authUgi;
  }

  @Override
  public TimelinePutResponse putEntities(
      ApplicationAttemptId appAttemptId, TimelineEntityGroupId groupId,
      TimelineEntity... entities)
      throws IOException, YarnException {
    if (timeLineServiceVersion != 1.5) {
      throw new YarnException(
        "this API is not supported in current timeline service version:"
            + timeLineServiceVersion);
    }
    if (appAttemptId == null) {
      return putEntities(entities);
    }

    List<TimelineEntity> entitiesToDB = new ArrayList<TimelineEntity>();
    List<TimelineEntity> entitiesToSummary = new ArrayList<TimelineEntity>();
    List<TimelineEntity> entitiesToEntity = new ArrayList<TimelineEntity>();
    Path attemptDir = createAttemptDir(appAttemptId);

    for (TimelineEntity entity : entities) {
      if (summaryEntityTypes.contains(entity.getEntityType())) {
        entitiesToSummary.add(entity);
      } else {
        if (groupId != null) {
          entitiesToEntity.add(entity);
        } else {
          entitiesToDB.add(entity);
        }
      }
    }

    if (!entitiesToSummary.isEmpty()) {
      Path summaryLogPath =
          new Path(attemptDir, SUMMARY_LOG_PREFIX + appAttemptId.toString());
      LOG.info("Writing summary log for " + appAttemptId.toString() + " to "
          + summaryLogPath);
      this.logFDsCache.writeSummaryEntityLogs(fs, summaryLogPath, objMapper,
        appAttemptId, entitiesToSummary, isAppendSupported);
    }

    if (!entitiesToEntity.isEmpty()) {
      Path entityLogPath =
          new Path(attemptDir, ENTITY_LOG_PREFIX + groupId.toString());
      LOG.info("Writing entity log for " + groupId.toString() + " to "
          + entityLogPath);
      this.logFDsCache.writeEntityLogs(fs, entityLogPath, objMapper,
        appAttemptId, groupId, entitiesToEntity, isAppendSupported);
    }

    if (!entitiesToDB.isEmpty()) {
      putEntities(entitiesToDB.toArray(
          new TimelineEntity[entitiesToDB.size()]));
    }

    return new TimelinePutResponse();
  }

  @Override
  public void putDomain(ApplicationAttemptId appAttemptId,
      TimelineDomain domain) throws IOException, YarnException {
    if (timeLineServiceVersion != 1.5) {
      throw new YarnException(
        "this API is not supported in current timeline service version:"
            + timeLineServiceVersion);
    }
    if (appAttemptId == null) {
      putDomain(domain);
    } else {
      writeDomain(appAttemptId, domain);
    }
  }

  private ObjectMapper createObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setAnnotationIntrospector(new JaxbAnnotationIntrospector());
    mapper.setSerializationInclusion(Inclusion.NON_NULL);
    mapper.configure(Feature.CLOSE_CLOSEABLE, false);
    return mapper;
  }

  private Path createAttemptDir(ApplicationAttemptId appAttemptId)
      throws IOException {
    Path appDir = createApplicationDir(appAttemptId.getApplicationId());

    Path attemptDir = new Path(appDir, appAttemptId.toString());
    if (!fs.exists(attemptDir)) {
      FileSystem.mkdirs(fs, attemptDir, new FsPermission(
        APP_LOG_DIR_PERMISSIONS));
    }
    return attemptDir;
  }

  private Path createApplicationDir(ApplicationId appId) throws IOException {
    Path appDir =
        new Path(activePath, appId.toString());
    if (!fs.exists(appDir)) {
      FileSystem.mkdirs(fs, appDir, new FsPermission(APP_LOG_DIR_PERMISSIONS));
    }
    return appDir;
  }

  private void writeDomain(ApplicationAttemptId appAttemptId,
      TimelineDomain domain) throws IOException {
    Path domainLogPath =
        new Path(createAttemptDir(appAttemptId), DOMAIN_LOG_PREFIX
            + appAttemptId.toString());
    LOG.info("Writing domains for " + appAttemptId.toString() + " to "
        + domainLogPath);
    this.logFDsCache.writeDomainLog(
        fs, domainLogPath, objMapper, domain, isAppendSupported);
  }

  private static class DomainLogFD extends LogFD {
    public DomainLogFD(FileSystem fs, Path logPath, ObjectMapper objMapper,
        boolean isAppendSupported) throws IOException {
      super(fs, logPath, objMapper, isAppendSupported);
    }

    public void writeDomain(TimelineDomain domain)
        throws IOException {
      objMapper.writeValue(jsonGenerator, domain);
      lastModifiedTime = System.currentTimeMillis();
    }
  }

  private static class EntityLogFD extends LogFD {
    public EntityLogFD(FileSystem fs, Path logPath, ObjectMapper objMapper,
        boolean isAppendSupported) throws IOException {
      super(fs, logPath, objMapper, isAppendSupported);
    }

    public synchronized void writeEntities(List<TimelineEntity> entities)
        throws IOException {
      if (writerClosed()) {
        prepareForWrite();
      }
      for (TimelineEntity entity : entities) {
        objMapper.writeValue(jsonGenerator, entity);
      }
      lastModifiedTime = System.currentTimeMillis();
    }
  }

  private static class LogFD {
    private FSDataOutputStream stream;
    protected ObjectMapper objMapper;
    protected JsonGenerator jsonGenerator;
    protected long lastModifiedTime;
    private final boolean isAppendSupported;
    private final ReentrantLock fdLock = new ReentrantLock();
    private final FileSystem fs;
    private final Path logPath;

    public LogFD(FileSystem fs, Path logPath, ObjectMapper objMapper,
        boolean isAppendSupported) throws IOException {
      this.fs = fs;
      this.logPath = logPath;
      this.isAppendSupported = isAppendSupported;
      this.objMapper = objMapper;
      prepareForWrite();
    }

    public void close() {
      if (stream != null) {
        IOUtils.cleanup(LOG, jsonGenerator);
        IOUtils.cleanup(LOG, stream);
        stream = null;
        jsonGenerator = null;
      }
    }

    public void flush() throws IOException {
      if (stream != null) {
        stream.hflush();
      }
    }

    public long getLastModifiedTime() {
      return this.lastModifiedTime;
    }

    protected void prepareForWrite() throws IOException{
      this.stream = createLogFileStream(fs, logPath);
      this.jsonGenerator = new JsonFactory().createJsonGenerator(stream);
      this.jsonGenerator.setPrettyPrinter(new MinimalPrettyPrinter("\n"));
      this.lastModifiedTime = System.currentTimeMillis();
    }

    protected boolean writerClosed() {
      return stream == null;
    }

    private FSDataOutputStream createLogFileStream(FileSystem fs, Path logPath)
        throws IOException {
      FSDataOutputStream stream;
      if (!isAppendSupported) {
        logPath =
            new Path(logPath.getParent(),
              (logPath.getName() + "_" + System.currentTimeMillis()));
      }
      if (!fs.exists(logPath)) {
        stream = fs.create(logPath, false);
        fs.setPermission(logPath, new FsPermission(FILE_LOG_PERMISSIONS));
      } else {
        stream = fs.append(logPath);
      }
      return stream;
    }

    public void lock() {
      this.fdLock.lock();
    }

    public void unlock() {
      this.fdLock.unlock();
    }
  }

  private static class LogFDsCache implements Closeable, Flushable{
    private DomainLogFD domainLogFD;
    private Map<ApplicationAttemptId, EntityLogFD> summanyLogFDs;
    private Map<ApplicationAttemptId, HashMap<TimelineEntityGroupId,
        EntityLogFD>> entityLogFDs;
    private Timer flushTimer = null;
    private Timer cleanInActiveFDsTimer = null;
    private Timer monitorTaskTimer = null;
    private final long ttl;
    private final ReentrantLock domainFDLocker = new ReentrantLock();
    private final ReentrantLock summaryTableLocker = new ReentrantLock();
    private final ReentrantLock entityTableLocker = new ReentrantLock();
    private volatile boolean serviceStopped = false;
    private volatile boolean timerTaskStarted = false;
    private final ReentrantLock timerTaskLocker = new ReentrantLock();
    private final long flushIntervalSecs;
    private final long cleanIntervalSecs;
    private final long timerTaskRetainTTL;
    private volatile long timeStampOfLastWrite = System.currentTimeMillis();
    private final ReadLock timerTasksMonitorReadLock;
    private final WriteLock timerTasksMonitorWriteLock;
    
    public LogFDsCache(long flushIntervalSecs, long cleanIntervalSecs,
        long ttl, long timerTaskRetainTTL) {
      domainLogFD = null;
      summanyLogFDs = new HashMap<ApplicationAttemptId, EntityLogFD>();
      entityLogFDs = new HashMap<ApplicationAttemptId,
          HashMap<TimelineEntityGroupId, EntityLogFD>>();
      this.flushIntervalSecs = flushIntervalSecs;
      this.cleanIntervalSecs = cleanIntervalSecs;
      this.ttl = ttl * 1000;
      long timerTaskRetainTTLVar = timerTaskRetainTTL * 1000;
      if (timerTaskRetainTTLVar > this.ttl) {
        this.timerTaskRetainTTL = timerTaskRetainTTLVar;
      } else {
        this.timerTaskRetainTTL = this.ttl + 2 * 60 * 1000;
        LOG.warn("The specific " + YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_INTERNAL_TIMERS_TTL_SECS + " : "
            + timerTaskRetainTTL + " is invalid, because it is less than or "
            + "equal to " + YarnConfiguration
            .TIMELINE_SERVICE_CLIENT_FD_RETAIN_SECS + " : " + ttl + ". Use "
            + YarnConfiguration.TIMELINE_SERVICE_CLIENT_FD_RETAIN_SECS + " : "
            + ttl + " + 120s instead.");
      }
      ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
      this.timerTasksMonitorReadLock = lock.readLock();
      this.timerTasksMonitorWriteLock = lock.writeLock();
    }

    @Override
    public void flush() throws IOException {
      try {
        this.domainFDLocker.lock();
        if (domainLogFD != null) {
          domainLogFD.flush();
        }
      } finally {
        this.domainFDLocker.unlock();
      }

      flushSummaryFDMap(summanyLogFDs);

      flushEntityFDMap(entityLogFDs);
    }

    private void flushSummaryFDMap(Map<ApplicationAttemptId,
        EntityLogFD> logFDs) throws IOException {
      if (!logFDs.isEmpty()) {
        for (Entry<ApplicationAttemptId, EntityLogFD> logFDEntry : logFDs
          .entrySet()) {
          EntityLogFD logFD = logFDEntry.getValue();
          try {
            logFD.lock();
            logFD.flush();            
          } finally {
            logFD.unlock();
          }
        }
      }
    }

    private void flushEntityFDMap(Map<ApplicationAttemptId,HashMap<
        TimelineEntityGroupId, EntityLogFD>> logFDs) throws IOException {
      if (!logFDs.isEmpty()) {
        for (Entry<ApplicationAttemptId, HashMap<TimelineEntityGroupId,
            EntityLogFD>> logFDMapEntry : logFDs.entrySet()) {
          HashMap<TimelineEntityGroupId, EntityLogFD> logFDMap
              = logFDMapEntry.getValue();
          for (Entry<TimelineEntityGroupId, EntityLogFD> logFDEntry
              : logFDMap.entrySet()) {
            EntityLogFD logFD = logFDEntry.getValue();
            try {
              logFD.lock();
              logFD.flush();
            } finally {
              logFD.unlock();
            }
          }
        }
      }
    }

    private class FlushTimerTask extends TimerTask {
      @Override
      public void run() {
        try {
          flush();
        } catch (Exception e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(e);
          }
        }
      }
    }

    private void cleanInActiveFDs() {
      long currentTimeStamp = System.currentTimeMillis();
      try {
        this.domainFDLocker.lock();
        if (domainLogFD != null) {
          if (currentTimeStamp - domainLogFD.getLastModifiedTime()
              >= ttl) {
            domainLogFD.close();
            domainLogFD = null;
          }
        }
      } finally {
        this.domainFDLocker.unlock();
      }

      cleanInActiveSummaryFDsforMap(summanyLogFDs, currentTimeStamp);

      cleanInActiveEntityFDsforMap(entityLogFDs, currentTimeStamp);
    }

    private void cleanInActiveSummaryFDsforMap(
        Map<ApplicationAttemptId, EntityLogFD> logFDs,
        long currentTimeStamp) {
      if (!logFDs.isEmpty()) {
        for (Entry<ApplicationAttemptId, EntityLogFD> logFDEntry : logFDs
          .entrySet()) {
          EntityLogFD logFD = logFDEntry.getValue();
          try {
            logFD.lock();
            if (currentTimeStamp - logFD.getLastModifiedTime() >= ttl) {
              logFD.close();
            }
          } finally {
            logFD.unlock();
          }
        }
      }
    }

    private void cleanInActiveEntityFDsforMap(Map<ApplicationAttemptId,
        HashMap<TimelineEntityGroupId, EntityLogFD>> logFDs,
        long currentTimeStamp) {
      if (!logFDs.isEmpty()) {
        for (Entry<ApplicationAttemptId, HashMap<
            TimelineEntityGroupId, EntityLogFD>> logFDMapEntry
                : logFDs.entrySet()) {
          HashMap<TimelineEntityGroupId, EntityLogFD> logFDMap
              = logFDMapEntry.getValue();
          for (Entry<TimelineEntityGroupId, EntityLogFD> logFDEntry
              : logFDMap.entrySet()) {
            EntityLogFD logFD = logFDEntry.getValue();
            try {
              logFD.lock();
              if (currentTimeStamp - logFD.getLastModifiedTime() >= ttl) {
                logFD.close();
              }                
            } finally {
              logFD.unlock();
            }
          }
        }
      }
    }

    private class CleanInActiveFDsTask extends TimerTask {
      @Override
      public void run() {
        try {
          cleanInActiveFDs();
        } catch (Exception e) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(e);
          }
        }
      }
    }

    private class TimerMonitorTask extends TimerTask {
      @Override
      public void run() {
        try {
          timerTasksMonitorWriteLock.lock();
          monitorTimerTasks();
        } finally {
          timerTasksMonitorWriteLock.unlock();
        }
      }
    }

    private void monitorTimerTasks() {
      if (System.currentTimeMillis() - this.timeStampOfLastWrite
          >= this.timerTaskRetainTTL) {
        cancelAndCloseTimerTasks();

        timerTaskStarted = false;
      } else {
        if (this.monitorTaskTimer != null) {
        this.monitorTaskTimer.schedule(new TimerMonitorTask(),
            this.timerTaskRetainTTL);
        }
      }
    }
    @Override
    public void close() throws IOException {

      serviceStopped = true;
      cancelAndCloseTimerTasks();
    }

    private void cancelAndCloseTimerTasks() {
      if (flushTimer != null) {
        flushTimer.cancel();
        flushTimer = null;
      }

      if (cleanInActiveFDsTimer != null) {
        cleanInActiveFDsTimer.cancel();
        cleanInActiveFDsTimer = null;
      }

      if (monitorTaskTimer != null) {
        monitorTaskTimer.cancel();
        monitorTaskTimer = null;
      }

      try {
        this.domainFDLocker.lock();
        if (domainLogFD != null) {
          domainLogFD.close();
          domainLogFD = null;
        }
      } finally {
        this.domainFDLocker.unlock();
      }

      closeSummaryFDs(summanyLogFDs);

      closeEntityFDs(entityLogFDs);
    }

    private void closeEntityFDs(Map<ApplicationAttemptId,
        HashMap<TimelineEntityGroupId, EntityLogFD>> logFDs) {
      try {
        entityTableLocker.lock();
        if (!logFDs.isEmpty()) {
          for (Entry<ApplicationAttemptId, HashMap<TimelineEntityGroupId,
              EntityLogFD>> logFDMapEntry : logFDs.entrySet()) {
            HashMap<TimelineEntityGroupId, EntityLogFD> logFDMap
                = logFDMapEntry.getValue();
            for (Entry<TimelineEntityGroupId, EntityLogFD> logFDEntry
                : logFDMap.entrySet()) {
              EntityLogFD logFD = logFDEntry.getValue();
              try {
                logFD.lock();
                logFD.close();              
              } finally {
                logFD.unlock();
              }
            }
          }
        }
      } finally {
        entityTableLocker.unlock();
      }
    }  

    private void closeSummaryFDs(Map<ApplicationAttemptId, EntityLogFD> logFDs) {
      try {
        summaryTableLocker.lock();
        if (!logFDs.isEmpty()) {
          for (Entry<ApplicationAttemptId, EntityLogFD> logFDEntry
              : logFDs.entrySet()) {
            EntityLogFD logFD = logFDEntry.getValue();
            try {
              logFD.lock();
              logFD.close();
            } finally {
              logFD.unlock();
            }
          }
        }
      } finally {
        summaryTableLocker.unlock();
      }
    } 

    public void writeDomainLog(FileSystem fs, Path logPath,
        ObjectMapper objMapper, TimelineDomain domain,
        boolean isAppendSupported) throws IOException {
      checkAndStartTimeTasks();
      try {
        this.domainFDLocker.lock();
        if (this.domainLogFD != null) {
          this.domainLogFD.writeDomain(domain);
        } else {
          this.domainLogFD =
              new DomainLogFD(fs, logPath, objMapper, isAppendSupported);
          this.domainLogFD.writeDomain(domain);
        }
      } finally {
        this.domainFDLocker.unlock();
      }
    }

    public void writeEntityLogs(FileSystem fs, Path entityLogPath,
        ObjectMapper objMapper, ApplicationAttemptId appAttemptId,
        TimelineEntityGroupId groupId, List<TimelineEntity> entitiesToEntity,
        boolean isAppendSupported) throws IOException{
      checkAndStartTimeTasks();
      writeEntityLogs(fs, entityLogPath, objMapper, appAttemptId, groupId, entitiesToEntity,
        isAppendSupported, this.entityLogFDs);
    }

    private void writeEntityLogs(FileSystem fs, Path logPath,
        ObjectMapper objMapper, ApplicationAttemptId attemptId,
        TimelineEntityGroupId groupId, List<TimelineEntity> entities,
        boolean isAppendSupported, Map<ApplicationAttemptId, HashMap<
            TimelineEntityGroupId, EntityLogFD>> logFDs) throws IOException {
      HashMap<TimelineEntityGroupId, EntityLogFD>logMapFD = logFDs.get(attemptId);
      if (logMapFD != null) {
        EntityLogFD logFD = logMapFD.get(groupId);
        if (logFD != null) {
          try {
            logFD.lock();
            if (serviceStopped) {
              return;
            }
            logFD.writeEntities(entities);
          } finally {
            logFD.unlock();
          }
        } else {
          createEntityFDandWrite(fs, logPath, objMapper, attemptId, groupId,
            entities, isAppendSupported, logFDs);
        }
      } else {
        createEntityFDandWrite(fs, logPath, objMapper, attemptId, groupId,
          entities, isAppendSupported, logFDs);
      }
    }

    private void createEntityFDandWrite(FileSystem fs, Path logPath,
        ObjectMapper objMapper, ApplicationAttemptId attemptId,
        TimelineEntityGroupId groupId, List<TimelineEntity> entities,
        boolean isAppendSupported, Map<ApplicationAttemptId, HashMap<
            TimelineEntityGroupId, EntityLogFD>> logFDs) throws IOException{
      try {
        entityTableLocker.lock();
        if (serviceStopped) {
          return;
        }
        HashMap<TimelineEntityGroupId, EntityLogFD> logFDMap = logFDs.get(attemptId);
        if (logFDMap == null) {
          logFDMap = new HashMap<TimelineEntityGroupId, EntityLogFD>();
        }
        EntityLogFD logFD = logFDMap.get(groupId);
        if (logFD == null) {
         logFD = new EntityLogFD(fs, logPath, objMapper, isAppendSupported);
        }
        try {
          logFD.lock();
          logFD.writeEntities(entities);
          logFDMap.put(groupId, logFD);
          logFDs.put(attemptId, logFDMap);        
        } finally {
          logFD.unlock();
        }
      } finally {
        entityTableLocker.unlock();
      }
    }

    public void writeSummaryEntityLogs(FileSystem fs, Path logPath,
        ObjectMapper objMapper, ApplicationAttemptId attemptId,
        List<TimelineEntity> entities, boolean isAppendSupported)
        throws IOException {
      checkAndStartTimeTasks();
      writeSummmaryEntityLogs(fs, logPath, objMapper, attemptId, entities,
        isAppendSupported, this.summanyLogFDs);
    }

    private void writeSummmaryEntityLogs(FileSystem fs, Path logPath,
        ObjectMapper objMapper, ApplicationAttemptId attemptId,
        List<TimelineEntity> entities, boolean isAppendSupported,
        Map<ApplicationAttemptId, EntityLogFD> logFDs) throws IOException {
      EntityLogFD logFD = null;
      logFD = logFDs.get(attemptId);
      if (logFD != null) {
        try {
          logFD.lock();
          if (serviceStopped) {
            return;
          }
          logFD.writeEntities(entities);
        } finally {
          logFD.unlock();
        }
      } else {
        createSummaryFDAndWrite(fs, logPath, objMapper, attemptId, entities,
          isAppendSupported, logFDs);
      }
    }

    private void createSummaryFDAndWrite(FileSystem fs, Path logPath,
        ObjectMapper objMapper, ApplicationAttemptId attemptId,
        List<TimelineEntity> entities, boolean isAppendSupported,
        Map<ApplicationAttemptId, EntityLogFD> logFDs) throws IOException {
      try {
        summaryTableLocker.lock();
        if (serviceStopped) {
          return;
        }
        EntityLogFD logFD = logFDs.get(attemptId);
        if (logFD == null) {
            logFD = new EntityLogFD(fs, logPath, objMapper, isAppendSupported);
        }
        try {
          logFD.lock();
          logFD.writeEntities(entities);
          logFDs.put(attemptId, logFD);
        } finally {
          logFD.unlock();
        }
      } finally {
        summaryTableLocker.unlock();
      }
    }

    private void checkAndStartTimeTasks() {
      try {
        this.timerTasksMonitorReadLock.lock();
        this.timeStampOfLastWrite = System.currentTimeMillis();
        if(!timerTaskStarted) {
          try {
            timerTaskLocker.lock();
            if (!timerTaskStarted) {
              createAndStartTimerTasks();
              timerTaskStarted = true;
            }
          } finally {
            timerTaskLocker.unlock();
          }
        }
      } finally {
        this.timerTasksMonitorReadLock.unlock();
      }
    }

    private void createAndStartTimerTasks() {
      this.flushTimer =
          new Timer(LogFDsCache.class.getSimpleName() + "FlushTimer",
            true);
      this.flushTimer.schedule(new FlushTimerTask(), flushIntervalSecs * 1000,
          flushIntervalSecs * 1000);

      this.cleanInActiveFDsTimer =
          new Timer(LogFDsCache.class.getSimpleName()
            + "cleanInActiveFDsTimer", true);
      this.cleanInActiveFDsTimer.schedule(new CleanInActiveFDsTask(),
          cleanIntervalSecs * 1000, cleanIntervalSecs * 1000);

      this.monitorTaskTimer =
          new Timer(LogFDsCache.class.getSimpleName() + "MonitorTimer",
          true);
      this.monitorTaskTimer.schedule(new TimerMonitorTask(),
          this.timerTaskRetainTTL);
    }
  }
}
