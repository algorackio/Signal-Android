///**
// * Copyright (C) 2011 Whisper Systems
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as published by
// * the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// *
// * You should have received a copy of the GNU General Public License
// * along with this program.  If not, see <http://www.gnu.org/licenses/>.
// */
package org.thoughtcrime.securesms.jobs;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.gms.tasks.Task;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.backup.BackupFileIOError;
import org.thoughtcrime.securesms.backup.BackupPassphrase;
import org.thoughtcrime.securesms.backup.FullBackupExporter;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.ChargingConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.service.GenericForegroundService;
import org.thoughtcrime.securesms.service.NotificationController;
import org.thoughtcrime.securesms.util.BackupUtil;
import org.thoughtcrime.securesms.util.GoogleDriveServiceHelper;

import com.google.api.services.drive.model.FileList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ezvcard.util.IOUtils;

public class GoogleDriveBackupJob extends BaseJob {
    private static GoogleDriveServiceHelper driveServiceHelper;

    private static final String TAG                     = Log.tag(GoogleDriveBackupJob.class);
    public static final String KEY                      = "GoogleDriveBackupJob";

    private static final String QUEUE                   = "__GOOGLE_DRIVE_BACKUP__";

    public static final String TEMP_BACKUP_FILE_PREFIX  = ".backup";
    public static final String TEMP_BACKUP_FILE_SUFFIX  = ".tmp";

    private GoogleDriveBackupJob(@NonNull Job.Parameters parameters) {
        super(parameters);
    }

    public static void enqueue(boolean force, @NonNull GoogleDriveServiceHelper helper) {
        driveServiceHelper = helper;
        JobManager jobManager = ApplicationDependencies.getJobManager();
        Parameters.Builder parameters = new Parameters.Builder()
                .setQueue(QUEUE)
                .setMaxInstancesForFactory(1)
                .setMaxAttempts(3);
        if (force) {
            jobManager.cancelAllInQueue(QUEUE);
        } else {
            parameters.addConstraint(ChargingConstraint.KEY);
        }
        jobManager.add(new GoogleDriveBackupJob(parameters.build()));

//        if (BackupUtil.isUserSelectionRequired(ApplicationDependencies.getApplication())) {
//            jobManager.add(new LocalBackupJobApi29(parameters.build()));
//        } else {
//            jobManager.add(new GoogleDriveBackupJob(parameters.build()));
//        }
    }

