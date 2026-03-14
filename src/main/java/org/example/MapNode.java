package org.example;

import org.jxmapviewer.viewer.GeoPosition;

import java.util.*;

public class MapNode {
    private double lat;
    private double lon;
    private String name = "";        // ← اسم المدينة
    private List<Road> roads;

    // A* values
    public double g;
    public double h;
    public double f;
    public MapNode parent;

    public MapNode(double lat, double lon) {
        this.lat   = lat;
        this.lon   = lon;
        this.roads = new ArrayList<>();
    }

    public void addRoad(Road road)       { roads.add(road); }
    public List<Road> getRoads()         { return roads; }
    public double getIat()               { return lat; }
    public double getLon()               { return lon; }
    public void setName(String name)     { this.name = name; }
    public String getName()              { return name; }

}
