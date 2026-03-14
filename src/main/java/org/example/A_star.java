
import java.util.*;

public class A_star {

    private RoutingMode mode;
    private int nodesExplored;

    public A_star(RoutingMode mode) {
        this.mode = mode;
    }

    public int getNodesExplored() {
        return nodesExplored;
    }

    public List<MapNode> findPath(MapNode start, MapNode goal) {

        nodesExplored = 0;

        PriorityQueue<MapNode> openSet =
                new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        Set<MapNode> closedSet = new HashSet<>();

        start.g = 0;
        start.h = heuristic(start, goal);
        start.f = start.g + start.h;
        start.parent = null;

        openSet.add(start);

        while (!openSet.isEmpty()) {

            MapNode current = openSet.poll();
            nodesExplored++;

            if (current == goal) {
                return reconstructPath(goal);
            }

            closedSet.add(current);

            for (Road road : current.getRoads()) {

                MapNode neighbor = road.getTo();

                if (closedSet.contains(neighbor))
                    continue;

                double cost = getCost(road);

                double tentativeG = current.g + cost;

                if (!openSet.contains(neighbor) || tentativeG < neighbor.g) {

                    neighbor.parent = current;
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(neighbor, goal);
                    neighbor.f = neighbor.g + neighbor.h;

                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor);
                    }
                }
            }
        }

        return null;
    }

    private double getCost(Road road) {

        switch (mode) {

            case SHORTEST:
                return road.getDistance();  //we need getters for this from the Road class ()

            case TRAFFICS:
                return road.getCostWithTraffic();

            case FASTEST:
                return road.getTime();

            default:
                return road.getDistance();
        }
    }

    private double heuristic(MapNode a, MapNode b) {

        double dx = a.getIat() - b.getIat();
        double dy = a.getLon() - b.getLon();

        return Math.sqrt(dx * dx + dy * dy);
    }

    private List<MapNode> reconstructPath(MapNode goal) {

        List<MapNode> path = new ArrayList<>();

        MapNode current = goal;

        while (current != null) {
            path.add(current);
            current = current.parent;
        }

        Collections.reverse(path);

        return path;
    }
}