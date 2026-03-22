
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Scanner;

import javax.imageio.ImageIO;
import java.io.IOException;
import javax.swing.JPanel;
import javax.swing.JFrame;
import java.awt.Graphics;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

import java.util.Optional;

public class AIScout extends JPanel{
    private static HashSet<Integer> autoFrameIndices = new HashSet<>();
    private static HashSet<Integer> teleFrameIndices = new HashSet<>();
    private static final long serialVersionUID = 1L; //Recommended for JPanel subclasses

    // Field calibration — loaded from calibrations/{event_key}.json or calibrations/default.json
    protected static Point TOP_LEFT;
    protected static Point BOTTOM_LEFT;
    protected static Point TOP_RIGHT;
    protected static Point BOTTOM_RIGHT;
    protected static boolean RED_ON_LEFT;

    public AIScout() {
        //Empty constructor for JPanel subclass
    }

    public static void main(String[] args) throws IOException {
        // Argument structure:
        //   args[0]   = event_key
        //   args[1-3] = red alliance team numbers (or "no_show")
        //   args[4-6] = blue alliance team numbers (or "no_show")
        //   Optional flags (after position args):
        //     --auto          skip all user confirmations (for automated/web use)
        //     --json <path>   override default temp/output.json path

        if (args.length < 7) {
            throw new IllegalArgumentException(
                "Usage: AIScout <event_key> <red1> <red2> <red3> <blue1> <blue2> <blue3> [--auto] [--json <path>]"
            );
        }

        String eventKey = args[0];
        String[] teamArgs = Arrays.copyOfRange(args, 1, 7);

        boolean autoMode = false;
        String jsonPath = "temp/output.json";

        for (int i = 7; i < args.length; i++) {
            if (args[i].equals("--auto")) {
                autoMode = true;
            } else if (args[i].equals("--json") && i + 1 < args.length) {
                jsonPath = args[++i];
            }
        }

        // Load calibration from JSON file
        loadCalibration(eventKey);

        ArrayList<ArrayList<Optional<Point>>> detections = detect(jsonPath);

        if (!autoMode) {
            JPanel confirm = new AIScout();
            JFrame frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setResizable(false);
            frame.setSize((int) Visualization.WIDTH/2, (int) Visualization.HEIGHT/2);
            frame.setLocation(0, 0);
            frame.setName("The P.A.C.K. (Predictive, Analytical, and Competitive Knowledge-base) Field Calibration Confirmation");
            frame.setTitle("The P.A.C.K. (Predictive, Analytical, and Competitive Knowledge-base) Field Calibration Confirmation");
            try { frame.setIconImage(ImageIO.read(new File("pop.png"))); } catch (Exception e) { /* icon optional */ }
            frame.add(confirm);
            frame.setVisible(true);
            System.out.println("Is field properly aligned and the alliances consistent in the window that just opened? (y/n)");
            Scanner scanner = new Scanner(System.in);
            if (!scanner.nextLine().equalsIgnoreCase("y")) {
                scanner.close();
                frame.dispose();
                throw new IllegalStateException("Field not properly aligned. Please adjust the calibration JSON and try again.");
            }
            frame.dispose();
            scanner.close();
        }

        int amountShows = 0;
        int leftShows = 0;
        int rightShows = 0;
        for (int i = 0; i < teamArgs.length; i++) {
            if (!teamArgs[i].equals("no_show")) {
                amountShows++;
                if (i < 3) {
                    leftShows++;
                } else {
                    rightShows++;
                }
            }
        }

        FRCRobot[] robots = new FRCRobot[amountShows];
        int insertionIndex = 0;

        // Find first frame with expected number of robots
        int firstFrameIndex = 0;
        while (firstFrameIndex < detections.size() && detections.get(firstFrameIndex).size() != amountShows) {
            firstFrameIndex++;
        }
        if (firstFrameIndex >= detections.size()) {
            throw new IllegalStateException(
                "Cannot confirm starting point. No frame with " + amountShows + " robots detected found. "
                + "Please try another video or check that the detector is working correctly."
            );
        }

        if (!autoMode) {
            System.out.println("First frame with " + amountShows + " robots detected is at index " + firstFrameIndex
                    + " (which is about " + Math.round(firstFrameIndex * 1000.0 / detections.size()) / 10.0
                    + "% of the video). Confirm as starting point? (y/n)");
            Scanner scanner = new Scanner(System.in);
            if (!scanner.nextLine().equalsIgnoreCase("y")) {
                scanner.close();
                throw new IllegalStateException("Starting point not confirmed. Exiting.");
            }
            scanner.close();
        } else {
            System.out.println("Auto mode: using frame " + firstFrameIndex + " as starting point ("
                    + Math.round(firstFrameIndex * 1000.0 / detections.size()) / 10.0 + "% through video).");
        }

        long time = System.currentTimeMillis();
        System.out.println("Starting point confirmed. Initializing robots and writing data...");

        ArrayList<Optional<Point>> startingDetections = detections.get(firstFrameIndex);

        startingDetections.sort(Comparator.comparing(Optional::get, Comparator.comparing(Point::getX)));

        String[] firstHalf = Arrays.copyOfRange(teamArgs, 0, 3);
        String[] secondHalf = Arrays.copyOfRange(teamArgs, 3, teamArgs.length);

        List<Optional<Point>> leftHalf = startingDetections.subList(0, leftShows);
        leftHalf.sort(Comparator.comparing(Optional::get, Comparator.comparing(Point::getY).reversed()));

        List<Optional<Point>> rightHalf = startingDetections.subList(leftShows, leftShows + rightShows);
        rightHalf.sort(Comparator.comparing(Optional::get, Comparator.comparing(Point::getY).reversed()));

        int pointIndex = 0;
        for (int i = 0; i < firstHalf.length; i++) {
            if (!firstHalf[i].equals("no_show")) {
                Point coord = leftHalf.get(pointIndex).get();
                robots[insertionIndex] = new FRCRobot(coord, firstHalf[i], eventKey);
                insertionIndex++;
                pointIndex++;
            }
        }
        pointIndex = 0;
        for (int i = 0; i < secondHalf.length; i++) {
            if (!secondHalf[i].equals("no_show")) {
                Point coord = rightHalf.get(pointIndex).get();
                robots[insertionIndex] = new FRCRobot(coord, secondHalf[i], eventKey);
                insertionIndex++;
                pointIndex++;
            }
        }

        // For every frame, assign robots new positions using Hungarian Algorithm
        for (int i = firstFrameIndex + 1; i < detections.size(); i++) {
            System.out.print("\r");

            double percent = (double) i / (detections.size() - 1);
            int hashtags = (int) Math.round(percent * 10);
            String display = String.format("%.1f", percent * 100);

            System.out.print("Analyzing... |");
            for (int j = 0; j < hashtags; j++) System.out.print("#");
            for (int j = 0; j < 10 - hashtags; j++) System.out.print("-");
            System.out.print("| " + display + "% (" + i + "/" + (detections.size() - 1) + " frames)");

            ArrayList<Optional<Point>> frameDetections = detections.get(i);
            if (frameDetections.size() == 0) continue;

            double[][] costMatrix = new double[amountShows][frameDetections.size()];
            for (int j = 0; j < amountShows; j++) {
                for (int k = 0; k < frameDetections.size(); k++) {
                    costMatrix[j][k] = robots[j].getPosition().distanceTo(frameDetections.get(k).get());
                }
            }

            int[] assignment = new HungarianAlgorithm(costMatrix).execute();
            for (int j = 0; j < assignment.length; j++) {
                if (assignment[j] != -1) {
                    robots[j].updatePosition(frameDetections.get(assignment[j]).get(), autoFrameIndices.contains(i));
                }
            }
        }

        System.out.println(" Done!");
        System.out.println("Analysis complete. Writing data to files...");
        for (FRCRobot robot : robots) {
            robot.writeData();
        }
        System.out.println("Wrote data to files. (took "
                + Math.round((System.currentTimeMillis() - time) / 100.0) / 10.0 + " seconds)");
    }

