package qupath.ext.cellpose;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import javafx.application.Platform;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public class RunCellposePrototypeCommand implements Runnable {

    private static final Gson GSON = new Gson();
    private static final List<String> DEFAULT_PYTHON_COMMANDS = List.of("python3", "python");
    private static final List<Path> DEFAULT_SCRIPT_CANDIDATES = List.of(
            Path.of("scripts", "cellpose_segment.py"),
            Path.of("..", "scripts", "cellpose_segment.py")
    );
    private static final String DEFAULT_MODEL = "cyto";
    private static final double DEFAULT_DIAMETER = 30.0;
    private static final PathClass CELLPOSE_CLASS = PathClass.getInstance("Cellpose");

    private final QuPathGUI qupath;

    public RunCellposePrototypeCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorNotification("Cellpose", "No image is open.");
            return;
        }

        var hierarchy = imageData.getHierarchy();
        var selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());
        var selectedAnnotations = selectedObjects.stream()
                .filter(PathObject::isAnnotation)
                .filter(PathObject::hasROI)
                .sorted(Comparator.comparingDouble(pathObject -> pathObject.getROI().getArea()))
                .toList();

        if (selectedAnnotations.isEmpty()) {
            Dialogs.showErrorNotification("Cellpose", "Select at least one annotation with an ROI.");
            return;
        }

        var pythonExecutable = resolvePythonExecutable();
        if (pythonExecutable == null) {
            Dialogs.showErrorNotification("Cellpose", "Python executable not found: " + pythonExecutable);
            return;
        }

        var scriptPath = resolveScriptPath();
        if (!Files.isRegularFile(scriptPath)) {
            Dialogs.showErrorNotification("Cellpose", "Cellpose script not found: " + scriptPath);
            return;
        }

        Dialogs.showInfoNotification(
                "Cellpose",
                "Cellpose is running in the background for " + selectedAnnotations.size() + " annotation(s)."
        );

        var selectedAnnotationsCopy = List.copyOf(selectedAnnotations);
        qupath.getThreadPoolManager().getSingleThreadExecutor(this).submit(() -> {
            try {
                var tempDir = Files.createTempDirectory("qupath-cellpose-");
                var totalDetections = 0;
                var server = imageData.getServer();

                for (int i = 0; i < selectedAnnotationsCopy.size(); i++) {
                    var annotation = selectedAnnotationsCopy.get(i);
                    var annotationName = "annotation-" + (i + 1);
                    var regionRequest = RegionRequest.createInstance(server.getPath(), 1.0, annotation.getROI());
                    var patch = server.readRegion(regionRequest);

                    var inputPath = tempDir.resolve(annotationName + ".png");
                    var outputPath = tempDir.resolve(annotationName + ".json");
                    var labelsPath = tempDir.resolve(annotationName + "-labels.png");
                    writePatch(patch, inputPath);

                    var result = runCellpose(scriptPath, pythonExecutable, inputPath, outputPath, labelsPath);
                    var detections = createDetections(annotation, regionRequest, result);
                    totalDetections += detections.size();

                    Platform.runLater(() -> annotation.addChildObjects(detections));
                }

                var classes = selectedAnnotationsCopy.stream()
                        .map(pathObject -> pathObject.getPathClass() == null ? "Unclassified" : pathObject.getPathClass().toString())
                        .distinct()
                        .collect(Collectors.joining(", "));

                var summary = new StringBuilder()
                        .append("Cellpose run completed.\n\n")
                        .append("Image type: ").append(imageData.getImageType()).append('\n')
                        .append("Selected objects: ").append(selectedObjects.size()).append('\n')
                        .append("Selected annotations with ROI: ").append(selectedAnnotationsCopy.size()).append('\n')
                        .append("Detections created: ").append(totalDetections).append('\n')
                        .append("Annotation classes: ").append(classes).append('\n')
                        .append("Python: ").append(pythonExecutable).append('\n')
                        .append("Temp output: ").append(tempDir);

                Platform.runLater(() -> {
                    hierarchy.fireHierarchyChangedEvent(this);
                    Dialogs.showPlainMessage("Cellpose", summary.toString());
                });
            } catch (Exception e) {
                Platform.runLater(() -> Dialogs.showErrorNotification("Cellpose", e));
            }
        });
    }

    private static String resolvePythonExecutable() {
        var configured = Optional.ofNullable(System.getenv("QUPATH_CELLPOSE_PYTHON"))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .orElse(null);
        if (configured != null) {
            return isPathLike(configured) ? (Files.isExecutable(Path.of(configured)) ? configured : null) : configured;
        }

        for (var command : DEFAULT_PYTHON_COMMANDS) {
            var resolved = findCommandOnPath(command);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static Path resolveScriptPath() {
        var configured = Optional.ofNullable(System.getenv("QUPATH_CELLPOSE_SCRIPT"))
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .orElse(null);
        if (configured != null) {
            return configured.toAbsolutePath().normalize();
        }

        for (var candidate : DEFAULT_SCRIPT_CANDIDATES) {
            var absoluteCandidate = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(absoluteCandidate)) {
                return absoluteCandidate;
            }
        }
        return Path.of("scripts", "cellpose_segment.py").toAbsolutePath().normalize();
    }

    private static String findCommandOnPath(String command) {
        var pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }

        for (var directory : pathEnv.split(java.io.File.pathSeparator)) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            var candidate = Path.of(directory, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static boolean isPathLike(String value) {
        return value.contains("/") || value.contains("\\");
    }

    private static void writePatch(BufferedImage patch, Path inputPath) throws IOException {
        if (!ImageIO.write(patch, "PNG", inputPath.toFile())) {
            throw new IOException("Failed to write patch image to " + inputPath);
        }
    }

    private static CellposeResult runCellpose(
            Path scriptPath,
            String pythonExecutable,
            Path inputPath,
            Path outputPath,
            Path labelsPath
    ) throws IOException, InterruptedException {
        var command = List.of(
                pythonExecutable,
                scriptPath.toString(),
                "--input",
                inputPath.toString(),
                "--output",
                outputPath.toString(),
                "--labels-output",
                labelsPath.toString(),
                "--model",
                DEFAULT_MODEL,
                "--diameter",
                String.format(Locale.US, "%.1f", DEFAULT_DIAMETER)
        );

        var processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("MPLCONFIGDIR", labelsPath.getParent().resolve("mplconfig").toString());
        processBuilder.environment().put("CELLPOSE_LOCAL_MODELS_PATH", labelsPath.getParent().resolve("cellpose-models").toString());
        var process = processBuilder.start();

        String stdout;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Cellpose process failed with exit code " + exitCode + ". Output:\n" + stdout);
        }

        if (!Files.exists(outputPath)) {
            throw new IOException("Cellpose process completed but did not create " + outputPath);
        }

        try (Reader reader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, CellposeResult.class);
        }
    }

    private static List<PathObject> createDetections(PathObject annotation, RegionRequest regionRequest, CellposeResult result) {
        var plane = annotation.getROI().getImagePlane();
        var originX = regionRequest.getX();
        var originY = regionRequest.getY();
        var detections = new ArrayList<PathObject>();

        if (result == null || result.polygons == null) {
            return detections;
        }

        for (var polygon : result.polygons) {
            if (polygon == null || polygon.points == null || polygon.points.size() < 3) {
                continue;
            }

            var points = polygon.points.stream()
                    .map(point -> new Point2(originX + point.x(), originY + point.y()))
                    .toList();

            var roi = ROIs.createPolygonROI(points, plane);
            detections.add(PathObjects.createDetectionObject(roi, CELLPOSE_CLASS));
        }

        return detections;
    }

    private record CellposePoint(double x, double y) {
    }

    private static class CellposePolygon {
        @SerializedName("label")
        int label;

        @SerializedName("points")
        List<CellposePoint> points;
    }

    private static class CellposeResult {
        @SerializedName("polygons")
        List<CellposePolygon> polygons;
    }
}
