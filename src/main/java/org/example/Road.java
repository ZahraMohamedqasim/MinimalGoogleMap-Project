package org.example;

import java.util.Random;
public class Road {

    private MapNode from; // the start from where I am
    private MapNode to;// the gaol where the end of path
    private double distance;
    private double trafficFactor; //traffics percentage
    private double speed; //  for the  fastest road

    public Road(MapNode from, MapNode to,
                double distance,
                double trafficFactor,
                double speed) {

        this.from = from;
        this.to = to;
        this.distance = distance;
        this.trafficFactor = trafficFactor;
        this.speed = speed;
    }

    public MapNode getTo() {
        return to;
    }

    public double getDistance() {
        return distance;
    }

    public double getTrafficFactor() {
        return trafficFactor;
    }

    public double getSpeed() {
        return speed;
    }

    // cost with the traffics
    public double getCostWithTraffic() {
        return distance * trafficFactor;
    }

    // the speed with the traffics
    public double getTime() {
        return distance / speed;
    }


    public void regenerateTraffic() {

        Random rand = new Random();

        /*value from 1 to 3 (1 : small traffics
                              2: average traffics
                               3: many traffics )
                               */
        this.trafficFactor = 1 + rand.nextInt(3);

    }
}