    @Override
    protected void onRun() throws Exception {
        Log.i(TAG, "Executing backup job...");

        BackupFileIOError.clearNotification(context);

         if (!BackupUtil.isUserSelectionRequired(context)) {
            throw new IOException("Wrong backup job!");
        }

        Uri backupDirectoryUri = SignalStore.settings().getSignalBackupDirectory();
        if (backupDirectoryUri == null || backupDirectoryUri.getPath() == null) {
            throw new IOException("Backup Directory has not been selected!");
        }

//        Task<FileList> list = driveServiceHelper.queryFiles();

//        list.addOnSuccessListener(result -> {
//           result.getFiles().forEach(f -> {
//               Log.d(TAG, f.getName());
//           });
//        });

//        list.addOnFailureListener(Throwable::printStackTrace);

        try (NotificationController notification = GenericForegroundService.startForegroundTask(context,
                context.getString(R.string.LocalBackupJob_creating_backup),
                NotificationChannels.BACKUPS,
                R.drawable.ic_signal_backup))
        {
            notification.setIndeterminateProgress();

            String       backupPassword  = BackupPassphrase.get(context);
            DocumentFile backupDirectory = DocumentFile.fromTreeUri(context, backupDirectoryUri);
            String       timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
            String       fileName        = String.format("signal-%s.backup", timestamp);

            if (backupDirectory == null || !backupDirectory.canWrite()) {
                BackupFileIOError.ACCESS_ERROR.postNotification(context);
                throw new IOException("Cannot write to backup directory location.");
            }

            deleteOldTemporaryBackups(backupDirectory);

            if (backupDirectory.findFile(fileName) != null) {
                throw new IOException("Backup file already exists!");
            }

            String       temporaryName = String.format(Locale.US, "%s%s%s", TEMP_BACKUP_FILE_PREFIX, UUID.randomUUID(), TEMP_BACKUP_FILE_SUFFIX);
            DocumentFile temporaryFile = backupDirectory.createFile("application/octet-stream", temporaryName);

            if (temporaryFile == null) {
                throw new IOException("Failed to create temporary backup file.");
            }

            if (backupPassword == null) {
                throw new IOException("Backup password is null");
            }

            try {
                FullBackupExporter.export(context,
                        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                        DatabaseFactory.getBackupDatabase(context),
                        temporaryFile,
                        backupPassword);

                try {
                    InputStream stream = context.getContentResolver().openInputStream(temporaryFile.getUri());

                    OutputStream buffer = new ByteArrayOutputStream();

                    int nRead;
                    byte[] data = new byte[16384];
//
                    while ((nRead = stream.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }

                    stream.close();

                    driveServiceHelper.createFile(fileName, buffer)
                            .addOnSuccessListener(id -> {
                                Log.i(TAG, "Successfully uploaded backup to google drive");
                                Log.i(TAG, "Deleting locally created file now... " + temporaryFile.delete());

//                            driveServiceHelper.storeBackup(id, "test-file", temporaryFile.toString())
//                                    .addOnSuccessListener(unused -> {
//                                        Log.i(TAG, "Successfully uploaded backup to google drive");
//                                    })
//                                    .addOnFailureListener(e -> {
//                                        Log.e(TAG, e);
//                                        e.printStackTrace();
//                                    });
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, e);
                                e.printStackTrace();
                            });
                } catch (IOException e) {
                    Log.e(TAG, e);
                }

                if (!temporaryFile.renameTo(fileName)) {
                    Log.w(TAG, "Failed to rename temp file");
                    throw new IOException("Renaming temporary backup file failed!");
                }
            } catch (IOException e) {
                Log.w(TAG, "Error during backup!", e);
                BackupFileIOError.postNotificationForException(context, e, getRunAttempt());
                throw e;
            } finally {
                DocumentFile fileToCleanUp = backupDirectory.findFile(temporaryName);
                if (fileToCleanUp != null) {
                    if (fileToCleanUp.delete()) {
                        Log.w(TAG, "Backup failed. Deleted temp file");
                    } else {
                        Log.w(TAG, "Backup failed. Failed to delete temp file " + temporaryName);
                    }
                }
            }

            BackupUtil.deleteOldBackups();
        }
                // TODO: Use GoogleDriveServiceHelper to upload file to Google drive (and possibly delete Local file created for this by exporter?)
    }


    // TODO: Delete temporary old/temp backups from Google Drive as well.
    private static void deleteOldTemporaryBackups(@NonNull DocumentFile backupDirectory) {
        for (DocumentFile file : backupDirectory.listFiles()) {
            if (file.isFile()) {
                String name = file.getName();
                if (name != null && name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
                    if (file.delete()) {
                        Log.w(TAG, "Deleted old temporary backup file");
                    } else {
                        Log.w(TAG, "Could not delete old temporary backup file");
                    }
                }
            }
        }
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        return false;
    }

    @NonNull
    @Override
    public Data serialize() {
        return Data.EMPTY;
    }

    @NonNull
    @Override
    public String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onFailure() {
    }