    /** Load calibration from calibrations/{eventKey}.json, falling back to calibrations/default.json */
    private static void loadCalibration(String eventKey) {
        String[] candidates = {
            "calibrations" + File.separator + eventKey + ".json",
            "calibrations" + File.separator + "default.json"
        };

        JSONObject cal = null;
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    String content = new String(Files.readAllBytes(Paths.get(path)));
                    cal = new JSONObject(content);
                    System.out.println("Loaded calibration from: " + path);
                    break;
                } catch (Exception e) {
                    System.err.println("Failed to read calibration file " + path + ": " + e.getMessage());
                }
            }
        }

        if (cal == null) {
            // Hardcoded PNW District Sammamish 2025 as last resort
            System.err.println("Warning: no calibration file found, using hardcoded PNW Sammamish 2025 values.");
            TOP_LEFT     = new Point(0.19620, 0.19515);
            BOTTOM_LEFT  = new Point(0.02259, 0.69198);
            TOP_RIGHT    = new Point(0.84483, 0.21941);
            BOTTOM_RIGHT = new Point(0.98811, 0.72152);
            RED_ON_LEFT  = true;
            return;
        }

        try {
            JSONObject tl = cal.getJSONObject("top_left");
            JSONObject bl = cal.getJSONObject("bottom_left");
            JSONObject tr = cal.getJSONObject("top_right");
            JSONObject br = cal.getJSONObject("bottom_right");
            TOP_LEFT     = new Point(tl.getDouble("x"), tl.getDouble("y"));
            BOTTOM_LEFT  = new Point(bl.getDouble("x"), bl.getDouble("y"));
            TOP_RIGHT    = new Point(tr.getDouble("x"), tr.getDouble("y"));
            BOTTOM_RIGHT = new Point(br.getDouble("x"), br.getDouble("y"));
            RED_ON_LEFT  = cal.getBoolean("red_on_left");
        } catch (Exception e) {
            throw new RuntimeException("Malformed calibration JSON: " + e.getMessage(), e);
        }
    }

    public static ArrayList<ArrayList<Optional<Point>>> detect(String jsonPath) {
        ArrayList<ArrayList<Optional<Point>>> allDetections = new ArrayList<>();
        ArrayList<Detection> detections = new ArrayList<>();

        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get(jsonPath)));
            JSONArray detectionsArray = new JSONArray(jsonContent);

            for (int i = 0; i < detectionsArray.length(); i++) {
                JSONObject detectionObject = detectionsArray.getJSONObject(i);
                Detection detection = new Detection(
                    detectionObject.getDouble("x_min"),
                    detectionObject.getDouble("y_min"),
                    detectionObject.getDouble("x_max"),
                    detectionObject.getDouble("y_max"),
                    detectionObject.getString("class_name"),
                    detectionObject.getDouble("confidence"),
                    detectionObject.getString("tracker_id"),
                    detectionObject.getInt("frame_id"),
                    detectionObject.getInt("class_id"),
                    detectionObject.getInt("frame_width"),
                    detectionObject.getInt("frame_height")
                );
                detections.add(detection);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        int prevFrame = 0;
        for (Detection det : detections) {
            if (det.getClassName().equals("Robot") || det.getClassName().equals("Auto")) {
                double centerX = (det.getXMin() + det.getXMax()) / 2.0;
                double centerY = (det.getYMin() + det.getYMax()) / 2.0;
                centerX = centerX / det.getFrameWidth();
                centerY = centerY / det.getFrameHeight();

                if (prevFrame != det.getFrameId()) {
                    ArrayList<Optional<Point>> frameDetections = new ArrayList<>();
                    if (det.getClassName().equals("Auto")) {
                        frameDetections.add(Optional.empty());
                    } else if (det.getClassName().equals("Robot")) {
                        Optional<Double> xCoord = estimateXcoord(new Point(centerX, centerY), TOP_LEFT, TOP_RIGHT,
                                BOTTOM_LEFT, BOTTOM_RIGHT, 10, 0, 1);
                        Optional<Double> yCoord = estimateYcoord(new Point(centerX, centerY), TOP_LEFT, TOP_RIGHT,
                                BOTTOM_LEFT, BOTTOM_RIGHT, 10, 0, 1);
                        if (xCoord.isPresent() && yCoord.isPresent()) {
                            frameDetections.add(Optional.of(new Point(xCoord.get(), yCoord.get())));
                        }
                    }
                    allDetections.add(frameDetections);
                    prevFrame = det.getFrameId();
                } else {
                    ArrayList<Optional<Point>> frameDetections = allDetections.get(allDetections.size() - 1);
                    if (det.getClassName().equals("Auto")) {
                        frameDetections.add(Optional.empty());
                    } else if (det.getClassName().equals("Robot")) {
                        Optional<Double> xCoord = estimateXcoord(new Point(centerX, centerY), TOP_LEFT, TOP_RIGHT,
                                BOTTOM_LEFT, BOTTOM_RIGHT, 10, 0, 1);
                        Optional<Double> yCoord = estimateYcoord(new Point(centerX, centerY), TOP_LEFT, TOP_RIGHT,
                                BOTTOM_LEFT, BOTTOM_RIGHT, 10, 0, 1);
                        if (xCoord.isPresent() && yCoord.isPresent()) {
                            frameDetections.add(Optional.of(new Point(xCoord.get(), yCoord.get())));
                        }
                    }
                }
            }
        }

        for (int i = 0; i < allDetections.size(); i++) {
            int nullIndex = allDetections.get(i).indexOf(Optional.empty());
            boolean autoDetected = false;
            while (nullIndex != -1) {
                allDetections.get(i).remove(nullIndex);
                nullIndex = allDetections.get(i).indexOf(Optional.empty());
                autoDetected = true;
            }
            if (autoDetected) {
                autoFrameIndices.add(i);
            } else {
                teleFrameIndices.add(i);
            }
        }
        return allDetections;
    }

    public static Optional<Double> estimateYcoord(Point robot, Point topLeft, Point topRight, Point bottomLeft,
            Point bottomRight, int iterations, double bound0, double bound1) {

        if (iterations == 0) {
            return Optional.of((bound0 + bound1) / 2);
        }

        Line lineTop = new Line(topLeft, topRight);
        if (!lineTop.isAbove(robot)) {
            return Optional.empty();
        }

        Line lineBottom = new Line(bottomLeft, bottomRight);
        if (lineBottom.isAbove(robot)) {
            return Optional.empty();
        }

        Line lineLeft = new Line(topLeft, bottomRight);
        Line lineRight = new Line(bottomLeft, topRight);
        Point intersect = lineLeft.intersection(lineRight);
        Line lineMid = new Line(intersect, (lineLeft.getSlope() + lineRight.getSlope()) / 2);

        Line sideLeft = new Line(topLeft, bottomLeft);
        Line sideRight = new Line(topRight, bottomRight);

        Point leftIntersect = lineMid.intersection(sideLeft);
        Point rightIntersect = lineMid.intersection(sideRight);

        if (!lineMid.isAbove(robot)) {
            return estimateYcoord(robot, topLeft, topRight, leftIntersect, rightIntersect, iterations - 1, bound0,
                    (bound0 + bound1) / 2);
        }

        return estimateYcoord(robot, leftIntersect, rightIntersect, bottomLeft, bottomRight, iterations - 1,
                (bound0 + bound1) / 2, bound1);
    }

    public static Optional<Double> estimateXcoord(Point robot, Point topLeft, Point topRight, Point bottomLeft,
            Point bottomRight, int iterations, double bound0, double bound1) {
        Optional<Double> result = estimateYcoord(robot.invert(), topRight.invert(), bottomRight.invert(),
                topLeft.invert(), bottomLeft.invert(), iterations, bound0, bound1);
        if (result.isPresent()) {
            return Optional.of(1 - result.get().doubleValue());
        }
        return Optional.empty();
    }

    public void paint(Graphics g){
        super.paint(g);

        try {
            Visualization.drawImage(0, 0, Visualization.WIDTH/2, Visualization.HEIGHT/2, 0, ImageIO.read(new File("matches/cover.png")), g);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Graphics2D g2d = (Graphics2D) g;
        g2d.setStroke(new BasicStroke(5f));
        g2d.setColor(Color.GREEN);
        g2d.drawLine((int) (TOP_LEFT.getX() * Visualization.WIDTH/2), (int) (TOP_LEFT.getY() * Visualization.HEIGHT/2), (int) (TOP_RIGHT.getX() * Visualization.WIDTH/2), (int) (TOP_RIGHT.getY() * Visualization.HEIGHT/2));
        g2d.drawLine((int) (TOP_LEFT.getX() * Visualization.WIDTH/2), (int) (TOP_LEFT.getY() * Visualization.HEIGHT/2), (int) (BOTTOM_LEFT.getX() * Visualization.WIDTH/2), (int) (BOTTOM_LEFT.getY() * Visualization.HEIGHT/2));
        g2d.drawLine((int) (BOTTOM_LEFT.getX() * Visualization.WIDTH/2), (int) (BOTTOM_LEFT.getY() * Visualization.HEIGHT/2), (int) (BOTTOM_RIGHT.getX() * Visualization.WIDTH/2), (int) (BOTTOM_RIGHT.getY() * Visualization.HEIGHT/2));
        g2d.drawLine((int) (TOP_RIGHT.getX() * Visualization.WIDTH/2), (int) (TOP_RIGHT.getY() * Visualization.HEIGHT/2), (int) (BOTTOM_RIGHT.getX() * Visualization.WIDTH/2), (int) (BOTTOM_RIGHT.getY() * Visualization.HEIGHT/2));

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
        if (RED_ON_LEFT) {
            g.setColor(Color.RED);
            g.drawString("Red Alliance Side", (int)(Visualization.WIDTH/16), (int) (Visualization.HEIGHT/4));
            g.setColor(Color.BLUE);
            g.drawString("Blue Alliance Side", (int)(Visualization.WIDTH/4 + Visualization.WIDTH/16), (int) (Visualization.HEIGHT/4));
        } else {
            g.setColor(Color.BLUE);
            g.drawString("Blue Alliance Side", (int)(Visualization.WIDTH/16), (int) (Visualization.HEIGHT/4));
            g.setColor(Color.RED);
            g.drawString("Red Alliance Side", (int)(Visualization.WIDTH/4 + Visualization.WIDTH/16), (int) (Visualization.HEIGHT/4));
        }
        g.dispose();
        g2d.dispose();
    }

}
