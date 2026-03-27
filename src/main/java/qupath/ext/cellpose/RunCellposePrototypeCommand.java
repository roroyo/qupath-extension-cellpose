package qupath.ext.cellpose;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.plugins.parameters.ParameterList;
import java.util.Arrays;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.common.ColorTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.color.ColorSpace;
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

    private static final Logger logger = LoggerFactory.getLogger(RunCellposePrototypeCommand.class);
    private static final Gson GSON = new Gson();
    private static final List<String> DEFAULT_PYTHON_COMMANDS = List.of("python3", "python");
    private static final List<Path> DEFAULT_SCRIPT_CANDIDATES = List.of(
            Path.of("scripts", "cellpose_segment.py"),
            Path.of("..", "scripts", "cellpose_segment.py")
    );
    private static final PathClass CELLPOSE_CLASS = PathClass.getInstance("Cellpose", ColorTools.packRGB(200, 0, 0));

    private String model = "cpsam";
    private double diameter = 0.0;
    private double flowThreshold = 0.4;
    private double cellprobThreshold = 0.0;

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
        var runModel = model;
        var runDiameter = diameter;
        var runFlowThreshold = flowThreshold;
        var runCellprobThreshold = cellprobThreshold;
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

                    var inputPath = writePatch(patch, tempDir.resolve(annotationName + ".png"));
                    var outputPath = tempDir.resolve(annotationName + ".json");
                    var labelsPath = tempDir.resolve(annotationName + "-labels.png");

                    var result = runCellpose(scriptPath, pythonExecutable, inputPath, outputPath, labelsPath,
                            runModel, runDiameter, runFlowThreshold, runCellprobThreshold);
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

    public void showSettingsDialog() {
        var modelOptions = Arrays.asList(
                "cpsam", "cyto3", "cyto2", "cyto", "nuclei",
                "livecell", "tissuenet", "deepbact", "bact_omni", "CP", "CPx"
        );
        var params = new ParameterList()
                .addChoiceParameter("model", "Model", model, modelOptions, "Cellpose model to use")
                .addDoubleParameter("diameter", "Diameter (0 = auto)", diameter, "px", "Estimated cell diameter in pixels; 0 lets Cellpose estimate automatically")
                .addDoubleParameter("flowThreshold", "Flow threshold", flowThreshold, null, "Maximum flow error per mask (default 0.4); increase to detect more cells")
                .addDoubleParameter("cellprobThreshold", "Cellprob threshold", cellprobThreshold, null, "Cell probability cutoff (default 0.0); decrease to detect more cells");

        var panel = new ParameterPanelFX(params);

        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Cellpose Settings");
        dialog.getDialogPane().setContent(panel.getPane());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.orElse(ButtonType.CANCEL) == ButtonType.OK) {
            model = (String) params.getChoiceParameterValue("model");
            diameter = params.getDoubleParameterValue("diameter");
            flowThreshold = params.getDoubleParameterValue("flowThreshold");
            cellprobThreshold = params.getDoubleParameterValue("cellprobThreshold");
        }
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

    private static Path writePatch(BufferedImage patch, Path inputPath) throws IOException {
        if (ImageIO.write(patch, "PNG", inputPath.toFile())) {
            return inputPath;
        }

        var standardizedPatch = standardizePatchForExport(patch);
        if (ImageIO.write(standardizedPatch, "PNG", inputPath.toFile())) {
            return inputPath;
        }

        // Fall back to TIFF after standardizing the image into a writer-compatible layout.
        var tiffPath = Path.of(inputPath.toString().replaceAll("\\.png$", ".tif"));
        if (ImageIO.write(standardizedPatch, "TIFF", tiffPath.toFile())) {
            return tiffPath;
        }
        throw new IOException("Failed to write patch image to " + inputPath);
    }

    private static BufferedImage standardizePatchForExport(BufferedImage patch) throws IOException {
        var raster = patch.getRaster();
        var bands = raster.getNumBands();
        var transferType = raster.getTransferType();

        if (transferType == DataBuffer.TYPE_USHORT) {
            if (bands == 1) {
                return copyBandToGrayscale(patch, BufferedImage.TYPE_USHORT_GRAY, 0);
            }
            if (bands >= 3) {
                if (bands > 3) {
                    logger.warn("Cellpose export dropping {} extra channel(s); exporting first 3 channels as 16-bit RGB", bands - 3);
                }
                return copyBandsToUShortRgb(patch);
            }
            logger.warn("Cellpose export found 2-channel 16-bit patch; exporting first channel as 16-bit grayscale");
            return copyBandToGrayscale(patch, BufferedImage.TYPE_USHORT_GRAY, 0);
        }

        if (transferType == DataBuffer.TYPE_BYTE) {
            if (bands == 1) {
                return copyBandToGrayscale(patch, BufferedImage.TYPE_BYTE_GRAY, 0);
            }
            if (bands >= 3) {
                if (bands > 3) {
                    logger.warn("Cellpose export dropping {} extra channel(s); exporting first 3 channels as 8-bit RGB", bands - 3);
                }
                return copyBandsToByteRgb(patch);
            }
            logger.warn("Cellpose export found 2-channel 8-bit patch; exporting first channel as 8-bit grayscale");
            return copyBandToGrayscale(patch, BufferedImage.TYPE_BYTE_GRAY, 0);
        }

        throw new IOException(
                "Unsupported patch raster for Cellpose export: transferType=" + transferType + ", bands=" + bands
                        + ". Export currently supports 8-bit or 16-bit grayscale/RGB data."
        );
    }

    private static BufferedImage copyBandToGrayscale(BufferedImage source, int bufferedImageType, int band) {
        var width = source.getWidth();
        var height = source.getHeight();
        var target = new BufferedImage(width, height, bufferedImageType);
        var sourceRaster = source.getRaster();
        var targetRaster = target.getRaster();
        var samples = sourceRaster.getSamples(0, 0, width, height, band, (int[]) null);
        targetRaster.setSamples(0, 0, width, height, 0, samples);
        return target;
    }

    private static BufferedImage copyBandsToByteRgb(BufferedImage source) {
        var width = source.getWidth();
        var height = source.getHeight();
        var target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        copyRgbBands(source, target, 3);
        return target;
    }

    private static BufferedImage copyBandsToUShortRgb(BufferedImage source) {
        var width = source.getWidth();
        var height = source.getHeight();
        var colorModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                false,
                false,
                BufferedImage.OPAQUE,
                DataBuffer.TYPE_USHORT
        );
        var raster = java.awt.image.Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, width, height, 3, null);
        var target = new BufferedImage(colorModel, raster, false, null);
        copyRgbBands(source, target, 3);
        return target;
    }

    private static void copyRgbBands(BufferedImage source, BufferedImage target, int channels) {
        var width = source.getWidth();
        var height = source.getHeight();
        var sourceRaster = source.getRaster();
        var targetRaster = target.getRaster();

        for (int band = 0; band < channels; band++) {
            var samples = sourceRaster.getSamples(0, 0, width, height, band, (int[]) null);
            targetRaster.setSamples(0, 0, width, height, band, samples);
        }
    }

    private static CellposeResult runCellpose(
            Path scriptPath,
            String pythonExecutable,
            Path inputPath,
            Path outputPath,
            Path labelsPath,
            String model,
            double diameter,
            double flowThreshold,
            double cellprobThreshold
    ) throws IOException, InterruptedException {
        var commandList = new java.util.ArrayList<>(List.of(
                pythonExecutable,
                scriptPath.toString(),
                "--input",
                inputPath.toString(),
                "--output",
                outputPath.toString(),
                "--labels-output",
                labelsPath.toString(),
                "--model",
                model
        ));
        if (diameter > 0) {
            commandList.add("--diameter");
            commandList.add(String.format(Locale.US, "%.1f", diameter));
        }
        commandList.add("--flow-threshold");
        commandList.add(String.format(Locale.US, "%.4f", flowThreshold));
        commandList.add("--cellprob-threshold");
        commandList.add(String.format(Locale.US, "%.4f", cellprobThreshold));
        var command = List.copyOf(commandList);

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
