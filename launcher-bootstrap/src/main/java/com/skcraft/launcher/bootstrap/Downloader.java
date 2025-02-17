/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import com.skcraft.launcher.Bootstrap;
import lombok.extern.java.Log;
import net.lingala.zip4j.ZipFile;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.sound.midi.MidiFileFormat;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static com.skcraft.launcher.bootstrap.BootstrapUtils.checkInterrupted;
import static com.skcraft.launcher.bootstrap.SharedLocale.tr;

@Log
public class Downloader implements Runnable, ProgressObservable {

    private final Bootstrap bootstrap;
    private DownloadFrame dialog;
    private HttpRequest httpRequest;
    private Thread thread;

    public Downloader(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run() {
        this.thread = Thread.currentThread();

        try {
            execute();
        } catch (InterruptedException e) {
            log.log(Level.WARNING, "Interrupted");
            System.exit(0);
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to download launcher", t);
            SwingHelper.showErrorDialog(null, tr("errors.failedDownloadError"), tr("errorTitle"), t);
            System.exit(0);
        }
    }

    private void execute() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                Bootstrap.setSwingLookAndFeel();
                dialog = new DownloadFrame(Downloader.this);
                dialog.setVisible(true);
                dialog.setDownloader(Downloader.this);
            }
        });

        URL updateUrl = HttpRequest.url(bootstrap.getProperties().getProperty("latestUrl"));
        URL javaUrl = HttpRequest.url(bootstrap.getProperties().getProperty("javaUrl"));

        log.info("Reading update URL " + updateUrl + "...");
        List<LauncherBinary> binaries = new ArrayList<LauncherBinary>();

        try {
            //Downloading Java
            File tempJava = new File(bootstrap.getBaseDir() + "/java", "jre_temp.zip");
            tempJava.getParentFile().mkdirs();
            tempJava.createNewFile();
            httpRequest = HttpRequest.get(javaUrl);
            httpRequest
                    .execute()
                    .expectResponseCode(200)
                    .saveContent(tempJava);
            //ExtractZip
            new ZipFile(tempJava).extractAll(bootstrap.getBaseDir() + "/java");
            tempJava.delete();

            //Downloading Launcher
            String data = HttpRequest
                    .get(updateUrl)
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asString("UTF-8");

            Object object = JSONValue.parse(data);
            URL url;

            if (object instanceof JSONObject) {
                String rawUrl = String.valueOf(((JSONObject) object).get("url"));
                if (rawUrl != null) {
                    url = HttpRequest.url(rawUrl.trim());
                } else {
                    log.warning("Did not get valid update document - got:\n\n" + data);
                    throw new IOException("Update URL did not return a valid result");
                }
            } else {
                log.warning("Did not get valid update document - got:\n\n" + data);
                throw new IOException("Update URL did not return a valid result");
            }

            checkInterrupted();

            File finalFile = new File(bootstrap.getBinariesDir(), System.currentTimeMillis() + ".jar");
            File tempFile = new File(finalFile.getParentFile(), finalFile.getName() + ".tmp");

            log.info("Downloading " + url + " to " + tempFile.getAbsolutePath());

            httpRequest = HttpRequest.get(url);
            httpRequest
                    .execute()
                    .expectResponseCode(200)
                    .saveContent(tempFile);

            finalFile.delete();
            tempFile.renameTo(finalFile);

            LauncherBinary binary = new LauncherBinary(finalFile);
            binaries.add(binary);
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    dialog.setDownloader(null);
                    dialog.dispose();
                }
            });
        }

        bootstrap.launchExisting(binaries, false);
    }

    public void cancel() {
        thread.interrupt();
    }

    public String getStatus() {
        HttpRequest httpRequest = this.httpRequest;
        if (httpRequest != null) {
            double progress = httpRequest.getProgress();
            if (progress >= 0) {
                return String.format(tr("downloader.progressStatus"), progress * 100);
            }
        }

        return tr("downloader.status");
    }

    @Override
    public double getProgress() {
        HttpRequest httpRequest = this.httpRequest;
        return httpRequest != null ? httpRequest.getProgress() : -1;
    }
}
