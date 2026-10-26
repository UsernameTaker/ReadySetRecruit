package org.readysetrecruit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Offline US geocoding from bundled GeoNames data (CC BY 4.0, geonames.org):
 *  - geo/us-zips.tsv.gz:   zip, lat, lng, place name, state
 *  - geo/us-cities.tsv.gz: state, city, lat, lng, ascii name (towns with 500+ people, catches suburbs that
 *    aren't postal place names)
 * Lets imports place thousands of teams without calling a web API for each one.
 */
@Component
public class GeoData {
    private static final Logger log = LoggerFactory.getLogger(GeoData.class);

    public record Place(String zip, double lat, double lng, String city, String state) {}

    private volatile Map<String, Place> byZip;
    private volatile Map<String, Place> byCity;

    public Optional<Place> zip(String zip) {
        load();
        return Optional.ofNullable(byZip.get(zip));
    }

    public Optional<Place> city(String state, String city) {
        if (TextUtil.isBlank(state) || TextUtil.isBlank(city)) return Optional.empty();
        load();
        return Optional.ofNullable(byCity.get(key(state, city)));
    }

    private synchronized void load() {
        if (byZip != null) return;
        Map<String, Place> zips = new HashMap<>(50_000);
        Map<String, Place> cities = new HashMap<>(80_000);
        try {
            read("geo/us-zips.tsv.gz", p -> {
                Place place = new Place(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), p[3], p[4]);
                zips.put(place.zip(), place);
                cities.putIfAbsent(key(place.state(), place.city()), place); // first ZIP of a postal city
            });
            read("geo/us-cities.tsv.gz", p -> {
                Place place = new Place(null, Double.parseDouble(p[2]), Double.parseDouble(p[3]), p[1], p[0]);
                cities.putIfAbsent(key(p[0], p[1]), place);
                cities.putIfAbsent(key(p[0], p[4]), place);
            });
        } catch (Exception e) {
            log.warn("Couldn't load bundled geo data: {}", e.toString());
        }
        byCity = cities;
        byZip = zips;
    }

    private interface RowHandler { void row(String[] cols); }

    private static void read(String resource, RowHandler handler) throws Exception {
        try (InputStream in = GeoData.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String[] cols = line.split("\t", -1);
                if (cols.length >= 5) handler.row(cols);
            }
        }
    }

    /** "St. Louis, MO" / "saint louis" / "Ft Worth" -> comparable keys. */
    static String key(String state, String city) {
        String c = city.toLowerCase(Locale.ROOT).trim()
                .replaceFirst(",\\s*[a-z]{2}$", "")            // "Galesville, WI"
                .replaceFirst(",\\s*city of$", "")             // "Ann Arbor, City of"
                .replaceFirst("\\s+(township|twp\\.?|village|borough)$", "")
                .replaceFirst("^(st|ste)\\.?\\s+", "saint ")
                .replaceFirst("^ft\\.?\\s+", "fort ")
                .replaceFirst("^mt\\.?\\s+", "mount ")
                .replaceAll("\\s(st|ste)\\.?\\s", " saint ")
                .replaceAll("[^a-z0-9]+", " ").trim();
        return state.trim().toUpperCase(Locale.ROOT) + "|" + c;
    }
}
