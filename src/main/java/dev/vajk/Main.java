
package dev.vajk;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class Main {

    private static final String API_KEY = System.getenv("GOOGLE_SEARCH_API_KEY");
    private static final String SEARCH_ENGINE_ID = System.getenv("GOOGLE_SEARCH_ENGINE_ID");

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java GoogleImageSearch <search_query> <output_path> <image_count>");
            return;
        }

        String searchQuery = args[0];
        String outputPath = args[1];
        int imageCount = Integer.parseInt(args[2]);

        searchAndDownloadImages(searchQuery, outputPath, imageCount);
    }

    public static void searchAndDownloadImages(String query, String outputPath, int numImages) {
        try {
            int downloadedImages = 0;
            int startIndex = 1;

            ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()*2);

            while (downloadedImages < numImages) {
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
                String requestUrl = "https://www.googleapis.com/customsearch/v1?q=" + encodedQuery
                        + "&cx=" + SEARCH_ENGINE_ID + "&key=" + API_KEY
                        + "&searchType=image&start=" + startIndex;
                HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setReadTimeout(3000);
                connection.setConnectTimeout(3000);

                try (Scanner scanner = new Scanner(connection.getInputStream())) {
                    String responseBody = scanner.useDelimiter("\\A").next();
                    int imagesDownloadedThisBatch = parseAndDownloadImages(responseBody, outputPath, numImages, downloadedImages, executor);
                    downloadedImages += imagesDownloadedThisBatch;
                    startIndex += imagesDownloadedThisBatch;

                    if (imagesDownloadedThisBatch == 0) {
                        System.out.println("No more images found, ending.");
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("Failed to download images from: " + requestUrl);
                    e.printStackTrace();
                    break;
                }
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                // wait for all tasks to finish
            }

        } catch (IOException e) {
            e.printStackTrace();
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
                return "unsure.jpg";
        }
    }
}
