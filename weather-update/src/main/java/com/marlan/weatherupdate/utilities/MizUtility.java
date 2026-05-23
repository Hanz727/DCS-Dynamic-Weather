package com.marlan.weatherupdate.utilities;

import com.marlan.shared.utilities.Log;
import com.marlan.shared.model.Config;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.lang.System.getenv;

/**
 * Handles extracting mission file from DCS *.miz archive file and updating with the new
 * mission file. Uses 7zip to handle extraction/rearchiving.
 */
public class MizUtility {
    private static final Log log = Log.getInstance();
    private static final long MIN_VALID_MIZ_BYTES = 10_000L;
    @Getter private final String sevenZipPath;

    public MizUtility(@NotNull Config config) {
        if (config.getCustomSevenZipPath().isEmpty()) {
            this.sevenZipPath = getenv("ProgramFiles") + "\\7-Zip\\7z.exe";
        } else {
            this.sevenZipPath = config.getCustomSevenZipPath();
        }
    }

    /**
     * Extracts mission file from .miz using 7zip
     */
    public void extractMission(String dir, String mizName) {
        log.info("Extracting: " + dir + mizName);
        ProcessBuilder pb = new ProcessBuilder(
                this.sevenZipPath,
                "x",
                "-tzip",
                dir + mizName,
                "-o" + dir,
                "mission",
                "-y"
        );
        runProcess(pb);
    }

    /**
     * Updates .miz with new mission file using 7zip. Backs up the original first
     * and restores it if the resulting archive fails validation, so a partial or
     * empty 7z write cannot leave a poisoned _A/_B file behind.
     */
    public void updateMiz(String dir, String mizName, String missionFile) {
        log.info("Updating: " + dir + mizName);
        Path miz = Path.of(dir + mizName);
        Path backup = Path.of(dir + mizName + ".bak");

        boolean haveBackup = false;
        try {
            if (Files.exists(miz)) {
                Files.copy(miz, backup, StandardCopyOption.REPLACE_EXISTING);
                haveBackup = true;
            }
        } catch (IOException ioe) {
            log.error("Could not back up miz before update: " + ioe.getMessage());
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                this.sevenZipPath,
                "a",
                "-tzip",
                dir + mizName,
                dir + missionFile
        );
        int exit = runProcess(pb);

        if (exit != 0 || !validateMiz(miz)) {
            log.error("updateMiz produced invalid archive (exit=" + exit + "); restoring " + mizName);
            if (haveBackup) {
                try {
                    Files.move(backup, miz, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ioe) {
                    log.error("Could not restore miz backup: " + ioe.getMessage());
                }
            } else {
                try {
                    Files.deleteIfExists(miz);
                } catch (IOException ioe) {
                    log.error("Could not delete invalid miz: " + ioe.getMessage());
                }
            }
            return;
        }

        if (haveBackup) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException ioe) {
                log.error("Could not delete miz backup: " + ioe.getMessage());
            }
        }
    }

    /**
     * A valid .miz must be a readable zip containing a non-empty "mission" entry,
     * and the archive itself must clear a minimum size to catch the empty-zip case.
     */
    private boolean validateMiz(Path miz) {
        try {
            long size = Files.size(miz);
            if (size < MIN_VALID_MIZ_BYTES) {
                log.error("Miz too small (" + size + " bytes), treating as invalid: " + miz);
                return false;
            }
            try (ZipFile zf = new ZipFile(miz.toFile())) {
                ZipEntry entry = zf.getEntry("mission");
                if (entry == null) {
                    log.error("Miz has no 'mission' entry: " + miz);
                    return false;
                }
                if (entry.getSize() <= 0) {
                    log.error("Miz 'mission' entry is empty: " + miz);
                    return false;
                }
            }
            return true;
        } catch (IOException ioe) {
            log.error("Miz validation failed: " + ioe.getMessage());
            return false;
        }
    }

    private int runProcess(@NotNull ProcessBuilder pb) {
        try {
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                log.error("Process exit=" + exit + " cmd=" + pb.command() + " output=" + output.trim());
            }
            return exit;
        } catch (IOException ioe) {
            log.error("Error running process: " + ioe.getMessage());
            return -1;
        } catch (InterruptedException ie) {
            log.error("Error running process: " + ie.getMessage());
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
