package org.readysetrecruit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cached ZIP code centroid (looked up once from a free geocoding API, then stored). */
@Entity
@Table(name = "zip_locations")
public class ZipLocation {
    @Id
    private String zip;
    private double latitude;
    private double longitude;
    private String city;
    private String state;

    public ZipLocation() {}
    public ZipLocation(String zip, double latitude, double longitude, String city, String state) {
        this.zip = zip;
        this.latitude = latitude;
        this.longitude = longitude;
        this.city = city;
        this.state = state;
    }

    public String getZip() { return zip; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getCity() { return city; }
    public String getState() { return state; }
}