    public static class Factory implements Job.Factory<GoogleDriveBackupJob> {
        @Override
        public @NonNull GoogleDriveBackupJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new GoogleDriveBackupJob(parameters);
        }
    }
}
//
//import android.Manifest;
//
//import androidx.annotation.NonNull;
//
//import com.google.api.client.auth.oauth2.Credential;
//import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
//import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
//
//import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
//import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
//import com.google.api.client.json.JsonFactory;
//import com.google.api.client.util.store.FileDataStoreFactory;
//import com.google.api.client.http.javanet.NetHttpTransport;
//import com.google.api.services.drive.Drive;
//import com.google.api.services.drive.DriveScopes;
//import com.google.api.client.json.jackson2.JacksonFactory;
//
//import org.signal.core.util.logging.Log;
//import org.thoughtcrime.securesms.R;
//import org.thoughtcrime.securesms.backup.BackupFileIOError;
//import org.thoughtcrime.securesms.backup.BackupPassphrase;
//import org.thoughtcrime.securesms.backup.FullBackupExporter;
//import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
//import org.thoughtcrime.securesms.database.DatabaseFactory;
//import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
//import org.thoughtcrime.securesms.jobmanager.Data;
//import org.thoughtcrime.securesms.jobmanager.Job;
//import org.thoughtcrime.securesms.jobmanager.JobManager;
//import org.thoughtcrime.securesms.jobmanager.impl.ChargingConstraint;
//import org.thoughtcrime.securesms.notifications.NotificationChannels;
//import org.thoughtcrime.securesms.permissions.Permissions;
//import org.thoughtcrime.securesms.service.GenericForegroundService;
//import org.thoughtcrime.securesms.service.NotificationController;
//import org.thoughtcrime.securesms.util.BackupUtil;
//import org.thoughtcrime.securesms.util.StorageUtil;
//
//import java.io.File;
//import java.io.FileNotFoundException;
//import java.io.IOException;
//import java.io.InputStream;
//import java.io.InputStreamReader;
//import java.text.SimpleDateFormat;
//import java.util.Collections;
//import java.util.Date;
//import java.util.List;
//import java.util.Locale;
//
//public class GoogleDriveBackupJob extends BaseJob {
//
//    public static final String APPLICATION_NAME         = "Signal-Android";
//
//    public static final String KEY                      = "GoogleDriveBackupJob";
//
//    private static final String TAG                     = Log.tag(GoogleDriveBackupJob.class);
//
//    private static final String QUEUE                   = "__DRIVE_BACKUP__";
//
//    public static final String TEMP_BACKUP_FILE_PREFIX  = ".backup";
//
//    public static final String TEMP_BACKUP_FILE_SUFFIX  = ".tmp";
//
//    private static final JsonFactory JSON_FACTORY = (JsonFactory) new JacksonFactory();
//
//    private static final String TOKENS_DIRECTORY_PATH = "tokens";
//
//    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_METADATA_READONLY);
//
//    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
//
//    private static final String CREDENTIALS_FILE_PATH = "./credentials.json";
//
//    private static Drive driveService;
//
//    public static void enqueue(boolean force) {
//        JobManager jobManager = ApplicationDependencies.getJobManager();
//        Parameters.Builder parameters = new Parameters.Builder()
//                .setQueue(QUEUE)
//                .setMaxInstancesForFactory(1)
//                .setMaxAttempts(3);
//        if (force) {
//            jobManager.cancelAllInQueue(QUEUE);
//        } else {
//            parameters.addConstraint(ChargingConstraint.KEY);
//        }
//
//        if (BackupUtil.isUserSelectionRequired(ApplicationDependencies.getApplication())) {
//            jobManager.add(new LocalBackupJobApi29(parameters.build()));
//        } else {
//            jobManager.add(new GoogleDriveBackupJob(parameters.build()));
//        }
//    }
//
//    /**
//     * Creates an authorized Credential object.
//     * @param HTTP_TRANSPORT The network HTTP Transport.
//     * @return An authorized Credential object.
//     * @throws IOException If the credentials.json file cannot be found.
//     */
//    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
//        // Load client secrets.
//        InputStream in = GoogleDriveBackupJob.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
//        if (in == null) {
//            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
//        }
//        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
//
//        // Build flow and trigger user authorization request.
//        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
//                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
//                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
//                .setAccessType("offline")
//                .build();
//        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
//        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
//    }
//
//    private GoogleDriveBackupJob(@NonNull Job.Parameters parameters) {
//        super(parameters);
////        InputStream in = GoogleDriveBackupJob.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
//        InputStream in = GoogleDriveBackupJob.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
//
//        try {
//            driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
//                    .setApplicationName(APPLICATION_NAME)
//                    .build();
//            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
//            FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH));
//            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
//                    HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
//                    .setDataStoreFactory(dataStoreFactory)
//                    .setAccessType("online")
//                    .build();
//            LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
//        } catch (IOException e) {
//            Log.e(TAG, "Error initialising Google Drive Backup, Reason: " + e.getMessage());
//        }
//    }
//
//    @Override
//    protected void onRun() throws Exception {
//        Log.i(TAG, "Executing backup job...");
//
//        BackupFileIOError.clearNotification(context);
//
//        if (!Permissions.hasAll(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//            throw new IOException("No external storage permission!");
//        }
//
//        try (NotificationController notification = GenericForegroundService.startForegroundTask(context,
//                context.getString(R.string.LocalBackupJob_creating_backup),
//                NotificationChannels.BACKUPS,
//                R.drawable.ic_signal_backup))
//        {
//            notification.setIndeterminateProgress();
//
//            String backupPassword  = BackupPassphrase.get(context);
//            File   backupDirectory = StorageUtil.getOrCreateBackupDirectory();
//            String timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
//            String fileName        = String.format("signal-%s.backup", timestamp);
//            File   backupFile      = new File(backupDirectory, fileName);
//
//            deleteOldTemporaryBackups(backupDirectory);
//
//            if (backupFile.exists()) {
//                throw new IOException("Backup file already exists?");
//            }
//
//            if (backupPassword == null) {
//                throw new IOException("Backup password is null");
//            }
//
//            File tempFile = File.createTempFile(TEMP_BACKUP_FILE_PREFIX, TEMP_BACKUP_FILE_SUFFIX, backupDirectory);
//
//            try {
//                FullBackupExporter.export(context,
//                        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
//                        DatabaseFactory.getBackupDatabase(context),
//                        tempFile,
//                        backupPassword);
//
//                if (!tempFile.renameTo(backupFile)) {
//                    Log.w(TAG, "Failed to rename temp file");
//                    throw new IOException("Renaming temporary backup file failed!");
//                }
//            } catch (IOException e) {
//                BackupFileIOError.postNotificationForException(context, e, getRunAttempt());
//                throw e;
//            } finally {
//                if (tempFile.exists()) {
//                    if (tempFile.delete()) {
//                        Log.w(TAG, "Backup failed. Deleted temp file");
//                    } else {
//                        Log.w(TAG, "Backup failed. Failed to delete temp file " + tempFile);
//                    }
//                }
//            }
//
//            BackupUtil.deleteOldBackups();
//        }
//    }
//
//    private static void deleteOldTemporaryBackups(@NonNull File backupDirectory) {
//        for (File file : backupDirectory.listFiles()) {
//            if (file.isFile()) {
//                String name = file.getName();
//                if (name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
//                    if (file.delete()) {
//                        Log.w(TAG, "Deleted old temporary backup file");
//                    } else {
//                        Log.w(TAG, "Could not delete old temporary backup file");
//                    }
//                }
//            }
//        }
//    }
//
//    @Override
//    protected boolean onShouldRetry(@NonNull Exception e) {
//        return false;
//    }
//
//    @Override
//    public @NonNull Data serialize() {
//        return Data.EMPTY;
//    }
//
//    @Override
//    public @NonNull String getFactoryKey() {
//        return KEY;
//    }
//
//    @Override
//    public void onFailure() {
//    }
//
//    public static class Factory implements Job.Factory<GoogleDriveBackupJob> {
//        @Override
//        public @NonNull GoogleDriveBackupJob create(@NonNull Parameters parameters, @NonNull Data data) {
//            return new GoogleDriveBackupJob(parameters);
//        }
//    }
//}
