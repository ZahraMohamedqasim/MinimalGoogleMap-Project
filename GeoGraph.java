package model;

import java.util.*;

public class GeoGraph {

    private List<MapNode> nodes;

    public GeoGraph() {
        nodes = new ArrayList<>();
    }

    public void addNode(MapNode node) {
        nodes.add(node);
    }

    //road with two paths
    public void connectTwoWays(MapNode a, MapNode b,
                               double distance,
                               double trafficFactor,
                               double speed) {

        Road r1 = new Road(a, b, distance, trafficFactor, speed);
        Road r2 = new Road(b, a, distance, trafficFactor, speed);

        a.addRoad(r1);
        b.addRoad(r2);
    }

    // road with one path
    public void connectOneWay(MapNode from, MapNode to,
                              double distance,
                              double trafficFactor,
                              double speed) {

        Road r = new Road(from, to, distance, trafficFactor, speed);

        from.addRoad(r);
    }

    public List<MapNode> getNodes() {
        return nodes;
    }
    public void regenerateAllTraffic() {

        for (MapNode node : nodes) {

            for (Road road : node.getRoads()) {

                road.regenerateTraffic();

            }
        }
    }
}