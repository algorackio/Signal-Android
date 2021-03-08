package org.thoughtcrime.securesms.preferences;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.AppCompatButton;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import org.signal.core.util.logging.Log;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.backup.BackupDialog;
import org.thoughtcrime.securesms.jobs.GoogleDriveBackupJob;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.BackupUtil;
import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.DriveBackupUtil;
import org.thoughtcrime.securesms.util.GoogleDriveServiceHelper;
import org.thoughtcrime.securesms.util.StorageUtil;

import java.util.Collections;
import java.util.Objects;

public class GoogleDriveBackupFragment extends Fragment {
    private static final String TAG = Log.tag(GoogleDriveBackupFragment.class);

    private static final int REQUEST_CODE_SIGN_IN = 1;
    private static final int REQUEST_CODE_OPEN_DOCUMENT = 2;
    private static final int REQUEST_CODE_COMPLETE_AUTHORIZATION = 3;

    private GoogleSignInAccount account;

    private GoogleDriveServiceHelper serviceHelper;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_google_drive_backup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.signinButton).setOnClickListener(unused -> requestSignIn());
//        EventBus.getDefault().register(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
//        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    @RequiresApi(24)
    public void onReceiveBackupLocationRequest(@Nullable Intent data) {
        BackupDialog.showEnableBackupDialog(requireContext(),
                data,
                StorageUtil.getDisplayPath(requireContext(), data.getData()),
                () -> Log.i(TAG, "Received backup location!"));
    }
    @RequiresApi(24)
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_SIGN_IN:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    handleSignInResult(data);
                }
                break;

            case REQUEST_CODE_OPEN_DOCUMENT:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        openFileFromFilePicker(uri);
                    }
                }
                break;
            case REQUEST_CODE_COMPLETE_AUTHORIZATION:
                if (resultCode == Activity.RESULT_OK) {
                    Dialogs.showInfoDialog(this.getContext(), "Sign in Success", "Sign in successful!");
                } else {
                    Dialogs.showAlertDialog(this.getContext(), "Sign in Failure", "Please try signing in again!");
                    requestSignIn();
                }
            default:
                Log.d(TAG, "Google Sign In exit");
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Opens the Storage Access Framework file picker using {@link #REQUEST_CODE_OPEN_DOCUMENT}.
     */
    private void openFilePicker() {
        if (serviceHelper != null) {
            Log.d(TAG, "Opening file picker.");

            Intent pickerIntent = serviceHelper.createFilePickerIntent();

            // The result of the SAF Intent is handled in onActivityResult.
            startActivityForResult(pickerIntent, REQUEST_CODE_OPEN_DOCUMENT);
        }
    }

    /**
     * Opens a file from its {@code uri} returned from the Storage Access Framework file picker
     * initiated by {@link #openFilePicker()}.
     */
    private void openFileFromFilePicker(Uri uri) {
        if (serviceHelper != null) {
            Log.d(TAG, "Opening " + uri.getPath());

            serviceHelper.openFileUsingStorageAccessFramework(this.getActivity().getContentResolver(), uri)
                    .addOnSuccessListener(nameAndContent -> {
                        String name = nameAndContent.first;
                        String content = nameAndContent.second;
                        Log.d(TAG, "FILE NAME AND CONTENT");
                        Log.d(TAG, "FILE NAME: " + name);
                        Log.d(TAG, "FILE CONTENT: " + content);

//                        mFileTitleEditText.setText(name);
//                        mDocContentEditText.setText(content);

                        // Files opened through SAF cannot be modified.
//                        setReadOnlyMode();
                    })
                    .addOnFailureListener(exception ->
                            Log.e(TAG, "Unable to open file from picker.", exception));
        }
    }

    @RequiresApi(29)
    private void onBackupClickedApi29() {
        Log.i(TAG, "Queuing drive backup...");
        GoogleDriveBackupJob.enqueue(true, serviceHelper);
    }

    private void onBackupClickedLegacy() {
        Permissions.with(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .ifNecessary()
                .onAllGranted(() -> {
                    Log.i(TAG, "Queuing drive backup...");
                    GoogleDriveBackupJob.enqueue(true, serviceHelper);
                })
                .withPermanentDenialDialog(getString(R.string.BackupsPreferenceFragment_signal_requires_external_storage_permission_in_order_to_create_backups))
                .execute();
    }

    private void onBackupClicked() {
        if (BackupUtil.isUserSelectionRequired(requireContext())) {
            onBackupClickedApi29();
        } else {
            onBackupClickedLegacy();
        }
    }

    /**
     * Updates the Google Drive Button based on account details
     */
    @RequiresApi(24)
    private void updateInfo() {
        if (account != null) {
            AppCompatButton button = getView().findViewById(R.id.signinButton);
//            button.setOnClickListener(unused -> Log.d(TAG, "Run Google Drive Backup Job"));
//            button.setOnClickListener(unused -> onBackupClicked());
            button.setOnClickListener(unused -> DriveBackupUtil.getLatestBackup(Objects.requireNonNull(getActivity(), "Activity for GoogleDriveBackupFragment not found").getApplicationContext(), serviceHelper, file -> {
                Log.d(TAG, "LATEST BACKUP!!");
                Log.d(TAG, file.getUri().toString());
            }));
//            button.setOnClickListener(unused -> openFilePicker());
            button.setText(R.string.BackupsPreferenceFragment__google_drive_backup);
        }
    }

    /**
     * Updates the UI to read-only mode.
     */
    private void setReadOnlyMode() {
//        mFileTitleEditText.setEnabled(false);
//        mDocContentEditText.setEnabled(false);
//        mOpenFileId = null;
    }

    /**
     * Starts a sign-in activity using {@link #REQUEST_CODE_SIGN_IN}.
     */
    private void requestSignIn() {
        Log.d(TAG, "Requesting sign-in");

        GoogleSignInOptions signInOptions =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                        .build();
        GoogleSignInClient client = GoogleSignIn.getClient(this.getActivity(), signInOptions);

        // The result of the sign-in Intent is handled in onActivityResult.
        startActivityForResult(client.getSignInIntent(), REQUEST_CODE_SIGN_IN);
    }

    /**
     * Handles the {@code result} of a completed sign-in activity initiated from {@link
     * #requestSignIn()}.
     */
    @RequiresApi(24)
    private void handleSignInResult(Intent result) {
        GoogleSignIn.getSignedInAccountFromIntent(result)
                .addOnSuccessListener(googleAccount -> {
                    account = googleAccount;
                    Log.d(TAG, "Signed in as " + googleAccount.getEmail());

                    // Use the authenticated account to sign in to the Drive service.
                    try {
                        GoogleAccountCredential credential =
                                GoogleAccountCredential.usingOAuth2(
                                        this.getContext(), Collections.singleton(DriveScopes.DRIVE_FILE));
                        credential.setSelectedAccount(googleAccount.getAccount());

                        HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
                        GsonFactory gsonFactory = new GsonFactory();

//                    Drive googleDriveService = new Drive.Builder(httpTransport, new GsonFactory(), credential).setApplicationName("Signal").build();
//                    Drive googleDriveService = new Drive.Builder(httpTransport, gsonFactory, credential).setApplicationName("Signal").build();
                        Drive googleDriveService = new Drive.Builder(
                                httpTransport,
                                gsonFactory,
                                credential).setApplicationName("Signal").build();

                        // The DriveServiceHelper encapsulates all REST API and SAF functionality.
                        // Its instantiation is required before handling any onClick actions.
                        serviceHelper = new GoogleDriveServiceHelper(googleDriveService);
                        updateInfo();
//                    AppCompatButton button = getView().findViewById(R.id.signinButton);
//                    button.setOnClickListener(unused -> Log.d(TAG, "Run Google Drive Backup Job"));
//                    button.setText(R.string.BackupsPreferenceFragment__google_drive_backup);
                    } catch (Exception e) {
                        Log.e(TAG, e);
                    }
                })
                .addOnFailureListener(exception -> Log.e(TAG, "Unable to sign in.", exception));
    }
}
