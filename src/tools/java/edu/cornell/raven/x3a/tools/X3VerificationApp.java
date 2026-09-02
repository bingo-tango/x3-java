package edu.cornell.raven.x3a.tools;

import edu.cornell.raven.x3a.X3Files;
import edu.cornell.raven.x3a.internal.DecodeOptions;
import edu.cornell.raven.x3a.sud.FileMetadata;
import edu.cornell.raven.x3a.sud.X3Decoder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/// Drag-and-drop verification app for the codec. Dropping a single `.x3a` file
/// converts it to `.wav` in place, and dropping a single `.wav` file encodes it
/// to `.x3a` in place. Dropping one or more `.SUD` files, `.x3a` files, `.wav` files,
/// or a folder, walks every dropped folder for supported files (recursively) and
/// converts the whole batch in parallel, showing overall progress plus a per-file status list.
/// Each output `.wav` gets an `.xml` metadata sidecar next to it when recovered metadata is available.
///
/// Test scaffolding, not library code: no file chooser, no settings, no playback.
public final class X3VerificationApp extends Application {

    private static final int WINDOW_FRAMES = 65536;

    /// Batch drops fan every file out to a virtual thread immediately, but at most this
    /// many are ever mid-conversion at once — a whole-file decode is much heavier than the
    /// per-chunk work [DecodeOptions]'s shared limiter gates.
    private static final int MAX_PARALLEL_FILES = 4;

    private final ExecutorService conversions = Executors.newVirtualThreadPerTaskExecutor();

    /// Builds the scene: a single drop target that toggles between the plain
    /// single-file status view and the batch progress view.
    @Override
    public void start(Stage stage) {
        Label status = new Label("Drop a .SUD, .x3a, or .wav file, multiple files, or a folder");
        StackPane singleView = new StackPane(status);
        singleView.setAlignment(Pos.CENTER);

        ListView<FileTask> fileList = new ListView<>();
        fileList.setCellFactory(lv -> new FileTaskCell());
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        Label progressLabel = new Label();
        VBox batchView = new VBox(8, progressLabel, progressBar, fileList);
        batchView.setPadding(new Insets(10));
        VBox.setVgrow(fileList, Priority.ALWAYS);
        batchView.setVisible(false);
        batchView.setManaged(false);

        StackPane root = new StackPane(singleView, batchView);
        root.setPrefSize(480, 320);
        root.setStyle("-fx-border-color: gray; -fx-border-style: dashed; -fx-border-width: 2;");

        root.setOnDragOver(event -> onDragOver(event, root));
        root.setOnDragDropped(event ->
                onDragDropped(event, root, status, singleView, batchView, fileList, progressBar, progressLabel));

        stage.setTitle("X3 Verification");
        stage.setScene(new Scene(root));
        stage.show();
    }

    /// Stops accepting new conversions; in-flight ones still finish.
    @Override
    public void stop() {
        conversions.shutdown();
    }

