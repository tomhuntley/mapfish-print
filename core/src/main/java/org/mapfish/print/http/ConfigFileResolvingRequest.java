package org.mapfish.print.http;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.locationtech.jts.util.Assert;
import org.mapfish.print.PrintException;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.processor.Processor;
import org.mapfish.print.url.data.DataUrlConnection;
import org.mapfish.print.url.data.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

class ConfigFileResolvingRequest extends AbstractClientHttpRequest {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFileResolvingRequest.class);
  private final ConfigFileResolvingHttpRequestFactory configFileResolvingHttpRequestFactory;
  private final URI uri;
  private final HttpMethod httpMethod;
  private ClientHttpRequest request;

  ConfigFileResolvingRequest(
      @Nonnull final ConfigFileResolvingHttpRequestFactory configFileResolvingHttpRequestFactory,
      @Nonnull final URI uri,
      @Nonnull final HttpMethod httpMethod) {
    this.configFileResolvingHttpRequestFactory = configFileResolvingHttpRequestFactory;
    this.uri = uri;
    this.httpMethod = httpMethod;
  }

  @Override
  protected synchronized OutputStream getBodyInternal(final HttpHeaders headers)
      throws IOException {
    Assert.isTrue(this.request == null, "getBodyInternal() can only be called once.");
    this.request = createRequestFromWrapped(headers);
    return this.request.getBody();
  }

  private synchronized ClientHttpRequest createRequestFromWrapped(final HttpHeaders headers)
      throws IOException {
    final ConfigurableRequest httpRequest =
        configFileResolvingHttpRequestFactory
            .getHttpRequestFactory()
            .createRequest(this.uri, this.httpMethod);
    httpRequest.setConfiguration(configFileResolvingHttpRequestFactory.getConfig());

    httpRequest.getHeaders().putAll(headers);
    if (configFileResolvingHttpRequestFactory
        .getMdcContext()
        .containsKey(Processor.MDC_JOB_ID_KEY)) {
      String jobId =
          configFileResolvingHttpRequestFactory.getMdcContext().get(Processor.MDC_JOB_ID_KEY);
      httpRequest.getHeaders().set("X-Request-ID", jobId);
      httpRequest.getHeaders().set("X-Job-ID", jobId);
    }
    if (configFileResolvingHttpRequestFactory
        .getMdcContext()
        .containsKey(Processor.MDC_APPLICATION_ID_KEY)) {
      String applicationId =
          configFileResolvingHttpRequestFactory
              .getMdcContext()
              .get(Processor.MDC_APPLICATION_ID_KEY);
      httpRequest.getHeaders().set("X-Application-ID", applicationId);
    }
    return httpRequest;
  }

  @Override
  protected synchronized ClientHttpResponse executeInternal(final HttpHeaders headers)
      throws IOException {
    final Map<String, String> prev = MDC.getCopyOfContextMap();
    boolean mdcChanged = configFileResolvingHttpRequestFactory.getMdcContext().equals(prev);
    if (mdcChanged) {
      MDC.setContextMap(configFileResolvingHttpRequestFactory.getMdcContext());
    }
    try {
      if ("data".equals(this.uri.getScheme())) {
        return doDataUriRequest();
      }
      if (getURI().getScheme() == null
          || Arrays.asList("file", "", "servlet", "classpath").contains(getURI().getScheme())) {
        return doFileRequest();
      }
      return doHttpRequestWithRetry(headers);
    } finally {
      if (mdcChanged) {
        MDC.setContextMap(prev);
      }
    }
  }

  private ClientHttpResponse doDataUriRequest() throws IOException {
    final String urlStr = this.uri.toString();
    final URL url = new URL("data", null, 0, urlStr.substring("data:".length()), new Handler());
    final DataUrlConnection duc = new DataUrlConnection(url);
    final InputStream is = duc.getInputStream();
    final String contentType = duc.getContentType();
    final HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.set("Content-Type", contentType);
    final ConfigFileResolverHttpResponse response =
        new ConfigFileResolverHttpResponse(is, responseHeaders);
    LOGGER.debug("Resolved request using DataUrlConnection: {}", contentType);
    return response;
  }

  private ClientHttpResponse doFileRequest() throws IOException {
    final String uriString = this.uri.toString();
    final Configuration configuration = configFileResolvingHttpRequestFactory.getConfig();
    final byte[] bytes = configuration.loadFile(uriString);
    final InputStream is = new ByteArrayInputStream(bytes);
    final HttpHeaders responseHeaders = new HttpHeaders();
    final Optional<File> file = configuration.getFile(uriString);
    if (file.isPresent()) {
      responseHeaders.set(
          "Content-Length", String.valueOf(Files.probeContentType(file.get().toPath())));
    }
    final ConfigFileResolverHttpResponse response =
        new ConfigFileResolverHttpResponse(is, responseHeaders);
    LOGGER.debug("Resolved request: {} using MapFish print config file loaders.", uriString);
    return response;
  }

  private ClientHttpResponse doHttpRequestWithRetry(final HttpHeaders headers) throws IOException {
    AtomicInteger counter = new AtomicInteger();
    do {
      // Display headers, one by line <name>: <value>
      LOGGER.debug(
          "Fetching URI resource {}, headers:\n{}",
          this.getURI(),
          headers.entrySet().stream()
              .map(entry -> entry.getKey() + "=" + String.join(", ", entry.getValue()))
              .collect(Collectors.joining("\n")));
      try {
        ClientHttpResponse response = attemptToFetchResponse(headers, counter);
        if (response != null) {
          return response;
        }
      } catch (final IOException e) {
        handleIOException(e, counter);
      }
    } while (true);
  }

  private ClientHttpResponse attemptToFetchResponse(
      final HttpHeaders headers, final AtomicInteger counter) throws IOException {
    ClientHttpRequest requestUsed =
        this.request != null ? this.request : createRequestFromWrapped(headers);
    LOGGER.debug("Executing http request: {}", requestUsed.getURI());
    ClientHttpResponse response = executeCallbacksAndRequest(requestUsed);
    if (response.getRawStatusCode() < 500) {
      LOGGER.debug(
          "Fetching success URI resource {}, error code {}", getURI(), response.getRawStatusCode());
      return response;
    }
    LOGGER.debug(
        "Fetching failed URI resource {}, error code {}", getURI(), response.getRawStatusCode());
    if (counter.incrementAndGet()
        < configFileResolvingHttpRequestFactory.getHttpRequestMaxNumberFetchRetry()) {
      try {
        TimeUnit.MILLISECONDS.sleep(
            configFileResolvingHttpRequestFactory.getHttpRequestFetchRetryIntervalMillis());
      } catch (InterruptedException e1) {
        Thread.currentThread().interrupt();
        throw new PrintException("Interrupted while sleeping", e1);
      }
      LOGGER.debug("Retry fetching URI resource {}", this.getURI());
    } else {
      throw new PrintException(
          String.format(
              "Fetching failed URI resource %s, error code %s",
              getURI(), response.getRawStatusCode()));
    }
    return null;
  }

  private void handleIOException(final IOException e, final AtomicInteger counter)
      throws IOException {
    if (counter.incrementAndGet()
        < configFileResolvingHttpRequestFactory.getHttpRequestMaxNumberFetchRetry()) {
      try {
        TimeUnit.MILLISECONDS.sleep(
            configFileResolvingHttpRequestFactory.getHttpRequestFetchRetryIntervalMillis());
      } catch (InterruptedException e1) {
        Thread.currentThread().interrupt();
        throw new PrintException("Interrupted while sleeping", e1);
      }
      LOGGER.debug("Retry fetching URI resource {}", this.getURI());
    } else {
      LOGGER.debug("Fetching failed URI resource {}", getURI());
      throw e;
    }
  }

  private ClientHttpResponse executeCallbacksAndRequest(final ClientHttpRequest requestToExecute)
      throws IOException {
    for (MfClientHttpRequestFactory.RequestConfigurator callback :
        configFileResolvingHttpRequestFactory.getCallbacks()) {
      callback.configureRequest(requestToExecute);
    }

    return requestToExecute.execute();
  }

  @Override
  public HttpMethod getMethod() {
    return this.httpMethod;
  }

  @Override
  public String getMethodValue() {
    return this.httpMethod.name();
  }

  @Override
  public URI getURI() {
    return this.uri;
  }

  private static class ConfigFileResolverHttpResponse implements ClientHttpResponse {
    private final InputStream is;
    private final HttpHeaders headers;

    ConfigFileResolverHttpResponse(final InputStream is, final HttpHeaders headers) {
      this.headers = headers;
      this.is = is;
    }

    @Override
    public HttpStatus getStatusCode() {
      return HttpStatus.OK;
    }

    @Override
    public int getRawStatusCode() {
      return getStatusCode().value();
    }

    @Override
    public String getStatusText() {
      return "OK";
    }

    @Override
    public void close() {
      // nothing to do
    }

    @Override
    public InputStream getBody() {
      return this.is;
    }

    @Override
    public HttpHeaders getHeaders() {
      return this.headers;
    }
  }
}
