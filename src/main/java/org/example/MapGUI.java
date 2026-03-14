package org.example;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Baghdad A* Pathfinding — Offline Real Map
 *
 * ══════════════════════════════════════════════════════════════
 *  HOW TO USE A REAL MAP IMAGE (one-time setup):
 *
 *  1. Open this URL in your browser:
 *     https://www.openstreetmap.org/export
 *
 *  2. Manually set these bounding box values:
 *       Left  (West)  = 44.18
 *       Bottom(South) = 33.19
 *       Right (East)  = 44.68
 *       Top   (North) = 33.56
 *
 *  3. Click "Export" — download the image
 *
 *  4. Save the file as:   baghdad_map.png
 *     Place it in your project ROOT folder  (same level as pom.xml)
 *     OR in:  src/main/resources/baghdad_map.png
 *
 *  5. Run the project — the real map loads automatically!
 *
 *  Without the image file the app still works with a plain
 *  dark background + drawn road network.
 * ══════════════════════════════════════════════════════════════
 *
 *  CONTROLS:
 *    Left-click  = select Start / Goal / Reset
 *    Right-drag  = pan the map
 *    Scroll      = zoom in / out
 */
public class MapGUI extends JPanel {

    // ── Bounding box — MUST match your exported OSM image ────────────────
    private static final double LAT_MIN = 33.19, LAT_MAX = 33.56;
    private static final double LON_MIN = 44.18, LON_MAX = 44.68;

    private GeoGraph      graph;
    private List<MapNode> allNodes    = new ArrayList<>();
    private MapNode       startNode   = null;
    private MapNode       goalNode    = null;
    private List<MapNode> currentPath = new ArrayList<>();
    private RoutingMode   mode        = RoutingMode.SHORTEST;
    private boolean       autoTraffic = false;

    private Point  pressedAt = null;
    private Image  mapImage  = null;   // real OSM background

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

    // ── Constructor ──────────────────────────────────────────────────────
    public MapGUI() {
        graph = new GeoGraph();
        setBackground(new Color(30, 40, 55));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        loadMapImage();   // load real map if available

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                pressedAt = e.getPoint();
                if (e.getButton() == MouseEvent.BUTTON3) {
                    dragStartX = e.getX() - offsetX;
                    dragStartY = e.getY() - offsetY;
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (pressedAt == null) return;
                if (e.getButton() == MouseEvent.BUTTON1
                        && e.getPoint().distance(pressedAt) < 8) {
                    handleClick(e.getX(), e.getY());
                }
                pressedAt = null;
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0) {
                    offsetX = e.getX() - dragStartX;
                    offsetY = e.getY() - dragStartY;
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> {
            double f = e.getWheelRotation() < 0 ? 1.15 : 0.87;
            scale = Math.max(0.4, Math.min(14.0, scale * f));
            repaint();
        });

        new javax.swing.Timer(5000, e -> {
            if (autoTraffic) {
                graph.regenerateAllTraffic();
                if (startNode != null && goalNode != null) runAStar();
                repaint();
            }
        }).start();

        buildBaghdadMap();
    }