    private void onDragOver(DragEvent event, StackPane root) {
        if (root.isDisabled()) {
            return;
        }
        Dragboard db = event.getDragboard();
        if (db.hasFiles() && db.getFiles().stream().allMatch(X3VerificationApp::isDroppable)) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onDragDropped(DragEvent event, StackPane root, Label status, StackPane singleView,
                               VBox batchView, ListView<FileTask> fileList, ProgressBar progressBar,
                               Label progressLabel) {
        Dragboard db = event.getDragboard();
        List<File> dropped = db.hasFiles() ? new ArrayList<>(db.getFiles()) : List.of();
        boolean accepted = !dropped.isEmpty() && dropped.stream().allMatch(X3VerificationApp::isDroppable);
        if (accepted) {
            boolean singleDirect = dropped.size() == 1 && !dropped.getFirst().isDirectory()
                    && ("x3a".equals(extensionOf(dropped.getFirst().getName()))
                    || "wav".equals(extensionOf(dropped.getFirst().getName())));
            root.setDisable(true);
            if (singleDirect) {
                Path input = dropped.getFirst().toPath();
                status.setText("Converting " + input.getFileName() + "…");
                conversions.submit(() -> runSingleConversion(input, root, status));
            } else {
                status.setText("Scanning…");
                conversions.submit(() ->
                        startBatch(dropped, root, singleView, batchView, fileList, progressBar, progressLabel, status));
            }
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    private static boolean isDroppable(File file) {
        return file.isDirectory() || isSupported(file);
    }

    private static boolean isSupported(File file) {
        String ext = extensionOf(file.getName());
        return "sud".equals(ext) || "x3a".equals(ext) || "wav".equals(ext);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void runSingleConversion(Path input, StackPane root, Label status) {
        try {
            String ext = extensionOf(input.getFileName().toString());
            Path out;
            if ("wav".equals(ext)) {
                out = swapExtension(input, "x3a");
                convertWav(input, out);
            } else {
                out = swapExtension(input, "wav");
                convertX3a(input, out);
            }
            final Path finalOut = out;
            Platform.runLater(() -> {
                status.setText("Wrote " + finalOut.getFileName());
                root.setDisable(false);
            });
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            Platform.runLater(() -> {
                status.setText("Failed: " + message);
                root.setDisable(false);
            });
        }
    }

    /// Walks dropped folders for supported files off the FX thread (runs on a conversion
    /// worker), then hands the resolved batch back to the FX thread to build the
    /// progress view and fan out per-file conversion tasks.
    private void startBatch(List<File> dropped, StackPane root, StackPane singleView, VBox batchView,
                            ListView<FileTask> fileList, ProgressBar progressBar, Label progressLabel,
                            Label status) {
        List<Path> files;
        try {
            files = collectSupportedFiles(dropped);
        } catch (UncheckedIOException e) {
            Platform.runLater(() -> {
                status.setText("Failed: " + e.getMessage());
                root.setDisable(false);
            });
            return;
        }
        if (files.isEmpty()) {
            Platform.runLater(() -> {
                status.setText("No supported files found");
                root.setDisable(false);
            });
            return;
        }

        List<FileTask> tasks = new ArrayList<>(files.size());
        for (Path path : files) {
            tasks.add(new FileTask(path));
        }
        int total = tasks.size();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        Platform.runLater(() -> {
            fileList.setItems(FXCollections.observableArrayList(tasks));
            progressBar.setProgress(0);
            progressLabel.setText("0 / " + total + " done");
            singleView.setVisible(false);
            singleView.setManaged(false);
            batchView.setVisible(true);
            batchView.setManaged(true);
        });

        Semaphore limiter = new Semaphore(MAX_PARALLEL_FILES);
        for (FileTask task : tasks) {
            conversions.submit(() -> runBatchConversion(
                    task, completed, failed, total, root, fileList, progressBar, progressLabel, limiter));
        }
    }

    /// Recursively collects every supported file (.sud, .x3a, .wav) among the dropped files/folders
    /// (case-insensitive extension), ignoring any other file types mixed into the same drop.
    private static List<Path> collectSupportedFiles(List<File> dropped) {
        List<Path> result = new ArrayList<>();
        for (File file : dropped) {
            Path path = file.toPath();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> isSupported(p.toFile()))
                            .sorted()
                            .forEach(result::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (isSupported(file)) {
                result.add(path);
            }
        }
        return result;
    }

    /// Decodes or encodes one batch entry, held to at most [#MAX_PARALLEL_FILES] concurrent
    /// conversions via `limiter` — every task is submitted up front, but blocks here
    /// until a slot frees up, so a task only flips to RUNNING once it's actually
    /// converting. Updates its row and the overall progress bar/count as soon as it's
    /// done, independent of how many other files are mid-conversion.
    private void runBatchConversion(FileTask task, AtomicInteger completed, AtomicInteger failed, int total,
                                    StackPane root, ListView<FileTask> fileList, ProgressBar progressBar,
                                    Label progressLabel, Semaphore limiter) {
        try {
            limiter.acquire();
            try {
                task.state = FileTask.State.RUNNING;
                Platform.runLater(fileList::refresh);
                String ext = extensionOf(task.path.getFileName().toString());
                if ("wav".equals(ext)) {
                    convertWav(task.path, swapExtension(task.path, "x3a"));
                } else if ("x3a".equals(ext)) {
                    convertX3a(task.path, swapExtension(task.path, "wav"));
                } else {
                    convertSud(task.path, swapExtension(task.path, "wav"));
                }
                task.state = FileTask.State.DONE;
            } finally {
                limiter.release();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            task.state = FileTask.State.FAILED;
            task.message = e.getMessage() != null ? e.getMessage() : e.toString();
            failed.incrementAndGet();
        }
        int done = completed.incrementAndGet();
        Platform.runLater(() -> {
            fileList.refresh();
            progressBar.setProgress((double) done / total);
            progressLabel.setText(done + " / " + total + " done"
                    + (failed.get() > 0 ? " (" + failed.get() + " failed)" : ""));
            if (done == total) {
                root.setDisable(false);
            }
        });
    }

    private void convertSud(Path input, Path wavOut) throws Exception {
        try (X3Decoder decoder = new X3Decoder(input)) {
            FileMetadata metadata = decoder.metadata();
            int channels = Math.max(1, metadata.channels());
            long total = decoder.chunkIndex().totalSamples();

            // XML is metadata recovered up front, before any PCM is decoded — write it
            // before the WAV so a reader sees the sidecar as soon as it appears on disk.
            writeXmlSidecar(wavOut, metadata.xmlConfig());

            // Guardrail 1 (AGENTS.md): allocate the window buffer once, reuse for every window.
            short[] window = new short[WINDOW_FRAMES * channels];

            try (StreamingWavWriter writer = new StreamingWavWriter(wavOut, metadata.sampleRate(), channels)) {
                long offset = 0;
                while (offset < total) {
                    int want = (int) Math.min((long) WINDOW_FRAMES, total - offset);
                    int got = decoder.decodeSamplesInt(offset, want, window);
                    if (got <= 0) {
                        break;
                    }
                    writer.writeFrames(window, got);
                    offset += got;
                }
            }
        }
    }

    private void convertX3a(Path input, Path wavOut) throws IOException {
        byte[] archive = Files.readAllBytes(input);
        X3Files.DecodedArchive decoded = X3Files.decodeArchive(archive);

        writeXmlSidecar(wavOut, decoded.xml);

        try (StreamingWavWriter writer = new StreamingWavWriter(wavOut, decoded.sampleRate, decoded.channels)) {
            writer.writeFrames(decoded.pcm, decoded.frames());
        }
    }

    private void convertWav(Path input, Path x3aOut) throws IOException {
        X3Files.wavToX3a(input, x3aOut);
    }

    private static void writeXmlSidecar(Path wavOut, String xml) throws IOException {
        if (xml == null || xml.isEmpty()) {
            return;
        }
        Files.writeString(swapExtension(wavOut, "xml"), xml);
    }

    private static Path swapExtension(Path path, String newExtension) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        Path parent = path.getParent();
        String newName = base + "." + newExtension;
        return parent == null ? Path.of(newName) : parent.resolve(newName);
    }

    /// One batch row's mutable state; written from a conversion worker thread and read
    /// back on the FX thread via [ListView#refresh()], so [#state] and [#message] are
    /// volatile rather than relying on any other handoff.
    private static final class FileTask {
        final Path path;
        volatile State state = State.QUEUED;
        volatile String message = "";

        FileTask(Path path) {
            this.path = path;
        }

        enum State { QUEUED, RUNNING, DONE, FAILED }
    }

    private static final class FileTaskCell extends ListCell<FileTask> {
        @Override
        protected void updateItem(FileTask item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            String text = "[" + item.state + "] " + item.path.getFileName();
            if (item.state == FileTask.State.FAILED && !item.message.isEmpty()) {
                text += " — " + item.message;
            }
            setText(text);
        }
    }

    /// Launches the JavaFX app.
    public static void main(String[] args) {
        launch(args);
    }
}
