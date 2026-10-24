/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.http.OutboundOutcome;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Report storage on the AWS SDK 2 S3 clients (synchronous on Apache HttpClient 5, asynchronous on
 * Netty), pointed at ext-stub (path-style requests to SAMPLE_EXT_URL, which answers any GET with a
 * JSON echo). Never talks to AWS: the endpoint, region and (obviously fake) credentials are fixed,
 * the defaults mode is fixed and the EC2 metadata service is disabled, so building the client makes
 * no network call.
 */
@Service
public class ObjectStorage implements DisposableBean {

  /** Bucket of the sample reports. */
  public static final String BUCKET = "argos-sample";

  /** Not a credential: ext-stub does not check signatures. */
  private static final String SAMPLE_KEY = "argos-sample";

  private final URI endpoint;
  private final S3Client s3;
  private final S3AsyncClient s3Async;
  private final S3Presigner presigner;

  public ObjectStorage(SampleConfig config) {
    // The SDK never needs the instance metadata service here; make sure it is never asked.
    if (System.getProperty("aws.disableEc2Metadata") == null) {
      System.setProperty("aws.disableEc2Metadata", "true");
    }
    this.endpoint = URI.create(config.extUrl());
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(SAMPLE_KEY, SAMPLE_KEY));
    this.s3 =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .endpointOverride(endpoint)
            .forcePathStyle(true)
            .defaultsMode(DefaultsMode.STANDARD)
            .overrideConfiguration(
                o ->
                    o.apiCallTimeout(Duration.ofMillis(config.extTimeoutMs() * 2L))
                        .retryStrategy(r -> r.maxAttempts(1)))
            .build();
    // The asynchronous client runs on the SDK's Netty NIO client (event loops start on first use).
    this.s3Async =
        S3AsyncClient.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .endpointOverride(endpoint)
            .forcePathStyle(true)
            .defaultsMode(DefaultsMode.STANDARD)
            .overrideConfiguration(
                o ->
                    o.apiCallTimeout(Duration.ofMillis(config.extTimeoutMs() * 2L))
                        .retryStrategy(r -> r.maxAttempts(1)))
            .build();
    this.presigner =
        S3Presigner.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .endpointOverride(endpoint)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
  }

  /** GET of one object from ext-stub (one outgoing call through the SDK's Apache 5 client). */
  public Map<String, Object> fetch(String key) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("bucket", BUCKET);
    result.put("key", key);
    try {
      ResponseBytes<GetObjectResponse> object =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
      result.put("outcome", OutboundOutcome.OK.name());
      result.put("bytes", object.asByteArray().length);
      result.put("contentType", object.response().contentType());
    } catch (SdkServiceException e) {
      result.put("outcome", OutboundOutcome.ofStatus(e.statusCode()).name());
      result.put("upstreamStatus", e.statusCode());
    } catch (RuntimeException e) {
      result.put("outcome", OutboundOutcome.classify(e).name());
      result.put("error", e.getClass().getSimpleName());
    }
    return result;
  }

  /** The same GET with the asynchronous client (Netty); completes on an SDK thread. */
  public CompletableFuture<Map<String, Object>> fetchAsync(String key) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("bucket", BUCKET);
    result.put("key", key);
    result.put("client", "async");
    return s3Async
        .getObject(
            GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
            AsyncResponseTransformer.toBytes())
        .handle(
            (object, failure) -> {
              if (failure == null) {
                result.put("outcome", OutboundOutcome.OK.name());
                result.put("bytes", object.asByteArray().length);
                return result;
              }
              Throwable cause =
                  failure instanceof CompletionException ? failure.getCause() : failure;
              if (cause instanceof SdkServiceException e) {
                result.put("outcome", OutboundOutcome.ofStatus(e.statusCode()).name());
                result.put("upstreamStatus", e.statusCode());
              } else {
                result.put("outcome", OutboundOutcome.classify(cause).name());
                result.put("error", cause.getClass().getSimpleName());
              }
              return result;
            });
  }

  /** Startup: a presigned download URL (SigV4 signing, offline) and the object URL. */
  public Map<String, Object> prepare() {
    String key = "reports/order-1.xlsx";
    String presigned =
        presigner
            .presignGetObject(
                p ->
                    p.signatureDuration(Duration.ofMinutes(10))
                        .getObjectRequest(g -> g.bucket(BUCKET).key(key)))
            .url()
            .toString();
    String url = s3.utilities().getUrl(g -> g.bucket(BUCKET).key(key)).toString();
    if (!presigned.contains("X-Amz-Signature=") || !url.startsWith(endpoint.toString())) {
      throw new IllegalStateException("Unexpected S3 URLs: " + url);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("endpoint", endpoint.toString());
    result.put("objectUrl", url);
    result.put("presigned", true);
    return result;
  }

  @Override
  public void destroy() {
    presigner.close();
    s3Async.close();
    s3.close();
  }
}
