/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.iot.evergreen.packagemanager.exceptions.InvalidArtifactUriException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.util.S3SdkClientFactory;
import com.aws.iot.evergreen.util.Utils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

/**
 * Downloads component artifacts from S3 bucket URI specified in the component recipe.
 */
public class S3Downloader implements ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(S3Downloader.class);
    private static final Pattern S3_PATH_REGEX = Pattern.compile("s3:\\/\\/([^\\/]+)\\/(.*)");
    private static final String ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT =
            "Failed to download artifact %s for component %s-%s, reason: %s";
    private final S3Client s3Client;
    private final S3SdkClientFactory s3ClientFactory;

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     */
    @Inject
    public S3Downloader(S3SdkClientFactory clientFactory) {
        this.s3Client = clientFactory.getS3Client();
        this.s3ClientFactory = clientFactory;
    }

    @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
    @Override
    public File downloadToPath(PackageIdentifier packageIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException, InvalidArtifactUriException {

        logger.atInfo().setEventType("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactUri", artifact.getArtifactUri()).log();

        // Parse artifact path
        Matcher s3PathMatcher = getS3PathMatcherForURI(artifact.getArtifactUri(), packageIdentifier);
        String bucket = s3PathMatcher.group(1);
        String key = s3PathMatcher.group(2);

        try {
            // Get artifact from S3
            // TODO : Calculating hash for integrity check needs the whole object in memory,
            //  However it could be an issue in the case of large files, need to evaluate if
            //  there's a way to get around this
            byte[] artifactObject = getObject(bucket, key, artifact, packageIdentifier);

            // TODO : There is ongoing discussion on whether integrity check should be made mandatory
            //  and who should own calculating checksums i.e. customer vs greengrass cloud. Until
            //  that is resolved, integrity check here is made optional, it will be performed only if
            //  the downloaded recipe has checksum that can be used for validation
            // Perform integrity check
            if (!Utils.isEmpty(artifact.getChecksum()) && !Utils.isEmpty(artifact.getAlgorithm())) {
                performIntegrityCheck(artifactObject, artifact, packageIdentifier);
            }

            // Save file to store
            Files.write(saveToPath.resolve(extractFileName(key)), artifactObject, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return saveToPath.resolve(extractFileName(key)).toFile();
        } catch (PackageDownloadException e) {
            if (e instanceof ArtifactChecksumMismatchException || !saveToPath.resolve(extractFileName(key)).toFile()
                    .exists()) {
                throw e;
            }
            logger.atInfo("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
            return saveToPath.resolve(extractFileName(key)).toFile();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private byte[] getObject(String bucket, String key, ComponentArtifact artifact, PackageIdentifier packageIdentifier)
            throws PackageDownloadException {
        try {
            GetBucketLocationRequest getBucketLocationRequest =
                    GetBucketLocationRequest.builder().bucket(bucket).build();
            String region = s3Client.getBucketLocation(getBucketLocationRequest).locationConstraintAsString();
            // If the region is empty, it is us-east-1
            S3Client regionClient =
                    s3ClientFactory.getClientForRegion(Utils.isEmpty(region) ? Region.US_EAST_1 : Region.of(region));
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
            return regionClient.getObjectAsBytes(getObjectRequest).asByteArray();
        } catch (S3Exception e) {
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifact.getArtifactUri(),
                            packageIdentifier.getName(), packageIdentifier.getVersion().toString(),
                            "Failed to get artifact object from S3"), e);
        }
    }

    private Matcher getS3PathMatcherForURI(URI artifactURI, PackageIdentifier packageIdentifier)
            throws InvalidArtifactUriException {
        Matcher s3PathMatcher = S3_PATH_REGEX.matcher(artifactURI.toString());
        if (!s3PathMatcher.matches()) {
            // Bad URI
            throw new InvalidArtifactUriException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactURI, packageIdentifier.getName(),
                            packageIdentifier.getVersion().toString(), "Invalid artifact URI"));
        }
        return s3PathMatcher;
    }

    private void performIntegrityCheck(byte[] artifactObject, ComponentArtifact artifact,
                                       PackageIdentifier packageIdentifier) throws PackageDownloadException {
        try {
            String digest = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance(artifact.getAlgorithm()).digest(artifactObject));
            if (!digest.equals(artifact.getChecksum())) {
                // Handle failure in integrity check
                throw new ArtifactChecksumMismatchException(
                        String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifact.getArtifactUri(),
                                packageIdentifier.getName(), packageIdentifier.getVersion().toString(),
                                "Integrity check for downloaded artifact failed"));
            }
            logger.atDebug().setEventType("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri()).log("Passed integrity check");
        } catch (NoSuchAlgorithmException e) {
            throw new ArtifactChecksumMismatchException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifact.getArtifactUri(),
                            packageIdentifier.getName(), packageIdentifier.getVersion().toString(),
                            "Algorithm requested for artifact checksum is not supported"), e);
        }
    }

    private String extractFileName(String objectKey) {
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }
}