    // ── Load real map image ──────────────────────────────────────────────
    private void loadMapImage() {
        String[] candidates = {
                "baghdad_map.png",
                "baghdad_map.jpg",
                "src/main/resources/baghdad_map.png",
                "src/main/resources/baghdad_map.jpg",
                "resources/baghdad_map.png"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    mapImage = ImageIO.read(f);
                    System.out.println("[Map] Loaded real map: " + f.getAbsolutePath());
                    return;
                } catch (Exception ex) {
                    System.err.println("[Map] Failed to read " + path + ": " + ex.getMessage());
                }
            }
        }
        System.out.println("[Map] No map image found — using plain background.");
        System.out.println("[Map] To use a real map, export Baghdad from:");
        System.out.println("[Map]   https://www.openstreetmap.org/export");
        System.out.println("[Map] with bbox: W=44.18 S=33.19 E=44.68 N=33.56");
        System.out.println("[Map] Save as 'baghdad_map.png' in the project root.");
    }

    // ── Click handler ────────────────────────────────────────────────────
    private void handleClick(int px, int py) {
        MapNode nearest = findNearestNode(px, py);
        if (nearest == null) return;
        if (startNode == null) {
            startNode = nearest;
            statusLabel.setText("Start: " + nearest.getName() + "   |   Now click your destination");
            infoLabel.setText("");
        } else if (goalNode == null && nearest != startNode) {
            goalNode = nearest;
            statusLabel.setText("Calculating...");
            runAStar();
        } else {
            resetPath();
            return;
        }
        repaint();
    }

    // ── Coordinate helpers ───────────────────────────────────────────────
    private int lonToX(double lon) {
        int w = Math.max(getWidth(), 900);
        return (int)(((lon - LON_MIN) / (LON_MAX - LON_MIN) * w) * scale + offsetX);
    }
    private int latToY(double lat) {
        int h = Math.max(getHeight(), 700);
        return (int)(((1.0 - (lat - LAT_MIN) / (LAT_MAX - LAT_MIN)) * h) * scale + offsetY);
    }

    private MapNode findNearestNode(int px, int py) {
        MapNode nearest = null;
        double minDist = Double.MAX_VALUE;
        for (MapNode node : allNodes) {
            double d = Math.hypot(px - lonToX(node.getLon()), py - latToY(node.getIat()));
            if (d < minDist) { minDist = d; nearest = node; }
        }
        return nearest;
    }

    // ── Paint ────────────────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        drawBackground(g2);
        // Only draw synthetic rivers/grid when no real map loaded
        if (mapImage == null) {
            drawRivers(g2);
            drawGrid(g2);
        }
        drawRoads(g2);
        drawPath(g2);
        drawNodes(g2);
        drawHint(g2);
    }

    private void drawBackground(Graphics2D g2) {
        // Dark base
        g2.setColor(new Color(38, 52, 68));
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (mapImage != null) {
            // Stretch the image to exactly match the bounding box
            int x1 = lonToX(LON_MIN);
            int y1 = latToY(LAT_MAX);   // NW corner (top-left on screen)
            int x2 = lonToX(LON_MAX);
            int y2 = latToY(LAT_MIN);   // SE corner (bottom-right on screen)
            // Semi-transparent so road overlays are readable
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f));
            g2.drawImage(mapImage, x1, y1, x2 - x1, y2 - y1, null);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        } else {
            // Fallback: draw city polygon
            double[][] boundary = {
                    {44.20,33.19},{44.30,33.18},{44.42,33.19},{44.52,33.20},
                    {44.60,33.24},{44.65,33.30},{44.66,33.38},{44.63,33.46},
                    {44.57,33.52},{44.48,33.55},{44.38,33.55},{44.28,33.53},
                    {44.20,33.47},{44.18,33.38},{44.18,33.29},{44.20,33.19}
            };
            int[] bx=new int[boundary.length], by=new int[boundary.length];
            for(int i=0;i<boundary.length;i++){bx[i]=lonToX(boundary[i][0]);by[i]=latToY(boundary[i][1]);}
            g2.setColor(new Color(46,62,78));
            g2.fillPolygon(bx,by,boundary.length);
            g2.setColor(new Color(85,115,150,110));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawPolygon(bx,by,boundary.length);
        }
    }

    private void drawRivers(Graphics2D g2) {
        double[][] tigris = {
                {44.393,33.555},{44.389,33.540},{44.383,33.522},{44.377,33.506},
                {44.371,33.490},{44.367,33.474},{44.368,33.458},{44.372,33.442},
                {44.379,33.428},{44.386,33.413},{44.391,33.398},{44.394,33.383},
                {44.390,33.367},{44.384,33.351},{44.377,33.335},{44.371,33.319},
                {44.368,33.303},{44.371,33.287},{44.377,33.271},{44.384,33.254},{44.391,33.237}
        };
        int[] rx=new int[tigris.length], ry=new int[tigris.length];
        for(int i=0;i<tigris.length;i++){rx[i]=lonToX(tigris[i][0]);ry[i]=latToY(tigris[i][1]);}
        g2.setColor(new Color(40,75,120,180));
        g2.setStroke(new BasicStroke(14f*(float)scale,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        for(int i=0;i<tigris.length-1;i++) g2.drawLine(rx[i],ry[i],rx[i+1],ry[i+1]);
        g2.setColor(new Color(72,128,190,230));
        g2.setStroke(new BasicStroke(8f*(float)scale,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        for(int i=0;i<tigris.length-1;i++) g2.drawLine(rx[i],ry[i],rx[i+1],ry[i+1]);
        g2.setColor(new Color(130,185,240,190));
        g2.setFont(new Font("Arial",Font.ITALIC|Font.BOLD,12));
        g2.drawString("Tigris River",lonToX(44.400)+6,latToY(33.395));
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(255,255,255,7));
        g2.setStroke(new BasicStroke(0.5f));
        for(double lat=33.19;lat<=33.56;lat+=0.04)
            g2.drawLine(lonToX(44.18),latToY(lat),lonToX(44.68),latToY(lat));
        for(double lon=44.18;lon<=44.68;lon+=0.05)
            g2.drawLine(lonToX(lon),latToY(33.19),lonToX(lon),latToY(33.56));
    }

    private void drawRoads(Graphics2D g2) {
        // When real map is loaded, reduce road line opacity to avoid clutter
        int roadAlpha = mapImage != null ? 170 : 215;
        Set<String> drawn = new HashSet<>();
        for (MapNode node : allNodes) {
            for (Road road : node.getRoads()) {
                MapNode to = road.getTo();
                int id1=System.identityHashCode(node), id2=System.identityHashCode(to);
                String key=Math.min(id1,id2)+"_"+Math.max(id1,id2);
                if(drawn.contains(key)) continue;
                drawn.add(key);
                int x1=lonToX(node.getLon()), y1=latToY(node.getIat());
                int x2=lonToX(to.getLon()),   y2=latToY(to.getIat());
                double t=road.getTrafficFactor();
                Color c = t<=1.3 ? new Color(40,195,65,roadAlpha)
                        : t<=2.0 ? new Color(235,148,0,roadAlpha)
                        :          new Color(218,48,48,roadAlpha);
                g2.setColor(new Color(0,0,0,60));
                g2.setStroke(new BasicStroke(6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g2.drawLine(x1+1,y1+1,x2+1,y2+1);
                g2.setColor(c);
                g2.setStroke(new BasicStroke(3.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g2.drawLine(x1,y1,x2,y2);
            }
        }
    }

    private void drawPath(Graphics2D g2) {
        if(currentPath==null||currentPath.size()<2) return;
        g2.setColor(new Color(55,145,255,60));
        g2.setStroke(new BasicStroke(22f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        for(int i=0;i<currentPath.size()-1;i++)
            g2.drawLine(lonToX(currentPath.get(i).getLon()),latToY(currentPath.get(i).getIat()),
                    lonToX(currentPath.get(i+1).getLon()),latToY(currentPath.get(i+1).getIat()));
        g2.setColor(new Color(35,128,255));
        g2.setStroke(new BasicStroke(6.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        for(int i=0;i<currentPath.size()-1;i++)
            g2.drawLine(lonToX(currentPath.get(i).getLon()),latToY(currentPath.get(i).getIat()),
                    lonToX(currentPath.get(i+1).getLon()),latToY(currentPath.get(i+1).getIat()));
        g2.setColor(new Color(210,230,255,170));
        g2.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        for(int i=0;i<currentPath.size()-1;i++)
            g2.drawLine(lonToX(currentPath.get(i).getLon()),latToY(currentPath.get(i).getIat()),
                    lonToX(currentPath.get(i+1).getLon()),latToY(currentPath.get(i+1).getIat()));
    }

    private void drawNodes(Graphics2D g2) {
        for(MapNode node : allNodes) {
            int x=lonToX(node.getLon()), y=latToY(node.getIat());
            boolean inPath=currentPath!=null&&currentPath.contains(node);
            boolean isStart=node==startNode, isGoal=node==goalNode;
            int size=(isStart||isGoal)?24:13;
            if(isStart||isGoal){
                g2.setColor(isStart?new Color(0,255,80,50):new Color(255,60,60,50));
                g2.fillOval(x-size,y-size,size*2,size*2);
            }
            g2.setColor(new Color(0,0,0,130));
            g2.fillOval(x-size/2+1,y-size/2+1,size,size);
            if(isStart)     g2.setColor(new Color(0,232,90));
            else if(isGoal) g2.setColor(new Color(232,48,48));
            else if(inPath) g2.setColor(new Color(50,128,255));
            else            g2.setColor(new Color(80,118,205));
            g2.fillOval(x-size/2,y-size/2,size,size);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(isStart||isGoal?2.5f:1.4f));
            g2.drawOval(x-size/2,y-size/2,size,size);
            String label=node.getName();
            g2.setFont(new Font("Arial",Font.BOLD,isStart||isGoal?13:10));
            FontMetrics fm=g2.getFontMetrics();
            int lw=fm.stringWidth(label), lx=x+size/2+5, ly=y+4;
            g2.setColor(new Color(8,10,14,205));
            g2.fillRoundRect(lx-2,ly-fm.getAscent(),lw+6,fm.getHeight(),4,4);
            if(isStart)     g2.setColor(new Color(105,255,145));
            else if(isGoal) g2.setColor(new Color(255,128,128));
            else if(inPath) g2.setColor(new Color(155,205,255));
            else            g2.setColor(new Color(200,215,238));
            g2.drawString(label,lx+2,ly);
        }
    }

    private void drawHint(Graphics2D g2) {
        boolean hasMap = mapImage != null;
        String mapStatus = hasMap ? "✓ Real map loaded" : "⚠ No map image (see console for setup)";
        Color mapColor  = hasMap ? new Color(80,210,80) : new Color(255,180,60);
        String line1 = startNode==null ? "Click anywhere  →  set START point"
                : goalNode==null  ? "Click anywhere  →  set DESTINATION"
                :                   "Click anywhere  →  reset";
        g2.setColor(new Color(5,10,18,180));
        g2.fillRoundRect(10,getHeight()-58,520,52,8,8);
        g2.setFont(new Font("Arial",Font.BOLD,12));
        g2.setColor(new Color(255,220,100));
        g2.drawString(line1,18,getHeight()-38);
        g2.setFont(new Font("Arial",Font.PLAIN,10));
        g2.setColor(mapColor);
        g2.drawString(mapStatus,18,getHeight()-22);
        g2.setColor(new Color(150,165,200));
        g2.drawString("   |   Right-drag=pan   Scroll=zoom   Zoom:"+String.format("%.1fx",scale),
                18+g2.getFontMetrics().stringWidth(mapStatus),getHeight()-22);
    }

    // ── Baghdad Map — 50 nodes ────────────────────────────────────────────
    private void buildBaghdadMap() {

        // SOUTH
        MapNode airport      = createNode("Airport",       33.2614,44.2344);
        MapNode saidiyah     = createNode("Saidiyah",      33.2580,44.3350);
        MapNode dora         = createNode("Dora",          33.2442,44.3892);
        MapNode zafaraniyah  = createNode("Zafaraniyah",   33.2550,44.4500);
        MapNode sbaghdad     = createNode("S.Baghdad",     33.2200,44.3600);

        // SW / KARKH-WEST
        MapNode amiriyah     = createNode("Amiriyah",      33.3210,44.2850);
        MapNode ghazaliyah   = createNode("Ghazaliyah",    33.3420,44.2650);
        MapNode bayaa        = createNode("Bayaa",         33.2728,44.3189);
        MapNode iskan        = createNode("Iskan",         33.2950,44.3050);
        MapNode jihad        = createNode("Jihad",         33.2900,44.3100);
        MapNode yarmouk      = createNode("Yarmouk",       33.3050,44.3300);

        // KARKH-CENTRAL
        MapNode mansour      = createNode("Mansour",       33.3173,44.3555);
        MapNode karkh        = createNode("Karkh",         33.3148,44.3389);
        MapNode muthana      = createNode("Muthana",       33.3100,44.3620);
        MapNode khadra       = createNode("Khadra",        33.3300,44.3700);
        MapNode salhiyah     = createNode("Salhiyah",      33.3320,44.3780);
        MapNode nidhal       = createNode("Nidhal St.",    33.3385,44.3870);
        MapNode haifa_st     = createNode("Haifa St.",     33.3450,44.3820);

        // KARKH-NORTH
        MapNode washash      = createNode("Washash",       33.3480,44.3600);
        MapNode kadhimiyah   = createNode("Kadhimiyah",    33.3775,44.3436);
        MapNode alhurriyah   = createNode("Al-Hurriyah",   33.3639,44.3648);
        MapNode shuala       = createNode("Shuala",        33.3950,44.2950);
        MapNode qadisiyah    = createNode("Qadisiyah",     33.3500,44.3200);
        MapNode taji         = createNode("Taji",          33.5150,44.2640);
        MapNode talbiyah     = createNode("Talbiyah",      33.4000,44.3200);

        // RUSAFA-CENTRAL
        MapNode tahrir       = createNode("Tahrir Sq.",    33.3413,44.4012);
        MapNode karrada      = createNode("Karrada",       33.3017,44.3974);
        MapNode arasat       = createNode("Arasat",        33.3060,44.4170);
        MapNode jadriyah     = createNode("Jadriyah",      33.2905,44.3940);
        MapNode waziriyah    = createNode("Waziriyah",     33.3580,44.3970);
        MapNode mustansiriya = createNode("Mustansiriya",  33.3600,44.4100);
        MapNode palestine_st = createNode("Palestine St.", 33.3560,44.4230);
        MapNode medical_city = createNode("Medical City",  33.3350,44.4000);

        // RUSAFA-EAST
        MapNode zayouna      = createNode("Zayouna",       33.3203,44.4580);
        MapNode baladiyat    = createNode("Baladiyat",     33.3350,44.4700);
        MapNode new_baghdad  = createNode("New Baghdad",   33.3150,44.5100);
        MapNode amin         = createNode("Al-Amin",       33.3050,44.4800);
        MapNode mashtal      = createNode("Mashtal",       33.3000,44.5200);
        MapNode kamaliyah    = createNode("Kamaliyah",     33.3530,44.4900);
        MapNode jisr         = createNode("Al-Jisr",       33.3550,44.4200);

        // RUSAFA-NORTH
        MapNode adhamiyah    = createNode("Adhamiyah",     33.3779,44.3870);
        MapNode utaifiyah    = createNode("Utaifiyah",     33.3870,44.4120);
        MapNode shaab        = createNode("Sha'ab",        33.4055,44.4303);
        MapNode sadrCity     = createNode("Sadr City",     33.3720,44.4714);
        MapNode hussainiyah  = createNode("Hussainiyah",   33.4250,44.4600);
        MapNode nahrawan     = createNode("Nahrawan",      33.3700,44.5800);

        // ── Roads South ──
        graph.connectTwoWays(airport,     amiriyah,     10, 1.2,80);
        graph.connectTwoWays(airport,     ghazaliyah,   12, 1.1,80);
        graph.connectTwoWays(amiriyah,    bayaa,         6, 1.3,60);
        graph.connectTwoWays(bayaa,       saidiyah,      5, 1.4,55);
        graph.connectTwoWays(saidiyah,    dora,          8, 1.5,50);
        graph.connectTwoWays(saidiyah,    jihad,         4, 1.4,55);
        graph.connectTwoWays(dora,        jadriyah,      7, 1.5,45);
        graph.connectTwoWays(dora,        zafaraniyah,   9, 1.2,65);
        graph.connectTwoWays(dora,        sbaghdad,      8, 1.1,70);
        graph.connectTwoWays(zafaraniyah, amin,         10, 1.2,60);
        graph.connectTwoWays(zafaraniyah, new_baghdad,  12, 1.1,65);
        graph.connectTwoWays(sbaghdad,    saidiyah,     10, 1.0,75);

        // ── Roads Karkh West ──
        graph.connectTwoWays(bayaa,       iskan,         4, 1.3,55);
        graph.connectTwoWays(iskan,       jihad,         2, 1.5,45);
        graph.connectTwoWays(jihad,       yarmouk,       4, 1.4,50);
        graph.connectTwoWays(jihad,       karkh,         5, 1.6,40);
        graph.connectTwoWays(yarmouk,     karkh,         3, 1.5,50);
        graph.connectTwoWays(yarmouk,     mansour,       4, 1.4,55);
        graph.connectTwoWays(amiriyah,    ghazaliyah,    6, 1.2,65);
        graph.connectTwoWays(amiriyah,    mansour,       8, 1.3,60);
        graph.connectTwoWays(ghazaliyah,  qadisiyah,     8, 1.2,65);
        graph.connectTwoWays(ghazaliyah,  shuala,        9, 1.2,65);
        graph.connectTwoWays(mansour,     karkh,         4, 1.6,40);
        graph.connectTwoWays(mansour,     khadra,        3, 1.5,50);
        graph.connectTwoWays(mansour,     washash,       5, 1.4,50);
        graph.connectTwoWays(karkh,       muthana,       2, 1.6,35);
        graph.connectTwoWays(karkh,       salhiyah,      2, 1.7,35);
        graph.connectTwoWays(muthana,     khadra,        2, 1.5,40);
        graph.connectTwoWays(khadra,      salhiyah,      2, 1.6,38);
        graph.connectTwoWays(salhiyah,    nidhal,        1, 1.9,28);
        graph.connectTwoWays(nidhal,      haifa_st,      1, 2.0,25);
        graph.connectTwoWays(haifa_st,    washash,       2, 1.7,35);
        graph.connectTwoWays(washash,     alhurriyah,    4, 1.4,50);
        graph.connectTwoWays(washash,     kadhimiyah,    5, 1.5,45);
        graph.connectTwoWays(qadisiyah,   kadhimiyah,    5, 1.4,55);
        graph.connectTwoWays(qadisiyah,   washash,       4, 1.4,50);
        graph.connectTwoWays(kadhimiyah,  alhurriyah,    5, 1.3,55);
        graph.connectTwoWays(kadhimiyah,  shuala,        7, 1.4,50);
        graph.connectTwoWays(kadhimiyah,  talbiyah,      6, 1.3,55);
        graph.connectTwoWays(alhurriyah,  shuala,        8, 1.3,60);
        graph.connectTwoWays(alhurriyah,  talbiyah,      5, 1.2,60);
        graph.connectTwoWays(shuala,      taji,         20, 1.0,80);
        graph.connectTwoWays(alhurriyah,  taji,         22, 1.1,75);

        // ── Roads Rusafa ──
        graph.connectTwoWays(jadriyah,    karrada,       4, 1.5,38);
        graph.connectTwoWays(karrada,     arasat,        3, 1.7,30);
        graph.connectTwoWays(karrada,     medical_city,  3, 1.7,32);
        graph.connectTwoWays(karrada,     zayouna,       7, 1.5,50);
        graph.connectTwoWays(arasat,      medical_city,  2, 1.8,28);
        graph.connectTwoWays(arasat,      zayouna,       5, 1.4,50);
        graph.connectTwoWays(arasat,      jisr,          4, 1.6,38);
        graph.connectTwoWays(medical_city,tahrir,        2, 2.2,22);
        graph.connectTwoWays(tahrir,      nidhal,        1, 2.3,18);
        graph.connectTwoWays(tahrir,      waziriyah,     3, 1.7,35);
        graph.connectTwoWays(waziriyah,   mustansiriya,  2, 1.6,38);
        graph.connectTwoWays(waziriyah,   palestine_st,  3, 1.6,38);
        graph.connectTwoWays(mustansiriya,palestine_st,  2, 1.6,38);
        graph.connectTwoWays(mustansiriya,adhamiyah,     4, 1.5,42);
        graph.connectTwoWays(palestine_st,jisr,          3, 1.5,42);
        graph.connectTwoWays(palestine_st,utaifiyah,     4, 1.5,42);
        graph.connectTwoWays(jisr,        baladiyat,     5, 1.4,50);
        graph.connectTwoWays(jisr,        zayouna,       4, 1.4,52);
        graph.connectTwoWays(zayouna,     baladiyat,     4, 1.3,55);
        graph.connectTwoWays(zayouna,     amin,          5, 1.3,55);
        graph.connectTwoWays(baladiyat,   kamaliyah,     5, 1.3,55);
        graph.connectTwoWays(baladiyat,   sadrCity,      6, 1.5,48);
        graph.connectTwoWays(new_baghdad, amin,          4, 1.2,60);
        graph.connectTwoWays(new_baghdad, mashtal,       4, 1.1,65);
        graph.connectTwoWays(amin,        mashtal,       5, 1.2,60);
        graph.connectTwoWays(amin,        kamaliyah,     7, 1.3,55);
        graph.connectTwoWays(kamaliyah,   sadrCity,      5, 1.5,48);
        graph.connectTwoWays(kamaliyah,   nahrawan,     13, 1.1,65);
        graph.connectTwoWays(sadrCity,    utaifiyah,     6, 1.6,40);
        graph.connectTwoWays(sadrCity,    shaab,         6, 1.7,35);
        graph.connectTwoWays(sadrCity,    hussainiyah,   9, 1.4,55);
        graph.connectTwoWays(sadrCity,    nahrawan,     16, 1.2,60);
        graph.connectTwoWays(utaifiyah,   shaab,         4, 1.5,45);
        graph.connectTwoWays(shaab,       adhamiyah,     5, 1.6,42);
        graph.connectTwoWays(shaab,       hussainiyah,   7, 1.4,55);
        graph.connectTwoWays(adhamiyah,   waziriyah,     4, 1.5,42);
        graph.connectTwoWays(hussainiyah, nahrawan,     18, 1.1,65);

        // ── Bridges Karkh ↔ Rusafa ──
        graph.connectTwoWays(nidhal,      salhiyah,      1, 2.5,18);   // Ahrar
        graph.connectTwoWays(tahrir,      haifa_st,      2, 2.8,15);   // Jumhuriyah
        graph.connectTwoWays(adhamiyah,   kadhimiyah,    3, 2.0,22);   // Imams
        graph.connectTwoWays(jadriyah,    yarmouk,       4, 2.0,28);   // Jadriyah
        graph.connectTwoWays(waziriyah,   washash,       3, 1.8,30);   // 14th July
        graph.connectTwoWays(zayouna,     mansour,       9, 1.6,45);
        graph.connectTwoWays(talbiyah,    shaab,         8, 1.4,50);
    }

    private MapNode createNode(String name, double lat, double lon) {
        MapNode node = new MapNode(lat, lon);
        node.setName(name);
        graph.addNode(node);
        allNodes.add(node);
        return node;
    }

    // ── A* ────────────────────────────────────────────────────────────────
    private void runAStar() {
        if(startNode==null||goalNode==null) return;
        for(MapNode n:allNodes){n.g=Double.MAX_VALUE;n.h=0;n.f=Double.MAX_VALUE;n.parent=null;}
        long t0=System.nanoTime();
        A_star astar=new A_star(mode);
        currentPath=astar.findPath(startNode,goalNode);
        lastAlgoTime=(System.nanoTime()-t0)/1_000;
        if(currentPath!=null){
            lastDistance=0; lastTravelTime=0;
            lastNodesExplored=astar.getNodesExplored();
            for(int i=0;i<currentPath.size()-1;i++){
                MapNode a=currentPath.get(i), b=currentPath.get(i+1);
                for(Road r:a.getRoads()) if(r.getTo()==b){lastDistance+=r.getDistance();lastTravelTime+=r.getTime();break;}
            }
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<currentPath.size();i++){sb.append(currentPath.get(i).getName());if(i<currentPath.size()-1)sb.append(" → ");}
            statusLabel.setText(startNode.getName()+"  ➜  "+goalNode.getName());
            infoLabel.setText("Route: "+sb);
            updateStats();
        } else {
            statusLabel.setText("No path found between these points!");
            infoLabel.setText("");
            clearStats();
            JOptionPane.showMessageDialog(null,"No path found!","Warning",JOptionPane.WARNING_MESSAGE);
        }
    }

    private void resetPath(){
        startNode=null; goalNode=null; currentPath=new ArrayList<>();
        lastAlgoTime=0; lastNodesExplored=0; lastDistance=0; lastTravelTime=0;
        statusLabel.setText("Click anywhere on the map to set START");
        infoLabel.setText(""); clearStats(); repaint();
    }
    private void updateStats(){
        statDist.setText(String.format("%.0f km",lastDistance));
        statTravel.setText(String.format("%.0f min",lastTravelTime*60));
        statNodes.setText(String.valueOf(lastNodesExplored));
        statTime.setText(lastAlgoTime<1000?lastAlgoTime+" µs":String.format("%.2f ms",lastAlgoTime/1000.0));
    }
    private void clearStats(){statDist.setText("—");statTravel.setText("—");statNodes.setText("—");statTime.setText("—");}

    // ── Window ───────────────────────────────────────────────────────────
    public static void buildAndShow(){
        JFrame frame=new JFrame("Baghdad Map — A* Pathfinding");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        MapGUI map=new MapGUI();
        frame.add(map.buildTopBar(),     BorderLayout.NORTH);
        frame.add(map,                   BorderLayout.CENTER);
        frame.add(map.buildControls(),   BorderLayout.SOUTH);
        frame.add(map.buildStatsPanel(), BorderLayout.EAST);
        frame.setSize(1400,880);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel buildTopBar(){
        JPanel p=new JPanel(new BorderLayout()); p.setBackground(new Color(10,15,25)); p.setBorder(new EmptyBorder(8,14,8,14));
        statusLabel=new JLabel("Click anywhere on the map to set START"); statusLabel.setForeground(new Color(255,220,100)); statusLabel.setFont(new Font("Arial",Font.BOLD,14));
        infoLabel=new JLabel(""); infoLabel.setForeground(new Color(140,190,255)); infoLabel.setFont(new Font("Arial",Font.PLAIN,12));
        JPanel left=new JPanel(new GridLayout(2,1,0,3)); left.setOpaque(false); left.add(statusLabel); left.add(infoLabel);
        JLabel hint=new JLabel("① Click START   ② Click GOAL   ③ Click again = Reset"); hint.setForeground(new Color(80,90,130)); hint.setFont(new Font("Arial",Font.PLAIN,11));
        p.add(left,BorderLayout.CENTER); p.add(hint,BorderLayout.EAST); return p;
    }

    private JPanel buildStatsPanel(){
        JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setBackground(new Color(12,18,28)); p.setBorder(new EmptyBorder(16,14,16,14)); p.setPreferredSize(new Dimension(205,0));
        p.add(sTitle("Statistics")); p.add(Box.createVerticalStrut(10));
        JPanel d=sRow("Distance");   statDist  =(JLabel)d.getComponent(1);
        JPanel t=sRow("Travel Time");statTravel=(JLabel)t.getComponent(1);
        JPanel n=sRow("Nodes Exp."); statNodes =(JLabel)n.getComponent(1);
        JPanel a=sRow("Algo Time");  statTime  =(JLabel)a.getComponent(1);
        p.add(d);p.add(Box.createVerticalStrut(8));p.add(t);p.add(Box.createVerticalStrut(8));p.add(n);p.add(Box.createVerticalStrut(8));p.add(a);p.add(Box.createVerticalStrut(16));
        JSeparator sep=new JSeparator(); sep.setForeground(new Color(40,50,70)); sep.setMaximumSize(new Dimension(185,2));
        p.add(sep); p.add(Box.createVerticalStrut(12)); p.add(sTitle("Legend")); p.add(Box.createVerticalStrut(8));
        p.add(lRow("━  Light traffic", new Color(40,195,65)));  p.add(Box.createVerticalStrut(4));
        p.add(lRow("━  Medium traffic",new Color(235,148,0)));  p.add(Box.createVerticalStrut(4));
        p.add(lRow("━  Heavy traffic", new Color(218,48,48)));  p.add(Box.createVerticalStrut(4));
        p.add(lRow("━  A* path",       new Color(55,145,255))); p.add(Box.createVerticalStrut(4));
        p.add(lRow("●  Start",         new Color(0,232,90)));   p.add(Box.createVerticalStrut(4));
        p.add(lRow("●  Goal",          new Color(232,48,48)));
        p.add(Box.createVerticalGlue());
        JButton rb=new JButton("🔄  Reset"); rb.setAlignmentX(Component.CENTER_ALIGNMENT); rb.setMaximumSize(new Dimension(165,36));
        rb.setBackground(new Color(120,20,20)); rb.setForeground(Color.WHITE); rb.setFocusPainted(false); rb.setFont(new Font("Arial",Font.BOLD,13));
        rb.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(200,50,50),1),new EmptyBorder(6,16,6,16)));
        rb.addActionListener(e->resetPath()); p.add(Box.createVerticalStrut(14)); p.add(rb); return p;
    }

    private JPanel buildControls(){
        JPanel p=new JPanel(new FlowLayout(FlowLayout.CENTER,12,8)); p.setBackground(new Color(20,28,40));
        JComboBox<String> mb=new JComboBox<>(new String[]{"Shortest Distance","Fastest Route","Least Traffic"}); mb.setPreferredSize(new Dimension(170,28));
        mb.addActionListener(e->{mode=switch(mb.getSelectedIndex()){case 1->RoutingMode.FASTEST;case 2->RoutingMode.TRAFFICS;default->RoutingMode.SHORTEST;};if(startNode!=null&&goalNode!=null){runAStar();repaint();}});
        JButton ref=fBtn("🔃 Refresh Traffic"); ref.addActionListener(e->{graph.regenerateAllTraffic();if(startNode!=null&&goalNode!=null)runAStar();repaint();});
        JToggleButton auto=new JToggleButton("⏱ Auto: OFF"); auto.setBackground(new Color(40,40,55)); auto.setForeground(Color.WHITE); auto.setFocusPainted(false);
        auto.addActionListener(e->{autoTraffic=auto.isSelected();auto.setText(autoTraffic?"⏱ Auto: ON":"⏱ Auto: OFF");auto.setBackground(autoTraffic?new Color(0,90,40):new Color(40,40,55));});
        JButton rst=fBtn("🔄 Reset"); rst.setBackground(new Color(100,20,20)); rst.addActionListener(e->resetPath());
        p.add(cLabel("Mode:",Color.LIGHT_GRAY));p.add(mb);p.add(vSep());p.add(ref);p.add(auto);p.add(vSep());p.add(rst); return p;
    }

    private JLabel sTitle(String t){JLabel l=new JLabel(t);l.setForeground(new Color(180,190,230));l.setFont(new Font("Arial",Font.BOLD,13));l.setAlignmentX(0);return l;}
    private JPanel sRow(String lb){JPanel r=new JPanel(new BorderLayout());r.setOpaque(false);r.setMaximumSize(new Dimension(185,26));JLabel l=new JLabel(lb);l.setForeground(new Color(110,120,160));l.setFont(new Font("Arial",Font.PLAIN,11));JLabel v=new JLabel("—");v.setForeground(new Color(255,220,100));v.setFont(new Font("Arial",Font.BOLD,12));r.add(l,BorderLayout.WEST);r.add(v,BorderLayout.EAST);return r;}
    private JPanel lRow(String t,Color c){JPanel r=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));r.setOpaque(false);r.setMaximumSize(new Dimension(185,20));JLabel l=new JLabel(t);l.setForeground(c);l.setFont(new Font("Arial",Font.BOLD,12));r.add(l);return r;}
    private JLabel cLabel(String t,Color c){JLabel l=new JLabel(t);l.setForeground(c);l.setFont(new Font("Arial",Font.BOLD,12));return l;}
    private JButton fBtn(String t){JButton b=new JButton(t);b.setBackground(new Color(45,55,75));b.setForeground(Color.WHITE);b.setFocusPainted(false);b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(70,80,110),1),new EmptyBorder(4,12,4,12)));return b;}
    private JSeparator vSep(){JSeparator s=new JSeparator(SwingConstants.VERTICAL);s.setPreferredSize(new Dimension(2,28));s.setForeground(new Color(60,70,90));return s;}

    public static void main(String[] args){SwingUtilities.invokeLater(MapGUI::buildAndShow);}
}