package com.bakerframework.baker.model;

import android.util.Log;

import com.bakerframework.baker.BakerApplication;
import com.bakerframework.baker.R;
import com.bakerframework.baker.settings.Configuration;
import com.bakerframework.baker.task.DownloadTask;
import com.bakerframework.baker.task.DownloadTaskDelegate;
import com.bakerframework.baker.util.Inventory;
import com.bakerframework.baker.util.SkuDetails;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Created by Tobias Strebitzer <tobias.strebitzer@magloft.com> on 15/12/14.
 * http://www.magloft.com
 */
public class IssueCollection implements DownloadTaskDelegate {

    private HashMap<String, Issue> issueMap;
    private List<String> categories;

    // Tasks management
    private DownloadTask downloadManifestTask;
    private boolean isLoading = false;

    // Data Processing
    String JSON_ENCODING = "utf-8";
    SimpleDateFormat SDF_INPUT = new SimpleDateFormat(BakerApplication.getInstance().getString(R.string.inputDateFormat), Locale.US);
    SimpleDateFormat SDF_OUTPUT = new SimpleDateFormat(BakerApplication.getInstance().getString(R.string.outputDateFormat), Locale.US);

    // Categories
    public static final String ALL_CATEGORIES_STRING = "All Categories";

    // Event callbacks
    private ArrayList<IssueCollectionListener> listeners = new ArrayList<>();

    public IssueCollection() {
        // Initialize issue map
        issueMap = new HashMap<>();
    }

    public List<String> getCategories() {
        return categories;
    }

    public List<String> getSkuList() {
        List<String> skuList = new ArrayList<>();
        for(Issue issue : getIssues()) {
            if(issue.getProductId() != null && !issue.getProductId().equals("")) {
                skuList.add(issue.getProductId());
            }
        }
        return skuList;
    }

    public List<Issue> getIssues() {
        if(isLoading || issueMap == null) {
            return new ArrayList<>();
        }else{
            return new ArrayList<>(issueMap.values());
        }
    }

    public boolean isLoading() {
        return isLoading;
    }

    // Event listeners

    public void addListener(IssueCollectionListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(IssueCollectionListener listener) {
        this.listeners.remove(listener);
    }

    // Reload data from backend
    public void reload() {
        if(!isLoading) {
            isLoading = true;
            downloadManifestTask = new DownloadTask(this, Configuration.getManifestUrl(), getCachedFile());
            downloadManifestTask.execute();
        }else{
            throw new RuntimeException("reload method invoked on Manifest while already downloading data");
        }
    }

    public void processManifestFileFromCache() {
        processManifestFile(getCachedFile());
    }

    private void processManifestFile(File file)  {

        try {

            // Read file
            FileInputStream in = new FileInputStream(file);
            byte[] buffer = new byte[1024];
            StringBuilder data = new StringBuilder("");
            while (in.read(buffer) != -1) {
                data.append(new String(buffer));
            }
            in.close();

            // Create issues
            processJson(new JSONArray(data.toString()));

            // Process categories
            categories = extractAllCategories();

            // Trigger issues loaded event
            for (IssueCollectionListener listener : listeners) {
                listener.onIssueCollectionLoaded();
            }

        } catch (FileNotFoundException e) {
            Log.e(this.getClass().getName(), "processing error (not found): " + e);
        } catch (JSONException e) {
            Log.e(this.getClass().getName(), "processing error (invalid json): " + e);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "processing error (buffer error): " + e);
        } catch (ParseException e) {
            Log.e(this.getClass().getName(), "processing error (parse error): " + e);
        }

    }

