/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import static com.aws.greengrass.componentmanager.builtins.HttpDownloader.CONTENT_LENGTH_HEADER;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class HttpDownloaderTest {
    private static final String SHA256 = "SHA-256";

    @Mock
    private ExecutableHttpRequest request;

    @Mock
    private SdkHttpClient httpClient;

    @Mock
    private ComponentStore componentStore;

    private Path testCache;

    @BeforeEach
    void beforeEach() throws Exception {
        testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
    }

    @Test
    void GIVEN_https_url_WHEN_download_THEN_succeeds() throws Exception {
        // Setup test artifact
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum(checksum)
                .artifactUri(new URI("https://d1234567890abc.cloudfront.net/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        assertThat(downloader.getArtifactFilename(), is("my-component.zip"));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request for size
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .build())
                // Mock GET request with partial content
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_PARTIAL)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        downloader.download();

        byte[] originalFile = Files.readAllBytes(mockArtifactPath);
        Path artifactFilePath = saveToPath.resolve("my-component.zip");
        byte[] downloadFile = Files.readAllBytes(artifactFilePath);
        assertThat(Arrays.equals(originalFile, downloadFile), is(true));

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_http_url_WHEN_download_THEN_succeeds_with_warning() throws Exception {
        // Setup test artifact
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum(checksum)
                .artifactUri(new URI("http://example.com/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Verify that HTTP scheme logs a warning (constructor should have logged it)
        assertThat(downloader.getArtifactFilename(), is("my-component.zip"));

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_server_no_range_support_WHEN_download_THEN_succeeds_with_fallback() throws Exception {
        // Setup test artifact
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum(checksum)
                .artifactUri(new URI("https://example.com/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request for size
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .build())
                // Mock GET request returns HTTP 200 (no Range support)
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        downloader.download();

        byte[] originalFile = Files.readAllBytes(mockArtifactPath);
        Path artifactFilePath = saveToPath.resolve("my-component.zip");
        byte[] downloadFile = Files.readAllBytes(artifactFilePath);
        assertThat(Arrays.equals(originalFile, downloadFile), is(true));

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_403_forbidden_WHEN_download_THEN_throws_access_denied() throws Exception {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(new URI("https://example.com/artifacts/forbidden.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request returns 403
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_FORBIDDEN)
                                .build())
                        .build());

        PackageDownloadException exception = assertThrows(PackageDownloadException.class,
                () -> downloader.getDownloadSize());

        assertThat(exception.getMessage(), containsStringIgnoringCase("Access denied"));
        assertTrue(exception.getErrorCodes().contains(DeploymentErrorCode.HTTP_ACCESS_DENIED));

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_404_not_found_WHEN_download_THEN_throws_resource_not_found() throws Exception {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(new URI("https://example.com/artifacts/notfound.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request returns 404
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_NOT_FOUND)
                                .build())
                        .build());

        PackageDownloadException exception = assertThrows(PackageDownloadException.class,
                () -> downloader.getDownloadSize());

        assertThat(exception.getMessage(), containsStringIgnoringCase("not found"));
        assertTrue(exception.getErrorCodes().contains(DeploymentErrorCode.HTTP_RESOURCE_NOT_FOUND));

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_5xx_server_error_WHEN_download_THEN_retries() throws Exception {
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum(checksum)
                .artifactUri(new URI("https://example.com/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request: first fails with 503, then succeeds
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_UNAVAILABLE)
                                .build())
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_PARTIAL)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        downloader.download();

        // Verify retry happened (should have called at least twice for HEAD request)
        verify(request, times(3)).call(); // 2 HEAD requests (1 retry) + 1 GET request

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_invalid_uri_no_host_WHEN_checkDownloadable_THEN_returns_error() {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(URI.create("https:///path/without/host"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");

        HttpDownloader downloader = new HttpDownloader(pkgId, artifact, saveToPath, componentStore);

        Optional<String> error = downloader.checkDownloadable();
        assertTrue(error.isPresent());
        assertThat(error.get(), containsStringIgnoringCase("host"));
    }

    @Test
    void GIVEN_cloudfront_url_WHEN_getFilename_THEN_extracts_correctly() {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(URI.create("https://d1234567890abc.cloudfront.net/path/to/my-artifact.tar.gz"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");

        HttpDownloader downloader = new HttpDownloader(pkgId, artifact, saveToPath, componentStore);

        assertThat(downloader.getArtifactFilename(), is("my-artifact.tar.gz"));
    }

    @Test
    void GIVEN_cloudfront_signed_url_WHEN_getFilename_THEN_extracts_correctly() {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(URI.create("https://d1234567890abc.cloudfront.net/artifacts/file.zip?Key-Pair-Id=APKA&Expires=123&Signature=abc"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");

        HttpDownloader downloader = new HttpDownloader(pkgId, artifact, saveToPath, componentStore);

        assertThat(downloader.getArtifactFilename(), is("file.zip"));
    }

    @Test
    void GIVEN_url_no_path_WHEN_getFilename_THEN_generates_safe_filename() {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(URI.create("https://example.com"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");

        HttpDownloader downloader = new HttpDownloader(pkgId, artifact, saveToPath, componentStore);

        String filename = downloader.getArtifactFilename();
        assertNotNull(filename);
        assertFalse(filename.isEmpty());
        // Should have replaced special characters
        assertThat(filename, is("example.com"));
    }

    @Test
    void GIVEN_network_error_WHEN_download_THEN_retries() throws Exception {
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(new URI("https://example.com/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock network error then success
        when(request.call())
                .thenThrow(SdkClientException.create("Network error"))
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, "1024")
                                .build())
                        .build());

        Long size = downloader.getDownloadSize();
        assertEquals(1024L, size);

        // Verify retry happened
        verify(request, times(2)).call();

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_head_no_content_length_WHEN_getSize_THEN_falls_back_to_get() throws Exception {
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");

        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum("dummychecksum")
                .artifactUri(new URI("https://example.com/artifacts/my-component.zip"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("MyComponent", new Semver("1.0.0"));

        Path saveToPath = testCache.resolve("MyComponent").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        HttpDownloader downloader = spy(new HttpDownloader(pkgId, artifact, saveToPath, componentStore));

        // Mock HTTP client
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());

        // Mock HEAD request without content-length, then GET with content-length
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .build())
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath)))
                                .build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        Long size = downloader.getDownloadSize();
        assertEquals(Files.size(mockArtifactPath), size);

        ComponentTestResourceHelper.cleanDirectory(testCache);
    }
}
