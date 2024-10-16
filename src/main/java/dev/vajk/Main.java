
package dev.vajk;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class Main {

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java GoogleImageSearch <search_query> <output_path> <image_count>");
            return;
        }

        String searchQuery = args[0];
        String outputPath = args[1];
        int imageCount = Integer.parseInt(args[2]);
        String apiKeysEnv = System.getenv("GOOGLE_SEARCH_API_KEYS"); // format: {"creds":[{"engineId":"", "apiKey":""}, ...]}

        if (apiKeysEnv == null || apiKeysEnv.isEmpty()) {
            System.err.println("Environment variable GOOGLE_SEARCH_API_KEYS is not set.");
            return;
        }

        JSONArray apiCredentials = new JSONObject(apiKeysEnv).getJSONArray("creds");
        Iterator<Object> credsIterator = apiCredentials.iterator();

        while (credsIterator.hasNext()) {
            JSONObject cred = (JSONObject) credsIterator.next();
            String engineId = cred.getString("engineId");
            String apiKey = cred.getString("apiKey");

            if (searchAndDownloadImages(searchQuery, outputPath, imageCount, engineId, apiKey)) {
                break; // Exit if the download was successful
            }
        }
    }

    public static boolean searchAndDownloadImages(String query, String outputPath, int numImages, String engineId, String apiKey) {
        try {
            int downloadedImages = 0;
            int startIndex = 1;
            int searchLimit = 10;

            ExecutorService executor = Executors.newFixedThreadPool(10);

            while (downloadedImages < numImages) {
                if (numImages - downloadedImages < searchLimit) {
                    searchLimit = numImages - downloadedImages;
                }

                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
                String requestUrl = "https://www.googleapis.com/customsearch/v1?q=" + encodedQuery
                        + "&cx=" + engineId + "&key=" + apiKey
                        + "&searchType=image&start=" + startIndex
                        + "&num=" + searchLimit;

                HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setReadTimeout(3000);
                connection.setConnectTimeout(3000);

                if (connection.getResponseCode() == 429) {
                    System.out.println("Rate limit exceeded, switching API key...");
                    return false; // Return and try the next API key
                }

                try (Scanner scanner = new Scanner(connection.getInputStream())) {
                    String responseBody = scanner.useDelimiter("\\A").next();
                    int imagesDownloadedThisBatch = parseAndDownloadImages(responseBody, outputPath, numImages, downloadedImages, executor);
                    downloadedImages += imagesDownloadedThisBatch;
                    startIndex += imagesDownloadedThisBatch;

                    if (imagesDownloadedThisBatch == 0) {
                        System.out.println("No more images found, ending.");
                        break;
                    }
                }
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                // wait for all tasks to finish
            }

            return true; // Successfully downloaded images

        } catch (IOException e) {
            e.printStackTrace();
            return false; // Continue to next API key in case of an exception
        }
    }

    private static int parseAndDownloadImages(String jsonResponse, String outputPath, int totalImagesNeeded, int alreadyDownloaded, ExecutorService executor) {
        JSONObject jsonObject = new JSONObject(jsonResponse);
        JSONArray items = jsonObject.optJSONArray("items");
        if (items == null) {
            return 0;
        }

        int imagesToDownload = Math.min(items.length(), totalImagesNeeded - alreadyDownloaded);

        for (int i = 0; i < imagesToDownload; i++) {
            final int index = i;
            executor.submit(() -> {
                JSONObject item = items.getJSONObject(index);
                String imageUrl = item.getString("link");
                downloadImage(imageUrl, outputPath);
            });
        }

        return imagesToDownload;
    }

    private static void downloadImage(String imageUrl, String outputPath) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setReadTimeout(3000);
            connection.setConnectTimeout(3000);

            String extension = getFileExtension(connection.getContentType());

            try (InputStream in = connection.getInputStream()) {
                Files.createDirectories(Paths.get(outputPath));
                Files.copy(in, Paths.get(outputPath, UUID.randomUUID() + "." + extension));
                System.out.println("Downloaded: " + imageUrl);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            System.err.println("Failed to download " + imageUrl + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static String getFileExtension(String contentType) {
        switch (contentType) {
            case "image/jpeg":
                return "jpg";
            case "image/png":
                return "png";
            case "image/gif":
                return "gif";
            default:
                return "unsure";
        }
    }
}
