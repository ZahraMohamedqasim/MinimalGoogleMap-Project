package org.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

public class MapGUI extends JPanel {

    private static final double LAT_MIN = 29.0, LAT_MAX = 37.5;
    private static final double LON_MIN = 38.5, LON_MAX = 49.0;

    private GeoGraph graph;
    private List<MapNode> allNodes    = new ArrayList<>();
    private MapNode startNode  = null;
    private MapNode goalNode   = null;
    private List<MapNode> currentPath = new ArrayList<>();
    private RoutingMode mode   = RoutingMode.SHORTEST;
    private boolean autoTraffic = false;

    private long   lastAlgoTime      = 0;
    private int    lastNodesExplored = 0;
    private double lastDistance      = 0;
    private double lastTravelTime    = 0;

    private JLabel statusLabel, infoLabel;
    private JLabel statDist, statTravel, statNodes, statTime;

    private double scale   = 1.0;
    private int    offsetX = 0;
    private int    offsetY = 0;
    private int    dragStartX, dragStartY;

    public MapGUI() {
        graph = new GeoGraph();
        setBackground(new Color(15, 22, 35));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    MapNode nearest = findNearestNode(e.getX(), e.getY());
                    if (nearest == null) return;
                    if (startNode == null) {
                        startNode = nearest;
                        statusLabel.setText("Start: " + nearest.getName() + "  |  Now click destination");
                        infoLabel.setText("");
                    } else if (goalNode == null && nearest != startNode) {
                        goalNode = nearest;
                        statusLabel.setText("Calculating...");
                        runAStar();
                    } else {
                        resetPath();
                    }
                    repaint();
                }
            }
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    dragStartX = e.getX() - offsetX;
                    dragStartY = e.getY() - offsetY;
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0) {
                    offsetX = e.getX() - dragStartX;
                    offsetY = e.getY() - dragStartY;
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> {
            double f = e.getWheelRotation() < 0 ? 1.15 : 0.87;
            scale = Math.max(0.4, Math.min(8.0, scale * f));
            repaint();
        });

        new javax.swing.Timer(5000, e -> {
            if (autoTraffic) {
                graph.regenerateAllTraffic();
                if (startNode != null && goalNode != null) runAStar();
                repaint();
            }
        }).start();

        buildIraqMap();
    }

    // ── Coordinate conversion ─────────────────────
    private int lonToX(double lon) {
        int w = Math.max(getWidth(), 900);
        return (int) (((lon - LON_MIN) / (LON_MAX - LON_MIN) * w) * scale + offsetX);
    }

    private int latToY(double lat) {
        int h = Math.max(getHeight(), 700);
        return (int) (((1.0 - (lat - LAT_MIN) / (LAT_MAX - LAT_MIN)) * h) * scale + offsetY);
    }

    // ── Paint ─────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawBackground(g2);
        drawGrid(g2);
        drawRoads(g2);
        drawPath(g2);
        drawNodes(g2);
        drawHint(g2);
    }

    private void drawBackground(Graphics2D g2) {
        g2.setColor(new Color(15, 22, 35));
        g2.fillRect(0, 0, getWidth(), getHeight());
        // Iraq land
        double[][] border = {
                {48.5,29.6},{47.5,30.5},{46.5,31.5},{45.5,32.5},
                {44.5,33.5},{43.5,34.5},{42.5,35.5},{41.5,36.5},
                {40.5,37.0},{39.5,37.3},{38.8,36.8},{38.6,36.0},
                {38.9,35.0},{39.0,34.0},{39.5,33.0},{40.5,31.5},
                {41.5,30.5},{43.0,29.8},{45.0,29.4},{47.0,29.5},{48.5,29.6}
        };
        int[] bx = new int[border.length], by = new int[border.length];
        for (int i = 0; i < border.length; i++) {
            bx[i] = lonToX(border[i][0]); by[i] = latToY(border[i][1]);
        }
        g2.setColor(new Color(25, 38, 52));
        g2.fillPolygon(bx, by, border.length);
        g2.setColor(new Color(80, 120, 160, 140));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawPolygon(bx, by, border.length);
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(255,255,255,10));
        g2.setStroke(new BasicStroke(0.5f));
        for (double lat = 29; lat <= 38; lat++) {
            g2.drawLine(lonToX(38.0), latToY(lat), lonToX(49.5), latToY(lat));
        }
        for (double lon = 38; lon <= 49; lon++) {
            g2.drawLine(lonToX(lon), latToY(28.0), lonToX(lon), latToY(38.5));
        }
    }

    private void drawRoads(Graphics2D g2) {
        Set<String> drawn = new HashSet<>();
        for (MapNode node : allNodes) {
            for (Road road : node.getRoads()) {
                MapNode to = road.getTo();
                int id1 = System.identityHashCode(node);
                int id2 = System.identityHashCode(to);
                String key = Math.min(id1,id2) + "_" + Math.max(id1,id2);
                if (drawn.contains(key)) continue;
                drawn.add(key);
                int x1 = lonToX(node.getLon()), y1 = latToY(node.getIat());
                int x2 = lonToX(to.getLon()),   y2 = latToY(to.getIat());
                double t = road.getTrafficFactor();
                Color c = t <= 1.3 ? new Color(30,160,30,200)
                        : t <= 2.0 ? new Color(220,130,0,200)
                        :            new Color(200,30,30,200);
                g2.setColor(new Color(0,0,0,60));
                g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x1+1, y1+1, x2+1, y2+1);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x1, y1, x2, y2);
            }
        }
    }

    private void drawPath(Graphics2D g2) {
        if (currentPath == null || currentPath.size() < 2) return;
        g2.setColor(new Color(80, 160, 255, 60));
        g2.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < currentPath.size()-1; i++) {
            g2.drawLine(lonToX(currentPath.get(i).getLon()), latToY(currentPath.get(i).getIat()),
                    lonToX(currentPath.get(i+1).getLon()), latToY(currentPath.get(i+1).getIat()));
        }
        g2.setColor(new Color(30, 120, 255));
        g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < currentPath.size()-1; i++) {
            g2.drawLine(lonToX(currentPath.get(i).getLon()), latToY(currentPath.get(i).getIat()),
                    lonToX(currentPath.get(i+1).getLon()), latToY(currentPath.get(i+1).getIat()));
        }
    }

    private void drawNodes(Graphics2D g2) {
        for (MapNode node : allNodes) {
            int x = lonToX(node.getLon()), y = latToY(node.getIat());
            boolean inPath  = currentPath.contains(node);
            boolean isStart = node == startNode;
            boolean isGoal  = node == goalNode;
            int size = (isStart || isGoal) ? 22 : 14;

            if (isStart || isGoal) {
                g2.setColor(isStart ? new Color(0,255,80,40) : new Color(255,50,50,40));
                g2.fillOval(x-size, y-size, size*2, size*2);
            }
            g2.setColor(new Color(0,0,0,100));
            g2.fillOval(x-size/2+2, y-size/2+2, size, size);
            if      (isStart) g2.setColor(new Color(0,220,80));
            else if (isGoal)  g2.setColor(new Color(220,40,40));
            else if (inPath)  g2.setColor(new Color(50,120,255));
            else              g2.setColor(new Color(60,100,200));
            g2.fillOval(x-size/2, y-size/2, size, size);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(isStart || isGoal ? 2.5f : 1.5f));
            g2.drawOval(x-size/2, y-size/2, size, size);

            g2.setFont(new Font("Arial", Font.BOLD, isStart || isGoal ? 13 : 11));
            FontMetrics fm = g2.getFontMetrics();
            String name = node.getName();
            int lw = fm.stringWidth(name);
            int lx = x + size/2 + 5, ly = y + 4;
            g2.setColor(new Color(0,0,0,160));
            g2.fillRoundRect(lx-2, ly-fm.getAscent(), lw+6, fm.getHeight(), 4, 4);
            if      (isStart) g2.setColor(new Color(100,255,140));
            else if (isGoal)  g2.setColor(new Color(255,120,120));
            else if (inPath)  g2.setColor(new Color(150,200,255));
            else              g2.setColor(new Color(190,205,230));
            g2.drawString(name, lx+2, ly);
        }
    }

    private void drawHint(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,120));
        g2.fillRoundRect(10, getHeight()-48, 310, 36, 8, 8);
        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        g2.setColor(new Color(160,175,210));
        g2.drawString("Left click = select city  |  Right drag = pan  |  Scroll = zoom", 16, getHeight()-30);
        g2.setColor(new Color(100,110,150));
        g2.drawString("Zoom: " + String.format("%.1fx", scale), 16, getHeight()-14);
    }

    // ── Iraq Map Data ─────────────────────────────
    private void buildIraqMap() {
        MapNode baghdad      = createNode("Baghdad",       33.3152, 44.3661);
        MapNode basra        = createNode("Basra",         30.5085, 47.7804);
        MapNode mosul        = createNode("Mosul",         36.3350, 43.1189);
        MapNode erbil        = createNode("Erbil",         36.1901, 44.0091);
        MapNode kirkuk       = createNode("Kirkuk",        35.4681, 44.3922);
        MapNode najaf        = createNode("Najaf",         31.9980, 44.3350);
        MapNode karbala      = createNode("Karbala",       32.6160, 44.0240);
        MapNode hillah       = createNode("Hillah",        32.4726, 44.4220);
        MapNode ramadi       = createNode("Ramadi",        33.4258, 43.2996);
        MapNode tikrit       = createNode("Tikrit",        34.5974, 43.6939);
        MapNode diwaniya     = createNode("Diwaniya",      31.9893, 44.9258);
        MapNode amarah       = createNode("Amarah",        31.8350, 47.1500);
        MapNode nasiriya     = createNode("Nasiriya",      31.0462, 46.2530);
        MapNode samawa       = createNode("Samawa",        31.3188, 45.2835);
        MapNode sulaymaniyah = createNode("Sulaymaniyah",  35.5570, 45.4350);
        MapNode dohuk        = createNode("Dohuk",         36.8669, 42.9859);
        MapNode fallujah     = createNode("Fallujah",      33.3530, 43.7859);
        MapNode baqubah      = createNode("Baqubah",       33.7483, 44.6386);
        MapNode kut          = createNode("Kut",           32.4942, 45.8231);
        MapNode hindiya      = createNode("Hindiya",       32.5433, 44.2275);

        graph.connectTwoWays(baghdad, hillah,        100, 1.5, 100);
        graph.connectTwoWays(baghdad, ramadi,        110, 1.8, 90);
        graph.connectTwoWays(baghdad, tikrit,        160, 1.2, 110);
        graph.connectTwoWays(baghdad, karbala,       110, 1.6, 100);
        graph.connectTwoWays(baghdad, fallujah,       60, 1.7, 90);
        graph.connectTwoWays(baghdad, baqubah,        60, 1.3, 100);
        graph.connectTwoWays(baghdad, kut,           160, 1.2, 110);
        graph.connectTwoWays(hillah,  karbala,        50, 1.2, 90);
        graph.connectTwoWays(hillah,  diwaniya,       80, 1.0, 100);
        graph.connectTwoWays(hillah,  hindiya,        20, 1.1, 80);
        graph.connectTwoWays(karbala, najaf,          80, 1.3, 90);
        graph.connectTwoWays(najaf,   diwaniya,       70, 1.0, 100);
        graph.connectTwoWays(fallujah,ramadi,         50, 1.4, 90);
        graph.connectTwoWays(diwaniya,samawa,         90, 1.0, 100);
        graph.connectTwoWays(samawa,  nasiriya,       90, 1.1, 100);
        graph.connectTwoWays(nasiriya,basra,         150, 1.3, 110);
        graph.connectTwoWays(nasiriya,amarah,        120, 1.0, 100);
        graph.connectTwoWays(amarah,  basra,         170, 1.1, 100);
        graph.connectTwoWays(amarah,  kut,           130, 1.0, 100);
        graph.connectTwoWays(kut,     diwaniya,      120, 1.0, 100);
        graph.connectTwoWays(kut,     baqubah,       180, 1.1, 100);
        graph.connectTwoWays(tikrit,  mosul,         160, 1.0, 110);
        graph.connectTwoWays(tikrit,  kirkuk,        100, 1.2, 100);
        graph.connectTwoWays(tikrit,  baqubah,       120, 1.1, 100);
        graph.connectTwoWays(mosul,   erbil,         100, 1.1, 100);
        graph.connectTwoWays(mosul,   dohuk,          70, 1.0, 90);
        graph.connectTwoWays(kirkuk,  erbil,          80, 1.0, 100);
        graph.connectTwoWays(kirkuk,  sulaymaniyah,  160, 1.0, 90);
        graph.connectTwoWays(erbil,   sulaymaniyah,  120, 1.0, 100);
        graph.connectTwoWays(erbil,   dohuk,          80, 1.0, 100);
        graph.connectTwoWays(ramadi,  tikrit,        200, 1.3, 90);
    }

    private MapNode createNode(String name, double lat, double lon) {
        MapNode node = new MapNode(lat, lon);
        node.setName(name);
        graph.addNode(node);
        allNodes.add(node);
        return node;
    }

    // ── A* ────────────────────────────────────────
    private void runAStar() {
        if (startNode == null || goalNode == null) return;
        for (MapNode n : allNodes) {
            n.g = Double.MAX_VALUE; n.h = 0; n.f = Double.MAX_VALUE; n.parent = null;
        }
        long t0 = System.nanoTime();
        A_star astar = new A_star(mode);
        currentPath  = astar.findPath(startNode, goalNode);
        lastAlgoTime = (System.nanoTime() - t0) / 1_000;

        if (currentPath != null) {
            lastDistance = 0; lastTravelTime = 0;
            lastNodesExplored = astar.getNodesExplored();
            for (int i = 0; i < currentPath.size()-1; i++) {
                MapNode a = currentPath.get(i), b = currentPath.get(i+1);
                for (Road r : a.getRoads()) {
                    if (r.getTo() == b) { lastDistance += r.getDistance(); lastTravelTime += r.getTime(); break; }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < currentPath.size(); i++) {
                sb.append(currentPath.get(i).getName());
                if (i < currentPath.size()-1) sb.append(" → ");
            }
            statusLabel.setText(startNode.getName() + "  →→→  " + goalNode.getName());
            infoLabel.setText("Route: " + sb);
            updateStats();
        } else {
            statusLabel.setText("No path found!");
            infoLabel.setText("");
            clearStats();
            JOptionPane.showMessageDialog(null, "No path found!", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private MapNode findNearestNode(int px, int py) {
        MapNode nearest = null;
        double minDist = Double.MAX_VALUE;
        for (MapNode node : allNodes) {
            double d = Math.hypot(px - lonToX(node.getLon()), py - latToY(node.getIat()));
            if (d < minDist) { minDist = d; nearest = node; }
        }
        return minDist < 30 ? nearest : null;
    }

    private void resetPath() {
        startNode = null; goalNode = null; currentPath.clear();
        lastAlgoTime = 0; lastNodesExplored = 0; lastDistance = 0; lastTravelTime = 0;
        statusLabel.setText("🔄 Reset  |  Click a city to set START");
        infoLabel.setText("");
        clearStats();
        repaint();
    }

    private void updateStats() {
        statDist.setText(String.format("%.0f km", lastDistance));
        statTravel.setText(String.format("%.0f min", lastTravelTime * 60));
        statNodes.setText(String.valueOf(lastNodesExplored));
        statTime.setText(lastAlgoTime < 1000 ? lastAlgoTime + " µs"
                : String.format("%.2f ms", lastAlgoTime / 1000.0));
    }

    private void clearStats() {
        statDist.setText("—"); statTravel.setText("—");
        statNodes.setText("—"); statTime.setText("—");
    }

    // ── Build Window ──────────────────────────────
    public static void buildAndShow() {
        JFrame frame = new JFrame("Iraq Map — A* Pathfinding");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        MapGUI map = new MapGUI();
        frame.add(map.buildTopBar(),     BorderLayout.NORTH);
        frame.add(map,                   BorderLayout.CENTER);
        frame.add(map.buildControls(),   BorderLayout.SOUTH);
        frame.add(map.buildStatsPanel(), BorderLayout.EAST);
        frame.setSize(1300, 820);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildTopBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(10,15,25));
        panel.setBorder(new EmptyBorder(8,14,8,14));
        statusLabel = new JLabel("Click a city to set START point");
        statusLabel.setForeground(new Color(255,220,100));
        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        infoLabel = new JLabel("");
        infoLabel.setForeground(new Color(140,190,255));
        infoLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        JPanel left = new JPanel(new GridLayout(2,1,0,3));
        left.setOpaque(false);
        left.add(statusLabel); left.add(infoLabel);
        JLabel hint = new JLabel("① Click Start  ② Click Goal  ③ Controls below");
        hint.setForeground(new Color(80,90,130));
        hint.setFont(new Font("Arial", Font.PLAIN, 11));
        panel.add(left, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildStatsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(12,18,28));
        panel.setBorder(new EmptyBorder(16,14,16,14));
        panel.setPreferredSize(new Dimension(200,0));

        panel.add(sTitle("Statistics"));
        panel.add(Box.createVerticalStrut(10));
        JPanel dRow = sRow("Distance");     statDist   = (JLabel) dRow.getComponent(1);
        JPanel tRow = sRow("Travel Time");  statTravel = (JLabel) tRow.getComponent(1);
        JPanel nRow = sRow("Nodes Explored");statNodes  = (JLabel) nRow.getComponent(1);
        JPanel aRow = sRow("Algo Time");    statTime   = (JLabel) aRow.getComponent(1);
        panel.add(dRow); panel.add(Box.createVerticalStrut(8));
        panel.add(tRow); panel.add(Box.createVerticalStrut(8));
        panel.add(nRow); panel.add(Box.createVerticalStrut(8));
        panel.add(aRow); panel.add(Box.createVerticalStrut(16));

        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(40,50,70));
        sep.setMaximumSize(new Dimension(180,2));
        panel.add(sep); panel.add(Box.createVerticalStrut(14));
        panel.add(sTitle("Legend"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(lRow("━ Light",   new Color(30,160,30)));  panel.add(Box.createVerticalStrut(4));
        panel.add(lRow("━ Medium",  new Color(220,130,0)));  panel.add(Box.createVerticalStrut(4));
        panel.add(lRow("━ Heavy",   new Color(200,30,30)));  panel.add(Box.createVerticalStrut(4));
        panel.add(lRow("━ Path",    new Color(50,120,255))); panel.add(Box.createVerticalStrut(4));
        panel.add(lRow("● Start",   new Color(0,220,80)));   panel.add(Box.createVerticalStrut(4));
        panel.add(lRow("● Goal",    new Color(220,40,40)));
        panel.add(Box.createVerticalGlue());

        JButton resetBtn = new JButton("🔄  Reset");
        resetBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        resetBtn.setMaximumSize(new Dimension(160,36));
        resetBtn.setBackground(new Color(120,20,20));
        resetBtn.setForeground(Color.WHITE);
        resetBtn.setFocusPainted(false);
        resetBtn.setFont(new Font("Arial", Font.BOLD, 13));
        resetBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200,50,50),1),
                new EmptyBorder(6,16,6,16)));
        resetBtn.addActionListener(e -> resetPath());
        panel.add(Box.createVerticalStrut(14));
        panel.add(resetBtn);
        return panel;
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        panel.setBackground(new Color(20,28,40));
        JLabel modeLabel = cLabel("Mode:", Color.LIGHT_GRAY);
        JComboBox<String> modeBox = new JComboBox<>(
                new String[]{"Shortest Distance","Fastest Route","Least Traffic"});
        modeBox.setPreferredSize(new Dimension(170,28));
        modeBox.addActionListener(e -> {
            mode = switch (modeBox.getSelectedIndex()) {
                case 1  -> RoutingMode.FASTEST;
                case 2  -> RoutingMode.TRAFFICS;
                default -> RoutingMode.SHORTEST;
            };
            if (startNode != null && goalNode != null) { runAStar(); repaint(); }
        });
        JButton refreshBtn = fBtn("🔃 Refresh Traffic");
        refreshBtn.addActionListener(e -> {
            graph.regenerateAllTraffic();
            if (startNode != null && goalNode != null) runAStar();
            repaint();
        });
        JToggleButton autoBtn = new JToggleButton("⏱ Auto: OFF");
        autoBtn.setBackground(new Color(40,40,55)); autoBtn.setForeground(Color.WHITE); autoBtn.setFocusPainted(false);
        autoBtn.addActionListener(e -> {
            autoTraffic = autoBtn.isSelected();
            autoBtn.setText(autoTraffic ? "⏱ Auto: ON" : "⏱ Auto: OFF");
            autoBtn.setBackground(autoTraffic ? new Color(0,90,40) : new Color(40,40,55));
        });
        JButton resetBtn = fBtn("🔄 Reset");
        resetBtn.setBackground(new Color(100,20,20));
        resetBtn.addActionListener(e -> resetPath());
        panel.add(modeLabel); panel.add(modeBox); panel.add(vSep());
        panel.add(refreshBtn); panel.add(autoBtn); panel.add(vSep());
        panel.add(resetBtn); panel.add(vSep());
        panel.add(cLabel("Traffic:", Color.LIGHT_GRAY));
        panel.add(cLabel("● Light",  new Color(30,180,30)));
        panel.add(cLabel("● Medium", new Color(220,140,0)));
        panel.add(cLabel("● Heavy",  new Color(210,40,40)));
        return panel;
    }

    // Helpers
    private JLabel sTitle(String t) { JLabel l = new JLabel(t); l.setForeground(new Color(180,190,230)); l.setFont(new Font("Arial",Font.BOLD,13)); l.setAlignmentX(0); return l; }
    private JPanel sRow(String lbl) { JPanel r = new JPanel(new BorderLayout()); r.setOpaque(false); r.setMaximumSize(new Dimension(180,26)); JLabel l = new JLabel(lbl); l.setForeground(new Color(110,120,160)); l.setFont(new Font("Arial",Font.PLAIN,11)); JLabel v = new JLabel("—"); v.setForeground(new Color(255,220,100)); v.setFont(new Font("Arial",Font.BOLD,12)); r.add(l,BorderLayout.WEST); r.add(v,BorderLayout.EAST); return r; }
    private JPanel lRow(String t, Color c) { JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); r.setOpaque(false); r.setMaximumSize(new Dimension(180,20)); JLabel l = new JLabel(t); l.setForeground(c); l.setFont(new Font("Arial",Font.BOLD,12)); r.add(l); return r; }
    private JLabel cLabel(String t, Color c) { JLabel l = new JLabel(t); l.setForeground(c); l.setFont(new Font("Arial",Font.BOLD,12)); return l; }
    private JButton fBtn(String t) { JButton b = new JButton(t); b.setBackground(new Color(45,55,75)); b.setForeground(Color.WHITE); b.setFocusPainted(false); b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(70,80,110),1),new EmptyBorder(4,12,4,12))); return b; }
    private JSeparator vSep() { JSeparator s = new JSeparator(SwingConstants.VERTICAL); s.setPreferredSize(new Dimension(2,28)); s.setForeground(new Color(60,70,90)); return s; }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MapGUI::buildAndShow);
    }
}