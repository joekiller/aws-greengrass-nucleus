/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.tes.LazyCredentialProvider;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RetryUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP/HTTPS artifact downloader supporting standard web URLs including CloudFront distributions.
 * Supports resumable downloads using HTTP Range headers, AWS SigV4 request signing, and standard HTTP error handling.
 */
public class HttpDownloader extends ArtifactDownloader {
    static final String CONTENT_LENGTH_HEADER = "content-length";
    private static final List<DeploymentErrorCode> HTTP_DOWNLOAD_ERROR_CODE =
            Arrays.asList(DeploymentErrorCode.HTTP_GET_REQUEST_ERROR,
                    DeploymentErrorCode.HTTP_REQUEST_ERROR);
    private Long artifactSize = null;
    private final LazyCredentialProvider credentialProvider;
    private final DeviceConfiguration deviceConfiguration;
    private final Aws4Signer signer;

    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder()
                    .initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L))
                    .maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class,
                            RetryableServerErrorException.class))
                    .build();

    protected HttpDownloader(ComponentIdentifier identifier, ComponentArtifact artifact,
                             Path artifactDir, ComponentStore componentStore,
                             LazyCredentialProvider credentialProvider,
                             DeviceConfiguration deviceConfiguration) {
        super(identifier, artifact, artifactDir, componentStore);
        this.credentialProvider = credentialProvider;
        this.deviceConfiguration = deviceConfiguration;
        this.signer = Aws4Signer.create();

        // Log security warning for unencrypted HTTP
        if ("http".equalsIgnoreCase(artifact.getArtifactUri().getScheme())) {
            logger.atWarn().log("Using unencrypted HTTP for artifact download. "
                    + "HTTPS is strongly recommended for security.");
        }
    }

    /**
     * Sign an HTTP request using AWS Signature Version 4.
     * Signs the request with device credentials from IoT certificate + role alias.
     *
     * @param request the HTTP request to sign
     * @param serviceName the AWS service name (e.g., "execute-api", "s3")
     * @return signed HTTP request
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private SdkHttpFullRequest signRequest(SdkHttpFullRequest request, String serviceName) {
        try {
            AwsCredentials credentials = credentialProvider.resolveCredentials();
            Region region = Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()));

            Aws4SignerParams signerParams = Aws4SignerParams.builder()
                    .awsCredentials(credentials)
                    .signingName(serviceName)
                    .signingRegion(region)
                    .build();

            return signer.sign(request, signerParams);
        } catch (Exception e) {
            logger.atWarn().setCause(e).log("Failed to sign HTTP request, proceeding with unsigned request");
            return request;
        }
    }

    /**
     * Determine if request signing should be attempted based on URI and configuration.
     * Currently signs all HTTPS requests to AWS service endpoints.
     *
     * @param uri the URI to check
     * @return the service name to use for signing, or null if signing should not be performed
     */
    private String getServiceNameForSigning(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }

        String host = uri.getHost().toLowerCase();

        // Check for API Gateway endpoints
        if (host.contains(".execute-api.") && host.contains(".amazonaws.com")) {
            return "execute-api";
        }

        // Check for S3 endpoints (CloudFront in front of S3)
        if (host.contains(".s3.") && host.contains(".amazonaws.com")) {
            return "s3";
        }

        // Check for CloudFront distributions that might be fronting AWS services
        // For now, don't auto-sign CloudFront URLs as they could be public or use signed URLs
        // Users can extend this logic based on their specific CloudFront setup

        return null; // No signing by default
    }

    @Override
    protected String getArtifactFilename() {
        return getArtifactFilename(artifact);
    }

    protected static String getArtifactFilename(ComponentArtifact artifact) {
        String path = artifact.getArtifactUri().getPath();
        if (path == null || path.isEmpty()) {
            // Fallback to using the host and path as filename if no path component
            String uriString = artifact.getArtifactUri().toString();
            // Remove scheme
            uriString = uriString.replaceFirst("^https?://", "");
            // Replace slashes and special characters with underscores
            return uriString.replaceAll("[/\\\\:*?\"<>|]", "_");
        }
        return Objects.toString(Paths.get(path).getFileName());
    }

    @Override
    public void cleanup() throws IOException {
        // No cleanup needed - artifacts are managed by ComponentStore
    }

    @Override
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    public Long getDownloadSize() throws PackageDownloadException, InterruptedException {
        if (artifactSize != null) {
            return artifactSize;
        }
        try {
            artifactSize = RetryUtils.runWithRetry(clientExceptionRetryConfig,
                    this::getDownloadSizeWithoutRetry,
                    "get-artifact-size",
                    logger);
            return artifactSize;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to get download size"), e)
                    .withErrorContext(e, DeploymentErrorCode.HTTP_HEAD_REQUEST_ERROR);
        }
    }

    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.AvoidCatchingGenericException"})
    private Long getDownloadSizeWithoutRetry() throws InterruptedException, PackageDownloadException, IOException,
            RetryableServerErrorException {
        URI uri = artifact.getArtifactUri();

        try (SdkHttpClient client = getSdkHttpClient()) {
            // Build the HEAD request
            SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                    .uri(uri)
                    .method(SdkHttpMethod.HEAD);

            // Sign request if targeting an AWS service
            SdkHttpFullRequest request = requestBuilder.build();
            String serviceName = getServiceNameForSigning(uri);
            if (serviceName != null) {
                logger.atDebug().kv("service", serviceName).log("Signing HTTP HEAD request with SigV4");
                request = signRequest(request, serviceName);
            }

            HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                    .request(request)
                    .build();
            HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

            int responseCode = executeResponse.httpResponse().statusCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                long length = getContentLengthLong(executeResponse.httpResponse());

                if (length == -1) {
                    // If HEAD doesn't return content-length, try GET and read the stream
                    logger.atWarn().log("HEAD request did not return Content-Length, falling back to GET");
                    return getDownloadSizeUsingGet(uri, client);
                }
                return length;
            } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new PackageDownloadException(
                        getErrorString("Access denied (HTTP 403). Check URL permissions or credentials"),
                        DeploymentErrorCode.HTTP_ACCESS_DENIED);
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new PackageDownloadException(
                        getErrorString("Resource not found (HTTP 404). Verify the URL is correct"),
                        DeploymentErrorCode.HTTP_RESOURCE_NOT_FOUND);
            } else if (RetryUtils.retryErrorCodes(responseCode)) {
                throw new RetryableServerErrorException(
                        "Failed to get download size with retryable error. Error code: " + responseCode);
            } else {
                throw new PackageDownloadException(
                        getErrorString("Failed to get download size. HTTP response: " + responseCode),
                        DeploymentErrorCode.HTTP_HEAD_REQUEST_ERROR);
            }
        }
    }

    private Long getDownloadSizeUsingGet(URI uri, SdkHttpClient client)
            throws IOException, PackageDownloadException {
        // Build the GET request
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .uri(uri)
                .method(SdkHttpMethod.GET);

        // Sign request if targeting an AWS service
        SdkHttpFullRequest request = requestBuilder.build();
        String serviceName = getServiceNameForSigning(uri);
        if (serviceName != null) {
            logger.atDebug().kv("service", serviceName).log("Signing HTTP GET request with SigV4");
            request = signRequest(request, serviceName);
        }

        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(request)
                .build();
        HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

        int responseCode = executeResponse.httpResponse().statusCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            long length = getContentLengthLong(executeResponse.httpResponse());
            // Close the response body since we only wanted the header
            if (executeResponse.responseBody().isPresent()) {
                executeResponse.responseBody().get().close();
            }
            if (length == -1) {
                throw new PackageDownloadException(
                        getErrorString("Failed to get download size from both HEAD and GET requests"),
                        DeploymentErrorCode.HTTP_HEAD_REQUEST_ERROR);
            }
            return length;
        } else {
            throw new PackageDownloadException(
                    getErrorString("Failed to get download size. HTTP response: " + responseCode),
                    DeploymentErrorCode.HTTP_HEAD_REQUEST_ERROR);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        URI uri = artifact.getArtifactUri();

        try {
            return RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
                try (SdkHttpClient client = getSdkHttpClient()) {
                    // Build the GET request with Range header
                    SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                            .uri(uri)
                            .method(SdkHttpMethod.GET)
                            .putHeader(HTTP_RANGE_HEADER_KEY,
                                    String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd));

                    // Sign request if targeting an AWS service
                    SdkHttpFullRequest request = requestBuilder.build();
                    String serviceName = getServiceNameForSigning(uri);
                    if (serviceName != null) {
                        logger.atDebug().kv("service", serviceName).log("Signing HTTP GET request with SigV4");
                        request = signRequest(request, serviceName);
                    }

                    HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                            .request(request)
                            .build();
                    HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

                    int responseCode = executeResponse.httpResponse().statusCode();

                    // HTTP 206 Partial Content - server supports Range header
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        try (InputStream inputStream = executeResponse.responseBody().get()) {
                            long downloaded = download(inputStream, messageDigest);
                            if (downloaded == 0) {
                                // If 0 bytes read, the input stream is likely closed
                                throw new IOException(getErrorString("Failed to read any byte from the stream"));
                            }
                            return downloaded;
                        }
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        // HTTP 200 OK - server doesn't support Range header, returns full content
                        // Try to skip to the offset
                        long length = getContentLengthLong(executeResponse.httpResponse());
                        if (length != -1 && length < rangeEnd) {
                            String errMsg = String.format(
                                    "Artifact size mismatch. Expected artifact size %d. HTTP contentLength %d",
                                    rangeEnd, length);
                            throw new PackageDownloadException(getErrorString(errMsg), HTTP_DOWNLOAD_ERROR_CODE);
                        }

                        logger.atDebug().log("Server does not support Range requests, skipping {} bytes", rangeStart);

                        try (InputStream inputStream = executeResponse.responseBody().get()) {
                            long byteSkipped = inputStream.skip(rangeStart);
                            if (byteSkipped != rangeStart) {
                                throw new PackageDownloadException(
                                        getErrorString("Failed to skip to offset, reached end of stream"),
                                        HTTP_DOWNLOAD_ERROR_CODE);
                            }
                            long downloaded = download(inputStream, messageDigest);
                            if (downloaded == 0) {
                                throw new IOException("Failed to read any byte from the inputStream");
                            }
                            return downloaded;
                        }
                    } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        throw new PackageDownloadException(
                                getErrorString("Access denied (HTTP 403). Check URL permissions or credentials"),
                                DeploymentErrorCode.HTTP_ACCESS_DENIED);
                    } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                        throw new PackageDownloadException(
                                getErrorString("Resource not found (HTTP 404). Verify the URL is correct"),
                                DeploymentErrorCode.HTTP_RESOURCE_NOT_FOUND);
                    } else if (RetryUtils.retryErrorCodes(responseCode)) {
                        throw new RetryableServerErrorException(
                                "Failed to download artifact with retryable error, error code: " + responseCode);
                    } else {
                        throw new PackageDownloadException(
                                getErrorString("Unable to download artifact. HTTP Error: " + responseCode),
                                HTTP_DOWNLOAD_ERROR_CODE);
                    }
                }
            }, "download-artifact", logger);
        } catch (InterruptedException | PackageDownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download HTTP artifact"), e)
                    .withErrorContext(e, DeploymentErrorCode.HTTP_GET_REQUEST_ERROR);
        }
    }

    @Override
    public Optional<String> checkDownloadable() {
        URI uri = artifact.getArtifactUri();

        // Validate URI has a host
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return Optional.of("HTTP/HTTPS URI must have a valid host: " + uri);
        }

        // Validate scheme is http or https
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return Optional.of("URI scheme must be http or https: " + uri);
        }

        return Optional.empty();
    }

    SdkHttpClient getSdkHttpClient() {
        return ProxyUtils.getSdkHttpClientBuilder().build();
    }

    private long getContentLengthLong(SdkHttpResponse sdkHttpResponse) {
        long length = -1L;
        Optional<String> value = sdkHttpResponse.firstMatchingHeader(CONTENT_LENGTH_HEADER);
        try {
            if (value.isPresent()) {
                length = Long.parseLong(value.get());
            }
        } catch (NumberFormatException e) {
            logger.atError().log("Failed to parse content-length from http response", e);
        }
        return length;
    }
}
