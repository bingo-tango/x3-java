package edu.cornell.raven.core.audio.x3a.tools;

import edu.cornell.raven.core.audio.x3a.X3Files;
import edu.cornell.raven.core.audio.x3a.sud.FileMetadata;
import edu.cornell.raven.core.audio.x3a.sud.X3Decoder;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 6: minimal drag-and-drop verification app — drop a {@code .SUD} or
 * {@code .x3a} file, get a {@code .wav} (plus a {@code .xml} metadata sidecar
 * when recovered metadata is available) next to it.
 * <p>
 * Test scaffolding, not library code (development-plan.md Phase 6 / §1 scope
 * boundary): no file chooser, no settings, no playback, no batch queue.
 */
public final class X3VerificationApp extends Application {

    private static final int WINDOW_FRAMES = 65536;

    private final ExecutorService conversions = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void start(Stage stage) {
        Label status = new Label("Drop a .SUD or .x3a file");
        StackPane dropZone = new StackPane(status);
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPrefSize(420, 220);
        dropZone.setStyle("-fx-border-color: gray; -fx-border-style: dashed; -fx-border-width: 2;");

        dropZone.setOnDragOver(event -> onDragOver(event, dropZone));
        dropZone.setOnDragDropped(event -> onDragDropped(event, dropZone, status));

        stage.setTitle("X3 Decoder Verification");
        stage.setScene(new Scene(dropZone));
        stage.show();
    }

    @Override
    public void stop() {
        conversions.shutdown();
    }

    private void onDragOver(DragEvent event, StackPane dropZone) {
        if (dropZone.isDisabled()) {
            return;
        }
        Dragboard db = event.getDragboard();
        if (db.hasFiles() && db.getFiles().size() == 1 && isSupported(db.getFiles().get(0))) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onDragDropped(DragEvent event, StackPane dropZone, Label status) {
        Dragboard db = event.getDragboard();
        boolean accepted = db.hasFiles() && db.getFiles().size() == 1;
        if (accepted) {
            File dropped = db.getFiles().get(0);
            Path input = dropped.toPath();
            dropZone.setDisable(true);
            status.setText("Converting " + dropped.getName() + "…");
            conversions.submit(() -> runConversion(input, dropZone, status));
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    private static boolean isSupported(File file) {
        String ext = extensionOf(file.getName());
        return "sud".equals(ext) || "x3a".equals(ext);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void runConversion(Path input, StackPane dropZone, Label status) {
        try {
            Path wavOut = swapExtension(input, "wav");
            convert(input, wavOut);
            Platform.runLater(() -> {
                status.setText("Wrote " + wavOut.getFileName());
                dropZone.setDisable(false);
            });
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            Platform.runLater(() -> {
                status.setText("Failed: " + message);
                dropZone.setDisable(false);
            });
        }
    }

    private void convert(Path input, Path wavOut) throws Exception {
        String ext = extensionOf(input.getFileName().toString());
        if ("sud".equals(ext)) {
            convertSud(input, wavOut);
        } else if ("x3a".equals(ext)) {
            convertX3a(input, wavOut);
        } else {
            throw new IllegalArgumentException("unsupported file type: " + input.getFileName());
        }
    }

    private void convertSud(Path input, Path wavOut) throws Exception {
        try (X3Decoder decoder = new X3Decoder(input)) {
            FileMetadata metadata = decoder.metadata();
            int channels = Math.max(1, metadata.channels());
            long total = decoder.chunkIndex().totalSamples();

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

            writeXmlSidecar(wavOut, metadata.xmlConfig());
        }
    }

    private void convertX3a(Path input, Path wavOut) throws IOException {
        byte[] archive = Files.readAllBytes(input);
        X3Files.DecodedArchive decoded = X3Files.decodeArchive(archive);

        try (StreamingWavWriter writer = new StreamingWavWriter(wavOut, decoded.sampleRate, decoded.channels)) {
            writer.writeFrames(decoded.pcm, decoded.frames());
        }

        writeXmlSidecar(wavOut, decoded.xml);
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

    public static void main(String[] args) {
        launch(args);
    }
}
