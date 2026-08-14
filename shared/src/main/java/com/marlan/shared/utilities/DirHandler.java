package com.marlan.shared.utilities;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.System.getProperty;

/**
 * Returns working directory using either entry points's main String[] args passed through
 * from weather-scripts/utilities/JAR or if args are empty, then use user.dir
 * If args directory does not exist or cannot be accessed, program exits because if a custom directory
 * is specified, then it is required to be accessible.
 */
public class DirHandler {
    private DirHandler() {
    }

    @NotNull
    public static String getWorkingDir(String[] args) {
        if (args != null && args.length != 0) {
            if (!Files.exists(Path.of(args[0]))) {
                throw new IllegalArgumentException("Directory does not exist: " + args[0]);
            }
            // Round-trip through Path to collapse doubled separators: java.nio
            // tolerates "C:\\Users\\..." but raw-string consumers (7-Zip via
            // ProcessBuilder) fail on the empty path components with "The
            // filename, directory name, or volume label syntax is incorrect".
            // An over-escaped path can arrive from the mission-side Lua when a
            // trigger was edited with serialized (\\) backslashes.
            System.setProperty("user.dir", Path.of(args[0]).normalize().toString());
        }
        String workingDir = Path.of(getProperty("user.dir")).normalize() + "\\";

        Path workingDirPath = Path.of(workingDir);
        if (!Files.isReadable(workingDirPath) || !Files.isWritable(workingDirPath)) {
            throw new SecurityException("Missing read/write permissions: " + workingDir);
        }
        return workingDir;
    }

}
