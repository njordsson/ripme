package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AlbumRipper;
import com.rarchives.ripme.ripper.DownloadThreadPool;
import com.rarchives.ripme.utils.Base64;
import com.rarchives.ripme.utils.Utils;

public class FlickrRipper extends AlbumRipper {

    private static final String DOMAIN = "flickr.com",
                                HOST   = "flickr";
    private static final Logger logger = Logger.getLogger(FlickrRipper.class);

    private DownloadThreadPool flickrThreadPool;
    private Document albumDoc = null;

    public FlickrRipper(URL url) throws IOException {
        super(url);
        flickrThreadPool = new DownloadThreadPool();
    }

    @Override
    public String getHost() {
        return HOST;
    }

    public URL sanitizeURL(URL url) throws MalformedURLException {
        return url;
    }
    
    public String getAlbumTitle(URL url) throws MalformedURLException {
        if (!url.toExternalForm().contains("/sets/")) {
            return super.getAlbumTitle(url);
        }
        try {
            // Attempt to use album title as GID
            if (albumDoc == null) {
                albumDoc = Jsoup.connect(url.toExternalForm()).get();
            }
            String user = url.toExternalForm();
            user = user.substring(user.indexOf("/photos/") + "/photos/".length());
            user = user.substring(0, user.indexOf("/"));
            String title = albumDoc.select("meta[name=description]").get(0).attr("content");
            if (!title.equals("")) {
                return HOST + "_" + user + "_" + title;
            }
        } catch (Exception e) {
            // Fall back to default album naming convention
        }
        return super.getAlbumTitle(url);
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p; Matcher m;

        // Root:  https://www.flickr.com/photos/115858035@N04/
        // Album: https://www.flickr.com/photos/115858035@N04/sets/72157644042355643/
        
        final String userRegex = "[a-zA-Z0-9@]+";
        // Album
        p = Pattern.compile("^https?://[wm.]*flickr.com/photos/(" + userRegex + ")/sets/([0-9]+)/?.*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1) + "_" + m.group(2);
        }

        // User page
        p = Pattern.compile("^https?://[wm.]*flickr.com/photos/(" + userRegex + ").*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }

        throw new MalformedURLException(
                "Expected flickr.com URL formats: "
                        + "flickr.com/photos/username or "
                        + "flickr.com/photos/username/sets/albumid"
                        + " Got: " + url);
    }

    @Override
    public void rip() throws IOException {
        //Map<String,String> cookies = signinToFlickr();
        Set<String> attempted = new HashSet<String>();
        int index = 0, page = 1;
        String nextURL = this.url.toExternalForm();
        while (true) {
            if (isStopped()) {
                break;
            }
            logger.info("    Retrieving " + nextURL);
            if (albumDoc == null) {
                albumDoc = Jsoup.connect(nextURL)
                                .get();
            }
            for (Element thumb : albumDoc.select("a[data-track=photo-click]")) {
                String imageTitle = null;
                if (thumb.hasAttr("title")) {
                    imageTitle = thumb.attr("title");
                }
                String imagePage = thumb.attr("href");
                if (imagePage.startsWith("/")) {
                    imagePage = "http://www.flickr.com" + imagePage;
                }
                if (imagePage.contains("/in/")) {
                    imagePage = imagePage.substring(0, imagePage.indexOf("/in/") + 1);
                }
                if (!imagePage.endsWith("/")) {
                    imagePage += "/";
                }
                imagePage += "sizes/o/";

                // Check for duplicates
                if (attempted.contains(imagePage)) {
                    continue;
                }
                attempted.add(imagePage);

                index += 1;
                // Add image page to threadpool to grab the image & download it
                FlickrImageThread mit = new FlickrImageThread(new URL(imagePage), imageTitle, index);
                flickrThreadPool.addThread(mit);
            }
            // Find how many pages there are
            int lastPage = 0;
            for (Element apage : albumDoc.select("a[data-track^=page-]")) {
                String lastPageStr = apage.attr("data-track").replace("page-", "");
                lastPage = Integer.parseInt(lastPageStr);
            }
            // If we're at the last page, stop.
            if (page >= lastPage) {
                break;
            }
            // Load the next page
            page++;
            albumDoc = null;
            nextURL = this.url.toExternalForm();
            if (!nextURL.endsWith("/")) {
                nextURL += "/";
            }
            nextURL += "page" + page + "/";
            // Wait a bit
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting to load next page " + nextURL, e);
                break;
            }
        }
        flickrThreadPool.waitForThreads();
        waitForThreads();
    }

    public boolean canRip(URL url) {
        return url.getHost().endsWith(DOMAIN);
    }

    /**
     * Login to Flickr.
     * @return Cookies for logged-in session
     * @throws IOException
     */
    @SuppressWarnings("unused")
    private Map<String,String> signinToFlickr() throws IOException {
        Response resp = Jsoup.connect("http://www.flickr.com/signin/")
                            .userAgent(USER_AGENT)
                            .followRedirects(true)
                            .method(Method.GET)
                            .execute();
        Document doc = resp.parse();
        Map<String,String> postData = new HashMap<String,String>();
        for (Element input : doc.select("input[type=hidden]")) {
            postData.put(input.attr("name"),  input.attr("value"));
        }
        postData.put("passwd_raw",  "");
        postData.put(".save",   "");
        postData.put("login",   new String(Base64.decode("bGVmYWtlZGVmYWtl")));
        postData.put("passwd",  new String(Base64.decode("MUZha2V5ZmFrZQ==")));
        String action = doc.select("form[method=post]").get(0).attr("action");
        resp = Jsoup.connect(action)
                             .cookies(resp.cookies())
                             .data(postData)
                             .method(Method.POST)
                             .execute();
        return resp.cookies();
    }

    /**
     * Helper class to find and download images found on "image" pages
     */
    private class FlickrImageThread extends Thread {
        private URL    url;
        private String title;
        private int    index;

        public FlickrImageThread(URL url, String title, int index) {
            super();
            this.url = url;
            this.title = title;
            this.index = index;
        }

        @Override
        public void run() {
            try {
                Document doc = getLargestImagePageDocument(this.url);
                Elements fullsizeImages = doc.select("div#allsizes-photo img");
                if (fullsizeImages.size() == 0) {
                    logger.error("Could not find flickr image at " + doc.location() + " - missing 'div#allsizes-photo img'");
                    return;
                }
                else {
                    String prefix = String.format("%03d_%s_", index, Utils.filesystemSafe(title));
                    addURLToDownload(new URL(fullsizeImages.get(0).attr("src")), prefix);
                }
            } catch (IOException e) {
                logger.error("[!] Exception while loading/parsing " + this.url, e);
            }
        }
        
        private Document getLargestImagePageDocument(URL url) throws IOException {
            // Get current page
            Document doc = Jsoup.connect(url.toExternalForm())
                    .userAgent(USER_AGENT)
                    .get();
            // Look for larger image page
            String largestImagePage = this.url.toExternalForm();
            for (Element olSize : doc.select("ol.sizes-list > li > ol > li")) {
                Elements ola = olSize.select("a");
                if (ola.size() == 0) {
                    largestImagePage = this.url.toExternalForm();
                }
                else {
                    String candImage = ola.get(0).attr("href");
                    if (candImage.startsWith("/")) {
                        candImage = "http://www.flickr.com" + candImage;
                    }
                    largestImagePage = candImage;
                }
            }
            if (!largestImagePage.equals(this.url.toExternalForm())) {
                // Found larger image page, get it.
                doc = Jsoup.connect(largestImagePage)
                        .userAgent(USER_AGENT)
                        .get();
            }
            return doc;
        }
    }
}