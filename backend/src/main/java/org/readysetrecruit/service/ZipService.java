package org.readysetrecruit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.readysetrecruit.model.ZipLocation;
import org.readysetrecruit.repo.ZipLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

// zip code (or city + state) -> coordinates.
// tries our own GeoNames data first (GeoData), and if it's not in there asks api.zippopotam.us (free, no key)
// and saves the answer in the zip_locations table so we only ask once
@Service
public class ZipService {
    private static final Logger log = LoggerFactory.getLogger(ZipService.class);
    private static final double EARTH_RADIUS_MILES = 3958.8;

    private final ZipLocationRepository repo;
    private final GeoData geo;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ZipService(ZipLocationRepository repo, GeoData geo, ObjectMapper json) {
        this.repo = repo;
        this.geo = geo;
        this.json = json;
    }

    public Optional<ZipLocation> lookup(String zip) {
        if (zip == null) return Optional.empty();
        String z = zip.trim();
        if (z.length() > 5) z = z.substring(0, 5); // ZIP+4 -> ZIP
        if (!z.matches("\\d{5}")) return Optional.empty();
        Optional<ZipLocation> cached = repo.findById(z);
        if (cached.isPresent()) return cached;
        Optional<GeoData.Place> bundled = geo.zip(z);
        if (bundled.isPresent()) return bundled.map(ZipService::toLocation);
        try {
            JsonNode root = get("https://api.zippopotam.us/us/" + z);
            if (root == null || !root.has("places") || root.get("places").isEmpty()) return Optional.empty();
            JsonNode p = root.get("places").get(0);
            ZipLocation loc = new ZipLocation(z, p.get("latitude").asDouble(), p.get("longitude").asDouble(),
                    p.path("place name").asText(null), p.path("state abbreviation").asText(null));
            return Optional.of(repo.save(loc));
        } catch (Exception e) {
            log.warn("ZIP lookup failed for {}: {}", z, e.toString());
            return Optional.empty();
        }
    }

    // for imports that only give us a city and no zip (FTC teams)
    public Optional<ZipLocation> lookupCity(String stateAbbrev, String city) {
        if (TextUtil.isBlank(stateAbbrev) || TextUtil.isBlank(city) || stateAbbrev.trim().length() != 2) return Optional.empty();
        Optional<GeoData.Place> bundled = geo.city(stateAbbrev, city);
        if (bundled.isPresent()) return bundled.map(ZipService::toLocation);
        try {
            String url = "https://api.zippopotam.us/us/" + stateAbbrev.trim().toLowerCase() + "/"
                    + URLEncoder.encode(city.trim().toLowerCase(), StandardCharsets.UTF_8).replace("+", "%20");
            JsonNode root = get(url);
            if (root == null || !root.has("places") || root.get("places").isEmpty()) return Optional.empty();
            JsonNode p = root.get("places").get(0);
            String zip = p.path("post code").asText();
            ZipLocation loc = new ZipLocation(zip, p.get("latitude").asDouble(), p.get("longitude").asDouble(),
                    city.trim(), stateAbbrev.trim().toUpperCase());
            return Optional.of(repo.findById(zip).orElseGet(() -> repo.save(loc)));
        } catch (Exception e) {
            log.warn("City lookup failed for {}, {}: {}", city, stateAbbrev, e.toString());
            return Optional.empty();
        }
    }

    private static ZipLocation toLocation(GeoData.Place p) {
        return new ZipLocation(p.zip(), p.lat(), p.lng(), p.city(), p.state());
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        return json.readTree(res.body());
    }

    public static double distanceMiles(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