    private void processJson(final JSONArray jsonArray) throws JSONException, ParseException, UnsupportedEncodingException {
        JSONObject json;
        JSONArray jsonCategories;
        List<String> categories;
        List<String> issueNameList = new ArrayList<>();

        // Loop through issues
        int length = jsonArray.length();
        for (int i = 0; i < length; i++) {
            json = new JSONObject(jsonArray.getString(i));

            // Get issue data from json
            String issueName = jsonString(json.getString("name"));
            String issueProductId = jsonString(json.getString("product_id"));
            String issueTitle = jsonString(json.getString("title"));
            String issueInfo = jsonString(json.getString("info"));
            String issueDate = jsonDate(json.getString("date"));
            String issueCover = jsonString(json.getString("cover"));
            String issueUrl = jsonString(json.getString("url"));
            int issueSize = json.has("size") ? json.getInt("size") : 0;

            Issue issue;
            if(issueMap.containsKey(issueName)) {
                // Get issue from issue map
                issue = issueMap.get(issueName);
                // Flag fields for update
                if(!issue.getCover().equals(issueCover)) {
                    issue.setCoverChanged(true);
                }
                if(!issue.getUrl().equals(issueUrl)) {
                    issue.setUrlChanged(true);
                }
            }else{
                // Create new issue and store in issue map
                issue = new Issue(issueName);
                issueMap.put(issueName, issue);
            }

            // Set issue data
            issue.setTitle(issueTitle);
            issue.setProductId(issueProductId);
            issue.setInfo(issueInfo);
            issue.setDate(issueDate);
            issue.setCover(issueCover);
            issue.setUrl(issueUrl);
            issue.setSize(issueSize);

            // Set categories
            jsonCategories = json.getJSONArray("categories");
            categories = new ArrayList<>();
            for (int j = 0; j < jsonCategories.length(); j++) {
                categories.add(jsonCategories.get(j).toString());
            }
            issue.setCategories(categories);

            // Add name to issue name list
            issueNameList.add(issueName);

        }

        // Get rid of old issues that are no longer in the manifest
        for(Issue issue : issueMap.values()) {
            if(!issueNameList.contains(issue.getName())) {
                issueMap.remove(issue);
            }
        }

    }

    // Helpers

    private String jsonDate(String value) throws ParseException {
        return SDF_OUTPUT.format(SDF_INPUT.parse(value));
    }

    private String jsonString(String value) throws UnsupportedEncodingException {
        return new String(value.getBytes(JSON_ENCODING), JSON_ENCODING);
    }

    private String getCachedPath() {
        return Configuration.getCacheDirectory() + File.separator + BakerApplication.getInstance().getString(R.string.shelf);
    }

    private File getCachedFile() {
        return new File(getCachedPath());
    }

    public boolean isCacheAvailable() {
        return getCachedFile().exists() && getCachedFile().isFile();
    }

    public void updatePricesFromInventory(Inventory inventory) {
        SkuDetails details;
        for(Issue issue : issueMap.values()) {
            if (inventory.hasDetails(issue.getProductId())) {
                details = inventory.getSkuDetails(issue.getProductId());
                issue.setPrice(details.getPrice());
            }
        }
    }

    public List<String> extractAllCategories() {

        // Collect all categories from issues
        List<String> allCategories = new ArrayList<>();

        for(Issue issue : issueMap.values()) {
            for(String category : issue.getCategories()) {
                if(allCategories.indexOf(category) == -1) {
                    allCategories.add(category);
                }
            }
        }

        // Sort categories
        Collections.sort(allCategories);

        // Append all categories item
        allCategories.add(0, ALL_CATEGORIES_STRING);

        return allCategories;
    }


    public List<Issue> getDownloadingIssues() {
        List<Issue> downloadingIssues = new ArrayList<>();
        for (Issue issue : issueMap.values()) {
            if(issue.isDownloading()) {
                downloadingIssues.add(issue);
            }
        }
        return downloadingIssues;
    }


    public void cancelDownloadingIssues(final List<Issue> downloadingIssues) {
        for (Issue issue : downloadingIssues) {
            if(issue.isDownloading()) {
                issue.cancelDownloadTask();
            }
        }
    }

    // Overrides

    @Override
    public void onDownloadProgress(DownloadTask task, long progress, long bytesSoFar, long totalBytes) {

    }

    @Override
    public void onDownloadComplete(DownloadTask task, File file) {
        isLoading = false;
        processManifestFile(file);
    }

    @Override
    public void onDownloadFailed(DownloadTask task) {
        isLoading = false;
    }

}