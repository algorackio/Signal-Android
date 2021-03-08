package org.thoughtcrime.securesms.util;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.BackupUtil.BackupInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

public class DriveBackupUtil {
    private static final String TAG = Log.tag(DriveBackupUtil.class);

    private static File createTemporaryFile(Context context, String filename) throws IOException {
        File outputDir = context.getCacheDir();
        File file = File.createTempFile("prefix", "backup", outputDir);
        return file;
    }

    private static void writeBytesToFile(ByteArrayOutputStream byteOutputStream, File file) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        byteOutputStream.writeTo(fos);
        fos.flush();
        fos.close();
        Log.d(TAG, "Drive file bytes written to file successfully!");
    }

    @RequiresApi(19)
    private static File extractFileFromDrive(Context context, GoogleDriveServiceHelper driveService, com.google.api.services.drive.model.File driveFile) throws IOException, IllegalAccessException {
//        ByteArrayOutputStream fileStream = new ByteArrayOutputStream();
        File file = createTemporaryFile(context, driveFile.getName());
        OutputStream outputStream = new FileOutputStream(file);
        Drive.Files.Get downloaded = driveService.getDriveService().files().get(driveFile.getId());
        downloaded.executeMediaAndDownloadTo(outputStream);
//        driveService.getDriveService().files().get(driveFile.getId()).executeMediaAndDownloadTo(fileStream);
//        writeBytesToFile(fileStream, file);
        return file;
//            new File(fileStream);
//            backups.add(new BackupUtil.BackupInfo(BackupUtil.getBackupTimestamp(f.getName()), f.getSize(), fileStream.));
    }

    public interface OnGetBackup {
        void onBackupFetchComplete(BackupInfo file);
    }
    @RequiresApi(24)
    public static void getLatestBackup(Context context, GoogleDriveServiceHelper driveService, OnGetBackup callback) {
        driveService.queryFiles().addOnCompleteListener(result -> {
            if (result.isSuccessful()) {
                try {
                    FileList files = Objects.requireNonNull(result.getResult(), "Files couldn't be fetched from drive!");
                    if (files.getFiles().size() < 1) {
                        Log.i(TAG, "No Backups found!");
                        return;
                    }
                    List<com.google.api.services.drive.model.File> driveBackups = files.getFiles();
                    com.google.api.services.drive.model.File latestBackup = driveBackups.get(0);
                    if (latestBackup == null) {
                        return;
                    }
                    String id = latestBackup.getId();
//                    com.google.api.services.drive.model.File.Capabilities caps = latestBackup.getCapabilities();
//                    if (caps != null) {
//                        Log.i(TAG, "File name: " + latestBackup.getName());
//                        Log.i(TAG, "Can Download ? " + caps.getCanDownload());
//                    }
//                    File file = createTemporaryFile(context, latestBackup.getName());
//                    float size = file.getTotalSpace();
//                    driveService.readAndWriteToFile(latestBackup.getId(), file).addOnCompleteListener((task) -> {
                    driveService.readFile(latestBackup.getId()).addOnSuccessListener((resultPair) -> {
//                        if (!task.isSuccessful() || file.getTotalSpace() <= size) {
//                        File resultPair = task.getResult();
//                        Log.d(TAG, "Name of file: " + resultPair.getName());
                        Log.d(TAG, "Name of file: " + resultPair.first);
                        Log.d(TAG, "Contents: " + resultPair.second);
                    }).addOnFailureListener(f -> {
                            Log.e(TAG, f);
                    });
//                    file = driveService.readAndWriteToFile(latestBackup.getId(), file).getResult();
//                    File file = extractFileFromDrive(context, driveService, latestBackup);
//                    DocumentFile docFile = DocumentFile.fromFile(file);
//                    file.delete();
//                    long docFileTimeStamp = BackupUtil.getBackupTimestamp(Objects.requireNonNull(docFile.getName(), "Drive file couldn't be converted to DocumentFile"));
//                    callback.onBackupFetchComplete(new BackupInfo(docFileTimeStamp, docFile.length(), docFile.getUri()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
//                .addOnSuccessListener(result -> {
//            try {
//                Log.d(TAG, result.toPrettyString());
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }).addOnFailureListener(e -> {
//            Log.e(TAG, e);
//        });
//        driveService.queryFiles()
//                .addOnSuccessListener(result -> {
//                    List<com.google.api.services.drive.model.File> files = result.getFiles();
//                    for (com.google.api.services.drive.model.File file : files) {
//                        Log.i(TAG, "File Found");
//                        Log.d(TAG, file.getName());
//                    }
//                });
//        com.google.api.services.drive.model.File latestBackup = driveService.executeWithDrive((drive) -> {
//            try {
//                FileList result = drive.files().list()
////                        .setQ("mimeType='application/octet-stream'")
//                        .setSpaces("drive")
//                        .execute();
//                List<com.google.api.services.drive.model.File> files = result.getFiles();
//                for (com.google.api.services.drive.model.File file : files) {
//                    Log.i(TAG, "File Found");
//                    Log.d(TAG, file.getName());
//                }
//                return files.get(0);
//            } catch (IOException e) {
//                Log.e(TAG, e);
//                return null;
//            }
//        }).getResult();
//            FileList result = driveService.getDriveService().files().list()
//                    .setQ("mimeType='application/octet-stream'")
////                    .setSpaces(driveService.getAppFolder())
//                    .execute();
//        driveService.queryFiles()
//                .addOnSuccessListener(result -> {
//                    Log.d(TAG, "Files Queried");
//                    files = result;
//                    result.getFiles().forEach(f -> {
//                        Log.i(TAG, "Deleting file: " + f.getName());
//                        try {
//                            driveService.getDriveService().files().delete(f.getId());
//                        } catch (IllegalAccessException | IOException ignored) {
//                            ignored.printStackTrace();
//                        }
//                    });
//                }).addOnFailureListener(e -> {
//                    e.printStackTrace();
//            });
        /*com.google.api.services.drive.model.File latestFile = files.getFiles().get(0);
        try {
            File file = extractFileFromDrive(driveService, latestFile);
            DocumentFile docFile = DocumentFile.fromFile(file);
            long docFileTimeStamp = BackupUtil.getBackupTimestamp(docFile.getName());
            return new BackupInfo(docFileTimeStamp, docFile.length(), docFile.getUri());
        } catch (IllegalAccessException | IOException e) {
            e.printStackTrace();
        }

//        if (isUserSelectionRequired(ApplicationDependencies.getApplication())) {
//            return getAllBackupsNewestFirstApi29();
//        } else {
//            return getAllBackupsNewestFirstLegacy();
//        }
        return null;*/
    }
}
