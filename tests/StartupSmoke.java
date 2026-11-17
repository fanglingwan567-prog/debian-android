package app.birch.debian;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** Exercise the packaged Kotlin startup decision against the actual, persisted v0.1.27 header. */
public final class StartupSmoke {
    private static void expect(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws IOException {
        File directory = new File(args[0]);
        ImageStore images = new ImageStore(directory);
        File archive = new File(directory, "debian.download");
        File part = new File(directory, "debian.download.part");
        Files.write(part.toPath(), new byte[]{1});
        SetupState initial = SetupStateKt.resolveStartup("BIRCH_NEW", images);
        expect(initial instanceof SetupState.Download && !part.exists(), "fresh start ignores partial downloads");

        Files.write(archive.toPath(), new byte[]{1});
        SetupState.Download damaged = (SetupState.Download) SetupStateKt.resolveStartup("BIRCH_NEW", images);
        expect(damaged.getError() != null, "incomplete image allows re-download");

        try (DataOutputStream fixture = new DataOutputStream(new FileOutputStream(archive))) {
            fixture.writeUTF("13.7-slim");
            fixture.writeUTF("0000000000000000000000000000000000000000000000000000000000000000");
            fixture.writeLong(1);
            fixture.writeByte(0);
        }
        SetupState.Install ready = (SetupState.Install) SetupStateKt.resolveStartup("BIRCH_NEW", images);
        expect(ready.getImage().getTag().equals("13.7-slim") && archive.exists(), "preserve pending v0.1.27 image");
        try {
            SetupStateKt.resolveStartup("unrecognized output", images);
            throw new AssertionError("bad root response must not enter the app");
        } catch (IOException expected) {
            expect(archive.exists(), "failed permission check leaves archive unchanged");
        }
        SetupState.Terminal installed = (SetupState.Terminal) SetupStateKt.resolveStartup("BIRCH_READY", images);
        expect(installed.getCleanupError() == null && !archive.exists(), "installed guest bypasses setup");
        System.out.println("Kotlin startup: fresh/partial/corrupt/pending/unknown/installed: PASS");
    }
}
